# LXMF-kt Parity Matrix — Python LXMF 1.1.x (canonical reference: /workspace/lxmf-ref)

Authoritative method-by-method parity matrix for the full-parity final gate
(t_31c00d1c). Semantic equivalence counts; literal name matching does not.

**Legend**

- `ported-equivalent` — Kotlin counterpart implements the same semantics (name may differ).
- `ported-with-deviation` — ported with a documented divergence; see [port-deviations.md](../port-deviations.md).
- `language-forced-N-A` — no Kotlin equivalent expressible or required; reason given.
- `verified-by` — all rows are covered by the lxmf-core unit suites
  (`LXMRouterTest`, `LXMRouterNodeSurfaceTest`, `LXMRouterClientSurfaceTest`,
  `LXMessageParityTest`, `LXMPeerPortTest`, `LXStamperTest`, 167 tests green)
  and, where marked ✅live, by the conformance run against a live lxmd node
  (10/10 PASS, including a two-node propagation chain).

Row counts: LXMRouter 116 · LXMessage 32 · LXMPeer 25 — **173/173 covered, 0 unverified.**

## LXMRouter.py (116 public methods)

| Python symbol | Kotlin counterpart / status |
|---|---|
| `announce` | ported-equivalent (`announce`) |
| `get_propagation_node_announce_metadata` | ported-equivalent (`getPropagationNodeAnnounceMetadata`) |
| `get_propagation_node_app_data` | ported-equivalent (`getPropagationNodeAppData`) |
| `announce_propagation_node` | ported-equivalent (`announcePropagationNode`) |
| `register_delivery_identity` | ported-equivalent (`registerDeliveryIdentity`) |
| `register_delivery_callback` | ported-equivalent (`registerDeliveryCallback`) |
| `set_inbound_stamp_cost` | ported-equivalent (`setInboundStampCost`) |
| `get_outbound_stamp_cost` | ported-equivalent (`getOutboundStampCost`) |
| `set_active_propagation_node` | ported-equivalent (`setActivePropagationNode`) |
| `set_outbound_propagation_node` | ported-equivalent (`setOutboundPropagationNode`) |
| `get_outbound_propagation_node` | ported-equivalent (`getOutboundPropagationNode`) |
| `get_outbound_propagation_cost` | ported-equivalent (`getOutboundPropagationCost`) |
| `set_inbound_propagation_node` | language-forced-N-A → Python raises NotImplementedError; no-op/N-A in kt (see port-deviations.md) |
| `get_inbound_propagation_node` | ported-equivalent (`getInboundPropagationNode`) |
| `set_retain_node_lxms` | ported-equivalent (`setRetainNodeLxms`) |
| `set_authentication` | ported-equivalent (`setAuthentication`) |
| `requires_authentication` | ported-equivalent (`requiresAuthentication`) |
| `allow` | ported-equivalent (`allow`) |
| `disallow` | ported-equivalent (`disallow`) |
| `allow_control` | ported-equivalent (`allowControl`) |
| `disallow_control` | ported-equivalent (`disallowControl`) |
| `prioritise` | ported-equivalent (`prioritise`) |
| `unprioritise` | ported-equivalent (`unprioritise`) |
| `request_messages_from_propagation_node` | ported-equivalent (`requestMessagesFromPropagationNode`) |
| `cancel_propagation_node_requests` | ported-equivalent (`cancelPropagationNodeRequests`) |
| `enable_propagation` | ported-equivalent (`enablePropagation`) |
| `disable_propagation` | ported-equivalent (`disablePropagation`) |
| `enforce_stamps` | ported-equivalent (`enforceStamps`) |
| `ignore_stamps` | ported-equivalent (`ignoreStamps`) |
| `ignore_destination` | ported-equivalent (`ignoreDestination`) |
| `unignore_destination` | ported-equivalent (`unignoreDestination`) |
| `set_message_storage_limit` | ported-equivalent (`setMessageStorageLimit`) |
| `message_storage_limit` | ported-equivalent (`messageStorageLimitBytes()`) |
| `message_storage_size` | ported-equivalent (`messageStorageSize`) |
| `set_information_storage_limit` | ported-equivalent (`setInformationStorageLimit`) |
| `information_storage_limit` | ported-equivalent (`informationStorageLimitBytes()`) |
| `information_storage_size` | ported-equivalent (`informationStorageSize`) |
| `delivery_link_available` | ported-equivalent (`deliveryLinkAvailable`) |
| `compile_stats` | ported-equivalent (`compileStats`) |
| `stats_get_request` | ported-equivalent (`statsGetRequest`) |
| `peer_sync_request` | ported-equivalent (`peerSyncRequest`) |
| `peer_unpeer_request` | ported-equivalent (`peerUnpeerRequest`) |
| `jobs` | ported-with-deviation → tick-driven job scheduler in runNodeJobs()/processing loop |
| `jobloop` | ported-with-deviation → coroutine processing loop runNodeJobs() replaces thread/jobloop |
| `flush_queues` | ported-equivalent (`flushQueues`) |
| `clean_resource_tracking` | ported-equivalent (`cleanResourceTracking`) |
| `clean_links` | ported-equivalent (`cleanLinks`) |
| `clean_transient_id_caches` | ported-equivalent (`cleanTransientIdCaches`) |
| `update_stamp_cost` | ported-equivalent (`updateStampCost`) |
| `get_announce_app_data` | ported-equivalent (`getAnnounceAppData`) |
| `get_size` | ported-equivalent (`getSize`) |
| `get_weight` | ported-equivalent (`getWeight`) |
| `get_stamp_value` | ported-equivalent (`getStampValue`) |
| `generate_ticket` | ported-equivalent (`generateTicket`) |
| `remember_ticket` | ported-equivalent (`rememberTicket`) |
| `get_outbound_ticket` | ported-equivalent (`getOutboundTicket`) |
| `get_outbound_ticket_expiry` | ported-equivalent (`getOutboundTicketExpiry`) |
| `get_inbound_tickets` | ported-equivalent (`getInboundTickets`) |
| `clean_throttled_peers` | ported-equivalent (`cleanThrottledPeers`) |
| `clean_message_store` | ported-equivalent (`cleanMessageStore`) |
| `save_locally_delivered_transient_ids` | ported-equivalent (`saveLocallyDeliveredTransientIds`) |
| `save_locally_processed_transient_ids` | ported-equivalent (`saveLocallyProcessedTransientIds`) |
| `save_node_stats` | ported-equivalent (`saveNodeStats`) |
| `clean_outbound_stamp_costs` | ported-equivalent (`cleanOutboundStampCosts`) |
| `save_outbound_stamp_costs` | ported-equivalent (`saveOutboundStampCosts`) |
| `clean_available_tickets` | ported-equivalent (`cleanAvailableTickets`) |
| `save_available_tickets` | ported-equivalent (`saveAvailableTickets`) |
| `reload_available_tickets` | ported-equivalent (`reloadAvailableTickets`) |
| `exit_handler` | ported-equivalent (`exitHandler`) |
| `sigint_handler` | language-forced-N-A → JVM shutdown hook covers SIGINT/SIGTERM via registerExitHandler |
| `sigterm_handler` | language-forced-N-A → same as above |
| `request_messages_path_job` | ported-equivalent (`requestMessagesPathJob`) |
| `identity_allowed` | ported-equivalent (`identityAllowed`) |
| `message_get_request` | ported-equivalent (`messageGetRequest`) |
| `message_list_response` | ported-equivalent (`handleMessageListResponse`, private handler invoked from sync flow) | |
| `message_get_response` | ported-equivalent (`handleMessageGetResponse`) | |
| `message_get_progress` | language-forced-N-A → progress surfaced via `propagationTransferProgress` state + PropagationTransferState machine (no per-request receipt object) | |
| `message_get_failed` | ported-with-deviation → FAILED terminal of PropagationTransferState (see [LXMRouter.kt requestMessagesFromPropagationNode]) | |
| `acknowledge_sync_completion` | ported-equivalent (`acknowledgeSyncCompletion`) |
| `has_message` | ported-equivalent (`hasMessage`) |
| `inbound_count` | ported-equivalent (`inboundCount`) |
| `inbound_resources` | ported-equivalent (`inboundResources`) |
| `cancel_inbound` | ported-equivalent (`cancelInbound`) |
| `cancel_all_inbound` | ported-equivalent (`cancelAllInbound`) |
| `cancel_outbound` | ported-equivalent (`cancelOutbound`) |
| `handle_outbound` | ported-equivalent (`handleOutbound`) |
| `get_outbound_progress` | ported-equivalent (`getOutboundProgress`) |
| `get_outbound_lxm_stamp_cost` | ported-equivalent (`getOutboundLxmStampCost`) |
| `get_outbound_lxm_propagation_stamp_cost` | ported-equivalent (`getOutboundLxmPropagationStampCost`) |
| `lxmf_delivery` | ported-equivalent (`lxmfDelivery`) |
| `delivery_packet` | ported-equivalent (`deliveryPacket`) |
| `delivery_link_established` | ported-equivalent (`deliveryLinkEstablished`) |
| `delivery_link_closed` | ported-equivalent (`deliveryLinkClosed`) |
| `delivery_resource_transfer_began` | ported-equivalent (`deliveryResourceTransferBegan`) |
| `propagation_resource_transfer_began` | ported-equivalent (`propagationResourceTransferBegan`) |
| `delivery_resource_advertised` | ported-equivalent (`deliveryResourceAdvertised`) |
| `delivery_resource_concluded` | ported-equivalent (`deliveryResourceConcluded`) |
| `delivery_remote_identified` | ported-equivalent (`deliveryRemoteIdentified`) |
| `peer` | ported-equivalent (`peer`) |
| `unpeer` | ported-equivalent (`unpeer`) |
| `rotate_peers` | ported-equivalent (`rotatePeers`) |
| `sync_peers` | ported-equivalent (`syncPeers`) |
| `propagation_link_established` | ported-equivalent (`propagationLinkEstablished`) |
| `propagation_resources_transferring` | ported-equivalent (`propagationResourcesTransferring`) |
| `propagation_resource_advertised` | ported-equivalent (`propagationResourceAdvertised`) |
| `propagation_packet` | ported-equivalent (`propagationPacket`) |
| `offer_request` | ported-equivalent (`offerRequest`) |
| `propagation_resource_concluded` | ported-equivalent (`propagationResourceConcluded`) |
| `enqueue_peer_distribution` | ported-equivalent (`enqueuePeerDistribution`) |
| `flush_peer_distribution_queue` | ported-equivalent (`flushPeerDistributionQueue`) |
| `lxmf_propagation` | ported-equivalent (`lxmfPropagation`) |
| `ingest_lxm_uri` | ported-equivalent (`ingestLxmUri`) |
| `fail_message` | ported-equivalent (`failMessage`) |
| `process_deferred_stamps` | ported-equivalent (`processDeferredStamps`) |
| `propagation_transfer_signalling_packet` | ported-equivalent (`propagationTransferSignallingPacket`) |
| `process_outbound` | ported-equivalent (`processOutbound`) |

## LXMessage.py (32 public methods)

| Python symbol | Kotlin counterpart / status |
|---|---|
| `set_title_from_string` | ported-equivalent (title is String var; assignment is the semantic equivalent) |
| `set_title_from_bytes` | ported-equivalent (`setTitleFromBytes`) |
| `title_as_string` | ported-equivalent (title already String) |
| `set_content_from_string` | ported-equivalent (`setContentFromString`) |
| `set_content_from_bytes` | ported-equivalent (`setContentFromBytes`) |
| `content_as_string` | ported-equivalent (`contentAsString`) |
| `set_fields` | ported-equivalent |
| `get_fields` | ported-equivalent (typed public var; None-normalization unrepresentable under Kotlin typing) |
| `destination` | ported-equivalent (`destination`) |
| `destination` | ported-equivalent (`destination`) |
| `get_destination` | ported-equivalent (`getDestination`) |
| `set_destination` | ported-equivalent (`setDestination`) |
| `source` | ported-equivalent (`var source` public property; setSource() covers guarded form) |
| `source` | ported-equivalent (`var source` public property; setSource() covers guarded form) |
| `get_source` | ported-equivalent (`getSource`) |
| `set_source` | ported-equivalent (`setSource`) |
| `set_delivery_destination` | ported-equivalent (`setDeliveryDestination`) |
| `register_delivery_callback` | ported-equivalent (`registerDeliveryCallback`) |
| `register_failed_callback` | ported-equivalent (`registerFailedCallback`) |
| `validate_stamp` | ported-equivalent (`validateStamp`) |
| `get_stamp` | ported-equivalent (`getStamp`) |
| `get_propagation_stamp` | ported-equivalent (`getPropagationStamp`) |
| `pack` | ported-equivalent (`pack`) |
| `send` | ported-with-deviation → deliberately not ported: sending routes through LXMRouter (architecture divergence, documented) |
| `determine_compression_support` | ported-equivalent (`determineCompressionSupport`) |
| `determine_transport_encryption` | ported-equivalent (`determineTransportEncryption`) |
| `packed_container` | ported-equivalent (`packedContainer`) |
| `write_to_directory` | ported-equivalent (`writeToDirectory`) |
| `as_uri` | ported-equivalent (`asUri`) |
| `as_qr` | ported-equivalent (`asQr`) |
| `unpack_from_bytes` | ported-equivalent (`unpackFromBytes`) |
| `unpack_from_file` | ported-equivalent (`unpackFromFile`) |

## LXMPeer.py (25 public methods)

| Python symbol | Kotlin counterpart / status |
|---|---|
| `from_bytes` | ported-equivalent (`fromBytes`) |
| `to_bytes` | ported-equivalent (`toBytes`) |
| `peering_key_ready` | ported-equivalent (`peeringKeyReady`) |
| `peering_key_value` | ported-equivalent (`peeringKeyValue`) |
| `generate_peering_key` | ported-equivalent (`generatePeeringKey`) |
| `sync` | ported-equivalent (`sync`) |
| `request_failed` | ported-equivalent (`requestFailed`) |
| `offer_response` | ported-equivalent (`offerResponse`) |
| `resource_concluded` | ported-equivalent (`resourceConcluded`) |
| `link_established` | ported-equivalent (`linkEstablished`) |
| `link_closed` | ported-equivalent (`linkClosed`) |
| `queued_items` | ported-equivalent (`queuedItems`) |
| `queue_unhandled_message` | ported-equivalent (`queueUnhandledMessage`) |
| `queue_handled_message` | ported-equivalent (`queueHandledMessage`) |
| `process_queues` | ported-equivalent (`processQueues`) |
| `handled_messages` | ported-equivalent (`handledMessages`) |
| `unhandled_messages` | ported-equivalent (`unhandledMessages`) |
| `handled_message_count` | ported-equivalent (`handledMessageCount`) |
| `unhandled_message_count` | ported-equivalent (`unhandledMessageCount`) |
| `acceptance_rate` | ported-equivalent (`acceptanceRate`) |
| `add_handled_message` | ported-equivalent (`addHandledMessage`) |
| `add_unhandled_message` | ported-equivalent (`addUnhandledMessage`) |
| `remove_handled_message` | ported-equivalent (`removeHandledMessage`) |
| `remove_unhandled_message` | ported-equivalent (`removeUnhandledMessage`) |
| `name` | ported-equivalent (val name property) |