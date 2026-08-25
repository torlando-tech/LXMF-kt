package network.reticulum.lxmf

import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests for the LXMessage surface added to reach full semantic parity
 * with Python LXMF 1.1.0 LXMessage.py (task t_1a890b86).
 */
class LXMessageParityTest {

    private fun makeDestinations(): Triple<Identity, Destination, Destination> {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()
        val sourceDestination =
            Destination.create(
                identity = sourceIdentity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                "delivery",
            )
        val destDestination =
            Destination.create(
                identity = destIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                "delivery",
            )
        return Triple(sourceIdentity, sourceDestination, destDestination)
    }

    private fun makePackedPaperMessage(): Pair<LXMessage, Pair<Identity, Destination>> {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "paper payload for qr",
                title = "paper",
                desiredMethod = DeliveryMethod.PAPER,
            )
        msg.packForPaper()
        return Pair(msg, Pair(sourceIdentity, sourceDestination))
    }

    // ===== set_destination / set_source set-once semantics =====

    @Test
    fun `test setDestination fills null destination exactly once`() {
        val (_, _, destDestination) = makeDestinations()

        val unpacked =
            LXMessage.unpackFromBytes(
                // Build a minimal packed message first
                run {
                    val (srcId, srcDest, dstDest) = makeDestinations()
                    LXMessage.create(dstDest, srcDest, "hi").pack()
                },
            )!!

        assertNull(unpacked.destination)
        unpacked.setDestination(destDestination)
        assertEquals(destDestination.hash.toList(), unpacked.destination?.hash?.toList())

        val another = Identity.create()
        val anotherDest =
            Destination.create(
                identity = another,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                "delivery",
            )
        assertFailsWith<IllegalArgumentException> { unpacked.setDestination(anotherDest) }
    }

    @Test
    fun `test setSource rejects reassignment`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "x",
            )
        // Already set at construction → reassign must throw
        val otherIdentity = Identity.create()
        val otherSource =
            Destination.create(
                identity = otherIdentity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                "delivery",
            )
        assertFailsWith<IllegalArgumentException> { msg.setSource(otherSource) }
        assertNotNull(msg.source)
    }

    // ===== delivery destination + callback registration =====

    @Test
    fun `test delivery destination and callbacks`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "cb",
            )

        assertNull(msg.deliveryDestination)
        msg.setDeliveryDestination(sourceDestination)
        assertEquals(sourceDestination.hash.toList(), msg.deliveryDestination?.hash?.toList())
        msg.setDeliveryDestination(null)
        assertNull(msg.deliveryDestination)

        var delivered: LXMessage? = null
        var failed: LXMessage? = null
        msg.registerDeliveryCallback { delivered = it }
        msg.registerFailedCallback { failed = it }
        msg.deliveryCallback?.invoke(msg)
        msg.failedCallback?.invoke(msg)
        assertEquals(msg, delivered)
        assertEquals(msg, failed)
    }

    // ===== content_as_string =====

    @Test
    fun `test contentAsString returns content`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg = LXMessage.create(destDestination, sourceDestination, "hello parity")
        assertEquals("hello parity", msg.contentAsString())
    }

    // ===== determine_transport_encryption =====

    @Test
    fun `test determineTransportEncryption single opportunistic is EC`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg = LXMessage.create(destDestination, sourceDestination, "small", desiredMethod = DeliveryMethod.OPPORTUNISTIC)
        msg.pack()
        assertEquals(DeliveryMethod.OPPORTUNISTIC, msg.method)
        msg.determineTransportEncryption()
        assertTrue(msg.transportEncrypted)
        assertEquals(LXMFConstants.ENCRYPTION_DESCRIPTION_EC, msg.transportEncryption)

        val directMsg = LXMessage.create(destDestination, sourceDestination, "d", desiredMethod = DeliveryMethod.DIRECT)
        directMsg.pack()
        directMsg.determineTransportEncryption()
        assertTrue(directMsg.transportEncrypted)
        assertEquals(LXMFConstants.ENCRYPTION_DESCRIPTION_EC, directMsg.transportEncryption)
    }

    @Test
    fun `test determineTransportEncryption unencrypted without method`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg = LXMessage.create(destDestination, sourceDestination, "u")
        msg.determineTransportEncryption()
        assertEquals(false, msg.transportEncrypted)
        assertEquals(LXMFConstants.ENCRYPTION_DESCRIPTION_UNENCRYPTED, msg.transportEncryption)
    }

    // ===== determine_compression_support =====

    @Test
    fun `test compressionSupportFromAppData matches python semantics`() {
        // No app data → true (default path in determineCompressionSupport)
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg = LXMessage.create(destDestination, sourceDestination, "c")
        msg.determineCompressionSupport()
        assertTrue(msg.autoCompress)

        // Original (non-msgpack-list) format → true
        val legacy = byteArrayOf(0x01, 0x02, 0x03)
        assertTrue(LXMessage.compressionSupportFromAppData(legacy))

        // 0.5.0+ list format without feature list → true
        val noFeatures = MsgPackTestHelper.packList(listOf("display name"))
        assertTrue(LXMessage.compressionSupportFromAppData(noFeatures))

        // 0.5.0+ with feature list lacking SF_COMPRESSION → false
        val noCompression = MsgPackTestHelper.packList(listOf("name", null, listOf(0x01)))
        assertEquals(false, LXMessage.compressionSupportFromAppData(noCompression))

        // 0.5.0+ with SF_COMPRESSION present → true
        val withCompression = MsgPackTestHelper.packList(listOf("name", null, listOf(0x00)))
        assertTrue(LXMessage.compressionSupportFromAppData(withCompression))

        // List shorter than 3 → true
        val shortList = MsgPackTestHelper.packList(listOf("name", null))
        assertTrue(LXMessage.compressionSupportFromAppData(shortList))
    }

    // ===== get_propagation_stamp =====

    @Test
    fun `test getPropagationStamp generates and caches`() = runBlocking {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "propagate me",
                desiredMethod = DeliveryMethod.PROPAGATED,
            )

        assertFailsWith<IllegalArgumentException> { msg.getPropagationStamp(null) }

        // Low cost keeps the test fast
        val stamp = msg.getPropagationStamp(1)
        assertNotNull(stamp)
        assertEquals(32, stamp.size)
        assertTrue(msg.propagationStampValid)
        assertNotNull(msg.transientId)
        assertEquals(1, msg.propagationTargetCost)

        // Cached on second call
        val again = msg.getPropagationStamp(8)
        assertTrue(stamp.contentEquals(again!!))
    }

    // ===== packed_container / write_to_directory / unpack_from_file roundtrip =====

    @Test
    fun `test container write and read roundtrip`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "persist me",
                title = "persist",
            )
        msg.pack()
        msg.determineTransportEncryption()

        val container = msg.packedContainer()
        assertNotNull(container)
        assertTrue(container.isNotEmpty())

        val dir = File.createTempFile("lxmdir", "").let { f ->
            f.delete()
            f.mkdirs() ?: throw AssertionError("could not create temp dir")
            f
        }
        try {
            val writtenPath = msg.writeToDirectory(dir.absolutePath)
            assertNotNull(writtenPath)
            val file = File(writtenPath!!)
            // Filename == hex hash, like python
            assertEquals(msg.hash!!.toHexString(), file.name)

            val restored = LXMessage.unpackFromFile(file)
            assertNotNull(restored)
            assertEquals(msg.hash!!.toList(), restored!!.hash!!.toList())
            assertEquals(msg.content, restored.content)
            assertEquals(msg.title, restored.title)
            assertEquals(msg.state.value, restored.state.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `test writeToDirectory returns null without hash`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg = LXMessage.create(destDestination, sourceDestination, "never packed")
        assertNull(msg.writeToDirectory(System.getProperty("java.io.tmpdir")))
    }

    // ===== as_qr =====

    @Test
    fun `test asQr produces matrix for paper message`() {
        val (msg, _) = makePackedPaperMessage()
        val qr = msg.asQr()
        assertNotNull(qr)
        assertTrue(qr!!.isNotEmpty())
        // Square matrix with quiet zone border of 1 → side >= 21+2
        assertEquals(qr.size, qr[0].size)
        assertTrue(qr.size >= 23)
        // Corner finder patterns are dark inside border
        assertEquals(true, qr[3][3])
    }

    @Test
    fun `test asQr throws for non-paper message`() {
        val (sourceIdentity, sourceDestination, destDestination) = makeDestinations()
        val msg =
            LXMessage.create(
                destination = destDestination,
                source = sourceDestination,
                content = "not paper",
                desiredMethod = DeliveryMethod.DIRECT,
            )
        msg.pack()
        assertFailsWith<IllegalStateException> { msg.asQr() }
    }

    // ===== as_uri finalise behaviour =====

    @Test
    fun `test asUri finalise sets transport encryption and fires callback`() {
        val (msg, _) = makePackedPaperMessage()
        var called = false
        msg.registerDeliveryCallback { called = true }
        val uri = msg.asUri(finalise = true)
        assertTrue(uri.startsWith("lxm://"))
        assertTrue(msg.transportEncrypted)
        assertEquals(LXMFConstants.ENCRYPTION_DESCRIPTION_EC, msg.transportEncryption)
        assertEquals(1.0, msg.progress)
        assertTrue(called)

        // finalise=false does not fire the callback or touch transport state
        val (msg2, _) = makePackedPaperMessage()
        var called2 = false
        msg2.registerDeliveryCallback { called2 = true }
        val uri2 = msg2.asUri(finalise = false)
        assertTrue(uri2.startsWith("lxm://"))
        assertEquals(false, msg2.transportEncrypted)
        assertEquals(false, called2)
    }
}

/** Test helper: minimal msgpack array packer using the same library as main code. */
object MsgPackTestHelper {
    private fun packValue(packer: org.msgpack.core.MessagePacker, value: Any?) {
        when (value) {
            null -> packer.packNil()
            is String -> packer.packString(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { packValue(packer, it) }
            }
            else -> throw IllegalArgumentException("unsupported test type")
        }
    }

    fun packList(items: List<Any?>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val packer = org.msgpack.core.MessagePack.newDefaultPacker(buf)
        packValue(packer, items)
        packer.close()
        return buf.toByteArray()
    }
}
