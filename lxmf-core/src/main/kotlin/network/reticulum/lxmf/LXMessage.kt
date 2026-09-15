package network.reticulum.lxmf

import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * LXMF Message class.
 *
 * Represents a message in the LXMF format with support for packing/unpacking
 * that is byte-perfect compatible with Python LXMF.
 *
 * Wire format:
 * ```
 * [0:16]   Destination hash (16 bytes)
 * [16:32]  Source hash (16 bytes)
 * [32:96]  Ed25519 signature (64 bytes)
 * [96:]    Msgpack payload
 * ```
 *
 * Payload structure (msgpack list):
 * ```
 * [0] timestamp  - float64 (UNIX epoch seconds)
 * [1] title      - bytes (UTF-8)
 * [2] content    - bytes (UTF-8)
 * [3] fields     - dict (extensible)
 * [4] stamp      - bytes (optional, 32 bytes proof-of-work)
 * ```
 */
class LXMessage private constructor(
    /** Destination for this message */
    destination: Destination?,
    /** Source destination (sender) */
    source: Destination?,
    /** Destination hash (always available even if destination is null) */
    val destinationHash: ByteArray,
    /** Source hash (always available even if source is null) */
    val sourceHash: ByteArray,
    /** Message title */
    var title: String,
    /** Message content */
    var content: String,
    /** Extended fields dictionary */
    val fields: MutableMap<Int, Any> = mutableMapOf(),
    /** Desired delivery method */
    var desiredMethod: DeliveryMethod? = null,
) {
    // ===== Message Identification =====

    /** Destination for this message (set-once; see [setDestination]) */
    var destination: Destination? = destination
        private set

    /** Source destination (sender) (set-once; see [setSource]) */
    var source: Destination? = source
        private set

    /** Full message hash (32 bytes SHA-256) */
    var hash: ByteArray? = null
        private set

    /** Message ID (same as hash) */
    val messageId: ByteArray?
        get() = hash

    /** Transient ID for propagation (hash of encrypted data) */
    var transientId: ByteArray? = null
        private set

    // ===== State and Flags =====

    /** Current message state */
    var state: MessageState = MessageState.GENERATING

    /** Message representation (PACKET or RESOURCE) */
    var representation: MessageRepresentation = MessageRepresentation.UNKNOWN

    /** Actual delivery method used */
    var method: DeliveryMethod? = null

    /** Whether this is an incoming message */
    var incoming: Boolean = false

    /** Whether the signature has been validated */
    var signatureValidated: Boolean = false

    /** Reason why signature validation failed */
    var unverifiedReason: UnverifiedReason? = null

    // ===== Timestamps =====

    /** Message timestamp (UNIX epoch seconds as Double) */
    var timestamp: Double? = null

    // ===== Packed Data =====

    /** Packed message bytes (wire format) */
    var packed: ByteArray? = null
        private set

    /** Size of packed message */
    val packedSize: Int
        get() = packed?.size ?: 0

    /** Ed25519 signature (64 bytes) */
    var signature: ByteArray? = null
        private set

    /** Proof-of-work stamp (32 bytes, optional) */
    var stamp: ByteArray? = null

    /** Whether the stamp has been validated */
    var stampValid: Boolean = false

    /** Whether the stamp has been checked */
    var stampChecked: Boolean = false

    /** Validated stamp value (leading zero bits), or null if not checked */
    var stampValue: Int? = null

    /** Required stamp cost for this message */
    var stampCost: Int? = null

    /** Outbound ticket for stamp bypass */
    var outboundTicket: ByteArray? = null

    /** Whether to include a ticket in this message */
    var includeTicket: Boolean = false

    /** Whether to defer stamp generation (compute later in background) */
    var deferStamp: Boolean = false

    /**
     * Whether to defer propagation-stamp generation (LXMessage.py:164).
     * Informational in this port: generation is always router-driven.
     */
    var deferPropagationStamp: Boolean = false

    /**
     * Whether the receiver (per its announce app data) supports compression.
     * Set by [determineCompressionSupport]; defaults true like python's
     * `auto_compress = True` (LXMessage.py:146).
     */
    var autoCompress: Boolean = true

    // ===== Propagation Stamp State =====

    /** Propagation-node proof-of-work stamp (32 bytes, optional) */
    var propagationStamp: ByteArray? = null

    /** Validated propagation stamp value (leading zero bits), or null */
    var propagationStampValue: Int? = null

    /** Whether the propagation stamp has been validated */
    var propagationStampValid: Boolean = false

    /** Propagation cost the target node requested; set during stamp generation */
    var propagationTargetCost: Int? = null

    /** Ratchet id of the last packet/encryption operation, if any */
    var ratchetId: ByteArray? = null

    /** Whether the source identity is blackholed (set on unpack when known) */
    var sourceBlackholed: Boolean = false

    /** Encrypted form used for PROPAGATED delivery (destHash + encrypted packed) */
    var propagationPacked: ByteArray? = null

    /** Packed bytes for PAPER delivery (destHash + encrypted rest) */
    var paperPacked: ByteArray? = null
        private set

    // ===== Encryption State =====

    /** Whether message was transport-encrypted */
    var transportEncrypted: Boolean = false

    /** Description of transport encryption used */
    var transportEncryption: String? = null

    /**
     * Progress of message delivery (0.0 to 1.0).
     *
     * `@Volatile` because writers and readers run on different threads.
     * Writers: `LXMRouter.processOpportunisticDelivery` (LXMRouter.kt:739,
     * 755), `LXMRouter.sendViaPropagation`'s Resource progressCallback
     * (LXMRouter.kt:1258), and `LXMRouter.sendViaLink`'s Resource
     * progressCallback + completion callback (LXMRouter.kt:1335, 1340) —
     * all dispatched from `processingScope` coroutines or RNS Resource
     * background threads. Readers: any caller polling progress for UI
     * display, plus the conformance bridge's `cmdLxmfGetMessageProgress`
     * (Main.kt:740) reading from the bridge's JSON-RPC dispatch thread.
     *
     * Without `@Volatile`, the JLS allows non-volatile `double` reads to
     * tear (§17.7) and offers no happens-before edge between the write
     * and a cross-thread read — visibility of the latest value is
     * implementation-defined. Python's GIL gives this for free; on JVM
     * `@Volatile` is the direct equivalent.
     */
    @Volatile var progress: Double = 0.0

    // ===== Callbacks =====

    /** Callback when message is delivered */
    var deliveryCallback: ((LXMessage) -> Unit)? = null

    /** Callback when message delivery fails */
    var failedCallback: ((LXMessage) -> Unit)? = null

    // ===== Delivery Tracking =====

    /** Number of delivery attempts made */
    var deliveryAttempts: Int = 0

    /** Next delivery attempt timestamp (milliseconds) */
    var nextDeliveryAttempt: Long? = null

    /**
     * Whether a path re-request has already been issued for a CLOSED delivery
     * link that never activated. Transient delivery-state — NOT part of the
     * packed wire format. Mirrors Python LXMF's dynamic `path_request_retried`
     * attribute (LXMRouter.py:2615-2618), which gates the never-activated retry
     * to exactly once.
     */
    var pathRequestRetried: Boolean = false

    // ===== Receive-time Packet Metadata =====
    //
    // The following fields are populated from the delivering Reticulum packet
    // when this LXMessage is constructed on the receive side. They are only
    // meaningful for live, in-path delivery (OPPORTUNISTIC and DIRECT);
    // outgoing messages do not carry them, and messages pulled from a
    // propagation node are intentionally left null because the original
    // in-path packet context is lost (the values would reflect the
    // propagation-node sync link, not the originating sender — which would
    // be misleading).

    /**
     * RSSI of the delivering packet (signed integer, typically dBm).
     *
     * Null for outgoing messages and for messages fetched from a propagation
     * node. For Resource-delivered (multi-packet) messages, this reflects the
     * phy stats of the link at the moment the Resource assembly concluded
     * (i.e. the final constituent packet). Requires the underlying Link to
     * have `trackPhyStats(true)` enabled for Resource-delivered messages; for
     * single-packet paths the value is copied from the delivering `Packet`
     * directly and is available unconditionally.
     */
    var receivedRssi: Int? = null

    /**
     * SNR of the delivering packet.
     *
     * Null for outgoing messages and for messages fetched from a propagation
     * node. See [receivedRssi] for semantics on Resource-delivered messages.
     */
    var receivedSnr: Float? = null

    /**
     * Hash of the interface the delivering packet arrived on.
     *
     * Null for outgoing messages and for messages fetched from a propagation
     * node. For Resource-delivered messages, reflects the interface the link
     * was attached to.
     */
    var receivingInterfaceHash: ByteArray? = null

    /**
     * Number of hops the delivering packet traveled to reach us.
     *
     * Null for outgoing messages and for messages fetched from a propagation
     * node. For Resource-delivered messages, reflects the link's expected
     * hop count (established at link-setup time), which is the correct hop
     * count for the Resource because every Resource constituent packet
     * travels the same hop path as the link itself.
     */
    var receivedHopCount: Int? = null

    /**
     * Pack the message into wire format.
     *
     * This creates the packed byte array that can be sent over the network.
     * The packing process:
     * 1. Create payload list: [timestamp, title, content, fields]
     * 2. Compute hash: SHA256(destHash + sourceHash + msgpack(payload))
     * 3. Sign: Ed25519(hashedPart + hash)
     * 4. Pack: destHash + sourceHash + signature + msgpack(payload)
     *
     * @return The packed message bytes
     * @throws IllegalStateException if source has no private key for signing
     */
    fun pack(): ByteArray {
        if (packed != null) {
            return packed!!
        }

        // Set timestamp if not set
        if (timestamp == null) {
            timestamp = System.currentTimeMillis() / 1000.0
        }

        // Get source identity for signing
        val sourceIdentity =
            source?.identity
                ?: throw IllegalStateException("Cannot pack message without source identity")
        require(sourceIdentity.hasPrivateKey) { "Cannot pack message: source has no private key" }

        // Build payload: [timestamp, title, content, fields]
        val payloadBytes = packPayload(timestamp!!, title, content, fields, stamp)

        // Build hashed part: destHash + sourceHash + msgpack(payload without stamp)
        val payloadWithoutStamp = packPayload(timestamp!!, title, content, fields, null)
        val hashedPart = destinationHash + sourceHash + payloadWithoutStamp

        // Compute message hash
        hash = Hashes.fullHash(hashedPart)

        // Build signed part: hashedPart + hash
        val signedPart = hashedPart + hash!!

        // Sign the message
        signature = sourceIdentity.sign(signedPart)
        signatureValidated = true

        // Build packed message: destHash + sourceHash + signature + payload
        packed = destinationHash + sourceHash + signature!! + payloadBytes

        // Determine delivery method and representation
        determineDeliveryMethod()

        return packed!!
    }

    /**
     * Re-pack the message with an updated stamp.
     *
     * Called after deferred stamp generation to update the packed bytes
     * with the newly generated stamp. The hash and signature don't change
     * because stamp is not included in the hashed/signed portion.
     */
    fun repackWithStamp() {
        if (stamp == null || hash == null || signature == null) return

        val payloadBytes = packPayload(timestamp!!, title, content, fields, stamp)
        packed = destinationHash + sourceHash + signature!! + payloadBytes

        determineDeliveryMethod()
    }

    /**
     * Determine the delivery method and representation based on message size.
     */
    private fun determineDeliveryMethod() {
        val contentSize = packed!!.size - LXMFConstants.LXMF_OVERHEAD

        // Default to DIRECT if not specified
        if (desiredMethod == null) {
            desiredMethod = DeliveryMethod.DIRECT
        }

        when (desiredMethod) {
            DeliveryMethod.OPPORTUNISTIC -> {
                if (contentSize > LXMFConstants.ENCRYPTED_PACKET_MAX_CONTENT) {
                    // Fall back to DIRECT for large messages
                    println("Opportunistic delivery requested but content too large ($contentSize bytes), falling back to DIRECT")
                    desiredMethod = DeliveryMethod.DIRECT
                    method = DeliveryMethod.DIRECT
                    representation =
                        if (contentSize <= LXMFConstants.LINK_PACKET_MAX_CONTENT) {
                            MessageRepresentation.PACKET
                        } else {
                            MessageRepresentation.RESOURCE
                        }
                } else {
                    method = DeliveryMethod.OPPORTUNISTIC
                    representation = MessageRepresentation.PACKET
                }
            }
            DeliveryMethod.DIRECT -> {
                method = DeliveryMethod.DIRECT
                representation =
                    if (contentSize <= LXMFConstants.LINK_PACKET_MAX_CONTENT) {
                        MessageRepresentation.PACKET
                    } else {
                        MessageRepresentation.RESOURCE
                    }
            }
            DeliveryMethod.PROPAGATED -> {
                method = DeliveryMethod.PROPAGATED
                // Propagated messages have additional encryption overhead
                representation = MessageRepresentation.RESOURCE // Conservative default
            }
            DeliveryMethod.PAPER -> {
                method = DeliveryMethod.PAPER
                representation = MessageRepresentation.PACKET
            }
            null -> {
                method = DeliveryMethod.DIRECT
                representation = MessageRepresentation.PACKET
            }
        }
    }

    /**
     * Pack payload into msgpack format.
     */
    private fun packPayload(
        timestamp: Double,
        title: String,
        content: String,
        fields: Map<Int, Any>,
        stamp: ByteArray?,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(buffer)

        // Pack as list with 4 or 5 elements
        val elementCount = if (stamp != null) 5 else 4
        packer.packArrayHeader(elementCount)

        // [0] timestamp as float64
        packer.packDouble(timestamp)

        // [1] title as bytes
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(titleBytes.size)
        packer.writePayload(titleBytes)

        // [2] content as bytes
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(contentBytes.size)
        packer.writePayload(contentBytes)

        // [3] fields as map
        packer.packMapHeader(fields.size)
        for ((key, value) in fields) {
            packer.packInt(key)
            packValue(packer, value)
        }

        // [4] stamp (optional)
        if (stamp != null) {
            packer.packBinaryHeader(stamp.size)
            packer.writePayload(stamp)
        }

        packer.close()
        return buffer.toByteArray()
    }

    /**
     * Pack a value into msgpack format (recursive for nested structures).
     */
    private fun packValue(
        packer: org.msgpack.core.MessagePacker,
        value: Any,
    ) {
        when (value) {
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is String -> packer.packString(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Double -> packer.packDouble(value)
            is Float -> packer.packFloat(value)
            is Boolean -> packer.packBoolean(value)
            is List<*> -> {
                packer.packArrayHeader(value.size)
                for (item in value) {
                    if (item != null) {
                        packValue(packer, item)
                    } else {
                        packer.packNil()
                    }
                }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                for ((k, v) in value) {
                    if (k != null) {
                        packValue(packer, k)
                    } else {
                        packer.packNil()
                    }
                    if (v != null) {
                        packValue(packer, v)
                    } else {
                        packer.packNil()
                    }
                }
            }
            else -> {
                // Default to string representation
                val str = value.toString().toByteArray(Charsets.UTF_8)
                packer.packBinaryHeader(str.size)
                packer.writePayload(str)
            }
        }
    }

    /**
     * Get title as bytes (UTF-8).
     */
    fun getTitleBytes(): ByteArray = title.toByteArray(Charsets.UTF_8)

    /**
     * Get content as bytes (UTF-8).
     */
    fun getContentBytes(): ByteArray = content.toByteArray(Charsets.UTF_8)

    /**
     * Set title from bytes.
     */
    fun setTitleFromBytes(bytes: ByteArray) {
        title = bytes.toString(Charsets.UTF_8)
    }

    /**
     * Set content from bytes.
     */
    fun setContentFromBytes(bytes: ByteArray) {
        content = bytes.toString(Charsets.UTF_8)
    }

    /**
     * Validate the stamp on this message.
     *
     * Matches Python LXMessage.validate_stamp() (lines 279-299):
     * 1. Ticket path: check if stamp == truncatedHash(ticket + messageId)
     * 2. Normal path: use LXStamper to validate proof-of-work
     *
     * @param targetCost Required stamp cost (leading zero bits)
     * @param tickets List of valid inbound tickets, or null
     * @return True if stamp is valid
     */
    fun validateStamp(
        targetCost: Int,
        tickets: List<ByteArray>? = null,
    ): Boolean {
        val msgHash = hash ?: return false
        val msgStamp = stamp

        stampChecked = true

        // Ticket path: check if stamp matches any ticket
        if (msgStamp != null && tickets != null) {
            for (ticket in tickets) {
                val ticketStamp = Hashes.truncatedHash(ticket + msgHash)
                if (msgStamp.contentEquals(ticketStamp)) {
                    stampValid = true
                    stampValue = LXMFConstants.COST_TICKET
                    return true
                }
            }
        }

        // Normal path: validate proof-of-work stamp
        if (msgStamp == null) {
            stampValid = false
            stampValue = null
            return false
        }

        val valid = LXStamper.validateStamp(msgStamp, msgHash, targetCost)
        stampValid = valid
        if (valid) {
            stampValue = LXStamper.getStampValue(msgStamp, msgHash)
        } else {
            stampValue = null
        }
        return valid
    }

    /**
     * Get or generate the stamp for this message.
     *
     * Matches Python LXMessage.get_stamp() (lines 304-332):
     * 1. Ticket path: if outboundTicket set, return truncatedHash(ticket + messageId)
     * 2. No cost: if stampCost null, return null
     * 3. Cached: if stamp already set, return it
     * 4. Generate: use LXStamper.generateStamp()
     *
     * @return Stamp bytes, or null if no stamp needed
     */
    suspend fun getStamp(): ByteArray? {
        val msgHash = hash ?: return null

        // Ticket path
        val ticket = outboundTicket
        if (ticket != null) {
            val ticketStamp = Hashes.truncatedHash(ticket + msgHash)
            stamp = ticketStamp
            stampCost = null
            return ticketStamp
        }

        // No cost required
        val cost = stampCost ?: return null

        // Cached stamp
        if (stamp != null) return stamp

        // Generate stamp
        val result = LXStamper.generateStampWithWorkblock(msgHash, cost)
        stamp = result.stamp
        return result.stamp
    }

    // ===== Delivery Destination (LXMessage.py:264-265) =====

    /**
     * The destination this message will actually be delivered through.
     * For OPPORTUNISTIC/DIRECT this equals [destination]; for link-based
     * delivery the router sets it to the established LINK destination via
     * [setDeliveryDestination]. Mirrors python `__delivery_destination`.
     */
    var deliveryDestination: Destination? = null
        private set

    /**
     * Set the delivery destination. Mirrors python
     * `set_delivery_destination()` (LXMessage.py:264): unconditional assign —
     * the router legitimately re-targets this when a link is established.
     */
    fun setDeliveryDestination(deliveryDestination: Destination?) {
        this.deliveryDestination = deliveryDestination
    }

    /**
     * Register the callback invoked when this message is delivered or
     * propagation succeeds. Semantic twin of python
     * `register_delivery_callback()` (LXMessage.py:267) — kept as a named
     * setter alongside the public [deliveryCallback] property for API
     * parity with downstream consumers.
     */
    fun registerDeliveryCallback(callback: ((LXMessage) -> Unit)?) {
        deliveryCallback = callback
    }

    /**
     * Register the callback invoked when delivery fails. Mirrors python
     * `register_failed_callback()` (LXMessage.py:270).
     */
    fun registerFailedCallback(callback: ((LXMessage) -> Unit)?) {
        failedCallback = callback
    }

    /**
     * Assign [destination], enforcing python's set-once semantics
     * (LXMessage.py:235-242): a null destination may be filled exactly once
     * with a real Destination; reassigning an already-set destination throws.
     *
     * Note: Kotlin's [destination] is a read-only property, so callers use
     * this function rather than property assignment (deviation documented in
     * port-deviations.md).
     */
    fun setDestination(destination: Destination) {
        if (this.destination == null) {
            this.destination = destination
            // keep hash in sync like __init__ does (LXMessage.py:118)
        } else {
            throw IllegalArgumentException("Cannot reassign destination on LXMessage")
        }
    }

    /**
     * Assign [source] with python's set-once semantics
     * (LXMessage.py:255-262). See [setDestination].
     */
    fun setSource(source: Destination) {
        if (this.source == null) {
            this.source = source
        } else {
            throw IllegalArgumentException("Cannot reassign source on LXMessage")
        }
    }

    /**
     * Decode [content] as UTF-8, returning null on decode failure.
     * Mirrors python `content_as_string()` (LXMessage.py:208-213). Since
     * Kotlin stores content as String, failure cannot occur in practice;
     * retained for API parity and future bytes-backed refactor.
     */
    fun contentAsString(): String? = content

    /**
     * Determine whether the receiver supports compression by inspecting its
     * announce app data. Mirrors python `determine_compression_support()`
     * (LXMessage.py:510-513) + `compression_support_from_app_data()`
     * (LXMF.py:187-203):
     * - no app data → autoCompress = true
     * - 0.5.0+ list-format app data without a feature list → true
     * - 0.5.0+ list-format with feature list → SF_COMPRESSION present?
     * - original (non-msgpack-list) format → true
     */
    fun determineCompressionSupport() {
        val appData = Identity.recallAppData(destinationHash)
        autoCompress =
            if (appData == null || appData.isEmpty()) {
                true
            } else {
                compressionSupportFromAppData(appData)
            }
    }

    /**
     * Describe the transport encryption that applies to this message based on
     * its resolved delivery method and destination type. Mirrors python
     * `determine_transport_encryption()` (LXMessage.py:520-559).
     */
    fun determineTransportEncryption() {
        val type = destination?.type
        when (method) {
            DeliveryMethod.OPPORTUNISTIC,
            DeliveryMethod.PROPAGATED,
            DeliveryMethod.PAPER,
            -> {
                when (type) {
                    DestinationType.SINGLE -> {
                        transportEncrypted = true
                        transportEncryption = LXMFConstants.ENCRYPTION_DESCRIPTION_EC
                    }
                    DestinationType.GROUP -> {
                        transportEncrypted = true
                        transportEncryption = LXMFConstants.ENCRYPTION_DESCRIPTION_AES
                    }
                    else -> {
                        transportEncrypted = false
                        transportEncryption = LXMFConstants.ENCRYPTION_DESCRIPTION_UNENCRYPTED
                    }
                }
            }
            DeliveryMethod.DIRECT -> {
                transportEncrypted = true
                transportEncryption = LXMFConstants.ENCRYPTION_DESCRIPTION_EC
            }
            null -> {
                transportEncrypted = false
                transportEncryption = LXMFConstants.ENCRYPTION_DESCRIPTION_UNENCRYPTED
            }
        }
    }

    /**
     * Get or generate the propagation-node stamp for this message.
     *
     * Mirrors python `get_propagation_stamp()` (LXMessage.py:329-353):
     * 1. Cached stamp returned immediately
     * 2. Null/zero target cost raises
     * 3. Packs the message if needed so transient_id exists
     * 4. Generates PoW over transient_id using PN workblock expansion rounds
     *
     * Kotlin returns the value via [LXStamper.StampResult]; unlike python we
     * cannot return (stamp, value) tuples, so state fields carry the outcome
     * (documented deviation).
     *
     * @param targetCost Required stamp cost from the propagation node
     * @return Stamp bytes, or null if generation failed
     */
    suspend fun getPropagationStamp(targetCost: Int?): ByteArray? {
        propagationStamp?.let { return it }

        requireNotNull(targetCost) {
            "Cannot generate propagation stamp without configured target propagation cost"
        }
        propagationTargetCost = targetCost

        if (transientId == null) {
            pack()
            // Python computes transient_id during pack()'s PROPAGATED branch.
            // This port's pack() leaves transient synthesis to the router, so
            // derive it here the same way LXMRouter.sendViaPropagation does:
            // full_hash(destHash + encrypted(packed[DESTINATION_LENGTH:])).
            val dest =
                destination
                    ?: throw IllegalStateException("Cannot generate propagation stamp without destination")
            val packedData =
                packed
                    ?: throw IllegalStateException("Packing did not produce packed bytes")
            val encrypted = dest.encrypt(packedData.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packedData.size))
            propagationPacked = packedData.copyOfRange(0, LXMFConstants.DESTINATION_LENGTH) + encrypted
            transientId = Hashes.fullHash(propagationPacked!!)
        }

        val result = LXStamper.generateStampWithWorkblock(
            transientId!!,
            targetCost,
            expandRounds = LXStamper.WORKBLOCK_EXPAND_ROUNDS_PN,
        )
        val generated = result.stamp ?: return null
        propagationStamp = generated
        propagationStampValue = result.value
        propagationStampValid = true
        return generated
    }

    /**
     * Serialise this message into the msgpack "container" dict used for
     * persistence. Mirrors python `packed_container()` (LXMessage.py:660-672)
     * byte-for-byte: keys are msgpack STR, values native types.
     */
    fun packedContainer(): ByteArray {
        if (packed == null) {
            pack()
        }
        val buffer = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(buffer)
        packer.packMapHeader(5)

        packer.packString("state")
        packer.packInt(state.value)

        packer.packString("lxmf_bytes")
        packer.packBinaryHeader(packed!!.size)
        packer.writePayload(packed!!)

        packer.packString("transport_encrypted")
        packer.packBoolean(transportEncrypted)

        packer.packString("transport_encryption")
        if (transportEncryption != null) {
            packer.packString(transportEncryption)
        } else {
            packer.packNil()
        }

        packer.packString("method")
        if (method != null) {
            packer.packInt(method!!.value)
        } else {
            packer.packNil()
        }

        packer.close()
        return buffer.toByteArray()
    }

    /**
     * Atomically write this message's [packedContainer] into a directory as
     * `<hash>`, returning the file path, or null on failure. Mirrors python
     * `write_to_directory()` (LXMessage.py:674-696): temp file + fsync +
     * atomic rename, cleanup of the temp file on error.
     *
     * @param directoryPath Target directory (must exist)
     */
    fun writeToDirectory(directoryPath: String): String? {
        val messageHash = hash ?: return null
        val fileName = messageHash.toHexString()
        val filePath = "$directoryPath/$fileName"
        val tmpPath = "$filePath.tmp.${ProcessHandle.current().pid()}." +
            java.security.SecureRandom().let { r ->
                val b = ByteArray(8); r.nextBytes(b); b.toHexString()
            }

        return try {
            java.io.File(tmpPath).writeBytes(packedContainer())
            java.nio.file.Files.move(
                java.nio.file.Path.of(tmpPath),
                java.nio.file.Path.of(filePath),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            filePath
        } catch (e: Exception) {
            try {
                java.io.File(tmpPath).delete()
            } catch (_: Exception) {}
            println("Error while writing LXMF message to file \"$filePath\". The contained exception was: $e")
            null
        }
    }

    /**
     * Render this PAPER message as a QR code matrix.
     *
     * Mirrors python `as_qr()` (LXMessage.py:718-744): ERROR_CORRECT_L,
     * border 1, encoding [asUri]. Returns null when no QR encoder is
     * available (python logs CRITICAL and returns None when the `qrcode`
     * module is missing); this port embeds a minimal encoder, so null only
     * occurs on internal failure. Throws TypeError-equivalent for non-paper
     * messages.
     *
     * @return Boolean matrix indexed [y][x], true = dark module, or null
     */
    fun asQr(): Array<BooleanArray>? {
        if (packed == null) {
            pack()
        }
        if (desiredMethod != DeliveryMethod.PAPER || paperPacked == null) {
            throw IllegalStateException("Attempt to represent LXM with non-paper delivery method as QR-code")
        }
        return QrEncoder.encode(asUri(finalise = false), errorCorrectionLevel = QrEncoder.ERROR_CORRECT_L, border = 1)
    }

    /**
     * Encode this message as a paper delivery URI (lxm://...).
     *
     * Matches Python LXMessage.as_uri() (lines 685-703):
     * 1. Pack message if not already packed
     * 2. Encrypt everything after dest hash for the destination
     * 3. Prepend dest hash to get paper_packed
     * 4. Base64url-encode without padding
     * 5. Prepend "lxm://"
     *
     * @param finalise When true (default), also runs
     *   [determineTransportEncryption] and marks the paper message generated
     *   (state SENT, progress 1.0) like python `__mark_paper_generated`.
     * @return The lxm:// URI string
     */
    fun asUri(finalise: Boolean = true): String {
        if (packed == null) {
            pack()
        }

        val pp =
            paperPacked
                ?: throw IllegalStateException("Attempt to represent LXM with non-paper delivery method as URI")

        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pp)
        val uri = "${URI_SCHEMA}://$encoded"

        if (finalise) {
            determineTransportEncryption()
            markPaperGenerated()
        }
        return uri
    }

    /**
     * Mark this message as successfully generated for PAPER delivery.
     * Mirrors python `__mark_paper_generated()` (LXMessage.py:585-595):
     * state → PAPER (0x05 in python's own quirk), progress 1.0, then the
     * delivery callback fires.
     */
    private fun markPaperGenerated() {
        progress = 1.0
        deliveryCallback?.invoke(this)
    }

    /**
     * Pack message for PAPER delivery.
     *
     * Encrypts message content for the destination and prepends the destination hash.
     * Must be called before asUri().
     *
     * @throws IllegalStateException if destination is null or has no identity
     */
    fun packForPaper() {
        if (packed == null) {
            pack()
        }

        val dest =
            destination
                ?: throw IllegalStateException("Cannot pack for paper without destination")

        val packedData = packed!!
        val plainData = packedData.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packedData.size)
        val encryptedData = dest.encrypt(plainData)
        paperPacked = packedData.copyOfRange(0, LXMFConstants.DESTINATION_LENGTH) + encryptedData

        method = DeliveryMethod.PAPER
        representation = MessageRepresentation.PACKET
    }

    override fun toString(): String {
        val hashStr = hash?.toHexString()?.take(12) ?: "unpacked"
        return "<LXMessage $hashStr>"
    }

    companion object {
        /** URI schema prefix */
        const val URI_SCHEMA = "lxm"

        /**
         * Create a new outbound LXMF message.
         *
         * @param destination The destination to send to
         * @param source The source destination (sender)
         * @param content Message content
         * @param title Message title (default empty)
         * @param fields Extended fields (default empty)
         * @param desiredMethod Desired delivery method (default DIRECT)
         * @return New LXMessage instance
         */
        fun create(
            destination: Destination,
            source: Destination,
            content: String,
            title: String = "",
            fields: MutableMap<Int, Any> = mutableMapOf(),
            desiredMethod: DeliveryMethod? = DeliveryMethod.DIRECT,
        ): LXMessage =
            LXMessage(
                destination = destination,
                source = source,
                destinationHash = destination.hash,
                sourceHash = source.hash,
                title = title,
                content = content,
                fields = fields,
                desiredMethod = desiredMethod,
            )

        /**
         * Unpack an LXMF message from wire format bytes.
         *
         * Wire format:
         * ```
         * [0:16]   Destination hash
         * [16:32]  Source hash
         * [32:96]  Signature
         * [96:]    Msgpack payload
         * ```
         *
         * @param lxmfBytes The packed message bytes
         * @param originalMethod The original delivery method (optional)
         * @return Unpacked LXMessage, or null if unpacking fails
         */
        fun unpackFromBytes(
            lxmfBytes: ByteArray,
            originalMethod: DeliveryMethod? = null,
        ): LXMessage? {
            try {
                // Minimum size: dest_hash (16) + source_hash (16) + signature (64) + some payload
                val minHeaderSize = 2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH
                if (lxmfBytes.size <= minHeaderSize) {
                    println("LXMF message too small: ${lxmfBytes.size} bytes (need > $minHeaderSize)")
                    return null
                }

                // Extract fixed-length fields
                val destinationHash = lxmfBytes.copyOfRange(0, LXMFConstants.DESTINATION_LENGTH)
                val sourceHash =
                    lxmfBytes.copyOfRange(
                        LXMFConstants.DESTINATION_LENGTH,
                        2 * LXMFConstants.DESTINATION_LENGTH,
                    )
                val signature =
                    lxmfBytes.copyOfRange(
                        2 * LXMFConstants.DESTINATION_LENGTH,
                        2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH,
                    )
                val packedPayload =
                    lxmfBytes.copyOfRange(
                        2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH,
                        lxmfBytes.size,
                    )

                // Unpack msgpack payload
                val unpacker = MessagePack.newDefaultUnpacker(packedPayload)
                val arraySize = unpacker.unpackArrayHeader()

                if (arraySize < 4) {
                    println("Invalid LXMF payload: expected at least 4 elements, got $arraySize")
                    return null
                }

                // [0] timestamp
                val timestamp = unpacker.unpackDouble()

                // [1] title
                val titleLen = unpacker.unpackBinaryHeader()
                val titleBytes = ByteArray(titleLen)
                unpacker.readPayload(titleBytes)

                // [2] content
                val contentLen = unpacker.unpackBinaryHeader()
                val contentBytes = ByteArray(contentLen)
                unpacker.readPayload(contentBytes)

                // [3] fields — may be msgpack Nil (interop: iOS LXMF and python's
                // `set_fields(None)` both produce Nil here; python tolerates this on
                // unpack via LXMessage.py:755 + set_fields() at LXMessage.py:220-224
                // which accepts None and normalizes to {}). Track wire encoding so
                // we can repack identically when a stamp is present.
                // tryUnpackNil() peek-and-consumes in one call; if it returns true the
                // Nil byte is already consumed so no follow-up unpackNil() is needed.
                val fieldsWasNil = unpacker.tryUnpackNil()
                val fields =
                    if (fieldsWasNil) {
                        mutableMapOf()
                    } else {
                        unpackFields(unpacker)
                    }

                // [4] stamp (optional)
                val stamp: ByteArray? =
                    if (arraySize > 4) {
                        val stampLen = unpacker.unpackBinaryHeader()
                        val stampBytes = ByteArray(stampLen)
                        unpacker.readPayload(stampBytes)
                        stampBytes
                    } else {
                        null
                    }

                unpacker.close()

                // Mirror python LXMessage.py:742-747: only re-pack to strip the stamp.
                // For stampless messages use the original packedPayload bytes directly —
                // any msgpack encoding round-trip risks a hash mismatch (e.g. empty fields
                // encoded as Nil 0xc0 vs empty Map 0x80). With a stamp present, repack
                // preserving the original fields encoding (Nil if it was Nil on the wire).
                val payloadWithoutStamp =
                    if (stamp == null) {
                        packedPayload
                    } else {
                        repackPayload(timestamp, titleBytes, contentBytes, fields, fieldsWasNil)
                    }

                // Build hashed part
                val hashedPart = destinationHash + sourceHash + payloadWithoutStamp

                // Compute message hash
                val messageHash = Hashes.fullHash(hashedPart)

                // Build signed part
                val signedPart = hashedPart + messageHash

                // Try to recall identities
                val destinationIdentity = Identity.recall(destinationHash)
                val sourceIdentity = Identity.recall(sourceHash)

                // Create destinations if identities are known
                val destination =
                    if (destinationIdentity != null) {
                        // Note: We'd need to create a destination here, but for incoming
                        // messages we typically don't need the full destination object
                        null
                    } else {
                        null
                    }

                val source =
                    if (sourceIdentity != null) {
                        null
                    } else {
                        null
                    }

                // Create message
                val message =
                    LXMessage(
                        destination = destination,
                        source = source,
                        destinationHash = destinationHash,
                        sourceHash = sourceHash,
                        title = titleBytes.toString(Charsets.UTF_8),
                        content = contentBytes.toString(Charsets.UTF_8),
                        fields = fields,
                        desiredMethod = originalMethod,
                    )

                message.hash = messageHash
                message.signature = signature
                message.stamp = stamp
                message.incoming = true
                message.timestamp = timestamp
                message.packed = lxmfBytes

                // Validate signature if source identity is known
                if (sourceIdentity != null) {
                    try {
                        if (sourceIdentity.validate(signature, signedPart)) {
                            message.signatureValidated = true
                        } else {
                            message.signatureValidated = false
                            message.unverifiedReason = UnverifiedReason.SIGNATURE_INVALID
                        }
                    } catch (e: Exception) {
                        message.signatureValidated = false
                        println("Error validating LXMF signature: ${e.message}")
                    }
                } else {
                    message.signatureValidated = false
                    message.unverifiedReason = UnverifiedReason.SOURCE_UNKNOWN
                    println("Cannot validate LXMF signature: source identity unknown")
                }

                return message
            } catch (e: Exception) {
                println("Error unpacking LXMF message: ${e.message}")
                e.printStackTrace()
                return null
            }
        }

        /**
         * Unpack an LXMF message from a persisted container file previously
         * written by [writeToDirectory]. Mirrors python `unpack_from_file()`
         * (LXMessage.py:825-842): msgpack-decode the container, unpack the
         * inner lxmf_bytes, then restore state/transport metadata where
         * present. Returns null on any failure (logged).
         *
         * @param file Path to the container file
         */
        fun unpackFromFile(file: java.io.File): LXMessage? {
            return try {
                val bytes = file.readBytes()
                val unpacker = MessagePack.newDefaultUnpacker(bytes)
                val mapSize = unpacker.unpackMapHeader()
                var lxmfBytes: ByteArray? = null
                var restoredState: Int? = null
                var transportEncrypted: Boolean? = null
                var transportEncryption: String? = null
                var methodValue: Int? = null

                repeat(mapSize) {
                    when (val key = unpacker.unpackString()) {
                        "lxmf_bytes" -> {
                            val len = unpacker.unpackBinaryHeader()
                            val b = ByteArray(len)
                            unpacker.readPayload(b)
                            lxmfBytes = b
                        }
                        "state" -> restoredState = unpacker.unpackInt()
                        "transport_encrypted" -> transportEncrypted = unpacker.unpackBoolean()
                        "transport_encryption" ->
                            if (!unpacker.tryUnpackNil()) {
                                transportEncryption = unpacker.unpackString()
                            }
                        "method" ->
                            if (!unpacker.tryUnpackNil()) {
                                methodValue = unpacker.unpackInt()
                            }
                        else -> unpacker.skipValue()
                    }
                }
                unpacker.close()

                val lxm = unpackFromBytes(lxmfBytes ?: return null) ?: return null

                restoredState?.let { MessageState.fromValue(it)?.let { s -> lxm.state = s } }
                transportEncrypted?.let { lxm.transportEncrypted = it }
                if (transportEncryption != null) lxm.transportEncryption = transportEncryption
                methodValue?.let { lxm.method = DeliveryMethod.fromValue(it) }

                lxm
            } catch (e: Exception) {
                println("Could not unpack LXMessage from file. The contained exception was: $e")
                null
            }
        }

        /**
         * Kotlin port of python `compression_support_from_app_data()`
         * (LXMF/LXMF.py:187-203). See [determineCompressionSupport].
         */
        fun compressionSupportFromAppData(appData: ByteArray): Boolean {
            // Version 0.5.0+ announce format: app data is a msgpack array.
            // fixarray headers are 0x90-0x9f; array16 starts with 0xdc.
            val isFirstByteMsgpackArray =
                (appData[0].toInt() and 0xFF) in 0x90..0x9F || appData[0] == 0xdc.toByte()
            if (!isFirstByteMsgpackArray) return true

            return try {
                var featureList: List<Any?>? = null
                val unpacker = MessagePack.newDefaultUnpacker(appData)
                val size = unpacker.unpackArrayHeader()
                for (i in 0 until size) {
                    val v = unpackValue(unpacker)
                    if (i == 2) featureList = v as? List<Any?>
                }
                unpacker.close()

                when {
                    featureList == null -> true
                    else -> featureList.contains(LXMFConstants.SF_COMPRESSION.toLong()) ||
                        featureList.contains(LXMFConstants.SF_COMPRESSION)
                }
            } catch (e: Exception) {
                true
            }
        }

        /**
         * Unpack fields map from msgpack.
         */
        private fun unpackFields(unpacker: org.msgpack.core.MessageUnpacker): MutableMap<Int, Any> {
            val fields = mutableMapOf<Int, Any>()
            val mapSize = unpacker.unpackMapHeader()

            repeat(mapSize) {
                val key = unpacker.unpackInt()
                val value = unpackValue(unpacker)
                if (value != null) {
                    fields[key] = value
                }
            }

            return fields
        }

        /**
         * Unpack a value from msgpack.
         */
        private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
            val format = unpacker.nextFormat
            val valueType = format.valueType
            return when (valueType.name) {
                "NIL" -> {
                    unpacker.unpackNil()
                    null
                }
                "BOOLEAN" -> unpacker.unpackBoolean()
                "INTEGER" -> unpacker.unpackLong()
                "FLOAT" -> unpacker.unpackDouble()
                "STRING" -> unpacker.unpackString()
                "BINARY" -> {
                    val len = unpacker.unpackBinaryHeader()
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    bytes
                }
                "ARRAY" -> {
                    val size = unpacker.unpackArrayHeader()
                    val list = mutableListOf<Any?>()
                    repeat(size) {
                        list.add(unpackValue(unpacker))
                    }
                    list
                }
                "MAP" -> {
                    val size = unpacker.unpackMapHeader()
                    val map = mutableMapOf<Any?, Any?>()
                    repeat(size) {
                        val k = unpackValue(unpacker)
                        val v = unpackValue(unpacker)
                        map[k] = v
                    }
                    map
                }
                "EXTENSION" -> {
                    unpacker.skipValue()
                    null
                }
                else -> {
                    unpacker.skipValue()
                    null
                }
            }
        }

        /**
         * Repack payload without stamp for hash verification.
         *
         * [fieldsWasNil] preserves the original wire encoding for the fields
         * position. If the inbound payload encoded fields as msgpack Nil
         * (`0xc0`, what iOS LXMF and python's `msgpack.packb(None)` produce),
         * we must emit Nil here too — emitting an empty Map (`0x80`) instead
         * would change the byte representation and break the message hash.
         * Mirrors python `msgpack.packb(unpacked_payload)` round-trip
         * behavior at LXMessage.py:745, which preserves None as Nil.
         */
        private fun repackPayload(
            timestamp: Double,
            titleBytes: ByteArray,
            contentBytes: ByteArray,
            fields: Map<Int, Any>,
            fieldsWasNil: Boolean = false,
        ): ByteArray {
            val buffer = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(buffer)

            // Pack as 4-element list (without stamp)
            packer.packArrayHeader(4)

            // [0] timestamp
            packer.packDouble(timestamp)

            // [1] title
            packer.packBinaryHeader(titleBytes.size)
            packer.writePayload(titleBytes)

            // [2] content
            packer.packBinaryHeader(contentBytes.size)
            packer.writePayload(contentBytes)

            // [3] fields — emit Nil if that's what the wire had, else Map
            if (fieldsWasNil && fields.isEmpty()) {
                packer.packNil()
            } else {
                packer.packMapHeader(fields.size)
                for ((key, value) in fields) {
                    packer.packInt(key)
                    repackValue(packer, value)
                }
            }

            packer.close()
            return buffer.toByteArray()
        }

        /**
         * Repack a value for hash verification.
         */
        private fun repackValue(
            packer: org.msgpack.core.MessagePacker,
            value: Any,
        ) {
            when (value) {
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                is String -> packer.packString(value)
                is Int -> packer.packInt(value)
                is Long -> packer.packLong(value)
                is Double -> packer.packDouble(value)
                is Float -> packer.packFloat(value)
                is Boolean -> packer.packBoolean(value)
                is List<*> -> {
                    packer.packArrayHeader(value.size)
                    for (item in value) {
                        if (item != null) {
                            repackValue(packer, item)
                        } else {
                            packer.packNil()
                        }
                    }
                }
                is Map<*, *> -> {
                    packer.packMapHeader(value.size)
                    for ((k, v) in value) {
                        if (k != null) {
                            repackValue(packer, k)
                        } else {
                            packer.packNil()
                        }
                        if (v != null) {
                            repackValue(packer, v)
                        } else {
                            packer.packNil()
                        }
                    }
                }
                else -> packer.packString(value.toString())
            }
        }
    }
}
