package network.reticulum.lxmf

import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.Test
import org.msgpack.core.MessagePack
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for LXMF propagation packing format.
 *
 * Verifies that packForPropagation() produces the correct wire format:
 * - Encrypts message body for the destination
 * - Computes transientId over destHash + encryptedData
 * - Wraps in msgpack([timestamp, [lxmfData]])
 * - Propagation stamp is generated over transientId (not messageId)
 */
class PropagationPackingTest {

    private fun createTestMessage(
        desiredMethod: DeliveryMethod = DeliveryMethod.PROPAGATED
    ): Triple<LXMessage, Destination, Destination> {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
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
            content = "Hello via propagation",
            title = "Propagated Test",
            desiredMethod = desiredMethod
        )

        return Triple(message, sourceDestination, destDestination)
    }

    @Test
    fun `packForPropagation sets transientId different from messageId`() {
        val (message, _, _) = createTestMessage()

        // Pack normally first (computes messageId)
        message.pack()
        val messageId = message.messageId
        assertNotNull(messageId)

        // Pack for propagation (computes transientId from encrypted data)
        message.packForPropagation()
        val transientId = message.transientId
        assertNotNull(transientId)

        // transientId must differ from messageId because it's computed
        // over encrypted data, not plaintext
        assertFalse(
            messageId.contentEquals(transientId),
            "transientId should differ from messageId (different hash bases)"
        )
    }

    @Test
    fun `packForPropagation produces valid msgpack envelope`() {
        val (message, _, destDestination) = createTestMessage()
        message.pack()
        message.packForPropagation()

        val propagationPacked = message.propagationPacked
        assertNotNull(propagationPacked)

        // Unpack and verify structure: [timestamp, [lxmfData]]
        val unpacker = MessagePack.newDefaultUnpacker(propagationPacked)
        val outerArraySize = unpacker.unpackArrayHeader()
        assertEquals(2, outerArraySize, "Outer array should have 2 elements")

        // [0] timestamp (float64)
        val timestamp = unpacker.unpackDouble()
        assertTrue(timestamp > 0, "Timestamp should be positive")

        // [1] message list (array of 1)
        val innerArraySize = unpacker.unpackArrayHeader()
        assertEquals(1, innerArraySize, "Inner array should have 1 message")

        // The message data: destHash + encryptedData [+ propagationStamp]
        val lxmfDataLen = unpacker.unpackBinaryHeader()
        val lxmfData = ByteArray(lxmfDataLen)
        unpacker.readPayload(lxmfData)

        // First 16 bytes should be the destination hash
        val destHash = lxmfData.copyOfRange(0, LXMFConstants.DESTINATION_LENGTH)
        assertTrue(
            destHash.contentEquals(destDestination.hash),
            "First 16 bytes should be destination hash"
        )

        // Remaining bytes are encrypted data (and possibly stamp)
        assertTrue(
            lxmfData.size > LXMFConstants.DESTINATION_LENGTH,
            "lxmfData should be larger than just the dest hash"
        )

        unpacker.close()
    }

    @Test
    fun `packForPropagation encrypts message body`() {
        val (message, sourceDestination, _) = createTestMessage()
        message.pack()
        val packedData = message.packed!!

        // Get source hash from packed data (should be at bytes 16-32)
        val sourceHash = packedData.copyOfRange(
            LXMFConstants.DESTINATION_LENGTH,
            2 * LXMFConstants.DESTINATION_LENGTH
        )
        assertTrue(sourceHash.contentEquals(sourceDestination.hash))

        // Now pack for propagation
        message.packForPropagation()
        val propagationPacked = message.propagationPacked!!

        // The propagation packed should NOT contain the source hash in plaintext
        // (it's encrypted in the body). Extract the lxmfData from the envelope.
        val unpacker = MessagePack.newDefaultUnpacker(propagationPacked)
        unpacker.unpackArrayHeader()
        unpacker.unpackDouble() // timestamp
        unpacker.unpackArrayHeader()
        val lxmfDataLen = unpacker.unpackBinaryHeader()
        val lxmfData = ByteArray(lxmfDataLen)
        unpacker.readPayload(lxmfData)
        unpacker.close()

        // The encrypted body starts after dest hash
        val encryptedBody = lxmfData.copyOfRange(LXMFConstants.DESTINATION_LENGTH, lxmfData.size)

        // The encrypted body should NOT start with the source hash
        // (because it's encrypted — it will be random-looking bytes)
        val firstBytes = encryptedBody.copyOfRange(0, minOf(LXMFConstants.DESTINATION_LENGTH, encryptedBody.size))
        assertFalse(
            firstBytes.contentEquals(sourceHash),
            "Encrypted body should not start with plaintext source hash"
        )
    }

    @Test
    fun `packForPropagation without prior pack calls pack automatically`() {
        val (message, _, _) = createTestMessage()

        // Don't call pack() first — packForPropagation should call it
        assertNull(message.packed)

        message.packForPropagation()

        // Should now have both packed and propagationPacked
        assertNotNull(message.packed)
        assertNotNull(message.propagationPacked)
        assertNotNull(message.transientId)
        assertNotNull(message.hash)
    }

    @Test
    fun `getPropagationStamp generates stamp over transientId`() = runBlocking {
        val (message, _, _) = createTestMessage()
        message.pack()

        // Generate propagation stamp with low cost for speed
        val stamp = message.getPropagationStamp(targetCost = 2)
        assertNotNull(stamp, "Should generate a propagation stamp")
        assertEquals(32, stamp.size, "Stamp should be 32 bytes")

        // Verify stamp properties are set
        assertTrue(message.propagationStampValid)
        assertNotNull(message.propagationStampValue)
        assertTrue(message.propagationStampValue!! >= 2, "Stamp value should meet target cost")

        // Verify stamp is cached (second call returns same stamp)
        val stamp2 = message.getPropagationStamp(targetCost = 2)
        assertTrue(stamp.contentEquals(stamp2!!), "Second call should return cached stamp")
    }

    @Test
    fun `propagation stamp is included in propagationPacked after generation`() = runBlocking {
        val (message, _, _) = createTestMessage()
        message.pack()

        // Generate stamp first
        val stamp = message.getPropagationStamp(targetCost = 2)
        assertNotNull(stamp)

        // Now pack for propagation — stamp should be appended
        message.packForPropagation()

        val propagationPacked = message.propagationPacked!!

        // Extract lxmfData from envelope
        val unpacker = MessagePack.newDefaultUnpacker(propagationPacked)
        unpacker.unpackArrayHeader()
        unpacker.unpackDouble()
        unpacker.unpackArrayHeader()
        val lxmfDataLen = unpacker.unpackBinaryHeader()
        val lxmfData = ByteArray(lxmfDataLen)
        unpacker.readPayload(lxmfData)
        unpacker.close()

        // lxmfData should end with the 32-byte propagation stamp
        val lastBytes = lxmfData.copyOfRange(lxmfData.size - 32, lxmfData.size)
        assertTrue(
            lastBytes.contentEquals(stamp),
            "lxmfData should end with the propagation stamp"
        )
    }

    @Test
    fun `propagation stamp validates with WORKBLOCK_EXPAND_ROUNDS_PN`() = runBlocking {
        val (message, _, _) = createTestMessage()
        message.pack()
        message.packForPropagation()

        val transientId = message.transientId!!
        val targetCost = 2

        val stamp = message.getPropagationStamp(targetCost)
        assertNotNull(stamp)

        // Validate using PN expansion rounds (1000), not message rounds (3000)
        val valid = LXStamper.validateStamp(
            stamp,
            transientId,
            targetCost,
            expandRounds = LXStamper.WORKBLOCK_EXPAND_ROUNDS_PN
        )
        assertTrue(valid, "Propagation stamp should validate with PN expansion rounds")
    }

    @Test
    fun `propagation packing preserves message hash and signature`() {
        val (message, _, _) = createTestMessage()
        message.pack()

        val originalHash = message.hash!!.copyOf()
        val originalSignature = message.signature!!.copyOf()

        message.packForPropagation()

        // Hash and signature should not change
        assertTrue(originalHash.contentEquals(message.hash!!))
        assertTrue(originalSignature.contentEquals(message.signature!!))
    }

    @Test
    fun `propagation packing for message without destination throws`() {
        // Create a message that has been unpacked (no destination object)
        val (message, _, _) = createTestMessage()
        message.pack()

        val packed = message.packed!!
        val incomingMessage = LXMessage.unpackFromBytes(packed)
        assertNotNull(incomingMessage)

        // Incoming messages don't have destination objects
        assertNull(incomingMessage.destination)

        // Should throw when trying to pack for propagation
        try {
            incomingMessage.packForPropagation()
            assertTrue(false, "Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("destination"))
        }
    }
}
