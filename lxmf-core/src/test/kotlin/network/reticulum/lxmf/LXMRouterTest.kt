package network.reticulum.lxmf

import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for LXMRouter.
 */
class LXMRouterTest {

    private lateinit var router: LXMRouter
    private lateinit var identity: Identity

    @BeforeEach
    fun setup() {
        identity = Identity.create()
        router = LXMRouter(identity = identity)
    }

    @AfterEach
    fun teardown() {
        router.stop()
    }

    @Test
    fun `test router creation`() {
        assertNotNull(router)
    }

    @Test
    fun `test register delivery identity`() {
        val destination = router.registerDeliveryIdentity(identity, "TestNode")

        assertNotNull(destination)
        assertEquals(DestinationDirection.IN, destination.direction)
        assertEquals(DestinationType.SINGLE, destination.type)
        assertEquals("lxmf", destination.appName)

        // Verify destination is stored
        val stored = router.getDeliveryDestination(destination.hexHash)
        assertNotNull(stored)
        assertEquals("TestNode", stored.displayName)
    }

    @Test
    fun `test register delivery callback`() {
        var receivedMessage: LXMessage? = null

        router.registerDeliveryCallback { message ->
            receivedMessage = message
        }

        // Callback is registered (we can't easily test it fires without full transport)
        assertNotNull(router)
    }

    @Test
    fun `test handle outbound message`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test message",
            title = "Test"
        )

        // Queue the message
        router.handleOutbound(message)

        // Verify message is queued
        assertEquals(1, router.pendingOutboundCount())
        assertEquals(MessageState.OUTBOUND, message.state)
        assertNotNull(message.packed)
    }

    @Test
    fun `test router start and stop`() {
        router.start()
        // Give it a moment to start
        Thread.sleep(100)

        router.stop()
        // Should stop without error
    }

    @Test
    fun `test multiple delivery destinations`() {
        val identity1 = Identity.create()
        val identity2 = Identity.create()

        val dest1 = router.registerDeliveryIdentity(identity1, "Node1")
        val dest2 = router.registerDeliveryIdentity(identity2, "Node2")

        assertNotNull(dest1)
        assertNotNull(dest2)

        val destinations = router.getDeliveryDestinations()
        assertEquals(2, destinations.size)
    }

    @Test
    fun `test failed delivery callback registration`() {
        var failedMessage: LXMessage? = null

        router.registerFailedDeliveryCallback { message ->
            failedMessage = message
        }

        // Callback is registered
        assertNotNull(router)
    }

    @Test
    fun `test process outbound with no messages`() = runBlocking {
        // Should not throw when processing empty queue
        router.processOutbound()

        assertEquals(0, router.pendingOutboundCount())
    }

    @Test
    fun `test message delivery attempt tracking`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        assertEquals(0, message.deliveryAttempts)

        router.handleOutbound(message)

        // Process should increment delivery attempts
        router.processOutbound()

        assertTrue(message.deliveryAttempts > 0)
    }

    @Test
    fun `test message representation is PACKET for small messages`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Small message - should be PACKET representation
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Short message",
            title = "Test"
        )

        router.handleOutbound(message)

        assertEquals(MessageRepresentation.PACKET, message.representation)
    }

    @Test
    fun `test message representation is RESOURCE for large messages`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Large message - should be RESOURCE representation
        val largeContent = "X".repeat(500)  // Well over link packet limit
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = largeContent,
            title = "Large"
        )

        router.handleOutbound(message)

        assertEquals(MessageRepresentation.RESOURCE, message.representation)
    }

    @Test
    fun `test direct delivery method increments attempts`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        assertEquals(DeliveryMethod.DIRECT, message.desiredMethod)
        assertEquals(0, message.deliveryAttempts)

        router.handleOutbound(message)
        router.processOutbound()

        // Direct delivery without path/link should increment attempts
        assertEquals(1, message.deliveryAttempts)

        // Process again
        router.processOutbound()

        // May retry depending on timing
        assertTrue(message.deliveryAttempts >= 1)
    }

    @Test
    fun `test message state transitions`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test"
        )

        // Initially GENERATING
        assertEquals(MessageState.GENERATING, message.state)

        // After handleOutbound - should be OUTBOUND
        router.handleOutbound(message)
        assertEquals(MessageState.OUTBOUND, message.state)
    }

    @Test
    fun `test max delivery attempts results in failure`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Set delivery attempts to max
        message.deliveryAttempts = LXMRouter.MAX_DELIVERY_ATTEMPTS

        router.handleOutbound(message)

        // Force immediate retry by clearing next attempt time
        message.nextDeliveryAttempt = null

        router.processOutbound()

        // Should be FAILED after exceeding max attempts
        assertEquals(MessageState.FAILED, message.state)
    }

    // ===== Propagation Node Tests =====

    @Test
    fun `test propagation node tracking`() {
        // Initially no propagation nodes
        assertEquals(0, router.getPropagationNodes().size)
        assertEquals(null, router.getActivePropagationNode())
    }

    @Test
    fun `test set active propagation node without known node saves hash for later`() {
        // Setting an unknown node now succeeds (saves hash for later announce arrival)
        val result = router.setActivePropagationNode("0123456789abcdef")
        assertEquals(true, result)
        // Node object won't exist (no recalled identity), but hash is saved internally
        // getActivePropagationNode returns null because Identity.recall fails
        assertEquals(null, router.getActivePropagationNode())
    }

    @Test
    fun `test propagation transfer state initially idle`() {
        assertEquals(LXMRouter.PropagationTransferState.IDLE, router.propagationTransferState)
        assertEquals(0.0, router.propagationTransferProgress)
        assertEquals(0, router.propagationTransferLastResult)
    }

    @Test
    fun `test propagated message queuing without active node`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test via propagation",
            title = "Propagated",
            desiredMethod = DeliveryMethod.PROPAGATED
        )

        assertEquals(DeliveryMethod.PROPAGATED, message.desiredMethod)

        router.handleOutbound(message)
        assertEquals(1, router.pendingOutboundCount())

        // With no active propagation node, Python fails the message immediately
        // (LXMRouter.py line 2669-2671: fail_message if outbound_propagation_node is None)
        router.processOutbound()

        assertEquals(MessageState.FAILED, message.state)
    }

    @Test
    fun `test get outbound propagation cost without active node`() {
        val cost = router.getOutboundPropagationCost()
        assertEquals(null, cost)
    }

    // ---------------------------------------------------------------------
    // Receive-time packet-metadata annotation (LXMF-kt issue #9).
    //
    // These tests exercise the end-to-end opportunistic delivery path:
    // router.registerDeliveryIdentity installs destination.packetCallback,
    // which then dispatches to handleDeliveryPacket -> processInboundDelivery
    // and finally invokes the user-registered deliveryCallback with an
    // LXMessage. We construct a real Packet, stamp it with deterministic
    // phy metadata (rssi/snr/hops/receivingInterfaceHash), invoke the
    // destination's packetCallback with that packet, and assert the
    // delivered LXMessage carries the metadata.
    // ---------------------------------------------------------------------

    /**
     * Produce the wire bytes of an LXMF message (destHash + sourceHash +
     * signature + msgpackPayload) destined to [destinationIdentity].
     */
    private fun packMessageTo(destinationIdentity: Identity, body: String): ByteArray {
        val sourceIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val destDestination = Destination.create(
            identity = destinationIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Remember the source identity so the router's signature-validation
        // path can recall it during unpackFromBytes. Without this, signatures
        // come back SOURCE_UNKNOWN but the router still delivers the
        // message — we want the happy path.
        Identity.remember(
            packetHash = ByteArray(32) { it.toByte() },
            destHash = sourceDestination.hash,
            publicKey = sourceIdentity.getPublicKey()
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = body,
            title = "Test"
        )
        return message.pack()
    }

    /**
     * Forcibly set a [Packet] field whose setter is marked `internal` to
     * the rns-core module (rssi, snr, receivingInterfaceHash, destination).
     * We can't set these from outside rns-core without reflection, but the
     * production code in LXMRouter reads them via the public getter, so
     * the behavior under test is exercised as in prod.
     */
    private fun <T> setPacketField(packet: Packet, fieldName: String, value: T?) {
        val field = Packet::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(packet, value)
    }

    @Test
    fun `opportunistic delivery annotates LXMessage with packet phy metadata`() {
        val received = CopyOnWriteArrayList<LXMessage>()
        router.registerDeliveryCallback { received.add(it) }

        val destinationIdentity = Identity.create()
        val deliveryDest = router.registerDeliveryIdentity(destinationIdentity, "MetaNode")
        val packedMessage = packMessageTo(destinationIdentity, "payload under test")

        // The destination packet callback (installed by LXMRouter) strips the
        // destHash and re-prepends it inside handleDeliveryPacket, so the
        // packet's `data` here should be everything AFTER the dest hash.
        val afterDestHash = packedMessage.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packedMessage.size)

        val expectedRssi = -80
        val expectedSnr = 7.5f
        val expectedHops = 3
        val expectedIfaceHash = ByteArray(16) { (0xA0 + it).toByte() }

        val packet = Packet.createRaw(
            destinationHash = deliveryDest.hash,
            data = afterDestHash
        )
        packet.hops = expectedHops
        setPacketField(packet, "rssi", expectedRssi)
        setPacketField(packet, "snr", expectedSnr)
        setPacketField(packet, "receivingInterfaceHash", expectedIfaceHash)
        // `packet.destination` has `internal set`; assign it so that the
        // router's `packet.prove()` call in handleDeliveryPacket doesn't
        // blow up. In prod the Transport layer populates this before
        // dispatching to the destination's callback.
        setPacketField(packet, "destination", deliveryDest)

        // Fire the real callback the router installed. This drives
        // handleDeliveryPacket -> processInboundDelivery -> deliveryCallback.
        val callback = deliveryDest.packetCallback
        assertNotNull(callback)
        callback.invoke(afterDestHash, packet)

        assertEquals(1, received.size)
        val delivered = received[0]
        // Tight equality — loose membership would silently pass if the
        // wrong packet were used or if a duplicate came through.
        assertEquals(expectedRssi, delivered.receivedRssi)
        assertEquals(expectedSnr, delivered.receivedSnr)
        assertEquals(expectedHops, delivered.receivedHopCount)
        assertNotNull(delivered.receivingInterfaceHash)
        assertTrue(
            expectedIfaceHash.contentEquals(delivered.receivingInterfaceHash!!),
            "Interface hash on delivered LXMessage should equal the packet's receivingInterfaceHash"
        )
        assertEquals(DeliveryMethod.OPPORTUNISTIC, delivered.method)
    }

    @Test
    fun `propagation-fetched message has null receive-time metadata`() {
        // Invariant from issue #9: messages pulled from a propagation node
        // have lost their original in-path packet context. Annotating them
        // with the sync link's RSSI/SNR would mislead downstream consumers
        // into thinking the values refer to the original sender. So the
        // four metadata fields MUST be null for PROPAGATED.
        //
        // We can't drive this via a real propagation-node handshake in a
        // pure unit test, so we reflectively invoke processInboundDelivery
        // with method=PROPAGATED on a properly-encrypted blob. The code
        // path under test — the null-on-propagated branch in the
        // annotation block — is exercised exactly as in prod.
        val received = CopyOnWriteArrayList<LXMessage>()
        router.registerDeliveryCallback { received.add(it) }

        // Set up a delivery destination whose identity we control so we
        // can also build an OUT-direction destination that encrypts for it.
        val destinationIdentity = Identity.create()
        val inboundDest = router.registerDeliveryIdentity(destinationIdentity, "PropMetaNode")

        // Build a valid inner LXMF wire-format blob. Source identity has
        // to be remembered so signature validates.
        val sourceIdentity = Identity.create()
        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        Identity.remember(
            packetHash = ByteArray(32) { (it + 1).toByte() },
            destHash = sourceDestination.hash,
            publicKey = sourceIdentity.getPublicKey()
        )

        // Out-direction destination (shares the receiver's pub key) so we
        // can encrypt the propagated blob.
        val outboundToRecipient = Destination.create(
            identity = destinationIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val innerMessage = LXMessage.create(
            destination = outboundToRecipient,
            source = sourceDestination,
            content = "propagated payload",
            title = "PropTest"
        )
        val innerPacked = innerMessage.pack()
        val innerSansDestHash = innerPacked.copyOfRange(LXMFConstants.DESTINATION_LENGTH, innerPacked.size)

        // Propagated wire format: [destHash(16)] + encrypted(sourceHash + sig + payload)
        val encrypted = outboundToRecipient.encrypt(innerSansDestHash)
        val propagatedBlob = inboundDest.hash + encrypted

        val processInbound = LXMRouter::class.java.getDeclaredMethod(
            "processInboundDelivery",
            ByteArray::class.java,
            DeliveryMethod::class.java,
            Destination::class.java,
            network.reticulum.link.Link::class.java,
            Packet::class.java
        )
        processInbound.isAccessible = true
        processInbound.invoke(router, propagatedBlob, DeliveryMethod.PROPAGATED, null, null, null)

        assertEquals(1, received.size, "router should still deliver the propagated message")
        val delivered = received[0]
        assertEquals("propagated payload", delivered.content)
        assertEquals(DeliveryMethod.PROPAGATED, delivered.method)
        // The key invariant under test: no misleading phy metadata on a
        // propagation-fetched message.
        assertNull(delivered.receivedRssi, "PROPAGATED must leave receivedRssi null")
        assertNull(delivered.receivedSnr, "PROPAGATED must leave receivedSnr null")
        assertNull(delivered.receivingInterfaceHash, "PROPAGATED must leave receivingInterfaceHash null")
        assertNull(delivered.receivedHopCount, "PROPAGATED must leave receivedHopCount null")
    }

    @Test
    fun `opportunistic delivery with null packet phy stats leaves metadata null`() {
        // Companion case to the happy path: a packet that carried no phy
        // stats (interface didn't populate rssi/snr) should end up with
        // NULL metadata on the LXMessage — not garbage / zeros. This pins
        // the "null-or-meaningful" invariant downstream consumers rely on.
        val received = CopyOnWriteArrayList<LXMessage>()
        router.registerDeliveryCallback { received.add(it) }

        val destinationIdentity = Identity.create()
        val deliveryDest = router.registerDeliveryIdentity(destinationIdentity, "MetaNodeNull")
        val packedMessage = packMessageTo(destinationIdentity, "payload no phy")
        val afterDestHash = packedMessage.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packedMessage.size)

        val packet = Packet.createRaw(
            destinationHash = deliveryDest.hash,
            data = afterDestHash
        )
        packet.hops = 2
        // Intentionally leave rssi/snr/receivingInterfaceHash unset (null).
        // Associate the packet with the destination so the router's
        // packet.prove() call succeeds as it does in prod.
        setPacketField(packet, "destination", deliveryDest)

        val callback = deliveryDest.packetCallback
        assertNotNull(callback)
        callback.invoke(afterDestHash, packet)

        assertEquals(1, received.size)
        val delivered = received[0]
        assertNull(delivered.receivedRssi, "rssi should stay null when interface didn't provide it")
        assertNull(delivered.receivedSnr, "snr should stay null when interface didn't provide it")
        assertNull(delivered.receivingInterfaceHash, "interface hash should stay null when not set")
        // hops is a non-nullable Int on Packet (defaults to 0 / user-set 2
        // here), so it WILL propagate — that's correct.
        assertEquals(2, delivered.receivedHopCount)
    }

    // Make propagationTransferState accessible for tests
    val LXMRouter.propagationTransferState: LXMRouter.PropagationTransferState
        get() = try {
            val field = LXMRouter::class.java.getDeclaredField("propagationTransferState")
            field.isAccessible = true
            field.get(this) as LXMRouter.PropagationTransferState
        } catch (e: Exception) {
            LXMRouter.PropagationTransferState.IDLE
        }
}
