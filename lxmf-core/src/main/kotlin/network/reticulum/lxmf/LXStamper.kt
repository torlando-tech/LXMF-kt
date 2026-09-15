package network.reticulum.lxmf

import network.reticulum.crypto.Hashes
import network.reticulum.discovery.Stamper

/**
 * LXMF Stamp Generator and Validator.
 *
 * Thin facade over rns-core's [Stamper], preserving LXMF-specific constants
 * (3000/1000 expansion rounds) and the existing API used by [LXMessage].
 */
object LXStamper {

    private const val TAG = "LXStamper"

    /** Stamp size in bytes (256 bits) */
    const val STAMP_SIZE = Stamper.STAMP_SIZE

    /** Workblock expansion rounds for message stamps */
    const val WORKBLOCK_EXPAND_ROUNDS = 3000

    /** Workblock expansion rounds for propagation node stamps */
    const val WORKBLOCK_EXPAND_ROUNDS_PN = 1000

    /** Workblock expansion rounds for peering key generation (Python WORKBLOCK_EXPAND_ROUNDS_PEERING) */
    const val WORKBLOCK_EXPAND_ROUNDS_PEERING = 25

    /** HKDF output length per round */
    const val HKDF_OUTPUT_LENGTH = Stamper.HKDF_OUTPUT_LENGTH

    /**
     * Result of stamp generation.
     */
    data class StampResult(
        val stamp: ByteArray?,
        val value: Int,
        val rounds: Long
    ) {
        constructor(r: Stamper.StampResult) : this(r.stamp, r.value, r.rounds)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StampResult
            if (stamp != null) {
                if (other.stamp == null) return false
                if (!stamp.contentEquals(other.stamp)) return false
            } else if (other.stamp != null) return false
            if (value != other.value) return false
            if (rounds != other.rounds) return false
            return true
        }

        override fun hashCode(): Int {
            var result = stamp?.contentHashCode() ?: 0
            result = 31 * result + value
            result = 31 * result + rounds.hashCode()
            return result
        }
    }

    fun generateWorkblock(material: ByteArray, expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS): ByteArray =
        Stamper.generateWorkblock(material, expandRounds)

    suspend fun generateStamp(workblock: ByteArray, stampCost: Int): StampResult =
        StampResult(Stamper.generateStamp(workblock, stampCost))

    suspend fun generateStampWithWorkblock(
        messageId: ByteArray,
        stampCost: Int,
        expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS
    ): StampResult {
        println("[$TAG] Generating workblock with $expandRounds rounds...")
        val workblockStart = System.currentTimeMillis()
        val workblock = generateWorkblock(messageId, expandRounds)
        val workblockTime = System.currentTimeMillis() - workblockStart
        println("[$TAG] Workblock generated in ${workblockTime}ms (${workblock.size} bytes)")

        return generateStamp(workblock, stampCost)
    }

    fun isStampValid(stamp: ByteArray, targetCost: Int, workblock: ByteArray): Boolean =
        Stamper.stampValid(stamp, targetCost, workblock)

    fun stampValue(workblock: ByteArray, stamp: ByteArray): Int =
        Stamper.stampValue(workblock, stamp)

    fun validateStamp(
        stamp: ByteArray,
        messageHash: ByteArray,
        minCost: Int,
        expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS
    ): Boolean {
        if (stamp.size != STAMP_SIZE) return false
        val workblock = generateWorkblock(messageHash, expandRounds)
        return isStampValid(stamp, minCost, workblock)
    }

    fun getStampValue(
        stamp: ByteArray,
        messageHash: ByteArray,
        expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS
    ): Int {
        if (stamp.size != STAMP_SIZE) return 0
        val workblock = generateWorkblock(messageHash, expandRounds)
        return stampValue(workblock, stamp)
    }

    // ==================== Propagation Node Stamp Validation ====================
    // Ports Python LXMF/LXStamper.py validate_pn_stamp / validate_pn_stamps /
    // validate_peering_key (node-side surface, P3 card t_a3c5bdbc).
    //
    // Deviation vs Python: multiprocessing.Pool fan-out in
    // validate_pn_stamps_job_multip is replaced by sequential validation on the
    // caller's dispatcher — structured concurrency substitutes for the process
    // pool (same documented jobloop→coroutines substitution pattern). Batch CPU
    // parallelism can be revisited if node throughput requires it.

    /** Validated propagation-node stamp entry (mirrors Python validate_pn_stamp's 4-tuple). */
    data class PnStampEntry(
        val transientId: ByteArray,
        val lxmfData: ByteArray,
        val value: Int,
        val stampData: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is PnStampEntry &&
                transientId.contentEquals(other.transientId) &&
                lxmfData.contentEquals(other.lxmfData) &&
                value == other.value &&
                stampData.contentEquals(other.stampData)

        override fun hashCode(): Int =
            31 * (31 * transientId.contentHashCode() + lxmfData.contentHashCode()) + value
    }

    /**
     * Validate a single stamped propagation message transfer blob.
     *
     * Mirrors Python LXStamper.validate_pn_stamp(): the wire form is
     * `<lxmf_data><stamp>`; the transient id is the full hash of the UNstamped
     * lxmf_data; the stamp is validated against a PN-rounds (1000) workblock.
     *
     * @return the validated entry, or null when the blob is too short or the
     *   stamp does not meet [targetCost].
     */
    fun validatePnStamp(
        transientData: ByteArray,
        targetCost: Int,
    ): PnStampEntry? {
        if (transientData.size <= LXMFConstants.LXMF_OVERHEAD + STAMP_SIZE) return null
        val lxmfData = transientData.copyOfRange(0, transientData.size - STAMP_SIZE)
        val stamp = transientData.copyOfRange(transientData.size - STAMP_SIZE, transientData.size)
        val transientId = sha256(lxmfData)
        val workblock = generateWorkblock(transientId, WORKBLOCK_EXPAND_ROUNDS_PN)
        if (!isStampValid(stamp, targetCost, workblock)) return null
        return PnStampEntry(transientId, lxmfData, stampValue(workblock, stamp), stamp)
    }

    /** Validate a batch of stamped blobs, dropping invalid entries (order-preserving). */
    fun validatePnStamps(
        transientList: List<ByteArray>,
        targetCost: Int,
    ): List<PnStampEntry> = transientList.mapNotNull { validatePnStamp(it, targetCost) }

    /**
     * Validate a propagation-node peering key.
     *
     * Mirrors Python LXStamper.validate_peering_key(): the peering key is a
     * low-cost (25 expansion rounds) proof-of-work over the peering id
     * (`node_identity_hash + peer_identity_hash`) meeting [targetCost].
     */
    fun validatePeeringKey(
        peeringId: ByteArray,
        peeringKey: ByteArray,
        targetCost: Int,
    ): Boolean {
        val workblock = generateWorkblock(peeringId, WORKBLOCK_EXPAND_ROUNDS_PEERING)
        return isStampValid(peeringKey, targetCost, workblock)
    }

    // ==================== Crypto Primitives (delegated) ====================

    fun sha256(data: ByteArray): ByteArray = Hashes.fullHash(data)

    fun hkdfExpand(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        Stamper.hkdfExpand(ikm, salt, info, length)

    fun packInt(n: Int): ByteArray = Stamper.packInt(n)
}
