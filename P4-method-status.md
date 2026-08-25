# P4 LXMPeer port — per-method status table

Branch `feat/lxmpeer-port` (local commit only, owner hold — no push).
Python reference: `/workspace/lxmf-ref/LXMF/LXMPeer.py` (LXMF 1.1.0) + LXMRouter integration points.

## LXMPeer public surface

| Python member | Kind | Kotlin | Status | Notes |
|---|---|---|---|---|
| OFFER_REQUEST_PATH / MESSAGE_GET_PATH | const | companion consts | PORTED | MESSAGE_GET_PATH defined for parity; unused in Python too |
| IDLE..RESOURCE_TRANSFERRING (6 states) | const | consts | PORTED | exact values |
| ERROR_NO_IDENTITY..ERROR_TIMEOUT (8 codes) | const | consts | PORTED | exact values |
| STRATEGY_LAZY/PERSISTENT/DEFAULT_SYNC_STRATEGY | const | consts | PORTED | |
| MAX_UNREACHABLE / SYNC_BACKOFF_STEP / PATH_REQUEST_GRACE | const | consts | PORTED | |
| from_bytes(peer_bytes, router) | static | fromBytes() | PORTED | tolerant int/float decode (see deviations); handled/unhandled ids filtered against router store exactly as Python |
| to_bytes() | method | toBytes() | PORTED | same field set; round-trip test |
| __init__(router, destination_hash, sync_strategy) | ctor | ctor | PORTED | identity recall at construction, destination null + retry-on-sync when unrecallable — trust semantics identical |
| peering_key_ready() | method | peeringKeyReady() | PORTED | includes mismatch-clears-key path |
| peering_key_value() | method | peeringKeyValue() | PORTED | |
| generate_peering_key() | method | generatePeeringKey() | PORTED | lock-guarded, router-identity required, LXStamper WORKBLOCK_EXPAND_ROUNDS_PEERING=25 |
| sync() | method | sync() | PORTED | full postpone/backoff/purge/low-value/limit/offer pipeline; blocking path grace documented in deviations |
| request_failed(receipt) | callback | requestFailed() | PORTED | |
| offer_response(receipt) | callback | offerResponse() | PORTED | error-code / bool / partial-wanted-id decode via sealed OfferResponse |
| resource_concluded(resource) | callback | resourceConcluded() | PORTED | COMPLETE branch stats+counters and failure branch both mirrored |
| link_established(link) | callback | linkEstablished() | PORTED | rate-unit divergence documented |
| link_closed(link) | callback | linkClosed() | PORTED | |
| queued_items() | method | queuedItems() | PORTED | |
| queue_unhandled_message / queue_handled_message | methods | queueUnhandledMessage/queueHandledMessage | PORTED | |
| process_queues() | method | processQueues() | PORTED | stale-snapshot semantics preserved verbatim (deviation note) |
| handled_messages / unhandled_messages (properties) | property | computed properties | PORTED | derived from PropagationEntry.handledBy/unhandledBy (Python slots [4]/[5]) |
| handled_message_count / unhandled_message_count | property | properties | PORTED | lazy recount via *_counts_synced flags, as Python |
| acceptance_rate | property | acceptanceRate | PORTED | |
| _update_counts() | private | updateCounts() | PORTED | |
| add/remove_handled_message, add/remove_unhandled_message | methods | 4 methods | PORTED | membership-check-first, count-flag invalidation identical |
| name (property) | property | name | PORTED | PN_META_NAME utf-8 decode with fallbacks |
| __str__ | dunder | toString() | PORTED | |

## LXMRouter integration points

| Python method | Kotlin | Status | Notes |
|---|---|---|---|
| peers dict / static_peers | peers map / staticPeers + addStaticPeer() | PORTED | hex-keyed ConcurrentHashMap |
| propagation_entries (slots [0]..[6]) | PropagationEntry data class + propagationEntriesMap | PORTED | named fields replace fixed-index list |
| get_size / get_weight / get_stamp_value | getSize/getWeight/getStampValue | PORTED | weight formula incl. prioritised_list 0.1 factor |
| peer(...) | peer() | PORTED | timebase guard, max-peers admission, cost-ceiling break |
| unpeer(dest, ts=None) | unpeer() | PORTED | stale-timebase rejection |
| rotate_peers() | rotatePeers() | PORTED | headroom math, untested-postpone, fully-synced pool, drop pool, AR<50% cull |
| sync_peers() | syncPeers() | PORTED | culling, waiting/unresponsive buckets, fastest-N pool |
| enqueue_peer_distribution / flush_peer_distribution_queue | enqueuePeerDistribution/flushPeerDistributionQueue (+flushQueuesForPeers) | PORTED | originator exclusion preserved |
| flush_queues (peer half) | flushQueuesForPeers() | PORTED | |
| peer_sync_request | peerSyncRequest() | PORTED | NO_IDENTITY→NO_ACCESS→INVALID_DATA→NOT_FOUND chain exact |
| peer_unpeer_request | peerUnpeerRequest() | PORTED | same chain |
| clean_throttled_peers | cleanThrottledPeers() | PORTED | |
| allow_control/disallow_control | allowControl/disallowControl | PORTED | feeds the validation chains |
| FASTEST_N_RANDOM_POOL=2, ROTATION_HEADROOM_PCT=10, ROTATION_AR_MAX=0.5, PN_STAMP_THROTTLE=180 | companion consts | PORTED | |

## Tests

21 new tests in `LXMPeerPortTest.kt`: peer create/update/cost-ceiling/max-peers/stale-unpeer,
rotation postpone+drop, unreachable culling, static-peer protection, handled/unhandled
bookkeeping, distribution routing, queue processing, sync-state transitions,
throttle cleanup idempotence, control-request validation chains (all four outcomes),
serialisation round-trip, acceptance rate.
Full module suite: **107 tests, 0 failures** on testvm JDK21.

## Deliberately NOT ported (out of scope per card)

- Live multi-node network validation (T5 conformance card).
- Peers storage file persistence (`peers_storage_path` load/save) — router-side job scheduler wiring belongs to the propagation-node service layer; noted for T5/integration follow-up.
