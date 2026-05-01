package network.reticulum.lxmf

/**
 * Result of decoding an inbound LXMessage paired with its signature
 * verification state.
 *
 * Callbacks registered via [LXMRouter.registerDeliveryCallback] receive
 * an [LXMessageDelivery] rather than a raw [LXMessage] so consumers
 * cannot accidentally treat unverified messages as authentic — the
 * kotlin compiler forces an exhaustive `when` over the two subtypes.
 *
 * # Threat model context
 *
 * LXMF wire encryption protects *confidentiality* (only the recipient
 * can read messages addressed to them) but does NOT provide
 * *authenticity* on its own. Authenticity comes from the LXMessage
 * signature, which the sender computes over `dest_hash + source_hash +
 * packed_payload + message_hash` using its identity private key.
 *
 * Anyone who knows a recipient's destination hash can craft a packet
 * encrypted to that recipient (the recipient's public key is in their
 * announce — it's public). If the recipient does not check the
 * signature on receive, the packet is delivered as authentic from
 * whichever source the attacker put in the source_hash slot.
 *
 * The python LXMF library (`LXMRouter.lxmf_delivery`) delivers all
 * decoded messages to the consumer callback regardless of signature
 * status, expecting the consumer to filter on `message.signature_
 * validated`. Sideband enforces that contract by displaying a hard
 * "this message is likely to be fake" banner; consumers that don't
 * filter (Columba pre-this-PR) silently render forged messages as
 * authentic.
 *
 * # What LXMF-kt does
 *
 * Two layers of defense:
 *
 *  1. The router unconditionally drops [UnverifiedReason.SIGNATURE_INVALID]
 *     at `LXMRouter.processInboundDelivery` (the source identity was
 *     known and the signature did not validate against it — the
 *     "tampered" case). These never reach consumers. This is a
 *     port-mirror-reference deviation from python documented in
 *     `port-deviations.md`.
 *
 *  2. The router wraps everything else in [LXMessageDelivery] before
 *     invoking the callback. Verified messages and
 *     [UnverifiedReason.SOURCE_UNKNOWN] messages BOTH reach the
 *     consumer, but in distinct sealed-type branches that the kotlin
 *     compiler will not let the consumer collapse into a single arm.
 *     SOURCE_UNKNOWN cannot be silently dropped at the router layer
 *     because it represents a legitimate first-contact case (the
 *     sender's announce hasn't yet propagated to this receiver).
 *
 * # Wire compatibility
 *
 * This type is purely a kotlin API surface. LXMessage wire encoding
 * and the LXMRouter inbound packet processing are unchanged. Cross-impl
 * interop with python LXMF and iOS LXMF is unaffected.
 */
public sealed class LXMessageDelivery {
    /** The decoded message, with its raw [LXMessage.signatureValidated] flag intact. */
    public abstract val message: LXMessage

    /**
     * Signature was verified against a known source identity.
     *
     * Safe to deliver to user-facing surfaces without an authenticity
     * warning. The consumer's existing message-handling code path is
     * appropriate.
     */
    public data class Verified(
        override val message: LXMessage,
    ) : LXMessageDelivery()

    /**
     * Signature could not be verified.
     *
     * Reaches the consumer only when [reason] is
     * [UnverifiedReason.SOURCE_UNKNOWN] — the source identity has not
     * been observed on this peer's RNS yet, so there is no public key
     * available to validate the signature against. Common in legitimate
     * first-contact scenarios when the sender's announce has not yet
     * propagated.
     *
     * The [UnverifiedReason.SIGNATURE_INVALID] case (source identity
     * IS known and signature does NOT validate — the "tampered" case)
     * is dropped at the router layer and never reaches this branch.
     *
     * Consumer policy choices (in order of decreasing strictness):
     *  - Drop entirely. Most strict, but loses legitimate first-contact
     *    messages from peers whose announces haven't propagated.
     *  - Quarantine to a separate inbox section the user must opt into
     *    viewing. Stronger UI friction.
     *  - Ingest with a hard visual warning on every bubble (mirrors
     *    Sideband's pattern). User has full information; can choose
     *    to trust or not. Recommended default for chat-style apps.
     *
     * Whichever the consumer picks, it MUST do something explicit —
     * silently treating Unverified as Verified is the bug class this
     * sealed type prevents.
     */
    public data class Unverified(
        override val message: LXMessage,
        public val reason: UnverifiedReason,
    ) : LXMessageDelivery()
}
