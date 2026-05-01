package network.reticulum.lxmf

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the [LXMessageDelivery] sealed type and its
 * dispatch surface in the registerDeliveryCallback API.
 *
 * Companion to the port-deviation entry "registerDeliveryCallback uses
 * sealed LXMessageDelivery instead of raw LXMessage" — these tests
 * exist to lock in the contract that the sealed type cannot be
 * silently collapsed by callers and that Verified vs Unverified
 * dispatch correctly.
 */
class LXMessageDeliveryTest {

    private fun makeMessage(): LXMessage {
        val srcIdentity = Identity.create()
        val dstIdentity = Identity.create()
        val srcDest = Destination.create(
            identity = srcIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            aspects = arrayOf("delivery"),
        )
        val dstDest = Destination.create(
            identity = dstIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            aspects = arrayOf("delivery"),
        )
        return LXMessage.create(
            destination = dstDest,
            source = srcDest,
            content = "test",
            title = "t",
        )
    }

    @Test
    fun `Verified exposes the wrapped message via the abstract property`() {
        val msg = makeMessage()
        val delivery: LXMessageDelivery = LXMessageDelivery.Verified(msg)
        assertSame(msg, delivery.message)
    }

    @Test
    fun `Unverified exposes both the message and the reason`() {
        val msg = makeMessage()
        val delivery: LXMessageDelivery = LXMessageDelivery.Unverified(
            message = msg,
            reason = UnverifiedReason.SOURCE_UNKNOWN,
        )
        assertSame(msg, delivery.message)
        assertEquals(UnverifiedReason.SOURCE_UNKNOWN, (delivery as LXMessageDelivery.Unverified).reason)
    }

    @Test
    fun `exhaustive when over Verified and Unverified compiles and dispatches both branches`() {
        // Compile-time proof: this `when` has no `else` branch, which means
        // kotlin's exhaustiveness check confirmed the sealed type has no
        // other subtypes. If a third subtype is ever added without
        // updating callers, the consumer's `when` becomes a compile error
        // — that's the entire point of using a sealed type for this
        // security-sensitive API.
        val branches = mutableListOf<String>()

        fun route(delivery: LXMessageDelivery) {
            when (delivery) {
                is LXMessageDelivery.Verified -> branches.add("verified")
                is LXMessageDelivery.Unverified -> branches.add("unverified:${delivery.reason}")
            }
        }

        route(LXMessageDelivery.Verified(makeMessage()))
        route(LXMessageDelivery.Unverified(makeMessage(), UnverifiedReason.SOURCE_UNKNOWN))
        route(LXMessageDelivery.Unverified(makeMessage(), UnverifiedReason.SIGNATURE_INVALID))

        assertEquals(
            listOf("verified", "unverified:SOURCE_UNKNOWN", "unverified:SIGNATURE_INVALID"),
            branches,
            "All three deliveries must dispatch through their correct sealed-type branches in order",
        )
    }

    @Test
    fun `data class equality covers wrapper equivalence`() {
        // Sanity for callers that put deliveries in collections / use them
        // as map keys. data class equality uses the wrapped message's
        // referential identity (LXMessage doesn't override equals), so two
        // wrappers around the same message instance are equal.
        val msg = makeMessage()
        val a: LXMessageDelivery = LXMessageDelivery.Verified(msg)
        val b: LXMessageDelivery = LXMessageDelivery.Verified(msg)
        assertEquals(a, b)

        val u1: LXMessageDelivery = LXMessageDelivery.Unverified(msg, UnverifiedReason.SOURCE_UNKNOWN)
        val u2: LXMessageDelivery = LXMessageDelivery.Unverified(msg, UnverifiedReason.SOURCE_UNKNOWN)
        assertEquals(u1, u2)

        // Verified and Unverified wrapping the same message are NOT equal
        // — different sealed-type branches must compare distinct.
        assertTrue(a != u1, "Verified and Unverified must be distinct even with the same message")
    }
}
