# LXMF-kt — Documented Deviations from the Python Reference

This file is the **single source of truth** for every place where LXMF-kt's logic intentionally diverges from `markqvist/LXMF`. Any divergence not listed here is a bug, not a deviation.

## Rule

> All logic in LXMF-kt MUST mirror the python reference identically. Deviations are allowed ONLY for one of two reasons, both of which MUST be documented here before the code lands.

**Allowed reason 1 — Language/runtime forced.** The python pattern cannot be expressed faithfully in kotlin or on the JVM. Examples: coroutines vs threads, `@Volatile` vs the GIL, `ReentrantLock` where python relies on GIL-implicit serialization, `kotlinx.coroutines.runBlocking` boundaries at JVM/non-coroutine seams.

**Allowed reason 2 — New feature not present in python.** Kotlin-only API surface added for downstream consumers (Android lifecycle adapters, mobile-specific entry points, etc.). The kotlin-only behavior must not change semantics of any code path that *does* exist in python.

## Process

1. Before changing a kotlin port file in a way that diverges from the python reference, read the corresponding python source.
2. If the divergence is unavoidable for one of the two reasons above, add a section below using the template, then implement the change.
3. If you're unsure whether a divergence is justified, ask the human owner before picking unilaterally. Ports drift one small "harmless" choice at a time.
4. Reviewers should reject any PR that introduces a kotlin/python semantics divergence not represented in this file.

## Entry template

```markdown
### <short title> — <kotlin-file-relative-path>:<line-or-symbol>

**Python reference:** `<path>:<line>` (e.g. `LXMF/LXMRouter.py:2554-2580`)

**Category:** language/runtime forced  |  new feature

**Date:** YYYY-MM-DD

**Tracking:** issue/PR link, if any.

**Description:** what the kotlin code does, why it differs from python, and (for category 1) why no kotlin idiom can express the python semantics directly.

**Re-evaluation:** if a future kotlin/JVM/library change would make the python pattern expressible, what to look for.
```

---

## Deviations

### `@Volatile` on `LXMessage.progress` — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt:142`

**Python reference:** `LXMF/LXMF/LXMessage.py:156` (`self.progress = 0.0`), with cross-thread writers at `LXMessage.py:474, 488, 496, 506, 512, 559, 571, 583, 618` (the `__update_transfer_progress` callback path) and reads from any caller polling for UI progress display.

**Category:** language/runtime forced

**Date:** 2026-05-12

**Tracking:** torlando-tech/LXMF-kt#34 (greptile review of `cmdLxmfGetMessageProgress`)

**Description:** Python's GIL serialises attribute reads/writes — `self.progress = X` from a Resource progress callback thread is implicitly visible to a main-thread poller without any explicit synchronisation. On the JVM, `var progress: Double = 0.0` has neither visibility nor atomicity guarantees: JLS §17.7 explicitly permits non-volatile `double` (and `long`) reads to **tear** (be observed as 32-bit halves of two different writes), and there is no happens-before edge between a write on one thread and a read on another without a synchronisation action. HotSpot makes 64-bit reads atomic in practice on modern hardware, but ART (Android Runtime) does not guarantee this, and visibility (vs atomicity) is implementation-defined either way. `@Volatile` is the direct JVM idiom for "what Python's GIL gives you for free": each read sees the latest committed write, and 64-bit access is guaranteed atomic.

Writers in this port: `LXMRouter.processOpportunisticDelivery` (LXMRouter.kt:739, 755) — `processingScope` coroutine; `LXMRouter.sendViaPropagation` Resource progressCallback (LXMRouter.kt:1258) — Resource background thread; `LXMRouter.sendViaLink` Resource progressCallback + completion callback (LXMRouter.kt:1335, 1340) — Resource background thread.

Readers: any consumer polling progress for UI display, plus `:conformance-bridge`'s `cmdLxmfGetMessageProgress` (Main.kt:740), which is what surfaced this issue in code review.

**Re-evaluation:** Remove `@Volatile` only if `LXMessage` ever migrates to an immutable / coroutine-`StateFlow`-backed progress representation, or if Kotlin gains a portable concurrency annotation that subsumes JVM-`@Volatile` semantics across all targets (Native, JS) the lib might one day support.

### DIRECT-link CLOSED-branch path re-request relocated to the link `closedCallback` — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt::establishLinkForMessage` (closedCallback) and `::processDirectDelivery` (CLOSED branch)

**Python reference:** `LXMF/LXMF/LXMRouter.py:2610-2629` — inside `process_outbound`'s per-message loop, when a message's DIRECT delivery link is `CLOSED`, Python re-requests the path (`RNS.Transport.request_path`), distinguishing "was active, closed unexpectedly" (`direct_link.activated_at != None`) from "never activated" (re-request once, guarded by the dynamic `path_request_retried` attribute), then pops both `direct_links` and `backchannel_links` and reschedules.

**Category:** language/runtime forced

**Date:** 2026-06-10

**Tracking:** columba#1004 (D2). See also `columba` memory `issue-1004-path-requests-direct-delivery`.

**Description:** Python's `direct_links` retains a CLOSED link until the next `process_outbound` tick observes it and runs the per-message CLOSED branch. The kotlin port is event-driven: `establishLinkForMessage` creates the `RNS.Link` with a `closedCallback` that fires the instant the link closes (Link watchdog establishment-timeout or unexpected teardown) and **eagerly removes** the link from `directLinks`, then calls `triggerProcessing()`. Consequently a CLOSED link is essentially never observed by `processDirectDelivery` — the next tick lands in the no-link branch — so porting Python's re-request into that branch would be dead code.

The re-request is therefore relocated to the `closedCallback`, where kotlin actually handles link close. It replicates Python's logic faithfully: `closedLink.activatedAt > 0` ⇒ re-request (was active); else re-request once gated by `LXMessage.pathRequestRetried` (never activated). It is additionally gated on the initiating message still needing delivery (`state == OUTBOUND || SENDING`) to reproduce the fact that Python's CLOSED branch only runs for a message still in the outbound loop — without this, a normal post-delivery close would emit a spurious path request that Python never makes. `processDirectDelivery`'s CLOSED branch is retained as a no-op-ish safety net (clear both link maps + reschedule) for the close-callback race window, but performs no re-request to avoid double-firing.

This matters specifically for transport-enabled nodes: reticulum-kt's `Transport.deregisterLink` stale-path recovery (expire + re-request on pending-link timeout) is intentionally gated to non-transport nodes (Python `Transport.py:504` parity), so for transport-mode users the LXMF close-time re-request is the only mechanism that refreshes a stale path after a failed DIRECT link.

**Re-evaluation:** If the kotlin `LXMRouter` ever stops eagerly removing the link in `closedCallback` and instead lets `processDirectDelivery` observe and pop CLOSED links (matching Python's `direct_links` lifecycle), move the re-request back into the CLOSED branch and delete this deviation. The per-message `pathRequestRetried` semantics would then align 1:1 with Python without the close-event approximation.

### LXMPeer port — thread-model divergence: blocking path-request grace + off-thread peering-key generation

**Python reference:** `LXMF/LXMF/LXMPeer.py` `sync()` lines 295-298 (`time.sleep(PATH_REQUEST_GRACE)`), line 284-286 (`threading.Thread(target=job, daemon=True).start()` for key generation).

**Category:** language/runtime forced (deliberate, documented)

**Date:** 2026-08-23

**Description:** Python's sync runs inside a dedicated router job thread where a 7.5 s `time.sleep()` after `Transport.request_path` is acceptable. The Kotlin port keeps the same blocking grace sleep inside `LXMPeer.sync()` because callers (`syncPeers`, link-established callback) already run on background threads/coroutine dispatchers; a non-blocking rewrite would change call-flow semantics. Peering-key generation is dispatched to a daemon thread exactly as Python does. Callers embedding this in coroutine contexts should wrap `sync()` in `Dispatchers.IO`.

**Re-evaluation:** Convert to suspending path-request await if LXMRouter's job scheduler ever moves peer sync onto the shared processingScope.

### LXMPeer port — `link_establishment_rate` unit divergence

**Python reference:** `LXMPeer.py` `link_established` line 536 (`link.get_establishment_rate()` returns bytes/ms-derived value).

**Category:** upstream API difference (rns-kt)

**Date:** 2026-08-23

**Description:** rns-kt `Link.getEstablishmentRate()` converts its internal bytes/ms figure to bits/second before returning (`Link.kt:3442`). The stored `linkEstablishmentRate` field therefore carries bits/s rather than Python's raw rate. This affects only the fastest-peer sort ordering scale (relative order is preserved) and log output.

**Re-evaluation:** If cross-language parity of the stored number matters (e.g. shared stats files), divide by 8 at the call site and document the storage unit.

### LXMPeer port — `process_queues` stale-snapshot duplicate suppression preserved verbatim

**Python reference:** `LXMPeer.py` `process_queues` lines 557-572.

**Category:** semantic parity decision (no deviation)

**Date:** 2026-08-23

**Description:** Python snapshots `handled_messages`/`unhandled_messages` BEFORE draining the queues, so an id queued simultaneously as handled and unhandled passes the unhandled queue's suppression check against the stale snapshot and lands in BOTH live sets. The Kotlin port reproduces this exact behavior (verified by test) rather than "fixing" it — flagged here so future readers know it is intentional parity, not a bug.

**Re-evaluation:** Only revisit if upstream changes process_queues semantics.

### LXMPeer port — msgpack number decoding tolerant of int/float encodings

**Python reference:** `from_bytes` reads dict values with dynamic typing.

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Kotlin msgpack values are typed at decode time; Python floats round-trip as msgpack floats while counters encode as ints. `fromBytes` decodes every numeric field through an int-or-float-tolerant helper so peers serialised by either implementation load correctly. Wire format itself is unchanged from Python (`to_bytes` field names/ordering identical).

**Re-evaluation:** None needed.
