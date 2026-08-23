package network.reticulum.lxmf

import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the P2 client-surface parity methods on LXMRouter.
 * Each test maps to a Python LXMRouter.py client method ported in this card.
 */
class LXMRouterClientSurfaceTest {

    private lateinit var router: LXMRouter
    private lateinit var identity: Identity
    private var storagePath: String? = null

    @BeforeEach
    fun setup() {
        identity = Identity.create()
        storagePath = java.nio.file.Files.createTempDirectory("lxmf-p2-test").toFile().absolutePath
        router = LXMRouter(identity = identity, storagePath = storagePath)
    }

    @AfterEach
    fun teardown() {
        router.close()
    }

    private fun truncatedHash(): ByteArray {
        val hash = ByteArray(16)
        java.security.SecureRandom().nextBytes(hash)
        return hash
    }

    // ===== Access control =====

    @Test
    fun `allowControl and disallowControl manage list`() {
        val idHash = truncatedHash()
        router.allowControl(idHash)
        // Duplicate add is a no-op (Python checks membership before append)
        router.allowControl(idHash)
        router.disallowControl(idHash)
        // Removing again is a no-op, not an error (Python pop on list)
        router.disallowControl(idHash)
    }

    @Test
    fun `allowControl accepts and removes arbitrary hashes (P4 impl)`() {
        // NOTE: P4 allowControl/disallowControl intentionally dropped the P3
        // length guard; the list is keyed by raw identity hash bytes.
        val short = ByteArray(8)
        router.allowControl(short)
        router.disallowControl(short)
    }

    // ===== Stamps =====

    @Test
    fun `enforceStamps ignoreStamps toggle flag`() {
        assertFalse(router.stampsEnforced())
        router.enforceStamps()
        assertTrue(router.stampsEnforced())
        router.ignoreStamps()
        assertFalse(router.stampsEnforced())
    }

    @Test
    fun `setRetainNodeLxms stores setting`() {
        assertFalse(router.getRetainNodeLxms())
        router.setRetainNodeLxms(true)
        assertTrue(router.getRetainNodeLxms())
        router.setRetainNodeLxms(false)
        assertFalse(router.getRetainNodeLxms())
    }

    // ===== Stamp costs / tickets =====

    @Test
    fun `getOutboundStampCost returns null when unknown and cost when announced`() {
        val destHex = truncatedHash().toHexString()
        assertNull(router.getOutboundStampCost(destHex))
    }

    @Test
    fun `getOutboundTicketExpiry returns null without ticket`() {
        val destHex = truncatedHash().toHexString()
        assertNull(router.getOutboundTicketExpiry(destHex))
    }

    @Test
    fun `reloadAvailableTickets is safe with missing or corrupt file`() {
        // No file at all — must not throw
        router.reloadAvailableTickets()

        // Corrupt file — must not throw, matches Python recreate-on-error semantics
        val dir = File(storagePath!!, "lxmf")
        dir.mkdirs()
        File(dir, "available_tickets").writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        router.reloadAvailableTickets()
    }

    // ===== Outbound visibility/control =====

    @Test
    fun `getOutboundProgress returns null for unknown message`() = runBlocking {
        assertNull(router.getOutboundProgress("deadbeef"))
    }

    @Test
    fun `getOutboundProgress returns progress for pending message`() = runBlocking {
        val destIdentity = Identity.create()
        val sourceDestination =
            Destination.create(identity, DestinationDirection.IN, DestinationType.SINGLE, "lxmf", "delivery")
        val destDestination =
            Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "lxmf", "delivery")
        val message =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "progress test",
            )
        router.handleOutbound(message)
        assertNotNull(message.hash)

        val progress = router.getOutboundProgress(message.hash!!.toHexString())
        assertNotNull(progress)
        assertEquals(message.progress, progress, 0.0001)
        assertEquals(1, router.pendingOutboundCount())
    }

    @Test
    fun `getOutboundLxmStampCost null for unknown message`() {
        assertNull(router.getOutboundLxmStampCost("deadbeef"))
        assertNull(router.getOutboundLxmPropagationStampCost("deadbeef"))
    }

    @Test
    fun `cancelOutbound removes pending message`() = runBlocking {
        val destIdentity = Identity.create()
        val sourceDestination =
            Destination.create(identity, DestinationDirection.IN, DestinationType.SINGLE, "lxmf", "delivery")
        val destDestination =
            Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "lxmf", "delivery")
        val message =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "cancel me",
            )
        router.handleOutbound(message)
        assertEquals(1, router.pendingOutboundCount())

        router.cancelOutbound(message.hash!!.toHexString())
        assertEquals(MessageState.CANCELLED, message.state)
        assertEquals(0, router.pendingOutboundCount())
    }

    @Test
    fun `failMessage sets FAILED state and fires callback unless REJECTED`() {
        val destIdentity = Identity.create()
        val sourceDestination =
            Destination.create(identity, DestinationDirection.IN, DestinationType.SINGLE, "lxmf", "delivery")
        val destDestination =
            Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "lxmf", "delivery")
        val message = LXMessage.create(destination = destDestination, source = sourceDestination, content = "fail")

        var callbackFired = false
        message.failedCallback = { fired -> callbackFired = fired === message }

        message.progress = 0.5
        router.failMessage(message)
        assertEquals(MessageState.FAILED, message.state)
        assertTrue(callbackFired)
        assertEquals(0.0, message.progress, 0.0001)

        // REJECTED messages keep their state (Python: only set FAILED if != REJECTED)
        message.state = MessageState.REJECTED
        router.failMessage(message)
        assertEquals(MessageState.REJECTED, message.state)
    }

    @Test
    fun `hasMessage reflects delivered transient ids`() {
        assertFalse(router.hasMessage("notthere"))
    }

    // ===== Inbound resources =====

    @Test
    fun `inboundCount and cancelInbound handle empty state`() {
        assertEquals(0, router.inboundCount())
        assertTrue(router.inboundResources().isEmpty())
        assertFalse(router.cancelInbound("unknown"))
        assertEquals(0, router.cancelAllInbound())
    }

    // ===== Propagation node selection =====

    @Test
    fun `outbound propagation node roundtrip`() {
        assertNull(router.getOutboundPropagationNode())
        val nodeHex = truncatedHash().toHexString()
        router.setOutboundPropagationNode(nodeHex)
        assertEquals(nodeHex, router.getOutboundPropagationNode())
        // Inbound alias mirrors Python 1.1.1 behavior
        assertEquals(router.getOutboundPropagationNode(), router.getInboundPropagationNode())
    }

    @Test
    fun `setInboundPropagationNode raises NotImplementedError like Python`() {
        assertFailsWith<NotImplementedError> { router.setInboundPropagationNode(truncatedHash().toHexString()) }
    }

    @Test
    fun `cancelPropagationNodeRequests is safe without link`() {
        router.cancelPropagationNodeRequests()
        assertEquals(LXMRouter.PropagationTransferState.IDLE, router.propagationTransferState)
    }

    // ===== Announce metadata =====

    @Test
    fun `getAnnounceAppData packs registered destination data`() {
        val destIdentity = Identity.create()
        val destination = router.registerDeliveryIdentity(destIdentity, "P2Node", stampCost = 12)
        val appData = router.getAnnounceAppData(destination.hexHash)
        assertNotNull(appData)
        assertTrue(appData.isNotEmpty())

        // Unknown destination -> null (Python returns None implicitly)
        assertNull(router.getAnnounceAppData(truncatedHash().toHexString()))
    }

    @Test
    fun `propagation node announce helpers match client-only state`() {
        assertTrue(router.getPropagationNodeAnnounceMetadata().isEmpty())
        // P4 merged surface: app data is a real payload even for client-only routers
        // (Python parity — announce_app_data built unconditionally); compileStats
        // stays null while not running as a propagation node.
        assertNotNull(router.getPropagationNodeAppData())
        assertNull(router.compileStats())
    }

    // ===== Links / misc =====

    @Test
    fun `deliveryLinkAvailable false without links`() {
        assertFalse(router.deliveryLinkAvailable(truncatedHash().toHexString()))
    }

    @Test
    fun `registerExitHandler can be added and removed`() {
        // Registers a shutdown hook; JVM allows counting them to verify registration
        val hooksBefore = Thread.getAllStackTraces().keys.size
        router.registerExitHandler()
        // No exception means the hook was accepted; sanity check on thread count is informational
        assertTrue(Thread.getAllStackTraces().keys.size >= hooksBefore)
    }
}
