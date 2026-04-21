package network.reticulum.lxmf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /**
     * Reflectively stamp a [Link] with phy stats as if they had been
     * captured from the last inbound Resource packet. The `trackPhyStats`
     * flag must be enabled for `getRssi()`/`getSnr()` to return non-null.
     */
    private fun setLinkField(link: network.reticulum.link.Link, fieldName: String, value: Any?) {
        val field = network.reticulum.link.Link::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(link, value)
    }

    @Test
    fun `resource-delivered path annotates LXMessage from link phy stats`() {
        // Covers the Resource-delivered (multi-packet) DIRECT case: no
        // sourcePacket is available in handleResourceConcluded, so the
        // annotation block falls through to the link branch. Previously
        // this code path was only validated by inspection — now an actual
        // test exercises link.getRssi/getSnr/attachedInterfaceHash/
        // expectedHops as production does.
        val received = CopyOnWriteArrayList<LXMessage>()
        router.registerDeliveryCallback { received.add(it) }

        val destinationIdentity = Identity.create()
        router.registerDeliveryIdentity(destinationIdentity, "ResourceNode")

        // Build a valid packed LXMF message. Source identity is remembered
        // so unpackFromBytes can validate the signature.
        val packedMessage = packMessageTo(destinationIdentity, "resource payload")

        // Create a real Link and prepare it to look like one that has
        // accumulated phy stats from Resource constituent packets. We need
        // a destination whose identity has a private key for Link.create
        // to succeed.
        val peerIdentity = Identity.create()
        val peerDestination = Destination.create(
            identity = peerIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        val link = network.reticulum.link.Link.create(peerDestination)

        val expectedRssi = -95
        val expectedSnr = 2.25f
        val expectedHops = 4
        val expectedIfaceHash = ByteArray(16) { (0xC0 + it).toByte() }

        setLinkField(link, "trackPhyStats", true)
        setLinkField(link, "phyRssi", expectedRssi)
        setLinkField(link, "phySnr", expectedSnr)
        link.attachedInterfaceHash = expectedIfaceHash
        setLinkField(link, "expectedHops", expectedHops)

        // The handleResourceConcluded path calls processInboundDelivery
        // with (data, DIRECT, null, link, sourcePacket=null). We invoke it
        // directly via reflection because spinning up a full Resource
        // transfer in a unit test is infeasible, but the production
        // annotation block is exercised end-to-end.
        val processInbound = LXMRouter::class.java.getDeclaredMethod(
            "processInboundDelivery",
            ByteArray::class.java,
            DeliveryMethod::class.java,
            Destination::class.java,
            network.reticulum.link.Link::class.java,
            Packet::class.java
        )
        processInbound.isAccessible = true
        processInbound.invoke(router, packedMessage, DeliveryMethod.DIRECT, null, link, null)

        assertEquals(1, received.size)
        val delivered = received[0]
        assertEquals("resource payload", delivered.content)
        assertEquals(DeliveryMethod.DIRECT, delivered.method)
        // Tight equality on each of the four fields: a regression that
        // wires only hops (or only rssi) would fail distinctly.
        assertEquals(expectedRssi, delivered.receivedRssi)
        assertEquals(expectedSnr, delivered.receivedSnr)
        assertEquals(expectedHops, delivered.receivedHopCount)
        assertNotNull(delivered.receivingInterfaceHash)
        assertTrue(
            expectedIfaceHash.contentEquals(delivered.receivingInterfaceHash!!),
            "Interface hash on delivered LXMessage should equal link.attachedInterfaceHash"
        )
        // Defensive-copy invariant: the delivered message's interface hash
        // must not be the same ByteArray instance as the link's (mutating
        // one must not bleed into the other). If we ever drop the copyOf()
        // at the annotation site, this assertion fails loudly.
        assertTrue(
            delivered.receivingInterfaceHash !== link.attachedInterfaceHash,
            "Delivered receivingInterfaceHash must be a defensive copy, not a reference"
        )
    }

    @Test
    fun `opportunistic delivery defensively copies receivingInterfaceHash`() {
        // Companion invariant for the packet-based branch: same defensive
        // copy requirement as the link-based branch above. If the RNS
        // layer reuses a buffer for receivingInterfaceHash, mutations must
        // not reach the LXMessage after delivery.
        val received = CopyOnWriteArrayList<LXMessage>()
        router.registerDeliveryCallback { received.add(it) }

        val destinationIdentity = Identity.create()
        val deliveryDest = router.registerDeliveryIdentity(destinationIdentity, "DefensiveCopyNode")
        val packedMessage = packMessageTo(destinationIdentity, "copy-check payload")
        val afterDestHash = packedMessage.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packedMessage.size)

        val originalIface = ByteArray(16) { (it + 1).toByte() }
        val packet = Packet.createRaw(
            destinationHash = deliveryDest.hash,
            data = afterDestHash
        )
        packet.hops = 1
        setPacketField(packet, "receivingInterfaceHash", originalIface)
        setPacketField(packet, "destination", deliveryDest)

        deliveryDest.packetCallback!!.invoke(afterDestHash, packet)

        assertEquals(1, received.size)
        val delivered = received[0]
        assertNotNull(delivered.receivingInterfaceHash)
        // Content matches...
        assertTrue(originalIface.contentEquals(delivered.receivingInterfaceHash!!))
        // ...but it's a defensive copy, not an aliased reference.
        assertTrue(
            delivered.receivingInterfaceHash !== originalIface,
            "Delivered receivingInterfaceHash must be a defensive copy of the packet's buffer"
        )
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

    /**
     * Regression test for the send-spinner-hang observed on 2026-04-20.
     *
     * Symptom: two Columba phones messaging each other; after disabling
     * interfaces on one phone mid-send, subsequent sends hang forever with
     * the UI spinner never clearing. On-device instrumentation narrowed
     * the wedge to [LXMRouter.handleOutbound] blocking at
     * `pendingOutboundMutex.withLock`, waiting on a lock held by a prior
     * [processOutbound] call that was stuck inside [processOutboundMessage]
     * (synchronous `packet.send()` blocking on a flaky/disabled interface).
     *
     * Root cause: [processOutbound] held [pendingOutboundMutex] across
     * every per-message delivery dispatch. A single hung delivery wedged
     * every subsequent [handleOutbound] caller on the same mutex.
     *
     * Invariant this test pins: `handleOutbound` must not block on a
     * long-running [processOutboundMessage] call. Concretely, the lock
     * must be released BEFORE the per-message dispatch runs.
     *
     * Pre-fix behaviour: the `withTimeout(400)` below trips because
     * `handleOutbound` suspends on the mutex held by the wedged hook.
     * Post-fix behaviour: `handleOutbound` completes within a few ms.
     */
    @Test
    fun `handleOutbound not blocked when processOutboundMessage hangs (regression)`() = runBlocking {
        val destIdentity1 = Identity.create()
        val destIdentity2 = Identity.create()
        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        fun buildOutboundMessage(destIdentity: Identity): LXMessage {
            val destDestination = Destination.create(
                identity = destIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                "delivery"
            )
            return LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "test",
                title = "test"
            )
        }

        // 1. Queue one message that will be picked up by processOutbound.
        val firstMessage = buildOutboundMessage(destIdentity1)
        firstMessage.nextDeliveryAttempt = 0L // ready for dispatch now
        router.handleOutbound(firstMessage)
        assertEquals(1, router.pendingOutboundCount())

        // 2. Install the test hook so processOutboundMessage suspends
        // indefinitely (until we release it). Pre-fix this suspension
        // happens INSIDE pendingOutboundMutex.withLock, so any concurrent
        // handleOutbound call is wedged on the same mutex.
        val hookEntered = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        router.testHookOnProcessOutboundMessage = {
            hookEntered.complete(Unit)
            hookRelease.await()
        }

        // 3. Launch processOutbound in a separate coroutine. It will
        // call processOutboundMessage (hitting our hook) and suspend.
        val processJob = launch(Dispatchers.Default) { router.processOutbound() }

        try {
            // 4. Wait until the hook has been entered — at this point,
            // processOutbound is suspended inside processOutboundMessage.
            // The question under test: is pendingOutboundMutex still held?
            hookEntered.await()

            // 5. Call handleOutbound from the test coroutine. If the
            // mutex is held across the hook (bug), this suspends until
            // the timeout trips. Post-fix, the mutex has already been
            // released, so this returns in a few ms.
            withTimeout(400) {
                router.handleOutbound(buildOutboundMessage(destIdentity2))
            }

            assertEquals(
                2,
                router.pendingOutboundCount(),
                "Second message should be queued while processOutbound is mid-dispatch"
            )
        } finally {
            hookRelease.complete(Unit)
            router.testHookOnProcessOutboundMessage = null
            processJob.join()
        }
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
