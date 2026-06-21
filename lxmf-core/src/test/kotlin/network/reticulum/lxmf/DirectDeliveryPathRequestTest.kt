package network.reticulum.lxmf

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.PathEntry
import network.reticulum.transport.PathState
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for columba#1004 (PR 2 — LXMF-kt DIRECT delivery path
 * requests).
 *
 * Pre-fix, [LXMRouter.processDirectDelivery]'s no-link branch called
 * `establishLinkForMessage` with **no `Transport.hasPath` check and no
 * `Transport.requestPath`**, so a DIRECT message to a destination that hadn't
 * announced since the app opened (no usable path) would blindly attempt a link
 * that could never establish, then fall back to PROPAGATED — never emitting the
 * path request needed to actually reach the destination. Python LXMF requests a
 * path first (LXMRouter.py:2648-2652).
 *
 * Separately, a DIRECT link that closes (establishment timeout / unexpected
 * close) must re-request the path (Python LXMRouter.py:2610-2622). kt's link
 * lifecycle is event-driven, so that re-request lives in the link
 * `closedCallback` (see port-deviations.md), gated on the message still
 * needing delivery.
 *
 * Drives the real [Transport] singleton with an inline [CapturingInterface]
 * (same pattern as reticulum-kt's TransportOutboundHeaderTypeTest).
 */
@DisplayName("LXMF DIRECT delivery path requests (columba#1004)")
class DirectDeliveryPathRequestTest {

    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    private lateinit var iface: CapturingInterface
    private lateinit var routerIdentity: Identity
    private lateinit var router: LXMRouter

    private val pathRequestDestHash: ByteArray =
        Destination.create(
            identity = null,
            direction = DestinationDirection.IN,
            type = DestinationType.PLAIN,
            appName = "rnstransport",
            aspects = arrayOf("path", "request"),
        ).hash

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
        iface = CapturingInterface(name = "capture-${System.nanoTime()}")
        Transport.registerInterface(iface)

        routerIdentity = Identity.create()
        router = LXMRouter(identity = routerIdentity)
    }

    @AfterEach
    fun teardown() {
        router.close()
        try {
            Transport.deregisterInterface(iface)
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    private fun livePathEntry(receivingIfaceHash: ByteArray): PathEntry {
        val now = System.currentTimeMillis()
        return PathEntry(
            timestamp = now,
            nextHop = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xDD.toByte() },
            hops = 1,
            expires = now + 3_600_000L,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = receivingIfaceHash,
            announcePacketHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xCC.toByte() },
            state = PathState.ACTIVE,
            failureCount = 0,
        )
    }

    private fun directMessage(destIdentity: Identity): Pair<LXMessage, Destination> {
        val source = Destination.create(
            identity = routerIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            aspects = arrayOf("delivery"),
        )
        val dest = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            aspects = arrayOf("delivery"),
        )
        val message = LXMessage.create(
            destination = dest,
            source = source,
            content = "columba1004",
            title = "t",
            desiredMethod = DeliveryMethod.DIRECT,
        )
        return message to dest
    }

    /**
     * Number of distinct path requests (by request tag) for [destHash]. Counts
     * logical `Transport.requestPath` calls, not wire packets: each call mints a
     * unique 16-byte tag, and a single call can broadcast the same packet more
     * than once (e.g. transport-mode `outbound` emits a locally-originated path
     * request twice on an interface) — distinct tags collapse those back to one.
     */
    private fun pathRequestCountFor(destHash: ByteArray): Int =
        iface.sent.mapNotNull { wire ->
            val pkt = Packet.unpack(wire) ?: return@mapNotNull null
            val isPathReqForDest = pkt.destinationHash.contentEquals(pathRequestDestHash) &&
                pkt.data.size >= 2 * RnsConstants.TRUNCATED_HASH_BYTES &&
                pkt.data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES).contentEquals(destHash)
            if (!isPathReqForDest) {
                null
            } else {
                // tag = trailing 16 bytes of the path-request payload.
                pkt.data.copyOfRange(pkt.data.size - RnsConstants.TRUNCATED_HASH_BYTES, pkt.data.size)
                    .joinToString("") { "%02x".format(it) }
            }
        }.toSet().size

    private fun linkRequestCount(): Int =
        iface.sent.count { wire -> Packet.unpack(wire)?.packetType == PacketType.LINKREQUEST }

    @Test
    @DisplayName("direct send with no path emits exactly one path request and no link request")
    fun noPathEmitsPathRequest() = runBlocking {
        val (message, dest) = directMessage(Identity.create())
        // No path seeded — hasPath is false.
        router.handleOutbound(message)

        // Drive a few ticks; once the no-link branch sets nextDeliveryAttempt
        // into the future, further ticks are no-ops, so the count is stable.
        withTimeout(5_000) {
            while (pathRequestCountFor(dest.hash) == 0) {
                router.processOutbound()
                delay(20)
            }
        }
        repeat(3) { router.processOutbound(); delay(20) }

        // Pre-fix: 0 path requests, 1 link request (blind link attempt).
        assertEquals(1, pathRequestCountFor(dest.hash), "exactly one path request")
        assertEquals(0, linkRequestCount(), "no link request without a path")
        assertEquals(0.01, message.progress, 1e-9)
    }

    @Test
    @DisplayName("direct send with a seeded path emits a link request and no path request")
    fun seededPathEmitsLinkRequest() = runBlocking {
        val (message, dest) = directMessage(Identity.create())
        Transport.pathTable[dest.hash.toKey()] = livePathEntry(iface.hash)

        router.handleOutbound(message)

        withTimeout(5_000) {
            while (linkRequestCount() == 0) {
                router.processOutbound()
                delay(20)
            }
        }
        repeat(3) { router.processOutbound(); delay(20) }

        assertEquals(0, pathRequestCountFor(dest.hash), "no path request when a path is known")
        assertEquals(1, linkRequestCount(), "exactly one link request")
        assertEquals(0.03, message.progress, 1e-9)
    }

    @Test
    @DisplayName("pathless direct retries re-request the path each attempt until FAILED")
    fun pathlessRetriesUntilMax() = runBlocking {
        val (message, dest) = directMessage(Identity.create())
        var failedCount = 0
        message.failedCallback = { failedCount++ }

        router.handleOutbound(message)

        // Each increment of deliveryAttempts to [1, MAX-1] issues exactly one
        // path request; the count is independent of tick timing. Force each
        // attempt due so we don't sleep out PATH_REQUEST_WAIT/DELIVERY_RETRY_WAIT.
        withTimeout(15_000) {
            while (message.state != MessageState.FAILED) {
                message.nextDeliveryAttempt = 0L
                router.processOutbound()
                delay(15)
            }
        }

        assertEquals(
            LXMRouter.MAX_DELIVERY_ATTEMPTS - 1,
            pathRequestCountFor(dest.hash),
            "one path request per pathless attempt below MAX",
        )
        assertEquals(1, failedCount, "failedCallback invoked exactly once")
    }

    @Test
    @DisplayName("a closed link that never activated re-requests the path once in transport mode")
    fun closedNeverActivatedReRequestsOnce() = runBlocking {
        // Transport-enabled mode: reticulum-kt's Transport.deregisterLink stale-path
        // recovery is gated to non-transport nodes (Python Transport.py:504 parity),
        // so here the LXMF closedCallback is the SOLE source of the close-time path
        // re-request — exactly the columba#1004 transport-mode scenario. (In
        // non-transport mode both fire, matching Python's jobloop + LXMRouter pair.)
        Transport.stop()
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = true)
        Transport.registerInterface(iface)

        val (message, dest) = directMessage(Identity.create())
        val hex = dest.hash.toHexString()
        // Seed a path so the no-link branch establishes a link (rather than
        // requesting a path) — we want a real link to drive into CLOSED.
        Transport.pathTable[dest.hash.toKey()] = livePathEntry(iface.hash)

        router.handleOutbound(message)
        withTimeout(5_000) {
            while (router.directLinkForTest(hex) == null) {
                router.processOutbound()
                delay(20)
            }
        }
        val link = router.directLinkForTest(hex)!!
        iface.sent.clear() // drop the link request; isolate the close-time path request
        assertEquals(0, pathRequestCountFor(dest.hash))

        // Link never activated (still PENDING). Closing it fires the
        // closedCallback synchronously, which re-requests the path once. In
        // transport mode reticulum-kt's deregisterLink recovery is gated off, so
        // the closedCallback is the only source: exactly one logical request.
        link.teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT)

        assertEquals(1, pathRequestCountFor(dest.hash), "exactly one re-request on close (transport mode)")
        assertTrue(message.pathRequestRetried, "never-activated retry flag set")
    }
}
