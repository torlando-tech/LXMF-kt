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

    // ==================== Crypto Primitives (delegated) ====================

    fun sha256(data: ByteArray): ByteArray = Hashes.fullHash(data)

    fun hkdfExpand(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        Stamper.hkdfExpand(ikm, salt, info, length)

    fun packInt(n: Int): ByteArray = Stamper.packInt(n)
}
