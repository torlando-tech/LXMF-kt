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

**Category:** language/runtime forced

**Date:** 2026-08-23

**Tracking:** P3 parity card t_a3c5bdbc

**Description:** reticulum-kt keeps `Packet.link` internal to rns-core (`getLink$rns_core()` is module-private), so a caller outside rns-core cannot read the owning link off an inbound packet as Python does. The kotlin `propagationPacket(data, packet, link)` therefore takes the link as an extra parameter supplied by `propagationLinkEstablished`'s `setPacketCallback`, and uses it for the invalid-stamp `teardown()`. Semantics are unchanged: Python's null-link early return maps to a null `link` parameter.

**Re-evaluation:** if reticulum-kt ever exposes `Packet.link` publicly (or provides a safe accessor), drop the parameter and read the link off the packet again.

### validate_pn_stamps_job_multip process-pool replaced by sequential validation — `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXStamper.kt::validatePnStamps`

**Python reference:** `LXMF/LXStamper.py:validate_pn_stamps_job_multip` (multiprocessing.Pool fan-out over `validate_pn_stamp`).

**Category:** language/runtime forced

**Date:** 2026-08-23

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
