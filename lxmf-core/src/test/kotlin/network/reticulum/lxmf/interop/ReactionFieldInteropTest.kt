package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Tests the canonical LXMF reaction field (FIELD_REACTION 0x40) round-trips
 * Kotlin→Python.
 *
 * The reaction value is a nested dict with integer keys and byte-array values
 * (standardised in LXMF.py commit 764758d):
 * ```
 * fields[0x40] = { 0x00: <target LXMessage.hash bytes>,    # REACTION_TO
 *                  0x01: <reaction content UTF-8 bytes> }  # REACTION_CONTENT
 * ```
 * This is the shape Columba's kotlin-native backend puts on the wire (it packs
 * via [LXMessage.pack]); the assertion that matters is that Python LXMF
 * unpacks the int-keyed nested-bytes dict without error and surfaces field
 * 0x40. Value-level rendering of the nested dict is bridge-dependent, so —
 * like [TelemetryFieldInteropTest]'s map test — this asserts the field
 * survives and is a structured (map/dict) type rather than over-asserting the
 * bridge's nested representation.
 */
class ReactionFieldInteropTest : LXMFInteropTestBase() {

    private fun verifyInPythonWithFields(lxmfBytes: ByteArray): JsonObject =
        python("lxmf_unpack_with_fields", "lxmf_bytes" to lxmfBytes.toHex())

    private fun parseFieldType(pythonResult: JsonObject, fieldKey: Int): String? {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject ?: return null
        val fieldObj = fieldsHex[fieldKey.toString()]?.jsonObject ?: return null
        return fieldObj.getString("type")
    }

    @Test
    fun `FIELD_REACTION canonical 0x40 nested dict round-trips to Python`() {
        println("\n=== Test: FIELD_REACTION (0x40) canonical nested dict ===")

        // 32-byte target message hash + a multi-codepoint emoji as UTF-8 bytes.
        val targetHash = Random.nextBytes(32)
        val emojiBytes = "👍🏽".toByteArray(Charsets.UTF_8)

        val message = createTestMessage(
            content = "", // reaction is an otherwise-empty side-channel message
            fields = mutableMapOf(
                LXMFConstants.FIELD_REACTION to mapOf(
                    LXMFConstants.REACTION_TO to targetHash,
                    LXMFConstants.REACTION_CONTENT to emojiBytes,
                ),
            ),
        )

        val packed = message.pack()
        println("  [Kotlin] Packed ${packed.size} bytes with FIELD_REACTION (0x40)")

        val pythonResult = verifyInPythonWithFields(packed)
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject
        val reactionType = parseFieldType(pythonResult, LXMFConstants.FIELD_REACTION)
        println("  [Python] FIELD_REACTION type: $reactionType")

        assertSoftly {
            // Python LXMF unpacked the message and surfaced field 0x40 ("64").
            fieldsHex shouldNotBe null
            fieldsHex!!.containsKey(LXMFConstants.FIELD_REACTION.toString()) shouldBe true
            // The nested int-keyed bytes dict survives as a structured type
            // (not collapsed to bytes / dropped).
            reactionType shouldNotBe null
            reactionType shouldNotBe "bytes"
        }

        println("  SUCCESS: FIELD_REACTION 0x40 nested dict round-trips (type=$reactionType)")
    }
}
