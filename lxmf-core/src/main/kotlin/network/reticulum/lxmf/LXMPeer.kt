package network.reticulum.lxmf

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceConstants
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack
import org.msgpack.value.ValueFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * LXMF Propagation Node Peer.
 *
 * Represents a single peering relationship between this node (acting as a
 * propagation node) and another propagation node, and implements the
 * peer-to-peer synchronisation protocol (offer/response over an RNS Link).
 *
 * This is a Kotlin port of Python LXMF's LXMPeer class (LXMF 1.1.0),
 * preserving semantic parity including trust-bearing identity handling:
 * the peer destination identity is recalled from the local Transport
 * identity cache exactly as Python does — it is never inferred, trusted
 * from wire data, or substituted.
 */
class LXMPeer(
    /** The owning router */
    internal val router: LXMRouter,
    /** Destination hash of the peer's propagation destination */
    val destinationHash: ByteArray,
    /** Sync strategy: STRATEGY_LAZY or STRATEGY_PERSISTENT */
    var syncStrategy: Int = DEFAULT_SYNC_STRATEGY,
) {
    companion object {
        const val OFFER_REQUEST_PATH = "/offer"
        const val MESSAGE_GET_PATH = "/get"

        // ===== Link/sync state machine =====
        const val IDLE = 0x00
        const val LINK_ESTABLISHING = 0x01
        const val LINK_READY = 0x02
        const val REQUEST_SENT = 0x03
        const val RESPONSE_RECEIVED = 0x04
        const val RESOURCE_TRANSFERRING = 0x05

        // ===== Error responses =====
        const val ERROR_NO_IDENTITY = 0xf0
        const val ERROR_NO_ACCESS = 0xf1
        const val ERROR_INVALID_KEY = 0xf3
        const val ERROR_INVALID_DATA = 0xf4
        const val ERROR_INVALID_STAMP = 0xf5
        const val ERROR_THROTTLED = 0xf6
        const val ERROR_NOT_FOUND = 0xfd
        const val ERROR_TIMEOUT = 0xfe

        // ===== Sync strategies =====
        const val STRATEGY_LAZY = 0x01
        const val STRATEGY_PERSISTENT = 0x02
        const val DEFAULT_SYNC_STRATEGY = STRATEGY_PERSISTENT

        /** Maximum amount of time a peer can be unreachable before it is removed */
        const val MAX_UNREACHABLE = 14 * 24 * 60 * 60

        /**
         * Consecutive sync rounds an entry may be omitted from the transfer
         * payload (unreadable backing file) before it is dead-lettered.
         * Bounded-retry hardening for the persistent sync strategy — without
         * this, one permanently unreadable entry loops the peer forever
         * (Greptile PR#38 r5). Python reference has no equivalent cap.
         */
        const val MAX_UNSENDABLE_ROUNDS = 5

        /**
         * Every consecutive time a sync link fails to establish, add this
         * amount of time to wait before the next sync is attempted.
         */
        const val SYNC_BACKOFF_STEP = 12 * 60

        /** How long to wait for an answer to peer path requests before deferring sync to later. */
        const val PATH_REQUEST_GRACE = 7.5

        // ===== Deserialisation =====

        /**
         * Reconstruct a peer from its serialised form (Python `from_bytes`).
         *
         * Trust semantics preserved exactly: only messages still present in
         * the router's propagation entries become handled/unhandled state;
         * unknown transient ids are silently dropped.
         */
        fun fromBytes(peerBytes: ByteArray, router: LXMRouter): LXMPeer {
            val unpacker = MessagePack.newDefaultUnpacker(peerBytes)
            val value = unpacker.unpackValue()
            unpacker.close()
            val dictionary = value.asMapValue()
            fun str(key: String) = ValueFactory.newString(key.toByteArray())
            fun dictGet(key: String): org.msgpack.value.Value? =
                dictionary.map()[str(key)]

            val peerDestinationHash = dictGet("destination_hash")!!.asBinaryValue().asByteArray()
            // Numbers may be encoded as int or float depending on producer — decode tolerantly
            fun numAsDouble(key: String): Double {
                val v = dictGet(key)!!
                return if (v.isFloatValue()) v.asFloatValue().toDouble() else v.asIntegerValue().toDouble()
            }
            val peerPeeringTimebase = numAsDouble("peering_timebase")
            val peerAlive = dictGet("alive")!!.asBooleanValue().getBoolean()
            val peerLastHeard = numAsDouble("last_heard")

            val peer = LXMPeer(router, peerDestinationHash)
            peer.peeringTimebase = peerPeeringTimebase
            peer.alive = peerAlive
            peer.lastHeard = peerLastHeard

            // Numbers may be encoded as int or float depending on producer — decode tolerantly
            fun numAsDouble(v: org.msgpack.value.Value): Double =
                if (v.isFloatValue()) v.asFloatValue().toDouble() else v.asIntegerValue().toDouble()

            peer.linkEstablishmentRate =
                if (dictContains(dictionary, "link_establishment_rate")) numAsDouble(dictGet("link_establishment_rate")!!) else 0.0

            peer.syncTransferRate =
                if (dictContains(dictionary, "sync_transfer_rate")) numAsDouble(dictGet("sync_transfer_rate")!!) else 0.0

            peer.propagationTransferLimit =
                if (dictContains(dictionary, "propagation_transfer_limit")) {
                    try { dictGet("propagation_transfer_limit")!!.asFloatValue().toDouble() } catch (_: Exception) { null }
                } else null

            peer.propagationSyncLimit =
                if (dictContains(dictionary, "propagation_sync_limit")) {
                    try { numAsDouble(dictGet("propagation_sync_limit")!!) }
                    catch (_: Exception) { peer.propagationTransferLimit }
                } else peer.propagationTransferLimit

            peer.propagationStampCost =
                if (dictContains(dictionary, "propagation_stamp_cost")) {
                    try { dictGet("propagation_stamp_cost")!!.asIntegerValue().toInt() } catch (_: Exception) { null }
                } else null

            peer.propagationStampCostFlexibility =
                if (dictContains(dictionary, "propagation_stamp_cost_flexibility")) {
                    try { dictGet("propagation_stamp_cost_flexibility")!!.asIntegerValue().toInt() } catch (_: Exception) { null }
                } else null

            peer.peeringCost =
                if (dictContains(dictionary, "peering_cost")) {
                    try { dictGet("peering_cost")!!.asIntegerValue().toInt() } catch (_: Exception) { null }
                } else null

            peer.syncStrategy =
                if (dictContains(dictionary, "sync_strategy")) {
                    try { dictGet("sync_strategy")!!.asIntegerValue().toInt() } catch (_: Exception) { DEFAULT_SYNC_STRATEGY }
                } else DEFAULT_SYNC_STRATEGY

            peer.offered = if (dictContains(dictionary, "offered")) dictGet("offered")!!.asIntegerValue().toInt() else 0
            peer.outgoing = if (dictContains(dictionary, "outgoing")) dictGet("outgoing")!!.asIntegerValue().toInt() else 0
            peer.incoming = if (dictContains(dictionary, "incoming")) dictGet("incoming")!!.asIntegerValue().toInt() else 0
            peer.rxBytes = if (dictContains(dictionary, "rx_bytes")) dictGet("rx_bytes")!!.asIntegerValue().toLong() else 0L
            peer.txBytes = if (dictContains(dictionary, "tx_bytes")) dictGet("tx_bytes")!!.asIntegerValue().toLong() else 0L
            peer.lastSyncAttempt = if (dictContains(dictionary, "last_sync_attempt")) numAsDouble(dictGet("last_sync_attempt")!!) else 0.0
            peer.peeringKey =
                if (dictContains(dictionary, "peering_key")) {
                    val kv = dictGet("peering_key")
                    if (kv == null || kv.isNilValue) null else decodePeeringKey(kv)
                } else null
            peer.metadata =
                if (dictContains(dictionary, "metadata")) {
                    val mv = dictGet("metadata")
                    if (mv == null || mv.isNilValue) null else valueToAny(mv) as? Map<*, *>
                } else null

            var hmCount = 0
            for (transientId in dictGet("handled_ids")!!.asArrayValue()) {
                val tid = transientId.asBinaryValue().asByteArray()
                if (router.propagationEntriesMap.containsKey(tid.toHexString())) {
                    peer.addHandledMessage(tid)
                    hmCount += 1
                }
            }

            var umCount = 0
            for (transientId in dictGet("unhandled_ids")!!.asArrayValue()) {
                val tid = transientId.asBinaryValue().asByteArray()
                if (router.propagationEntriesMap.containsKey(tid.toHexString())) {
                    peer.addUnhandledMessage(tid)
                    umCount += 1
                }
            }

            peer.hmCountInternal = hmCount
            peer.umCountInternal = umCount
            peer.hmCountsSynced = true
            peer.umCountsSynced = true

            return peer
        }

        private fun dictContains(dict: org.msgpack.value.MapValue, key: String): Boolean =
            dict.map().containsKey(ValueFactory.newString(key.toByteArray()))

        private fun decodePeeringKey(v: org.msgpack.value.Value): Pair<ByteArray, Int>? {
            // Serialised as a two-element array [stamp, value]
            if (!v.isArrayValue) return null
            val arr = v.asArrayValue()
            if (arr.size() != 2) return null
            return try {
                Pair(arr[0].asBinaryValue().asByteArray(), arr[1].asIntegerValue().toInt())
            } catch (_: Exception) {
                null
            }
        }

        private fun valueToAny(v: org.msgpack.value.Value): Any? = when {
            v.isNilValue -> null
            v.isBooleanValue -> v.asBooleanValue().getBoolean()
            v.isIntegerValue -> v.asIntegerValue().toLong()
            v.isFloatValue -> v.asFloatValue().toDouble()
            v.isStringValue -> v.asStringValue().asString()
            v.isBinaryValue -> v.asBinaryValue().asByteArray()
            v.isArrayValue -> v.asArrayValue().map { valueToAny(it) }
            v.isMapValue -> v.asMapValue().map().entries.associate {
                (valueToAny(it.key) as Any?) to valueToAny(it.value)
            }
            else -> null
        }

        private fun anyToValue(v: Any?): org.msgpack.value.Value = when (v) {
            null -> ValueFactory.newNil()
            is Boolean -> ValueFactory.newBoolean(v)
            is Int -> ValueFactory.newInteger(v.toLong())
            is Long -> ValueFactory.newInteger(v)
            is Double -> ValueFactory.newFloat(v)
            is Float -> ValueFactory.newFloat(v.toDouble())
            is String -> ValueFactory.newString(v.toByteArray())
            is ByteArray -> ValueFactory.newBinary(v)
            is List<*> -> ValueFactory.newArray(v.map { anyToValue(it) })
            is Map<*, *> -> {
                val mb = ValueFactory.newMapBuilder()
                for ((k, value) in v) {
                    mb.put(anyToValue(k), anyToValue(value))
                }
                mb.build()
            }
            else -> ValueFactory.newString(v.toString().toByteArray())
        }
    }

    // ===== Live state =====

    var alive: Boolean = false
    var lastHeard: Double = 0.0
    var peeringKey: Pair<ByteArray, Int>? = null
    var peeringCost: Int? = null
    var metadata: Map<*, *>? = null

    var nextSyncAttempt: Double = 0.0
    var lastSyncAttempt: Double = 0.0
    var syncBackoff: Int = 0
    var peeringTimebase: Double = 0.0
    var linkEstablishmentRate: Double = 0.0
    var syncTransferRate: Double = 0.0

    var propagationTransferLimit: Double? = null
    var propagationSyncLimit: Double? = null
    var propagationStampCost: Int? = null
    var propagationStampCostFlexibility: Int? = null
    var currentlyTransferringMessages: List<ByteArray>? = null

    /**
     * Consecutive sync rounds in which an entry was wanted by the peer but
     * omitted from the payload (backing file unreadable). Keyed by transient
     * ID hex. After [MAX_UNSENDABLE_ROUNDS] consecutive omissions the entry
     * is dead-lettered (marked handled with an error log) so the persistent
     * strategy cannot loop forever — bounded-retry hardening, Greptile
     * PR#38 r5.
     */
    val unsendableRoundCount = HashMap<String, Int>()

    val handledMessagesQueue = ArrayDeque<ByteArray>()
    val unhandledMessagesQueue = ArrayDeque<ByteArray>()

    /** Messages offered to this peer */
    var offered: Int = 0

    /** Messages transferred to this peer */
    var outgoing: Int = 0

    /** Messages received from this peer */
    var incoming: Int = 0

    /** Bytes received from this peer */
    var rxBytes: Long = 0

    /** Bytes sent to this peer */
    var txBytes: Long = 0

    var hmCountInternal: Int = 0
    var umCountInternal: Int = 0
    var hmCountsSynced: Boolean = false
    var umCountsSynced: Boolean = false

    private val peeringKeyLock = ReentrantLock()

    var link: Link? = null
    var state: Int = IDLE

    var lastOffer: List<ByteArray> = emptyList()
    private var currentSyncTransferStarted: Double? = null

    // ===== Identity resolution =====
    //
    // Python __init__ recalls the peer's identity from the Transport cache;
    // if unavailable, destination stays None and resolution is retried on
    // the next sync attempt. Identical semantics here.

    var identity: Identity? = Identity.recall(destinationHash)

    var destination: Destination? = identity?.let {
        Destination.create(
            identity = it,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = LXMFConstants.APP_NAME,
            LXMRouter.PROPAGATION_ASPECT,
        )
    } ?: run {
        println(
            "[LXMPeer] Could not recall identity for LXMF propagation peer ${prettyHexRep(destinationHash)}, " +
                "will retry identity resolution on next sync"
        )
        null
    }

    // ===== Peering key =====

    /**
     * Whether a valid peering key of at least the required value exists.
     * Mirrors Python `peering_key_ready`, including the mismatch path that
     * clears an insufficient key so it will be regenerated.
     */
    fun peeringKeyReady(): Boolean {
        if (peeringCost == null) return false
        val key = peeringKey
        if (key != null && key.second >= peeringCost!!) return true
        if (key != null) {
            println(
                "[LXMPeer] Peering key value mismatch for $this. Current value is ${key.second}, " +
                    "but peer requires $peeringCost. Scheduling regeneration..."
            )
            peeringKey = null
        }
        return false
    }

    fun peeringKeyValue(): Int? = peeringKey?.second

    /**
     * Generate a proof-of-work peering key against the combined peer+local
     * identity material. Mirrors Python `generate_peering_key` including the
     * lock-guarded early-return when a key already exists, and the strict
     * identity-recall requirement (no fallback trust).
     */
    fun generatePeeringKey(): Boolean {
        if (peeringCost == null) return false
        peeringKeyLock.withLock {
            if (peeringKey != null) return true

            println("[LXMPeer] Generating peering key for $this")

            val routerIdentity = router.identityOrNull()
            if (routerIdentity == null) {
                println("[LXMPeer] Could not update peering key for $this since the local LXMF router identity is not configured")
                return false
            }

            if (identity == null) {
                identity = Identity.recall(destinationHash)
                if (identity == null) {
                    println("[LXMPeer] Could not update peering key for $this since its identity could not be recalled")
                    return false
                }
            }

            val keyMaterial = identity!!.hash + routerIdentity.hash
            val result = runBlocking {
                LXStamper.generateStampWithWorkblock(
                    messageId = keyMaterial,
                    stampCost = peeringCost!!,
                    expandRounds = LXStamper.WORKBLOCK_EXPAND_ROUNDS_PEERING,
                )
            }
            return if (result.stamp != null && result.value >= peeringCost!!) {
                peeringKey = Pair(result.stamp, result.value)
                println("[LXMPeer] Peering key successfully generated for $this")
                true
            } else {
                false
            }
        }
    }

    // ===== Sync =====

    /**
     * Initiate a propagation-node sync with this peer. Mirrors Python
     * `sync` including all postpone conditions, backoff accounting, offer
     * preparation (purged/low-value filtering, weight ordering, transfer and
     * sync limits) and the blocking path-request grace wait.
     */
    fun sync() {
        println("[LXMPeer] Initiating LXMF Propagation Node sync with peer ${prettyHexRep(destinationHash)}")
        lastSyncAttempt = nowSeconds()

        val syncTimeReached = nowSeconds() > nextSyncAttempt
        val stampCostsKnown =
            propagationStampCost != null &&
                propagationStampCostFlexibility != null &&
                peeringCost != null
        val peeringKeyReady = peeringKeyReady()
        val syncChecks = syncTimeReached && stampCostsKnown && peeringKeyReady

        if (!syncChecks) {
            try {
                val postponeReason = when {
                    !syncTimeReached -> {
                        if (lastSyncAttempt > lastHeard) alive = false
                        " due to previous failures"
                    }
                    !stampCostsKnown -> " since its required stamp costs are not yet known"
                    else -> {
                        // Peering key not generated yet — regenerate off-thread
                        val self = this
                        kotlin.concurrent.thread(isDaemon = true) { self.generatePeeringKey() }
                        " since a peering key has not been generated yet"
                    }
                }

                val delay = nextSyncAttempt - nowSeconds()
                val postponeDelay = if (delay > 0) " for ${prettyTime(delay)}" else ""
                println(
                    "[LXMPeer] Postponing sync with peer ${prettyHexRep(destinationHash)}$postponeDelay$postponeReason"
                )
            } catch (e: Exception) {
                println("[LXMPeer] Error while evaluating sync postponement: $e")
            }
        } else {
            if (!Transport.hasPath(destinationHash)) {
                println("[LXMPeer] No path to peer ${prettyHexRep(destinationHash)} exists, requesting...")
                Transport.requestPath(destinationHash)
                Thread.sleep((PATH_REQUEST_GRACE * 1000).toLong())
            }

            if (!Transport.hasPath(destinationHash)) {
                println("[LXMPeer] Path request was not answered, retrying sync with peer ${prettyHexRep(destinationHash)} later")
            } else {
                if (identity == null) {
                    identity = Identity.recall(destinationHash)
                    if (identity != null) {
                        destination = Destination.create(
                            identity = identity,
                            direction = DestinationDirection.OUT,
                            type = DestinationType.SINGLE,
                            appName = LXMFConstants.APP_NAME,
                            LXMRouter.PROPAGATION_ASPECT,
                        )
                    }
                }

                if (destination != null) {
                    if (unhandledMessages.isEmpty()) {
                        println("[LXMPeer] Sync requested for $this, but no unhandled messages exist for peer. Sync complete.")
                        return
                    }

                    if (currentlyTransferringMessages != null) {
                        println("[LXMPeer] Sync requested for $this, but current message transfer index was not clear. Aborting.")
                        return
                    }

                    if (state == IDLE) {
                        println("[LXMPeer] Establishing link for sync to peer ${prettyHexRep(destinationHash)}...")
                        syncBackoff += SYNC_BACKOFF_STEP
                        nextSyncAttempt = nowSeconds() + syncBackoff
                        val self = this
                        val dest = destination ?: error("Destination unexpectedly null during link establishment")
                        link = Link.create(
                            destination = dest,
                            establishedCallback = { l -> self.linkEstablished(l) },
                            closedCallback = { l -> self.linkClosed(l) },
                        )

                        state = LINK_ESTABLISHING
                    } else {
                        if (state == LINK_READY) {
                            alive = true
                            lastHeard = nowSeconds()
                            syncBackoff = 0
                            val minAcceptedCost =
                                maxOf(0, propagationStampCost!! - propagationStampCostFlexibility!!)

                            println(
                                "[LXMPeer] Synchronisation link to peer ${prettyHexRep(destinationHash)} established, preparing sync offer..."
                            )
                            val unhandledEntries = mutableListOf<Triple<ByteArray, Double, Int>>()
                            val unhandledIds = mutableListOf<ByteArray>()
                            val purgedIds = mutableListOf<ByteArray>()
                            val lowValueIds = mutableListOf<ByteArray>()
                            for (transientId in unhandledMessages) {
                                val entry = router.propagationEntriesMap[transientId.toHexString()]
                                if (entry != null) {
                                    val stampValue = router.getStampValue(transientId) ?: 0
                                    if (stampValue < minAcceptedCost) lowValueIds.add(transientId)
                                    else unhandledEntries.add(Triple(transientId, router.getWeight(transientId), router.getSize(transientId)))
                                } else purgedIds.add(transientId)
                            }

                            for (transientId in purgedIds) {
                                println(
                                    "[LXMPeer] Dropping unhandled message ${prettyHexRep(transientId)} for peer " +
                                        "${prettyHexRep(destinationHash)} since it no longer exists in the message store."
                                )
                                removeUnhandledMessage(transientId)
                            }

                            for (transientId in lowValueIds) {
                                println(
                                    "[LXMPeer] Dropping unhandled message ${prettyHexRep(transientId)} for peer " +
                                        "${prettyHexRep(destinationHash)} since its stamp value is lower than peer requirement of $minAcceptedCost."
                                )
                                removeUnhandledMessage(transientId)
                            }

                            unhandledEntries.sortBy { it.second }
                            val perMessageOverhead = 16 // Really only 2 bytes, but set a bit higher for now
                            var cumulativeSize = 24 // Initialised to highest reasonable binary structure overhead

                            for (entry in unhandledEntries) {
                                val transientId = entry.first
                                val lxmSize = entry.third
                                val lxmTransferSize = lxmSize + perMessageOverhead
                                val nextSize = cumulativeSize + lxmTransferSize

                                if (propagationTransferLimit != null && lxmTransferSize > (propagationTransferLimit!! * 1000)) {
                                    removeUnhandledMessage(transientId)
                                    addHandledMessage(transientId)
                                    continue
                                }

                                if (propagationSyncLimit != null && nextSize >= (propagationSyncLimit!! * 1000)) {
                                    continue
                                }

                                cumulativeSize += lxmTransferSize
                                unhandledIds.add(transientId)
                            }

                            if (unhandledIds.isEmpty()) {
                                println("[LXMPeer] Sync requested for $this, but no unhandled messages exist after offer preparation. Sync complete.")
                                return
                            }

                            val offer = listOf(peeringKey!!.first, unhandledIds)

                            println("[LXMPeer] Offering ${unhandledIds.size} messages to peer ${prettyHexRep(destination!!.hash)}")
                            lastOffer = unhandledIds.toList()
                            val self = this
                            link?.request(
                                path = OFFER_REQUEST_PATH,
                                data = offer,
                                responseCallback = { receipt -> self.offerResponse(receipt) },
                                failedCallback = { receipt -> self.requestFailed(receipt) },
                            )
                            state = REQUEST_SENT
                        }
                    }
                } else {
                    println("[LXMPeer] Could not request sync to peer ${prettyHexRep(destinationHash)} since its identity could not be recalled.")
                }
            }
        }
    }

    private fun requestFailed(requestReceipt: network.reticulum.link.RequestReceipt) {
        println("[LXMPeer] Sync request to peer $destination failed")
        link?.teardown()
        state = IDLE
    }

    private fun offerResponse(requestReceipt: network.reticulum.link.RequestReceipt) {
        try {
            state = RESPONSE_RECEIVED
            val responseBytes = requestReceipt.response

            val wantedMessages = mutableListOf<LXMRouter.PropagationEntry>()
            val wantedMessageIds = mutableListOf<ByteArray>()

            // Decode the response into either an error code (single byte/int)
            // or a boolean / wanted-id-list payload.
            val decoded = decodeOfferResponse(responseBytes)

            when (val response = decoded) {
                is OfferResponse.ErrorCode -> when (response.code) {
                    ERROR_NO_IDENTITY -> {
                        if (link != null) {
                            println("[LXMPeer] Remote peer indicated that no identification was received, retrying...")
                            router.identityOrNull()?.let { link?.identify(it) }
                            state = LINK_READY
                            sync()
                            return
                        }
                    }
                    ERROR_NO_ACCESS -> {
                        println("[LXMPeer] Remote indicated that access was denied, breaking peering")
                        router.unpeer(destinationHash)
                        return
                    }
                    ERROR_THROTTLED -> {
                        val throttleTime = LXMRouter.PN_STAMP_THROTTLE
                        println("[LXMPeer] Remote indicated that we're throttled, postponing sync for ${prettyTime(throttleTime.toDouble())}")
                        nextSyncAttempt = nowSeconds() + throttleTime
                        return
                    }
                }
                is OfferResponse.BooleanResponse -> {
                    if (!response.value) {
                        // Peer already has all advertised messages
                        for (transientId in lastOffer) {
                            if (containsId(unhandledMessages, transientId)) {
                                addHandledMessage(transientId)
                                removeUnhandledMessage(transientId)
                            }
                        }
                    } else {
                        // Peer wants all advertised messages. Bounds are the
                        // offer itself, so no filtering needed here beyond the
                        // store lookup (PR#38 P1 hardening note).
                        for (transientId in lastOffer) {
                            router.propagationEntriesMap[transientId.toHexString()]?.let {
                                wantedMessages.add(it)
                                wantedMessageIds.add(transientId)
                            }
                        }
                    }
                }
                is OfferResponse.WantedIds -> {
                    // Peer wants some advertised messages
                    for (transientId in lastOffer) {
                        // If the peer did not want the message, it has already
                        // received it from another peer.
                        if (!containsId(response.ids, transientId)) {
                            addHandledMessage(transientId)
                            removeUnhandledMessage(transientId)
                        }
                    }
                    // Security hardening (PR#38 review P1): only accept IDs that
                    // are (a) inside the CURRENT offer and (b) seen once. A peer
                    // replying with duplicate or never-offered IDs must not be
                    // able to expand the transfer beyond the advertised bounds.
                    // This is deliberately STRICTER than the Python reference
                    // (LXMPeer.py offer_response iterates response.ids against
                    // the global store; unoffered IDs raise KeyError and abort
                    // the sync, duplicates re-read files). Documented in
                    // port-deviations.md as a hardened deviation.
                    val wantedSeen = mutableSetOf<String>()
                    for (transientId in response.ids) {
                        if (!containsId(lastOffer, transientId)) continue
                        val hex = transientId.toHexString()
                        if (!wantedSeen.add(hex)) continue
                        router.propagationEntriesMap[hex]?.let {
                            wantedMessages.add(it)
                            wantedMessageIds.add(transientId)
                        }
                    }
                }
                null -> {
                    println("[LXMPeer] Received undecodable offer response from peer ${prettyHexRep(destinationHash)}")
                }
            }

            if (wantedMessages.isNotEmpty()) {
                println("[LXMPeer] Peer ${prettyHexRep(destinationHash)} wanted ${wantedMessages.size} of the available messages")

                val lxmList = mutableListOf<ByteArray>()
                val transferredIds = mutableListOf<ByteArray>()
                val omittedIds = mutableListOf<ByteArray>()
                for (entryIndex in wantedMessages.indices) {
                    val messageEntry = wantedMessages[entryIndex]
                    val filePath = messageEntry.filePath ?: continue
                    val file = java.io.File(filePath)
                    if (file.isFile) {
                        lxmList.add(file.readBytes())
                        // Track only entries whose bytes actually made it into
                        // the payload. Completion bookkeeping (handled/unhandled
                        // flips, counters) must never include an entry that was
                        // omitted — otherwise a missing/corrupt store file would
                        // permanently mark an unsent message as delivered.
                        // (Greptile PR#38 re-review finding; python reference
                        // has the same divergence — hardened beyond upstream.)
                        transferredIds.add(wantedMessageIds[entryIndex])
                    } else {
                        omittedIds.add(wantedMessageIds[entryIndex])
                    }
                }

                if (omittedIds.isNotEmpty()) {
                    println("[LXMPeer] ${omittedIds.size} of ${wantedMessages.size} wanted entries unreadable from the message store")
                }

                if (transferredIds.isEmpty()) {
                    println("[LXMPeer] Peer ${prettyHexRep(destinationHash)} wanted ${wantedMessages.size} messages, but none could be read from the message store")
                    offered += lastOffer.size
                    link?.teardown()
                    link = null
                    state = IDLE
                    return
                }

                val packer = MessagePack.newDefaultBufferPacker()
                packer.packArrayHeader(2)
                packer.packDouble(nowSeconds())
                packer.packArrayHeader(lxmList.size)
                for (lxmData in lxmList) packer.packBinaryHeader(lxmData.size).writePayload(lxmData)
                packer.close()
                val data = packer.toByteArray()
                println("[LXMPeer] Total transfer size for this sync is ${data.size} bytes")
                val self = this
                val resource = Resource.create(data, link!!, callback = { r -> self.resourceConcluded(r) })
                // Only IDs whose bytes are in this payload — completion
                // bookkeeping keys off this list (see transferredIds above).
                currentlyTransferringMessages = transferredIds.toList()
                // Dead-letter tracking: entries omitted from the payload are
                // counted per consecutive round; after MAX_UNSENDABLE_ROUNDS
                // they are marked handled with a loud log (bounded retry —
                // Greptile PR#38 r5). Prevents the persistent-strategy
                // immediate re-sync from looping forever on permanently
                // unreadable store files.
                if (omittedIds.isNotEmpty()) {
                    for (tid in omittedIds) {
                        val hex = tid.toHexString()
                        unsendableRoundCount[hex] = (unsendableRoundCount[hex] ?: 0) + 1
                    }
                    // Successful partial transfer resets nothing: counts only
                    // decay via dead-lettering or manual store repair.
                } else {
                    unsendableRoundCount.clear()
                }
                currentSyncTransferStarted = nowSeconds()
                state = RESOURCE_TRANSFERRING
            } else {
                println("[LXMPeer] Peer ${prettyHexRep(destinationHash)} did not request any of the available messages, sync completed")
                offered += lastOffer.size
                link?.teardown()
                link = null
                state = IDLE
            }
        } catch (e: Exception) {
            println("[LXMPeer] Error while handling offer response from peer $destination")
            println("[LXMPeer] The contained exception was: $e")

            link?.teardown()
            link = null
            state = IDLE
        }
    }

    private fun resourceConcluded(resource: Resource) {
        if (resource.status == ResourceConstants.COMPLETE) {
            val transferring = currentlyTransferringMessages
            if (transferring == null) {
                println("[LXMPeer] Sync transfer completed on $this, but transferred message index was unavailable. Aborting.")
                link?.teardown()
                link = null
                state = IDLE
                return
            }

            for (transientId in transferring) {
                addHandledMessage(transientId)
                removeUnhandledMessage(transientId)
            }

            // Dead-letter entries that have now been omitted for
            // MAX_UNSENDABLE_ROUNDS consecutive rounds. Their bytes cannot be
            // read, so they will never transfer; without this cap the
            // persistent strategy would re-offer them forever (Greptile
            // PR#38 r5). Loud log so operators can repair the store.
            val deadLettered = mutableListOf<ByteArray>()
            for ((hex, rounds) in unsendableRoundCount) {
                if (rounds >= MAX_UNSENDABLE_ROUNDS) {
                    val tid = hex.hexToBytesCompat()
                    addHandledMessage(tid)
                    removeUnhandledMessage(tid)
                    deadLettered.add(tid)
                }
            }
            if (deadLettered.isNotEmpty()) {
                println(
                    "[LXMPeer] DEAD-LETTER: ${deadLettered.size} entry(ies) omitted " +
                        "from transfers for $MAX_UNSENDABLE_ROUNDS consecutive syncs; " +
                        "marking handled to stop the retry loop. Store files are " +
                        "missing/unreadable — inspect the message store for peer " +
                        "${prettyHexRep(destinationHash)}: " +
                        deadLettered.joinToString(", ") { prettyHexRep(it) }
                )
                for (tid in deadLettered) {
                    unsendableRoundCount.remove(tid.toHexString())
                }
            }

            link?.teardown()
            link = null
            state = IDLE

            if (currentSyncTransferStarted != null) {
                val elapsed = nowSeconds() - currentSyncTransferStarted!!
                if (elapsed > 0) {
                    // resource.totalSize is the uncompressed data size (Python get_data_size);
                    // size is the transfer size (Python get_transfer_size).
                    syncTransferRate = (resource.size * 8.0) / elapsed
                }
            }
            println("[LXMPeer] Syncing ${transferring.size} messages to peer ${prettyHexRep(destinationHash)} completed")
            alive = true

            lastHeard = nowSeconds()
            offered += lastOffer.size
            outgoing += transferring.size
            txBytes += resource.totalSize


            currentlyTransferringMessages = null
            currentSyncTransferStarted = null

            if (syncStrategy == STRATEGY_PERSISTENT) {
                if (unhandledMessageCount > 0) sync()
            }
        } else {
            println("[LXMPeer] Resource transfer for LXMF peer sync failed to $destination")
            link?.teardown()
            link = null
            state = IDLE
            currentlyTransferringMessages = null
            currentSyncTransferStarted = null
        }
    }

    fun linkEstablished(link: Link?) {
        router.identityOrNull()?.let { this.link?.identify(it) }
        val rate = link?.getEstablishmentRate()
        if (rate != null) {
            // rns-kt reports bits/second; Python stores bytes-per-second style
            // establishment rate directly from link.get_establishment_rate().
            linkEstablishmentRate = rate.toDouble()
        }

        state = LINK_READY
        nextSyncAttempt = 0.0
        sync()
    }

    fun linkClosed(closedLink: Link?) {
        this.link = null
        state = IDLE
    }

    // ===== Distribution queues =====

    fun queuedItems(): Boolean = handledMessagesQueue.isNotEmpty() || unhandledMessagesQueue.isNotEmpty()

    fun queueUnhandledMessage(transientId: ByteArray) {
        unhandledMessagesQueue.addLast(transientId)
    }

    fun queueHandledMessage(transientId: ByteArray) {
        handledMessagesQueue.addLast(transientId)
    }

    /**
     * Drain pending distribution queues into the live handled/unhandled sets.
     * Mirrors Python `process_queues` including pop-from-end order and the
     * duplicate-suppression rules.
     */
    fun processQueues() {
        if (unhandledMessagesQueue.isNotEmpty() || handledMessagesQueue.isNotEmpty()) {
            val handledMessages = handledMessages
            val unhandledMessages = unhandledMessages

            while (handledMessagesQueue.isNotEmpty()) {
                val transientId = handledMessagesQueue.removeLast()
                if (!containsId(handledMessages, transientId)) addHandledMessage(transientId)
                if (containsId(unhandledMessages, transientId)) removeUnhandledMessage(transientId)
            }

            while (unhandledMessagesQueue.isNotEmpty()) {
                val transientId = unhandledMessagesQueue.removeLast()
                if (!containsId(handledMessages, transientId) && !containsId(unhandledMessages, transientId)) {
                    addUnhandledMessage(transientId)
                }
            }
        }
    }

    // ===== Handled / unhandled message views =====
    //
    // In Python these are computed properties over router.propagation_entries:
    // a message is "handled" for this peer when this peer's destination hash
    // appears in entry[4], and "unhandled" when it appears in entry[5].
    // The Kotlin PropagationEntry models those slots as handledBy/unhandledBy
    // lists; identical membership semantics.

    val handledMessages: List<ByteArray>
        get() {
            val hm = router.propagationEntriesMap.entries
                .filter { containsHash(it.value.handledBy, destinationHash) }
                .map { it.key.hexToBytesCompat() }
            hmCountInternal = hm.size
            hmCountsSynced = true
            return hm
        }

    val unhandledMessages: List<ByteArray>
        get() {
            val um = router.propagationEntriesMap.entries
                .filter { containsHash(it.value.unhandledBy, destinationHash) }
                .map { it.key.hexToBytesCompat() }
            umCountInternal = um.size
            umCountsSynced = true
            return um
        }

    val handledMessageCount: Int
        get() {
            if (!hmCountsSynced) updateCounts()
            return hmCountInternal
        }

    val unhandledMessageCount: Int
        get() {
            if (!umCountsSynced) updateCounts()
            return umCountInternal
        }

    val acceptanceRate: Double
        get() = if (offered == 0) 0.0 else (outgoing.toDouble() / offered.toDouble())

    fun updateCounts() {
        if (!hmCountsSynced) handledMessages
        if (!umCountsSynced) unhandledMessages
    }

    fun addHandledMessage(transientId: ByteArray) {
        router.propagationEntriesMap[transientId.toHexString()]?.let { entry ->
            if (!containsHash(entry.handledBy, destinationHash)) {
                entry.handledBy.add(destinationHash.copyOf())
                hmCountsSynced = false
            }
        }
    }

    fun addUnhandledMessage(transientId: ByteArray) {
        router.propagationEntriesMap[transientId.toHexString()]?.let { entry ->
            if (!containsHash(entry.unhandledBy, destinationHash)) {
                entry.unhandledBy.add(destinationHash.copyOf())
                umCountInternal += 1
            }
        }
    }

    fun removeHandledMessage(transientId: ByteArray) {
        router.propagationEntriesMap[transientId.toHexString()]?.let { entry ->
            if (removeHash(entry.handledBy, destinationHash)) {
                hmCountsSynced = false
            }
        }
    }

    fun removeUnhandledMessage(transientId: ByteArray) {
        router.propagationEntriesMap[transientId.toHexString()]?.let { entry ->
            if (removeHash(entry.unhandledBy, destinationHash)) {
                umCountsSynced = false
            }
        }
    }

    /** Display name from peer metadata, if present (Python `name` property). */
    val name: String?
        get() {
            val md = metadata ?: return null
            val raw = md[LXMFConstants.PN_META_NAME] ?: return null
            return try {
                when (raw) {
                    is ByteArray -> String(raw, Charsets.UTF_8)
                    is String -> raw
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

    override fun toString(): String =
        if (destinationHash != null) prettyHexRep(destinationHash) else "<Unknown>"

    // ===== Serialization =====

    fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val fields = linkedMapOf<String, Any?>(
            "peering_timebase" to peeringTimebase,
            "alive" to alive,
            "metadata" to metadata,
            "last_heard" to lastHeard,
            "sync_strategy" to syncStrategy,
            "peering_key" to peeringKey?.let { listOf<Any?>(it.first, it.second) },
            "destination_hash" to destinationHash,
            "link_establishment_rate" to linkEstablishmentRate,
            "sync_transfer_rate" to syncTransferRate,
            "propagation_transfer_limit" to propagationTransferLimit,
            "propagation_sync_limit" to propagationSyncLimit,
            "propagation_stamp_cost" to propagationStampCost,
            "propagation_stamp_cost_flexibility" to propagationStampCostFlexibility,
            "peering_cost" to peeringCost,
            "last_sync_attempt" to lastSyncAttempt,
            "offered" to offered,
            "outgoing" to outgoing,
            "incoming" to incoming,
            "rx_bytes" to rxBytes,
            "tx_bytes" to txBytes,
            "handled_ids" to handledMessages,
            "unhandled_ids" to unhandledMessages,
        )

        val mapBuilder = ValueFactory.newMapBuilder()
        for ((k, v) in fields) {
            mapBuilder.put(ValueFactory.newString(k.toByteArray()), anyToValue(v))
        }
        packer.packValue(mapBuilder.build())
        packer.close()
        return packer.toByteArray()
    }

    private fun String.hexToBytesCompat(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ===== Helpers =====

    /** Sealed view of a decoded offer response payload. */
    private sealed interface OfferResponse {
        data class ErrorCode(val code: Int) : OfferResponse
        data class BooleanResponse(val value: Boolean) : OfferResponse
        data class WantedIds(val ids: List<ByteArray>) : OfferResponse
    }

    private fun decodeOfferResponse(bytes: ByteArray?): OfferResponse? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val value = unpacker.unpackValue()
            unpacker.close()
            when {
                value.isIntegerValue -> OfferResponse.ErrorCode(value.asIntegerValue().toInt())
                value.isBooleanValue -> OfferResponse.BooleanResponse(value.asBooleanValue().getBoolean())
                value.isArrayValue -> OfferResponse.WantedIds(
                    value.asArrayValue().map { it.asBinaryValue().asByteArray() }
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun containsId(ids: List<ByteArray>, id: ByteArray): Boolean =
        ids.any { it.contentEquals(id) }

    private fun containsHash(hashes: List<ByteArray>, hash: ByteArray): Boolean =
        hashes.any { it.contentEquals(hash) }

    private fun removeHash(hashes: MutableList<ByteArray>, hash: ByteArray): Boolean {
        val it = hashes.iterator()
        while (it.hasNext()) {
            if (it.next().contentEquals(hash)) {
                it.remove()
                return true
            }
        }
        return false
    }

    private fun prettyHexRep(bytes: ByteArray): String = bytes.joinToString(":") { "%02x".format(it) }

    private fun prettyTime(seconds: Double): String = "${"%.1f".format(seconds)}s"

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
