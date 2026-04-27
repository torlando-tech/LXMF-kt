package network.reticulum.lxmf.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.MessageState
import network.reticulum.transport.Transport
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * LXMF Kotlin conformance bridge.
 *
 * JSON-RPC over stdio, one object per line. Mirrors the python reference
 * at lxmf-conformance/reference/lxmf_python.py command-for-command. See
 * the README in the lxmf-conformance repo for the contract.
 *
 * Wire protocol:
 *   - On startup, prints exactly "READY" on stdout, then enters loop.
 *   - Reads JSON requests from stdin, one per line:
 *       {"id": "req-N", "command": "...", "params": {...}}
 *   - Writes JSON responses on stdout, one per line:
 *       {"id": "req-N", "success": true, "result": {...}}
 *       {"id": "req-N", "success": false, "error": "..."}
 *
 * stderr is left for log noise; stdout is sacred — the test harness
 * filters non-JSON lines on stdout but it's still fragile, so we
 * suppress slf4j-simple at WARN.
 */

// ----------------------------------------------------------------------
// Bridge state. Single-instance per process, mirroring lxmf_python.py.
// ----------------------------------------------------------------------

private object BridgeState {
    @Volatile var reticulum: Reticulum? = null
    @Volatile var router: LXMRouter? = null
    @Volatile var identity: Identity? = null
    @Volatile var deliveryDestination: Destination? = null
    @Volatile var configDir: String? = null
    @Volatile var storagePath: String? = null

    // Inbound message queue. Append-only with monotonic seq.
    val inbox = mutableListOf<JSONObject>()
    val inboxLock = ReentrantLock()
    @Volatile var inboxSeq = 0

    // Outbound message state map: hex -> state name.
    val outboundState = ConcurrentHashMap<String, String>()

    // Started TCP interfaces (for shutdown).
    val interfaces = mutableListOf<Any>()
}

// ----------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------

private fun stateToString(state: MessageState?): String =
    when (state) {
        MessageState.GENERATING -> "generating"
        MessageState.OUTBOUND -> "outbound"
        MessageState.SENDING -> "sending"
        MessageState.SENT -> "sent"
        MessageState.DELIVERED -> "delivered"
        MessageState.FAILED -> "failed"
        MessageState.REJECTED -> "failed"
        MessageState.CANCELLED -> "failed"
        null -> "unknown"
    }

private fun methodToString(method: DeliveryMethod?): String =
    when (method) {
        DeliveryMethod.OPPORTUNISTIC -> "opportunistic"
        DeliveryMethod.DIRECT -> "direct"
        DeliveryMethod.PROPAGATED -> "propagated"
        else -> "unknown"
    }

private fun allocateFreePort(): Int {
    val s = ServerSocket()
    return try {
        s.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        s.localPort
    } finally {
        s.close()
    }
}

private fun ensureRouter(name: String): LXMRouter =
    BridgeState.router
        ?: throw IllegalStateException("lxmf_init must be called before $name")

// ----------------------------------------------------------------------
// LXMF field codec
// ----------------------------------------------------------------------
//
// LXMF `fields` is a `Map<Int, Any>` whose leaf values can be ByteArray,
// String, Int/Long, Boolean, or nested Lists/Maps; the wire format is
// msgpack and ByteArray vs String are *distinct* msgpack types. JSON-RPC
// can't carry bytes natively so the bridge contract uses tagged objects:
//   - {"bytes": "<hex>"}
//   - {"str": "..."}
//   - {"int": 123}
//   - {"bool": true}
//   - JSON arrays for lists
//   - bare JSON primitives pass through
//
// The explicit "bytes" tag is load-bearing for FIELD_FILE_ATTACHMENTS
// (id 0x05): each attachment is `[filename_str, data_bytes]` and without
// the tag we'd lose the bytes-vs-string distinction round-tripping
// through JSON. Mirrors reference/lxmf_python.py exactly.

private fun decodeFieldValue(value: Any?): Any? {
    if (value == null || value == JSONObject.NULL) return null
    return when (value) {
        is JSONObject -> when {
            value.has("bytes") -> hexToBytes(value.getString("bytes"))
            value.has("str") -> value.getString("str")
            value.has("int") -> value.getLong("int")
            value.has("bool") -> value.getBoolean("bool")
            else -> {
                // Bare object — treat as nested map with string keys.
                val out = mutableMapOf<String, Any?>()
                for (k in value.keys()) out[k] = decodeFieldValue(value.get(k))
                out
            }
        }
        is JSONArray -> {
            val out = mutableListOf<Any?>()
            for (i in 0 until value.length()) out.add(decodeFieldValue(value.get(i)))
            out
        }
        else -> value
    }
}

private fun decodeFieldsParam(params: JSONObject): MutableMap<Int, Any> {
    val raw = params.optJSONObject("fields") ?: return mutableMapOf()
    val out = mutableMapOf<Int, Any>()
    for (k in raw.keys()) {
        val decoded = decodeFieldValue(raw.get(k))
            ?: continue // null leaves don't survive into LXMF anyway
        out[k.toInt()] = decoded
    }
    return out
}

// Encoder: convert ByteArray -> hex so the value is JSON-safe. Mirrors
// the python bridge's _encode_field_value_for_inbox; the test suite
// asserts on hex strings (not the tagged form) when reading the inbox.
private fun encodeFieldValueForInbox(value: Any?): Any? {
    return when (value) {
        null -> JSONObject.NULL
        is ByteArray -> value.toHexString()
        is List<*> -> JSONArray().also { arr ->
            for (item in value) arr.put(encodeFieldValueForInbox(item))
        }
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) obj.put(k.toString(), encodeFieldValueForInbox(v))
        }
        is Long, is Int, is Double, is Float, is Boolean, is String -> value
        // Anything else: stringify to keep JSON valid; loud failure is
        // better than silently dropping the value.
        else -> value.toString()
    }
}

private fun encodeMessageFields(message: LXMessage): JSONObject {
    val out = JSONObject()
    for ((k, v) in message.fields) {
        out.put(k.toString(), encodeFieldValueForInbox(v))
    }
    return out
}

// ----------------------------------------------------------------------
// Command handlers
// ----------------------------------------------------------------------

private fun cmdLxmfInit(params: JSONObject): JSONObject {
    if (BridgeState.router != null) {
        throw IllegalStateException(
            "lxmf_init has already been called on this bridge process. " +
                "Spawn a separate bridge subprocess for each LXMF node."
        )
    }

    val storagePath = params.optString("storage_path", "")
        .ifEmpty { Files.createTempDirectory("lxmf_conf_storage_").toAbsolutePath().toString() }
    val configDir = Files.createTempDirectory("lxmf_conf_rns_").toAbsolutePath().toString()
    val displayName = params.optString("display_name", "lxmf-conformance-peer-kt")

    // Write a minimal Reticulum config so RNS doesn't auto-generate a
    // share_instance / LocalServerInterface that would collide between
    // bridges in the same test.
    val configFile = File(configDir, "config")
    configFile.writeText(
        """
        |[reticulum]
        |  enable_transport = No
        |  share_instance = No
        |  respond_to_probes = No
        |
        |[interfaces]
        |
        """.trimMargin()
    )

    // Reticulum.start in reticulum-kt uses configDir to load identity etc.
    val rns = Reticulum.start(
        configDir = configDir,
        enableTransport = false,
        shareInstance = false,
    )

    // Fresh identity per bridge process. Matches python reference.
    val identity = Identity.create()
    val router = LXMRouter(identity = identity, storagePath = storagePath)

    val deliveryDest = router.registerDeliveryIdentity(identity, displayName = displayName)

    router.registerDeliveryCallback { message ->
        val seq = BridgeState.inboxLock.withLock {
            BridgeState.inboxSeq += 1
            BridgeState.inboxSeq
        }
        val entry = JSONObject()
        entry.put("seq", seq)
        entry.put("message_hash", message.hash?.toHexString() ?: "")
        entry.put("source_hash", message.sourceHash.toHexString())
        entry.put("destination_hash", message.destinationHash.toHexString())
        entry.put("title", message.title)
        entry.put("content", message.content)
        entry.put("method", methodToString(message.method))
        entry.put("ack_status", "received")
        entry.put("received_at_ms", System.currentTimeMillis())
        entry.put("fields", encodeMessageFields(message))
        BridgeState.inboxLock.withLock {
            BridgeState.inbox.add(entry)
        }
    }

    router.start()

    BridgeState.reticulum = rns
    BridgeState.router = router
    BridgeState.identity = identity
    BridgeState.deliveryDestination = deliveryDest
    BridgeState.configDir = configDir
    BridgeState.storagePath = storagePath

    val result = JSONObject()
    result.put("identity_hash", identity.hash.toHexString())
    result.put("delivery_destination_hash", deliveryDest.hash.toHexString())
    result.put("config_dir", configDir)
    result.put("storage_path", storagePath)
    return result
}

private fun cmdLxmfAddTcpServerInterface(params: JSONObject): JSONObject {
    ensureRouter("lxmf_add_tcp_server_interface")
    val bindPort = params.optInt("bind_port", 0).takeIf { it > 0 } ?: allocateFreePort()
    val name = params.optString("name", "tcpserver").ifEmpty { "tcpserver" }

    val iface = TCPServerInterface(
        name = name,
        bindAddress = "127.0.0.1",
        bindPort = bindPort,
    )
    iface.start()
    Transport.registerInterface(iface.toRef())
    BridgeState.interfaces.add(iface)

    return JSONObject().put("port", bindPort).put("interface_name", name)
}

private fun cmdLxmfAddTcpClientInterface(params: JSONObject): JSONObject {
    ensureRouter("lxmf_add_tcp_client_interface")
    val targetHost = params.optString("target_host", "127.0.0.1").ifEmpty { "127.0.0.1" }
    val targetPort = params.getInt("target_port")
    val name = params.optString("name", "tcpclient").ifEmpty { "tcpclient" }

    val iface = TCPClientInterface(
        name = name,
        targetHost = targetHost,
        targetPort = targetPort,
    )
    iface.start()
    Transport.registerInterface(iface.toRef())
    BridgeState.interfaces.add(iface)

    return JSONObject().put("interface_name", name)
}

private fun cmdLxmfAnnounce(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_announce")
    val dest = BridgeState.deliveryDestination
        ?: throw IllegalStateException("Delivery destination not registered")
    router.announce(dest)
    return JSONObject().put("delivery_destination_hash", dest.hash.toHexString())
}

private fun makeOutboundDelivery(destHashHex: String): Destination {
    val destHash = hexToBytes(destHashHex)
    val recipientIdentity = Identity.recall(destHash)
        ?: throw IllegalStateException(
            "No identity known for destination $destHashHex. The recipient " +
                "must announce its delivery destination before this peer can send to it."
        )
    return Destination.create(
        identity = recipientIdentity,
        direction = DestinationDirection.OUT,
        type = DestinationType.SINGLE,
        appName = "lxmf",
        "delivery",
    )
}

private fun cmdLxmfSendOpportunistic(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_send_opportunistic")
    val source = BridgeState.deliveryDestination
        ?: throw IllegalStateException("source delivery destination not registered")

    val destHashHex = params.getString("destination_hash")
    val content = params.getString("content")
    val title = params.optString("title", "")

    val recipientDestination = makeOutboundDelivery(destHashHex)

    val message = LXMessage.create(
        destination = recipientDestination,
        source = source,
        content = content,
        title = title,
        fields = decodeFieldsParam(params),
        desiredMethod = DeliveryMethod.OPPORTUNISTIC,
    )
    // Per-message callbacks so we can track state transitions.
    message.deliveryCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }
    message.failedCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }

    runBlocking { router.handleOutbound(message) }
    val msgHashHex = message.hash?.toHexString() ?: ""
    if (msgHashHex.isNotEmpty()) {
        BridgeState.outboundState[msgHashHex] = stateToString(message.state)
    }

    return JSONObject().put("message_hash", msgHashHex)
}

private fun cmdLxmfSendDirect(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_send_direct")
    val source = BridgeState.deliveryDestination
        ?: throw IllegalStateException("source delivery destination not registered")

    val destHashHex = params.getString("destination_hash")
    val content = params.getString("content")
    val title = params.optString("title", "")

    val recipientDestination = makeOutboundDelivery(destHashHex)

    val message = LXMessage.create(
        destination = recipientDestination,
        source = source,
        content = content,
        title = title,
        fields = decodeFieldsParam(params),
        desiredMethod = DeliveryMethod.DIRECT,
    )
    message.deliveryCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }
    message.failedCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }

    runBlocking { router.handleOutbound(message) }
    val msgHashHex = message.hash?.toHexString() ?: ""
    if (msgHashHex.isNotEmpty()) {
        BridgeState.outboundState[msgHashHex] = stateToString(message.state)
    }

    return JSONObject().put("message_hash", msgHashHex)
}

// ----------------------------------------------------------------------
// Path probing
// ----------------------------------------------------------------------
//
// has_path requires BOTH Transport.hasPath() AND Identity.recall(). The
// LXMF send path needs the identity to encrypt the LXM, so a fixture
// that polls only on hasPath() can race past identity-recall and the
// next send fails. Mirrors the python bridge which made the same mistake
// once and got bitten — keep the AND-of-both contract identical.

private fun cmdLxmfHasPath(params: JSONObject): JSONObject {
    ensureRouter("lxmf_has_path")
    val destHash = hexToBytes(params.getString("destination_hash"))
    val transportHasPath = Transport.hasPath(destHash)
    val identityRecalled = Identity.recall(destHash) != null
    return JSONObject()
        .put("has_path", transportHasPath && identityRecalled)
        .put("transport_has_path", transportHasPath)
        .put("identity_recalled", identityRecalled)
}

private fun cmdLxmfRequestPath(params: JSONObject): JSONObject {
    ensureRouter("lxmf_request_path")
    val destHash = hexToBytes(params.getString("destination_hash"))
    Transport.requestPath(destHash)
    return JSONObject().put("requested", true)
}

// ----------------------------------------------------------------------
// Propagation
// ----------------------------------------------------------------------

private fun cmdLxmfSetOutboundPropagationNode(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_set_outbound_propagation_node")
    val destHashHex = params.getString("destination_hash")
    router.setActivePropagationNode(destHashHex)
    return JSONObject().put("ok", true)
}

private fun cmdLxmfSendPropagated(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_send_propagated")
    val source = BridgeState.deliveryDestination
        ?: throw IllegalStateException("source delivery destination not registered")

    val destHashHex = params.getString("destination_hash")
    val content = params.getString("content")
    val title = params.optString("title", "")

    val recipientDestination = makeOutboundDelivery(destHashHex)

    val message = LXMessage.create(
        destination = recipientDestination,
        source = source,
        content = content,
        title = title,
        fields = decodeFieldsParam(params),
        desiredMethod = DeliveryMethod.PROPAGATED,
    )
    message.deliveryCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }
    message.failedCallback = { m ->
        m.hash?.let { BridgeState.outboundState[it.toHexString()] = stateToString(m.state) }
    }

    runBlocking { router.handleOutbound(message) }
    val msgHashHex = message.hash?.toHexString() ?: ""
    if (msgHashHex.isNotEmpty()) {
        BridgeState.outboundState[msgHashHex] = stateToString(message.state)
    }
    return JSONObject().put("message_hash", msgHashHex)
}

// Treat as terminal: anything where the router is no longer actively
// driving a transfer. COMPLETE = success; FAILED/NO_PATH/NO_LINK =
// terminal failure; IDLE = transfer drained back to rest. The python
// bridge treats {None, 0, -1} as terminal and bails on the first one
// observed; this mirrors that.
private val TERMINAL_PROPAGATION_STATES = setOf(
    LXMRouter.PropagationTransferState.IDLE,
    LXMRouter.PropagationTransferState.COMPLETE,
    LXMRouter.PropagationTransferState.FAILED,
    LXMRouter.PropagationTransferState.NO_PATH,
    LXMRouter.PropagationTransferState.NO_LINK,
)

private fun cmdLxmfSyncInbound(params: JSONObject): JSONObject {
    val router = ensureRouter("lxmf_sync_inbound")
    val timeoutSec = params.optDouble("timeout_sec", 30.0)

    router.requestMessagesFromPropagationNode()

    val deadlineMs = System.currentTimeMillis() + (timeoutSec * 1000).toLong()
    var finalState = router.propagationTransferState
    while (System.currentTimeMillis() < deadlineMs) {
        finalState = router.propagationTransferState
        if (finalState in TERMINAL_PROPAGATION_STATES) {
            // Brief settle so an in-flight transfer that hasn't yet
            // raised its state has a chance to be observed before we
            // bail. Mirrors the python bridge's 0.5s tail.
            Thread.sleep(500)
            finalState = router.propagationTransferState
            break
        }
        Thread.sleep(200)
    }

    return JSONObject().put("final_state", finalState.name.lowercase())
}

private fun cmdLxmfGetReceivedMessages(params: JSONObject): JSONObject {
    ensureRouter("lxmf_get_received_messages")
    val sinceSeq = params.optInt("since_seq", 0)
    val out = JSONArray()
    val lastSeq: Int
    BridgeState.inboxLock.withLock {
        for (m in BridgeState.inbox) {
            if (m.getInt("seq") > sinceSeq) out.put(m)
        }
        lastSeq = BridgeState.inboxSeq
    }
    return JSONObject().put("messages", out).put("last_seq", lastSeq)
}

private fun cmdLxmfGetMessageState(params: JSONObject): JSONObject {
    ensureRouter("lxmf_get_message_state")
    val hashHex = params.getString("message_hash")
    val state = BridgeState.outboundState[hashHex] ?: "unknown"
    return JSONObject().put("state", state)
}

private fun cmdLxmfShutdown(params: JSONObject): JSONObject {
    var stopped = false
    val router = BridgeState.router
    if (router != null) {
        try { router.stop() } catch (_: Throwable) {}
        BridgeState.router = null
        stopped = true
    }
    for (iface in BridgeState.interfaces) {
        try {
            val m = iface.javaClass.getMethod("detach")
            m.invoke(iface)
        } catch (_: Throwable) {}
    }
    BridgeState.interfaces.clear()
    try { Reticulum.stop() } catch (_: Throwable) {}
    BridgeState.reticulum = null
    BridgeState.identity = null
    BridgeState.deliveryDestination = null

    // Best-effort cleanup of tempdirs.
    BridgeState.storagePath?.let { runCatching { File(it).deleteRecursively() } }
    BridgeState.configDir?.let { runCatching { File(it).deleteRecursively() } }
    BridgeState.storagePath = null
    BridgeState.configDir = null

    BridgeState.inboxLock.withLock {
        BridgeState.inbox.clear()
        BridgeState.inboxSeq = 0
    }
    BridgeState.outboundState.clear()

    return JSONObject().put("stopped", stopped)
}

// ----------------------------------------------------------------------
// Dispatch
// ----------------------------------------------------------------------

private val COMMANDS: Map<String, (JSONObject) -> JSONObject> = mapOf(
    "lxmf_init" to ::cmdLxmfInit,
    "lxmf_add_tcp_server_interface" to ::cmdLxmfAddTcpServerInterface,
    "lxmf_add_tcp_client_interface" to ::cmdLxmfAddTcpClientInterface,
    "lxmf_announce" to ::cmdLxmfAnnounce,
    "lxmf_send_opportunistic" to ::cmdLxmfSendOpportunistic,
    "lxmf_send_direct" to ::cmdLxmfSendDirect,
    "lxmf_send_propagated" to ::cmdLxmfSendPropagated,
    "lxmf_has_path" to ::cmdLxmfHasPath,
    "lxmf_request_path" to ::cmdLxmfRequestPath,
    "lxmf_set_outbound_propagation_node" to ::cmdLxmfSetOutboundPropagationNode,
    "lxmf_sync_inbound" to ::cmdLxmfSyncInbound,
    "lxmf_get_received_messages" to ::cmdLxmfGetReceivedMessages,
    "lxmf_get_message_state" to ::cmdLxmfGetMessageState,
    "lxmf_shutdown" to ::cmdLxmfShutdown,
)

private fun handleRequest(line: String): JSONObject {
    val request: JSONObject = try {
        JSONObject(line)
    } catch (e: Exception) {
        return JSONObject()
            .put("id", "parse_error")
            .put("success", false)
            .put("error", "JSON parse: ${e.message}")
    }

    val reqId = request.optString("id", "")
    val command = request.optString("command", "")
    val params = request.optJSONObject("params") ?: JSONObject()

    val handler = COMMANDS[command]
        ?: return JSONObject()
            .put("id", reqId)
            .put("success", false)
            .put("error", "Unknown command: $command")

    return try {
        val result = handler(params)
        JSONObject().put("id", reqId).put("success", true).put("result", result)
    } catch (t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        JSONObject()
            .put("id", reqId)
            .put("success", false)
            .put("error", "${t.javaClass.simpleName}: ${t.message}\n$sw")
    }
}

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.lowercase()
    require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or
            Character.digit(clean[i * 2 + 1], 16)).toByte()
    }
    return out
}

fun main() {
    // Suppress slf4j-simple noise on stdout — stdout is the JSON-RPC channel.
    // Default level is INFO; bump to WARN. Direct slf4j-simple to stderr.
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")

    val out = System.out
    val reader = BufferedReader(InputStreamReader(System.`in`))

    out.println("READY")
    out.flush()

    while (true) {
        val line = try { reader.readLine() } catch (e: Exception) { null }
        if (line == null) break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val response = handleRequest(trimmed)
        out.println(response.toString())
        out.flush()
    }
    exitProcess(0)
}
