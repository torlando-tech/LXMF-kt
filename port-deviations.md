# LXMF-kt — Documented Deviations from the Python Reference

This file is the **single source of truth** for every place where LXMF-kt's logic intentionally diverges from `markqvist/LXMF`. Any divergence not listed here is a bug, not a deviation.

## Rule

> All logic in LXMF-kt MUST mirror the python reference identically. Deviations are allowed ONLY for one of three reasons, all of which MUST be documented here before the code lands.

**Allowed reason 1 — Language/runtime forced.** The python pattern cannot be expressed faithfully in kotlin or on the JVM. Examples: coroutines vs threads, `@Volatile` vs the GIL, `ReentrantLock` where python relies on GIL-implicit serialization, `kotlinx.coroutines.runBlocking` boundaries at JVM/non-coroutine seams.

**Allowed reason 2 — New feature not present in python.** Kotlin-only API surface added for downstream consumers (Android lifecycle adapters, mobile-specific entry points, etc.). The kotlin-only behavior must not change semantics of any code path that *does* exist in python.

**Allowed reason 3 — Deferred python feature.** A python feature that the port has not yet implemented. The port's downstream consumers MUST NOT rely on the missing behavior, and the gap MUST be documented here so that (a) reviewers don't silently re-implement it incorrectly, (b) cross-impl conformance tests covering the missing path know to skip the kotlin axis (`pytest.mark.skipif(impl == "kotlin", reason=...)`), and (c) the gap shows up in any LXMF-kt feature-completeness audit. Removing a category-3 entry is itself the closing PR — the entry stays here only as long as the gap exists.

**Allowed reason 4 — Safety-strict default.** The kotlin port enforces a stricter default than python at the library layer for a documented security reason. Used when python delegates a security-sensitive policy decision to consumers (typical pattern: report a flag, let the consumer act), but every known consumer either gets the policy wrong or omits it entirely, AND the kotlin type system can prevent the failure mode at compile time. The deviation MUST cite (a) the python reference site, (b) the failure mode being prevented, and (c) why type-system enforcement is preferable to documentation/convention. Wire-format compatibility MUST be preserved — these deviations only affect kotlin-side API surface and library-internal policy, never the bytes on the wire. Cross-impl conformance tests MUST still pass; if a test fails because of the kotlin stricter behavior, that's a signal the test was probing the unsafe path and should be reframed.

## Process

1. Before changing a kotlin port file in a way that diverges from the python reference, read the corresponding python source.
2. If the divergence is unavoidable for one of the three reasons above, add a section below using the template. For categories 1 and 2, then implement the change. For category 3, open a tracking issue and STOP — do not implement; the gap is documented precisely because it isn't being closed in this PR.
3. If you're unsure whether a divergence is justified, ask the human owner before picking unilaterally. Ports drift one small "harmless" choice at a time.
4. Reviewers should reject any PR that introduces a kotlin/python semantics divergence not represented in this file.

## Entry template

```markdown
### <short title> — <kotlin-file-relative-path>:<line-or-symbol>

**Python reference:** `<path>:<line>` (e.g. `LXMF/LXMRouter.py:2554-2580`)

**Category:** language/runtime forced  |  new feature  |  deferred python feature  |  safety-strict default

**Date:** YYYY-MM-DD

**Tracking:** issue/PR link, if any.

**Description:** what the kotlin code does, why it differs from python, and (for category 1) why no kotlin idiom can express the python semantics directly.

**Re-evaluation:** for category 1 — if a future kotlin/JVM/library change would make the python pattern expressible, what to look for. For category 3 — the trigger condition(s) that should prompt implementation.
```

---

## Deviations

### Propagation node peering stamp generation — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMPeer.kt`

**Python reference:** `LXMF/LXMF/LXMPeer.py:259` — `LXStamper.generate_stamp(key_material, self.peering_cost, expand_rounds=LXStamper.WORKBLOCK_EXPAND_ROUNDS_PEERING)`. Constants at `LXMF/LXMF/LXStamper.py:12` (`WORKBLOCK_EXPAND_ROUNDS_PEERING = 25`) and used throughout `LXStamper.py:59,402` for peering-cost validation.

**Category:** deferred python feature

**Date:** 2026-04-30

**Tracking:** https://github.com/torlando-tech/LXMF-kt/issues/26

**Description:**
Python's `LXMPeer` generates a per-peering stamp (`peering_key`) during the peering handshake using `WORKBLOCK_EXPAND_ROUNDS_PEERING = 25`. The stamp is required by upstream propagation nodes that enforce peering-cost validation.

The kotlin port:
- Defines the cost constants (`LXMFConstants.kt:280` `PEERING_COST = 18`, `MAX_PEERING_COST = 26`)
- Threads `peeringCost` through `LXMRouter` (`LXMRouter.kt:330,1936`)
- **Does not** define `WORKBLOCK_EXPAND_ROUNDS_PEERING` in `LXStamper.kt` (kotlin only has `WORKBLOCK_EXPAND_ROUNDS = 3000` and `WORKBLOCK_EXPAND_ROUNDS_PN = 1000`)
- **Does not** implement peering stamp generation or validation in `LXMPeer.kt`

**Consequence:** A kotlin-hosted propagation node cannot peer with python PNs that require peering-cost validation, and a kotlin client peering with a python PN cannot generate the expected `peering_key`. In current Columba deployments this gap doesn't bite end users — Columba runs as a leaf client using python-hosted PNs for propagation, never as a PN itself. It would block kotlin from ever serving as a PN to python clients.

**Conformance impact:** Any cross-impl test that exercises peering-stamp generation/validation (planned: `tests/test_peering.py` in lxmf-conformance) MUST skip the kotlin axis until this is implemented (`@pytest.mark.skipif(impl == "kotlin", reason="LXMF-kt does not implement peering stamps yet — see port-deviations.md")`).

**Re-evaluation:** Implement when (a) a downstream consumer wants to host a kotlin-side PN that python clients peer with, OR (b) upstream LXMF makes peering-cost validation mandatory at all peering relationships. Recipe:
1. Add `WORKBLOCK_EXPAND_ROUNDS_PEERING = 25` to `LXStamper.kt`
2. Add `generateStamp(material, cost, expandRounds)` overload accepting custom expand rounds (mirror python `LXStamper.py:59`)
3. Wire into `LXMPeer.kt` peering handshake mirroring `LXMPeer.py:259`
4. Remove this entry; the closing PR lands the conformance test as well

### LXMRouter drops messages with SIGNATURE_INVALID at the library layer — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt:1515-1518`

**Python reference:** `LXMF/LXMF/LXMRouter.py:1714-1799` (`lxmf_delivery`). Python decodes the message via `LXMessage.unpack_from_bytes`, sets `message.signature_validated = False` and `message.unverified_reason = LXMessage.SIGNATURE_INVALID` (LXMessage.py:792-796), and **calls `self.__delivery_callback(message)` regardless** (LXMRouter.py:1787-1792). Python treats the signature-validity flag as informational metadata that consumers are expected to act on.

**Category:** safety-strict default

**Date:** 2026-04-30

**Tracking:** signature-forgery technique reproduced against unmodified python LXMF. Procedure: (a) read the recipient's announce off the network — this exposes the recipient identity public key + destination hash; (b) construct an `LXMessage` addressed to the recipient with an attacker-chosen `source_hash` referencing some other known identity; (c) encrypt the packet to the recipient's public key (the recipient's identity public key is by-design public, distributed via announces); (d) sign with the attacker's own private key, not the impersonated source's; (e) deliver via standard RNS transport. The recipient decodes the packet, sets `message.signature_validated = False` and `message.unverified_reason = SIGNATURE_INVALID` (because the `source_hash` matches a known identity but the signature did not validate against that identity's public key), and python LXMF unconditionally calls `__delivery_callback(message)` regardless (LXMRouter.py:1787-1792). A consumer that does not filter on `signature_validated` then renders the forged message as authentic. The kotlin port closes this at the library layer by dropping `SIGNATURE_INVALID` before the callback is invoked.

**Description:**
The kotlin port adds an explicit drop at `processInboundDelivery` for `UnverifiedReason.SIGNATURE_INVALID`:

```kotlin
if (!message.signatureValidated) {
    when (message.unverifiedReason) {
        UnverifiedReason.SOURCE_UNKNOWN -> {
            // Source not known — could still accept depending on policy
            println("Message from unknown source: ${message.sourceHash.toHexString()}")
        }
        UnverifiedReason.SIGNATURE_INVALID -> {
            println("Message signature invalid, rejecting")
            return
        }
        ...
    }
}
```

`SIGNATURE_INVALID` only fires when the source identity IS known to the receiver (so the receiver had a public key to validate against) AND the signature did NOT validate against that key — i.e. the "tampered message claiming to be from a known sender" case. There is no legitimate scenario where this should reach the consumer; the only reason python passes it through is the python LXMF library's design choice to delegate policy to consumers.

**Failure mode being prevented:**
A consumer that doesn't filter `signature_validated == False` (verified empirically: Columba's main-branch app code has zero references to `signatureValidated`) renders forged messages as authentic. The forgery POC demonstrates this against python LXMF; the kotlin library's drop closes the equivalent attack vector at the library layer for any LXMF-kt consumer.

**Why type-system enforcement is preferable:**
The complementary `SOURCE_UNKNOWN` case (legitimate first-contact from a sender whose announce hasn't propagated) cannot be dropped at the library layer — it represents real messages that the consumer might want to deliver-with-warning. Those reach the consumer wrapped in [LXMessageDelivery.Unverified] (separate deviation entry below) so kotlin's exhaustive `when` forces an explicit policy decision. The combination — drop `SIGNATURE_INVALID` at the library, force consumer to handle `SOURCE_UNKNOWN` at the API surface — covers the entire signature-forgery class without losing legitimate first-contact messages.

**Wire compatibility:** unchanged. The kotlin library still decodes the wire bytes, still sets `signatureValidated = False`, still records `unverifiedReason = SIGNATURE_INVALID`. Only the post-decode policy differs.

**Re-evaluation:** if upstream python LXMF adds a `router.enforce_signature` config option (or similar) that drops `SIGNATURE_INVALID` at the library layer, this deviation becomes "matching upstream's default" and can be removed — though the current behavior should remain as the kotlin default regardless of upstream's choice.

### registerDeliveryCallback uses sealed `LXMessageDelivery` instead of raw `LXMessage` — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt:451`

**Python reference:** `LXMF/LXMF/LXMRouter.py:356-357` — `register_delivery_callback(self, callback)`; the callback receives a single positional argument `(LXMessage)` and is expected to read `message.signature_validated` to decide policy.

**Category:** safety-strict default

**Date:** 2026-04-30

**Tracking:** companion to the `SIGNATURE_INVALID` drop entry above. New file `LXMessageDelivery.kt` defines the sealed type.

**Description:**
The kotlin port's `registerDeliveryCallback` takes a `(LXMessageDelivery) -> Unit` instead of a `(LXMessage) -> Unit`. `LXMessageDelivery` is a sealed class with two subtypes:

  - `LXMessageDelivery.Verified(message)` — signature was checked and valid against a known source identity
  - `LXMessageDelivery.Unverified(message, reason)` — signature could not be verified; reason is always `UnverifiedReason.SOURCE_UNKNOWN` because the SIGNATURE_INVALID case is dropped at the library layer (separate deviation above)

Consumers MUST exhaustively handle both subtypes — kotlin's `when` exhaustiveness check on a sealed type is a compile error if either branch is omitted.

**Failure mode being prevented:**
The python single-callback signature lets the consumer ignore `signature_validated` silently (Columba is the empirical example). The kotlin sealed-type signature makes that ignoring impossible at compile time — the consumer is forced to write a code path for the Unverified branch even if that path is "drop and log" or "ingest with warning".

**Why type-system enforcement is preferable:**
Documentation and convention have demonstrably failed (Columba). The kotlin compiler can prevent the failure mode mechanically with no runtime cost and no behavior change for correctly-implemented consumers — the Verified branch handles the same messages a python consumer would see in the all-validated case.

**Wire compatibility:** unchanged. LXMessage decoding and the LXMF wire format are untouched. This is purely a kotlin API surface decision.

**Re-evaluation:** unconditionally keep. If upstream python LXMF ever adopts a similar sum-typed callback (e.g. via a Result wrapper) for the same reason, the categorization could shift to "matching upstream", but the kotlin behavior should remain regardless.
