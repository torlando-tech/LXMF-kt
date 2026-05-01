# LXMF-kt — Documented Deviations from the Python Reference

This file is the **single source of truth** for every place where LXMF-kt's logic intentionally diverges from `markqvist/LXMF`. Any divergence not listed here is a bug, not a deviation.

## Rule

> All logic in LXMF-kt MUST mirror the python reference identically. Deviations are allowed ONLY for one of two reasons, both of which MUST be documented here before the code lands.

**Allowed reason 1 — Language/runtime forced.** The python pattern cannot be expressed faithfully in kotlin or on the JVM. Examples: coroutines vs threads, `@Volatile` vs the GIL, `ReentrantLock` where python relies on GIL-implicit serialization, `kotlinx.coroutines.runBlocking` boundaries at JVM/non-coroutine seams.

**Allowed reason 2 — New feature not present in python.** Kotlin-only API surface added for downstream consumers (Android lifecycle adapters, mobile-specific entry points, etc.). The kotlin-only behavior must not change semantics of any code path that *does* exist in python.

**Allowed reason 3 — Deferred python feature.** A python feature that the port has not yet implemented. The port's downstream consumers MUST NOT rely on the missing behavior, and the gap MUST be documented here so that (a) reviewers don't silently re-implement it incorrectly, (b) cross-impl conformance tests covering the missing path know to skip the kotlin axis (`pytest.mark.skipif(impl == "kotlin", reason=...)`), and (c) the gap shows up in any LXMF-kt feature-completeness audit. Removing a category-3 entry is itself the closing PR — the entry stays here only as long as the gap exists.

## Process

1. Before changing a kotlin port file in a way that diverges from the python reference, read the corresponding python source.
2. If the divergence is unavoidable for one of the two reasons above, add a section below using the template, then implement the change.
3. If you're unsure whether a divergence is justified, ask the human owner before picking unilaterally. Ports drift one small "harmless" choice at a time.
4. Reviewers should reject any PR that introduces a kotlin/python semantics divergence not represented in this file.

## Entry template

```markdown
### <short title> — <kotlin-file-relative-path>:<line-or-symbol>

**Python reference:** `<path>:<line>` (e.g. `LXMF/LXMRouter.py:2554-2580`)

**Category:** language/runtime forced  |  new feature  |  deferred python feature

**Date:** YYYY-MM-DD

**Tracking:** issue/PR link, if any.

**Description:** what the kotlin code does, why it differs from python, and (for category 1) why no kotlin idiom can express the python semantics directly.

**Re-evaluation:** if a future kotlin/JVM/library change would make the python pattern expressible, what to look for.
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
