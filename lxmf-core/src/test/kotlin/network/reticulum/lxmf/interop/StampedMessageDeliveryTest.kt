package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Reproduces the "Columba sends stamped LXMessage to Sideband, Sideband drops it
 * with invalid-stamp" bug as a deterministic unit test.
 *
 * The flow under test mirrors `LXMRouter.handleOutbound` exactly:
 *
 *   1. `pack()`            — produces 4-element wire (no stamp), records `hash`/`signature`
 *   2. `getStamp()`        — generates a stamp against the message hash
 *   3. `repackWithStamp()` — rebuilds `packed` as the 5-element wire (4 + stamp)
 *
 * Then hands the wire bytes to Python's `lxmf_validate_message_stamp`, which
 * runs the EXACT `LXMessage.unpack_from_bytes()` → `validate_stamp(target_cost)`
 * sequence Sideband's `LXMRouter.lxmf_delivery` uses to drop messages
 * (LXMRouter.py:1752-1772).
 *
 * Why this matters: existing `StampInteropTest` covers the stamp primitive in
 * isolation (known-message-id stamp generation/validation between Kotlin and
 * Python), and `KotlinToPythonDirectTest` covers wire-format round-trip without
 * a stamp. Neither catches the "stamp generated against Kotlin's hash but
 * Python's recomputed hash differs after stripping the stamp and repacking" or
 * "stamp wire-encoded incorrectly" failure modes, both of which manifest as
 * `validate_stamp() == False` on Sideband's side.
 *
 * If a test here fails with `signature_validated=false`, the bug is in
 * msgpack round-trip determinism (Kotlin's payloadWithoutStamp ≠ Python's
 * `umsgpack.packb(unpacked[:4])`), since the receiver's hash recomputation
 * also feeds signature verification. If `signature_validated=true` but
 * `stamp_valid=false`, the bug is in the stamp itself (wrong workblock,
 * wrong stamp bytes on the wire, or off-by-one in cost).
 */
class StampedMessageDeliveryTest : LXMFInteropTestBase() {

    /**
     * Register the Kotlin source identity with Python's RNS so signature
     * validation can happen at unpack time. Without this, every unpack
     * would set `signature_validated=false` with reason `SOURCE_UNKNOWN`,
     * which has nothing to do with the stamp algorithm under test.
     *
     * This mirrors what happens in production via Reticulum announces:
     * Sideband learns Columba's identity through an announce before the
     * first message arrives, so by the time it's validating, the source
     * identity is in the RNS Identity cache.
     */
    @BeforeAll
    fun rememberSourceIdentity() {
        python(
            "lxmf_remember_identity",
            "destination_hash" to sourceDestination.hash.toHex(),
            "public_key" to testSourceIdentity.getPublicKey().toHex(),
        )
    }

    /**
     * Send a Kotlin LXMessage through pack→getStamp→repackWithStamp and have
     * Python execute its real validate_stamp path. Returns Python's verdict.
     */
    private fun packStampAndValidate(
        message: LXMessage,
        stampCost: Int,
    ): JsonObject {
        message.stampCost = stampCost

        // 1. pack() — produces 4-element wire, computes hash + signature
        val first = message.pack()
        first.size shouldNotBe 0
        message.hash shouldNotBe null

        val hashBeforeStamp = message.hash!!.toHex()
        println("  [Kotlin] hash after pack(): $hashBeforeStamp")

        // 2. getStamp() — generates stamp against `message.hash`
        val stamp = runBlocking { message.getStamp() }
        stamp shouldNotBe null
        message.stamp shouldNotBe null
        println("  [Kotlin] stamp generated: ${stamp!!.toHex()}")

        // 3. repackWithStamp() — rebuilds packed as 5-element wire
        message.repackWithStamp()
        val wire = message.packed!!
        wire.size shouldNotBe 0
        println("  [Kotlin] wire after repackWithStamp(): ${wire.size} bytes")

        // hash should be unchanged (it was computed from the stampless payload
        // in pack(); repack only edits the wire bytes, not the stored hash)
        message.hash!!.toHex() shouldBe hashBeforeStamp

        // 4. Python's enforcing-receiver path
        val result = python(
            "lxmf_validate_message_stamp",
            "lxmf_bytes" to wire.toHex(),
            "target_cost" to stampCost,
        )
        println("  [Python] $result")
        return result
    }

    private fun assertAccepted(
        result: JsonObject,
        kotlinHash: String,
        cost: Int,
    ) {
        assertSoftly {
            result.getString("unpacked") shouldBe "true"
            // Receiver's recomputed hash MUST match what Kotlin signed/stamped against.
            result.getString("message_hash") shouldBe kotlinHash
            // Signature uses the same recomputed hash; it had better validate.
            result.getString("signature_validated") shouldBe "true"
            // The whole point of the test: the stamp Sideband validates passes.
            result.getString("stamp_present") shouldBe "true"
            result.getString("stamp_valid") shouldBe "true"
        }
        println("  PASS: cost=$cost stamp accepted by Python's enforcing receiver")
    }

    @Nested
    @DisplayName("BasicShapes")
    inner class BasicShapes {

        @Test
        fun `simple text message with stamp validates on Python side`() {
            println("\n=== simple text + stamp ===")
            val message = createTestMessage(content = "Hello, Sideband!")
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `empty content with stamp validates on Python side`() {
            println("\n=== empty content + stamp ===")
            val message = createTestMessage(content = "")
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `message with title and content with stamp validates on Python side`() {
            println("\n=== title + content + stamp ===")
            val message = createTestMessage(content = "body", title = "subj")
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }
    }

    @Nested
    @DisplayName("ColumbaFieldShapes")
    inner class ColumbaFieldShapes {

        @Test
        fun `renderer field markdown with stamp validates`() {
            println("\n=== FIELD_RENDERER (markdown) + stamp ===")
            val message = createTestMessage(
                content = "**bold**",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_MARKDOWN,
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `image field jpg + bytes with stamp validates`() {
            println("\n=== FIELD_IMAGE (str + bin) + stamp ===")
            val message = createTestMessage(
                content = "image attached",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_IMAGE to listOf("jpg", ByteArray(64) { it.toByte() }),
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `reply-to field nested string map with stamp validates`() {
            println("\n=== reply-to (nested str→str map) + stamp ===")
            val message = createTestMessage(
                content = "reply!",
                fields = mutableMapOf(
                    16 to mutableMapOf<String, Any>(
                        "reply_to" to "abcdef0123456789",
                    ),
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `icon-appearance field str + 2 bin with stamp validates`() {
            println("\n=== FIELD_ICON_APPEARANCE (str + bin + bin) + stamp ===")
            val message = createTestMessage(
                content = "iconed",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_ICON_APPEARANCE to listOf(
                        "compass",
                        byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00),
                        byteArrayOf(0x00, 0x00, 0x00),
                    ),
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `file-attachments field list of list of bin with stamp validates`() {
            println("\n=== FIELD_FILE_ATTACHMENTS (list[list[bin,bin]]) + stamp ===")
            val message = createTestMessage(
                content = "files",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_FILE_ATTACHMENTS to listOf(
                        listOf("readme.txt".toByteArray(), "hello world".toByteArray()),
                        listOf("data.bin".toByteArray(), ByteArray(32) { (it * 7).toByte() }),
                    ),
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        @Test
        fun `reaction-style fields nested map with stamp validates`() {
            println("\n=== reaction (nested str→str map, 3 keys) + stamp ===")
            val message = createTestMessage(
                content = "",
                fields = mutableMapOf(
                    16 to mutableMapOf<String, Any>(
                        "reaction_to" to "abcdef0123456789",
                        "emoji" to "👍",
                        "sender" to "0011223344556677",
                    ),
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }
    }

    @Nested
    @DisplayName("HigherStampCosts")
    inner class HigherStampCosts {

        @Test
        @Tag("slow")
        fun `cost 8 with empty fields validates`() {
            println("\n=== cost=8 + empty fields ===")
            val message = createTestMessage(content = "Hello")
            val result = packStampAndValidate(message, stampCost = 8)
            assertAccepted(result, message.hash!!.toHex(), cost = 8)
        }
    }

    @Nested
    @DisplayName("TicketStampPath")
    inner class TicketStampPath {

        /**
         * Replicates the production code path where Columba has a CACHED
         * outbound ticket from Sideband (received earlier via FIELD_TICKET in
         * one of Sideband's messages). When `message.outboundTicket` is set,
         * `LXMessage.getStamp()` skips the PoW path entirely and emits a
         * 16-byte `truncatedHash(ticket || message_hash)` instead of a
         * 32-byte PoW stamp.
         *
         * Sideband (the receiver) accepts this 16-byte stamp ONLY if it
         * still holds the matching INBOUND ticket — i.e. `validate_stamp`
         * sees `stamp == truncated_hash(ticket + message_id)` for some
         * ticket in `get_inbound_tickets(source_hash)`. If Sideband's
         * inbound-ticket cache was wiped (process restart without persisting,
         * cache eviction, ticket expiry), it falls through to PoW validation
         * on the 16-byte stamp — which fails with overwhelming probability
         * (~1/2^cost) at any non-trivial cost. The drop log line says
         * "invalid stamp" with no hint the actual issue is a stale ticket.
         */
        @Test
        fun `ticket-stamp validates when Python holds matching inbound ticket`() {
            println("\n=== ticket-stamp + receiver-has-ticket ===")
            val message = createTestMessage(content = "Reply with cached ticket")
            val ticket = ByteArray(LXMFConstants.TICKET_LENGTH) { (0x42 xor it).toByte() }
            message.outboundTicket = ticket

            // pack() — produces 4-element wire, computes hash + signature
            message.pack()
            val hashHex = message.hash!!.toHex()
            println("  [Kotlin] hash: $hashHex")

            // getStamp() — takes the ticket path, returns 16-byte truncated hash
            val stamp = runBlocking { message.getStamp() }
            stamp shouldNotBe null
            stamp!!.size shouldBe LXMFConstants.TICKET_LENGTH
            println("  [Kotlin] ticket-stamp (${stamp.size} bytes): ${stamp.toHex()}")

            message.repackWithStamp()
            val wire = message.packed!!

            // Python validates with the ticket present — should accept via the
            // ticket-match branch in LXMessage.validate_stamp().
            val result = python(
                "lxmf_validate_message_stamp_with_tickets",
                "lxmf_bytes" to wire.toHex(),
                "target_cost" to 4,
                "tickets" to listOf(ticket.toHex()),
            )
            println("  [Python with ticket]: $result")

            assertSoftly {
                result.getString("unpacked") shouldBe "true"
                result.getString("stamp_present") shouldBe "true"
                result.getString("stamp_valid") shouldBe "true"
            }
        }

        /**
         * Same wire bytes as the test above, but the receiver has FORGOTTEN
         * the ticket (e.g. Sideband restarted without persisted inbound
         * tickets). The ticket-match branch finds no match, so validation
         * falls through to PoW on a 16-byte stamp at the configured cost.
         *
         * **This test is expected to fail validation** — that's the point.
         * It demonstrates the silent-drop scenario the user is likely hitting
         * in production. The assertion checks the drop happens, so a future
         * change to bridge tickets correctly would surface here.
         */
        @Test
        fun `ticket-stamp DROPS when Python lost the inbound ticket`() {
            println("\n=== ticket-stamp + receiver-lost-ticket (expected drop) ===")
            val message = createTestMessage(content = "Reply with stale ticket")
            val ticket = ByteArray(LXMFConstants.TICKET_LENGTH) { (0x42 xor it).toByte() }
            message.outboundTicket = ticket

            message.pack()
            val stamp = runBlocking { message.getStamp() }
            stamp!!.size shouldBe LXMFConstants.TICKET_LENGTH
            message.repackWithStamp()

            // Python validates WITHOUT the ticket. Falls through to PoW on the
            // 16-byte truncated-hash stamp; passes only by accident at low cost.
            val result = python(
                "lxmf_validate_message_stamp",
                "lxmf_bytes" to message.packed!!.toHex(),
                "target_cost" to 8,  // 1/256 chance of accidental pass; low enough
                                     // that the test is reliable
            )
            println("  [Python without ticket]: $result")

            // Stamp must be present (Kotlin emitted one) but must NOT validate.
            // If this assertion ever flips to stamp_valid=true at cost=8, the
            // bridge ticket stub is now passing the ticket somehow and this
            // scenario stops reproducing the production bug.
            assertSoftly {
                result.getString("unpacked") shouldBe "true"
                result.getString("stamp_present") shouldBe "true"
                result.getString("stamp_valid") shouldBe "false"
            }
            println("  CONFIRMED: 16-byte ticket-stamp fails PoW validation at cost=8 — this is the production drop scenario when Sideband loses its inbound-ticket cache")
        }
    }

    @Nested
    @DisplayName("UnstampedMessageRejection")
    inner class UnstampedMessageRejection {

        /**
         * Pins the rejection contract: when no stampCost is set on the
         * outgoing LXMessage, Kotlin packs a 4-element payload with NO
         * stamp slot. A Sideband-style receiver with `stamp_cost > 0` and
         * `enforce_stamps()` calls `validate_stamp()`, sees
         * `message.stamp == None`, and immediately returns False — the
         * same path as Python `LXMRouter.lxmf_delivery` line 1762-1768
         * that logs "Dropping {message} with invalid stamp" and returns
         * False before the application delivery callback fires.
         *
         * **Historical context (fixed in this PR):** before this change,
         * LXMRouter never registered an announce handler for
         * `lxmf.delivery`, so `outboundStampCosts` was never populated
         * and every outbound message to an enforcing receiver was
         * unstamped — that's the bug this PR closes by mirroring Python
         * `LXMRouter.__init__`'s `LXMFDeliveryAnnounceHandler`
         * registration. The root cause is gone, but the drop scenario
         * this test pins is still real: any message that reaches
         * `handleOutbound` without a `stampCost` (e.g. because the
         * destination never sent an announce before the first send,
         * or the announce arrived but had no stamp_cost in the
         * app_data) will still be rejected by an enforcing receiver.
         * This test locks that contract in place.
         *
         * **This test is expected to demonstrate the drop**, not pass
         * cleanly. If the assertion direction ever flips to
         * `stamp_valid=true`, investigate whether some new code path is
         * silently emitting a stamp on messages that didn't ask for one.
         */
        @Test
        fun `unstamped message DROPS at enforcing receiver`() {
            println("\n=== unstamped message + receiver with enforce_stamps ===")
            val message = createTestMessage(content = "Hello with no stamp")
            // Note: stampCost intentionally NOT set. handleOutbound
            // auto-configures from outboundStampCosts (populated by the
            // lxmf.delivery announce handler), but if no announce has
            // arrived for this destination, the cache miss leaves
            // stampCost null and getStamp() returns null — wire has no
            // stamp slot.
            message.pack()
            val wire = message.packed!!
            println("  [Kotlin] wire (no stamp): ${wire.size} bytes")

            val result = python(
                "lxmf_validate_message_stamp",
                "lxmf_bytes" to wire.toHex(),
                "target_cost" to 4,
            )
            println("  [Python with enforce_stamps]: $result")

            assertSoftly {
                result.getString("unpacked") shouldBe "true"
                result.getString("signature_validated") shouldBe "true"
                // No stamp on the wire because Kotlin didn't generate one.
                result.getString("stamp_present") shouldBe "false"
                // Without a stamp, validate_stamp() returns False immediately.
                result.getString("stamp_valid") shouldBe "false"
            }
            println("  CONFIRMED: enforcing receiver drops unstamped messages.")
            println("  The fix in this PR (lxmf.delivery announce handler) prevents")
            println("  this for normal flows; the test guards against future regressions")
            println("  in code paths that bypass the stamp-cost auto-config.")
        }
    }

    @Nested
    @DisplayName("LargerWireShapes")
    inner class LargerWireShapes {

        /**
         * Exercises the bin16 size class for content (>255 bytes triggers
         * msgpack `0xc5` instead of `0xc4`). msgpack-java picks bin8 for
         * <256, bin16 for <65536; if any encoder anywhere chose differently
         * the round-trip mismatch would fail signature/stamp validation.
         */
        @Test
        fun `large content triggering bin16 encoding with stamp validates`() {
            println("\n=== bin16 content + stamp ===")
            val largeContent = "x".repeat(500)
            val message = createTestMessage(content = largeContent)
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }

        /**
         * Telemetry fields contain a pre-packed bin payload — the inner
         * msgpack structure is opaque to LXMessage's repack. This verifies
         * the outer wrap doesn't inadvertently re-encode the inner bytes.
         */
        @Test
        fun `telemetry-style large bin field with stamp validates`() {
            println("\n=== FIELD_TELEMETRY (pre-packed bin) + stamp ===")
            // Simulate ~120-byte pre-packed telemetry blob (typical location report)
            val prePackedTelemetry = ByteArray(120) { (it * 13).toByte() }
            val message = createTestMessage(
                content = "",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_TELEMETRY to prePackedTelemetry,
                ),
            )
            val result = packStampAndValidate(message, stampCost = 4)
            assertAccepted(result, message.hash!!.toHex(), cost = 4)
        }
    }
}
