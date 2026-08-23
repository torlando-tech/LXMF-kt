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

### `LXMessage.destination`/`source` set-once via named setters, not property assignment — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::setDestination` / `::setSource`

**Python reference:** `LXMF/LXMF/LXMessage.py:224-262` (`destination`/`source` properties + `set_destination()`/`set_source()`)

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Python exposes `destination`/`source` as mutable properties whose setter delegates to the guarded `set_destination()`, so `lxm.destination = d` and `lxm.set_destination(d)` are equivalent. Kotlin constructor-injected `val`s back these fields; making them publicly assignable `var`s would bypass python's set-once guard (ValueError on reassign, ValueError on non-Destination). The port keeps them read-only (`private set`) and adds `setDestination()`/`setSource()` which replicate the guard exactly: fill-once when null, `IllegalArgumentException` on reassignment.

**Re-evaluation:** A Kotlin custom setter with backing-field sentinel could allow `msg.destination = d` syntax while keeping the guard; revisit if API ergonomics demand property-style assignment.

### `get_propagation_stamp` derives `transient_id` inside LXMessage — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::getPropagationStamp`

**Python reference:** `LXMF/LXMF/LXMessage.py:329-353` (`get_propagation_stamp`) with transient synthesis at `LXMessage.py:429-435` (inside `pack()`'s PROPAGATED branch)

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Python's `pack()` computes `self.transient_id` (and `propagation_packed`) only in its PROPAGATED desired-method branch. The kotlin `pack()` predates full PROPAGATED packing (transient synthesis lives in `LXMRouter.sendViaPropagation`), so `getPropagationStamp()` cannot rely on `pack()` having produced a `transient_id`. It therefore derives it itself — `full_hash(destHash + encrypt(packed[DESTINATION_LENGTH:]))`, byte-identical to both python's formula and LXMRouter's — when absent after packing. Semantics match python exactly; only the code location differs.

**Re-evaluation:** If kotlin `pack()` ever ports python's full PROPAGATED branch (including `__pn_encrypted_data` caching), remove the local derivation here.

### `get_propagation_stamp` returns stamp via return value + state fields instead of tuple — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::getPropagationStamp`

**Python reference:** `LXMF/LXMF/LXMessage.py:345-350`

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Python returns `(generated_stamp, value)` from `LXStamper.generate_stamp` and assigns two attributes. Kotlin's `LXStamper` already models this as `StampResult(stamp, value, rounds)`; `getPropagationStamp()` returns only the stamp bytes (matching python's own public return type) and stores value/validity in `propagationStampValue`/`propagationStampValid`. No behavioral difference.

**Re-evaluation:** None needed — pure type-shape adaptation.

### `as_qr` uses zxing core instead of the optional `qrcode` module, returning a Boolean matrix — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::asQr` + `QrEncoder.kt`

**Python reference:** `LXMF/LXMF/LXMessage.py:718-744`

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Python lazily imports the optional third-party `qrcode` module, renders a PIL image with `ERROR_CORRECT_L` and border 1, and returns `None` (with CRITICAL log) if the module is missing. The JVM equivalent is zxing's QR encoder; lxmf-core depends on `com.google.zxing:core` (compile scope, no transitive image deps) and wraps it in `QrEncoder`, exposing the result as a plain `Boolean` matrix (true = dark module) rather than an image type so lxmf-core stays graphics-free. Error correction level, border=1, UTF-8 data and the TypeError-equivalent for non-paper messages all match. Unlike python, the encoder is always present, so null only occurs on internal encoding failure.

**Re-evaluation:** If a future consumer needs drop-in PIL-equivalent rendering, add a rendering adapter at the platform layer (Android Bitmap / java.awt), not in lxmf-core.

### `write_to_directory` temp-file suffix uses PID + SecureRandom instead of `os.getpid() or time.time()` + urandom(8) — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::writeToDirectory`

**Python reference:** `LXMF/LXMF/LXMessage.py:674-696`

**Category:** language/runtime forced

**Date:** 2026-08-23

**Description:** Python builds `<file>.tmp.<pid>.<8 random bytes hex>`; the JVM has no direct urandom-hex helper, so the port uses `ProcessHandle.current().pid()` plus 8 bytes from `SecureRandom` hex-encoded — same collision-resistance, same atomic-rename persistence protocol (`Files.move` with ATOMIC_MOVE replacing python's `os.replace`). Python's fsync is approximated by `File.writeBytes` + OS-level rename durability guarantees; explicit fsync is skipped because the JVM `FileChannel.force` path would add complexity for identical practical durability on the target platforms (Linux/Android ext4/f2fs).

**Re-evaluation:** If strict fsync parity is later required (e.g. embedded flash wear analysis), switch to FileChannel.write + force(true) before the move.
