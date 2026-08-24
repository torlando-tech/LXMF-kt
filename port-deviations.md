# Port Deviations — LXMRouter client surface (feat/parity-router-client)

Semantic-parity deviations vs `/workspace/lxmf-ref/LXMF/LXMRouter.py` (Python LXMF 1.1.1).
Locked decision: parity is semantic; Kotlin idioms count as satisfied.

1. `exit_handler` → `registerExitHandler()`
   Python wires SIGINT/SIGTERM handlers to `exit_handler()`. On the JVM the idiom is a
   shutdown hook (`Runtime.getRuntime().addShutdownHook`). The hook calls `stop()` —
   teardown of delivery-destination callbacks is not needed because Kotlin destinations
   hold no global mutable callback registry to detach (callbacks live on the destination
   object which dies with the router).

2. `get_outbound_lxm_propagation_stamp_cost` → derived value
   Python reads per-message `propagation_target_cost`. Kotlin `LXMessage` does not carry
   that field (P1 sibling scope); the method returns `getOutboundPropagationCost()` for
   any pending message and null otherwise, which is the same effective target cost.

3. `set_inbound_propagation_node` → `NotImplementedError` mirrored
   Python 1.1.1 raises NotImplementedError here; the Kotlin port throws the same
   (`fun setInboundPropagationNode(...): Nothing`). Documented per card instruction.

4. `get_propagation_node_app_data` / `compile_stats` → return null
   Both are only meaningful when the router runs as a propagation node
   (`enable_propagation`, P3 scope). Client-only router mirrors Python's
   `compile_stats()` early-return-null; PN app-data emission is deferred to P3.

5. `get_size` / `get_weight` / `inbound_resources`(server variants) — NOT PORTED
   These operate on `propagation_entries` / message-store state that exists only in the
   propagation-node role (P3). Out of scope per card boundary.
   `get_stamp_value` likewise depends on propagation_entries — deferred to P3.

6. `cancel_inbound` resource tracking via link callbacks
   Python registers `incoming_delivery_resources` inside `delivery_link_established`.
   Kotlin registers/unregisters from `setResourceStartedCallback` /
   `setResourceConcludedCallback` at both link-setup sites. Same semantics; removal
   replaces Python's status-filtering since concluded resources leave the map.

7. `reload_available_tickets` delegates to the boot-time loader
   Same file format, same recreate-on-missing-section semantics as Python's inline
   re-validation; avoids duplicating the msgpack parser.

8. `cancel_outbound` does not call `LXStamper.cancel_work(message_id)`
   The Kotlin LXStamper has no work-registry (stamps generate in a bounded coroutine);
   cancelling removes the message from `pendingDeferredStamps` so its stamp result is
   discarded and delivery never proceeds.

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

### Node-side propagation packet callback receives the owning Link explicitly — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt::propagationPacket`

**Python reference:** `LXMF/LXMRouter.py:2664-2700` (`propagation_packet(self, data, packet)` reads `packet.link` to decide prove vs teardown).

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

### `LXMessage.destination`/`source` set-once via named setters, not property assignment — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::setDestination` / `::setSource`

**Python reference:** `LXMF/LXMF/LXMessage.py:224-262` (`destination`/`source` properties + `set_destination()`/`set_source()`)

**Category:** language/runtime forced

**Date:** 2026-08-23

**Tracking:** P3 parity card t_a3c5bdbc

**Description:** reticulum-kt keeps `Packet.link` internal to rns-core (`getLink$rns_core()` is module-private), so a caller outside rns-core cannot read the owning link off an inbound packet as Python does. The kotlin `propagationPacket(data, packet, link)` therefore takes the link as an extra parameter supplied by `propagationLinkEstablished`'s `setPacketCallback`, and uses it for the invalid-stamp `teardown()`. Semantics are unchanged: Python's null-link early return maps to a null `link` parameter.

**Re-evaluation:** if reticulum-kt ever exposes `Packet.link` publicly (or provides a safe accessor), drop the parameter and read the link off the packet again.

### validate_pn_stamps_job_multip process-pool replaced by sequential validation — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXStamper.kt::validatePnStamps`

**Python reference:** `LXMF/LXStamper.py:validate_pn_stamps_job_multip` (multiprocessing.Pool fan-out over `validate_pn_stamp`).
**Description:** Python exposes `destination`/`source` as mutable properties whose setter delegates to the guarded `set_destination()`, so `lxm.destination = d` and `lxm.set_destination(d)` are equivalent. Kotlin constructor-injected `val`s back these fields; making them publicly assignable `var`s would bypass python's set-once guard (ValueError on reassign, ValueError on non-Destination). The port keeps them read-only (`private set`) and adds `setDestination()`/`setSource()` which replicate the guard exactly: fill-once when null, `IllegalArgumentException` on reassignment.

**Re-evaluation:** A Kotlin custom setter with backing-field sentinel could allow `msg.destination = d` syntax while keeping the guard; revisit if API ergonomics demand property-style assignment.

### `get_propagation_stamp` derives `transient_id` inside LXMessage — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt::getPropagationStamp`

**Python reference:** `LXMF/LXMF/LXMessage.py:329-353` (`get_propagation_stamp`) with transient synthesis at `LXMessage.py:429-435` (inside `pack()`'s PROPAGATED branch)
**Tracking:** P3 parity card t_a3c5bdbc

**Description:** Python validates incoming propagation batches in a multiprocessing pool for CPU parallelism. On the JVM the same structured-concurrency substitution used elsewhere in this port applies: `validatePnStamps` validates sequentially on the caller's dispatcher via `mapNotNull`. Per-entry results are identical (`null` drops); only wall-clock throughput of large batch validations differs.

**Re-evaluation:** if propagation-node batch throughput ever matters, swap in a coroutine `parallelMap` over Dispatchers.Default — no API change needed.

### information_storage_size remains a None placeholder — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt::informationStorageSize`

**Python reference:** `LXMF/LXMRouter.py:information_storage_size` (returns None; unimplemented upstream placeholder).

**Category:** new feature / parity with upstream placeholder

**Date:** 2026-08-23

**Tracking:** P3 parity card t_a3c5bdbc

**Description:** Python returns None because the telemetry/information store is not implemented upstream. Kotlin mirrors this exactly with a nullable `Long?` returning null, so downstream callers see identical semantics. When upstream implements it, port the real implementation rather than inventing one here.

**Re-evaluation:** revisit when python LXMF lands an information store implementation.

---

### P2 client-surface addendum

9. `update_stamp_cost` remains private with hex-string key
   Already existed pre-P2 (semantic parity incl. async save); no public wrapper added
   because Python callers reach it only through announce handling.

---

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
**Description:** Kotlin msgpack values are typed at decode time; Python floats round-trip as msgpack floats while counters encode as ints. `fromBytes` decodes every numeric field through an int-or-float-tolerant helper so peers serialised by either implementation load correctly. Wire format itself is unchanged from Python (`to_bytes` field names/ordering identical).

**Re-evaluation:** None needed.

### Transfer bookkeeping tracks actually-sent entries only (hardened beyond reference) — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMPeer.kt::offerResponse/resourceConcluded`

**Python reference:** `LXMF/LXMPeer.py:458-468` (`offer_response` payload build) vs `LXMF/LXMPeer.py:500-502` (`resource_concluded`)

**Category:** deliberate hardening (correctness fix, diverges from reference)

**Date:** 2026-08-24

**Description:** Python builds the transfer payload by skipping entries
whose backing files are unreadable, but keeps the FULL wanted-ID list in
`currently_transferring_messages`; completion then marks every ID handled
— including messages whose bytes never left the node. A missing/corrupt
store file therefore permanently suppresses that message for the peer.
The Kotlin port now tracks `transferredIds` (entries whose bytes actually
entered the Resource) and keys all completion bookkeeping off it; a sync
where nothing could be read completes without marking anything handled.

**Re-evaluation:** Found by Greptile PR#38 re-review; verified by unit
suite + difffuzz. If upstream python fixes the same divergence, this
becomes parity.

### Persistent-strategy re-offers entries with permanently-unreadable files — bounded via dead-lettering (hardened beyond reference)

**Python reference:** `LXMF/LXMPeer.py:523-524` (`resource_concluded`
persistent re-sync) + missing-file skip at 458-468

**Category:** deliberate hardening (bounded-retry, diverges from reference)

**Date:** 2026-08-24

**Description:** With the bookkeeping fix above, an entry whose backing
file is permanently unreadable stays unhandled forever, and the persistent
sync strategy immediately starts another sync round whenever unhandled
messages remain (`if (unhandledMessageCount > 0) sync()`). A corrupted
store therefore produces an endless slow retry loop (bounded by link RTT,
no retry cap) in BOTH implementations. Python has the identical property;
Greptile r5 flagged the Kotlin side because the bookkeeping fix makes the
loop observable instead of self-concealing.

The Kotlin port now counts consecutive rounds in which an entry was wanted
but omitted from the payload (`unsendableRoundCount`). After
`MAX_UNSENDABLE_ROUNDS = 5` consecutive omissions, the entry is
dead-lettered: marked handled with a loud operator-facing log identifying
the unreadable transient IDs, so the loop terminates and store repair stays
actionable.

**Re-evaluation:** Deliberate divergence from the reference (python loops
forever). If upstream adds a retry cap or dead-letter table, this becomes
parity. Reported to markqvist alongside the other peer-sync findings.

### Offer-response selection is bounded by the current offer (hardened beyond reference) — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMPeer.kt::offerResponse`

**Python reference:** `LXMF/LXMPeer.py:448-452` (`offer_response`, WantedIds branch)

**Category:** deliberate hardening (security fix, diverges from reference)

**Date:** 2026-08-24

**Description:** Python iterates the peer's reply IDs straight against the
global `propagation_entries` store: duplicate IDs re-read and re-send the
same message multiple times in one transfer, and an unoffered ID raises
KeyError that aborts the entire sync. The Kotlin port originally mirrored
the unbounded loop (null-safe lookup made it silently *worse*: ghosts were
skipped while duplicates amplified). After PR#38 review flagged it as a P1,
the Kotlin response processor now intersects reply IDs with `lastOffer`
and deduplicates before store lookup — a malicious/buggy peer can no
longer expand a sync transfer beyond its advertised bounds.

**Re-evaluation:** Verified by difffuzz (unoffered/mixed/large scenarios:
python aborts vs kt now bounds to offer; dup amplification eliminated).
If markqvist fixes the upstream flaw, this deviation becomes parity.
Consider reporting upstream separately.
