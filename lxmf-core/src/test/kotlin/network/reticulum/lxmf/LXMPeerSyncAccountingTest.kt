package network.reticulum.lxmf

import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files

/**
 * Regression net for the LXMPeer persistent-sync omission-accounting flow
 * (`offerResponse` -> dead-letter bookkeeping).
 *
 * Bug classes covered — each shipped through review and had to be caught
 * by hand; these tests exist so the NEXT regression of this family fails
 * here mechanically, before any external reviewer sees it:
 *
 *  - r6: null filePath entries escaped accounting entirely; all-unreadable
 *        rounds returned BEFORE incrementing, and the dead-letter sweep only
 *        ran inside resourceConcluded which never fires without a Resource
 *  - r7: partial rounds incremented twice per round after the r6 reorder
 *  - post-r7 hardening: mid-read IO failure escaped to the generic catch
 *        with zero accounting (third door, same infinite-retry class)
 *
 * Tests drive `offerResponse` reflectively with a bare RequestReceipt whose
 * msgpack response is injected exactly as the link layer would deliver it.
 * Assertions run against public surfaces: unsendableRoundCount and the
 * propagationEntriesMap handledBy/unhandledBy membership.
 */
class LXMPeerSyncAccountingTest {
    private lateinit var identity: Identity

    @BeforeEach
    fun setup() {
        identity = Identity.create()
        try {
            Transport.start(identity, enableTransport = false)
        } catch (_: Exception) {
            // already started
        }
    }

    private fun makeRouter(): LXMRouter = LXMRouter(identity = identity)

    private fun makePeerHash(): ByteArray {
        val peerIdentity = Identity.create()
        return network.reticulum.destination.Destination.hash(
            peerIdentity, "lxmf", "propagation"
        )
    }

    /**
     * Seed one propagation entry owned by [peerHash].
     *
     * Path semantics (explicit, no magic):
     *  - null                -> entry with null filePath (r6 class)
     *  - READABLE sentinel   -> real temp file with 64 bytes of content
     *  - anything else       -> used VERBATIM (caller controls existence:
     *                          missing name, directory, special file...)
     *
     * Returns the transient id hex key in propagationEntriesMap.
     */
    private fun seedEntry(
        router: LXMRouter,
        peerHash: ByteArray,
        filePath: String?,
    ): String {
        val dst = Identity.create().hash
        val tidBytes = Identity.create().hash // 32-byte transient id
        val hex = tidBytes.joinToString("") { "%02x".format(it) }
        val resolvedPath: String? = when (filePath) {
            READABLE -> Files.write(
                Files.createTempFile("acct", ".lxm"), ByteArray(64)
            ).toString()
            else -> filePath
        }
        router.propagationEntriesMap[hex] = LXMRouter.PropagationEntry(
            dstHash = dst,
            filePath = resolvedPath,
            receivedAt = 1000.0,
            size = 128,
        ).also { it.unhandledBy.add(peerHash.copyOf()) }
        return hex
    }

    private fun tidBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Invoke the private offerResponse() with a receipt whose response is
     * a msgpack ARRAY of wanted transient ids (the WantedIds branch).
     * Note: peer.link stays null, so partial rounds NPE at Resource.create
     * AFTER accounting has run — harmless for these assertions and proof
     * the accounting genuinely precedes resource creation.
     */
    private fun driveOfferResponse(peer: LXMPeer, wantedIdsHex: List<String>) {
        val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(wantedIdsHex.size)
        for (hex in wantedIdsHex) {
            val b = tidBytes(hex)
            packer.packBinaryHeader(b.size).writePayload(b)
        }
        packer.close()

        val theUnsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        theUnsafe.isAccessible = true
        val unsafe = theUnsafe.get(null) as sun.misc.Unsafe
        val receipt = unsafe.allocateInstance(
            network.reticulum.link.RequestReceipt::class.java
        ) as network.reticulum.link.RequestReceipt
        val f = network.reticulum.link.RequestReceipt::class.java.getDeclaredField("response")
        f.isAccessible = true
        f.set(receipt, packer.toByteArray())

        val m = LXMPeer::class.java.getDeclaredMethod(
            "offerResponse", network.reticulum.link.RequestReceipt::class.java
        )
        m.isAccessible = true
        m.invoke(peer, receipt)
    }

    companion object {
        /** Sentinel: seed a real, readable backing file. */
        const val READABLE = "\u0000readable"
    }

    // ===== r6 class: null filePath must count as an omission =====

    @Test
    fun `null file path entry counts toward unsendable accounting`() {
        val router = makeRouter()
        val peerHash = makePeerHash()
        router.peer(peerHash, 1000.0, 1024.0, null, 1, 0, 1, null)
        val peer = router.getPeer(peerHash)!!
        val tid = seedEntry(router, peerHash, null)

        peer.lastOffer = listOf(tidBytes(tid))
        driveOfferResponse(peer, listOf(tid))

        assertEquals(
            1, peer.unsendableRoundCount[tid],
            "null-path omission must increment the retry counter"
        )
    }

    // ===== r6 class: ALL-unreadable round still advances + sweeps inline =====

    @Test
    fun `all-unreadable rounds deadletter after MAX_UNSENDABLE_ROUNDS`() {
        val router = makeRouter()
        val peerHash = makePeerHash()
        router.peer(peerHash, 1000.0, 1024.0, null, 1, 0, 1, null)
        val peer = router.getPeer(peerHash)!!

        val missingPath = Files.createTempDirectory("acct")
            .resolve("gone.lxm").toString() // never created
        val tid = seedEntry(router, peerHash, missingPath)
        peer.lastOffer = listOf(tidBytes(tid))

        // Rounds 1..N-1: counter advances once per round, entry stays pending.
        for (round in 1 until LXMPeer.MAX_UNSENDABLE_ROUNDS) {
            driveOfferResponse(peer, listOf(tid))
            assertEquals(
                round, peer.unsendableRoundCount[tid],
                "round $round: counter must advance exactly once per all-unreadable round"
            )
        }

        // Round N: counter reaches the cap -> dead-lettered WITHIN this
        // call (inline sweep on the no-Resource path). The key's REMOVAL
        // from the map plus the handled flip is the success signal — an
        // entry that dead-letters without ever creating a Resource.
        driveOfferResponse(peer, listOf(tid))
        assertNull(
            peer.unsendableRoundCount[tid],
            "dead-lettered entry must leave the retry map immediately after the cap round"
        )
        assertTrue(
            peer.handledMessages.any { it.contentEquals(tidBytes(tid)) },
            "after MAX_UNSENDABLE_ROUNDS the entry must be dead-lettered to handled"
        )
        assertEquals(
            0, peer.unhandledMessages.size,
            "dead-lettered entry must be removed from the unhandled set"
        )
    }

    // ===== r7 class: partial round counts ONCE per round =====

    @Test
    fun `partial round increments each unreadable entry exactly once`() {
        val router = makeRouter()
        val peerHash = makePeerHash()
        router.peer(peerHash, 1000.0, 1024.0, null, 1, 0, 1, null)
        val peer = router.getPeer(peerHash)!!

        val goodTid = seedEntry(router, peerHash, READABLE)
        val missingPath = Files.createTempDirectory("acct").resolve("nope.lxm").toString()
        val badTid = seedEntry(router, peerHash, missingPath)

        peer.lastOffer = listOf(tidBytes(goodTid), tidBytes(badTid))
        driveOfferResponse(peer, listOf(goodTid, badTid))

        assertEquals(
            1, peer.unsendableRoundCount[badTid],
            "one partial sync round must consume exactly ONE retry unit (r7 double-increment regression)"
        )
        assertNull(peer.unsendableRoundCount[goodTid])
    }

    // ===== new door: non-regular / unreadable-at-read-time path =====

    @Test
    fun `directory-as-path counts as omission`() {
        val router = makeRouter()
        val peerHash = makePeerHash()
        router.peer(peerHash, 1000.0, 1024.0, null, 1, 0, 1, null)
        val peer = router.getPeer(peerHash)!!

        val dirAsPath = Files.createTempDirectory("acct-dir").toString()
        val tid = seedEntry(router, peerHash, dirAsPath)

        peer.lastOffer = listOf(tidBytes(tid))
        driveOfferResponse(peer, listOf(tid))

        assertEquals(
            1, peer.unsendableRoundCount[tid],
            "non-regular-file path must be treated as an omission"
        )
    }

    // ===== clean-path invariant: full success clears accumulated strikes =====

    @Test
    fun `fully-readable round clears stale retry counters`() {
        val router = makeRouter()
        val peerHash = makePeerHash()
        router.peer(peerHash, 1000.0, 1024.0, null, 1, 0, 1, null)
        val peer = router.getPeer(peerHash)!!

        val goodTid = seedEntry(router, peerHash, READABLE)
        val missingPath = Files.createTempDirectory("acct").resolve("tmp.lxm").toString()
        val badTid = seedEntry(router, peerHash, missingPath)

        // Round 1: partial — one strike against the unreadable entry.
        peer.lastOffer = listOf(tidBytes(goodTid), tidBytes(badTid))
        driveOfferResponse(peer, listOf(goodTid, badTid))
        assertEquals(1, peer.unsendableRoundCount[badTid])

        // Operator repairs the store: point the entry at a real file.
        val repaired = Files.write(
            Files.createTempFile("repaired", ".lxm"), ByteArray(64)
        ).toString()
        router.propagationEntriesMap[badTid] =
            router.propagationEntriesMap[badTid]!!.copy(filePath = repaired)

        // Round 2: everything readable — strikes must clear.
        peer.lastOffer = listOf(tidBytes(goodTid), tidBytes(badTid))
        driveOfferResponse(peer, listOf(goodTid, badTid))

        assertNull(
            peer.unsendableRoundCount[badTid],
            "a fully-readable round must clear accumulated strikes"
        )
    }
}
