# DTN Development Notes

Purpose: lightweight handoff notes for continuing `feature/dtn-architecture` without rereading the full history.

Base branch: `feature/dtn-architecture`

## Completed Steps

- [x] Step 1 - DTN foundation schema and control codec
  - Added `DtnMeta` foundation already present in models.
  - Added Room DTN tables/DAOs: peer state, advertised, sync cursor, pending pull.
  - Added migration `31 -> 32`.
  - Added `DtnControlPacket` / `DtnControlCodec`.
  - Hardened `TtlForwarder` for DTN TTL/hop eligibility without mutating signed payloads.

- [x] Step 2 - DTN sync core primitives
  - Added `DtnInventoryBuilder`, `DtnDiffResolver`, `DtnForwardPolicy`, `DtnSyncEngine`.
  - Added pure `DtnInventoryStore` abstraction in `transport`.
  - Added unit tests for `hello`, `inv_have`, `inv_want`, `data`, `ack` behavior.

- [x] Step 3 - Room inventory store adapter
  - Added `RoomDtnInventoryStore`.
  - Added `DtnEventEnvelopeFactory` to rebuild signed DTN envelopes from stored `EventCore`.
  - Added unit tests for inventory mapping and local existing-ID checks.

- [x] Step 4 - BLE control routing
  - Added `DtnControlRouter`.
  - Routed reassembled DTN control packets in `BleMeshTransport`.
  - Injected `DtnSyncEngine` from `BleMeshRuntime` using `RoomDtnInventoryStore`.
  - Wired both default and Nordic BLE backends.

- [x] Step 5 - DTN DATA ingest and accepted-ID ACK
  - Added `EnvelopeReceiver.onDtnDataReceived`.
  - `EnvelopeReceiverImpl` now handles DTN single-envelope DATA through verified envelope ingest with source `dtn`, bypassing normal mesh app-batch signature gating.
  - Added `DtnDataReceiver` and wired it into `DtnSyncEngine`.
  - DTN `DATA` now ACKs only items accepted by local ingest.

- [x] Step 6 - Automatic peer encounter handshake
  - Added `DtnSyncEngine.buildHelloPacket`.
  - `BleMeshTransport` now emits DTN `hello` when a stable peer is discovered.
  - HELLO is scoped to the active gid from `BleMeshRuntime`.
  - HELLO is rate-limited per peer with a 5 minute interval.
  - Both default and Nordic BLE backends receive the active-gid provider.

- [x] Step 7 - Persistent peer state/cursor/backoff
  - Added `DtnSyncStateStore` abstraction in transport.
  - Added `RoomDtnSyncStateStore` backed by `dtn_peer_state` and `dtn_sync_cursor`.
  - `DtnSyncEngine` marks peer seen on control packets.
  - `inv_have` generation now resumes from saved per-peer cursor and advances cursor after advertising a window.
  - ACK and no-missing `inv_have` paths mark sync success.

- [x] Step 8 - DTN observability and physical-device validation prep
  - Added `DtnDiagnosticsSink` and `DtnDiagnosticEvent`.
  - `DtnControlRouter` emits receive/send/summary diagnostic events for DTN control packets.
  - `BleMeshTransport` emits hello sent/drop diagnostic events.
  - `BleMeshRuntime` maps DTN diagnostics into `ActivityLog` with `source=dtn`.
  - Fixed `DelegatingReceiver.onDtnDataReceived` so DTN DATA uses the intended ingest path.

- [x] Leave-group chat menu patch
  - Added `Leave group` to `GroupChatActivity` popup menu.
  - Added confirmation UI and local group cleanup helpers in DAOs.
  - Leave group now calls `SecureKeyStore.clear(gid)` so the local actor cannot decrypt future group traffic after leaving.
  - The cleanup removes group row, local events, chat state, map/live trail state, relay cursor, DTN state, and outbound batches for the gid.

- [x] Full DTN Step 1 - Opaque carrier schema foundation
  - Added `opaque_dtn_capsules` entity/DAO for encrypted envelope/app-batch carrier storage.
  - Added `dtn_carrier_manifest` and `dtn_carrier_manifest_batch` entities/DAO for signed manifest offer/accept state.
  - Added migration `32 -> 33` and wired DAOs/entities into `AppDatabase`.
  - Runtime receive/forward behavior is unchanged in this step.

- [x] Full DTN Step 2 - Manifest control packet foundation
  - Added `manifest_offer` and `manifest_accept` DTN control packet types.
  - Added carrier manifest and manifest batch models in the transport layer.
  - Extended `DtnControlCodec` for manifest offer/accept encode/decode.
  - Added `DtnCarrierManifestPolicy` to accept only hash/size-bound batches within session quota.
  - Added optional `DtnCarrierManifestStore` hook and `DtnSyncEngine` response path for valid offers.
  - Runtime BLE send/receive integration is still pending.

- [x] Full DTN Step 3 - Room-backed accepted manifest persistence
  - Added `DtnCarrierManifestVerifier` hook in transport.
  - Added `RoomDtnCarrierManifestStore` to persist accepted manifests and accepted batch rows.
  - Wired the manifest store into `BleMeshRuntime` / `DtnSyncEngine`.
  - Updated manifest DAO graph upsert to replace stale batch rows for the same manifest.
  - Signature verification is still a hook; real cryptographic verification is pending.

- [x] Full DTN Step 4 - Carrier manifest signature verifier foundation
  - Added canonical carrier-manifest signing payload in transport.
  - Added HMAC-SHA256 manifest sign/verify utility and `DtnCarrierManifestKeyResolver`.
  - Added `HmacDtnCarrierManifestVerifier` that binds signature verification to `manifest.senderPeerId`.
  - Changed `DtnSyncEngine` manifest handling to fail closed unless a verifier accepts the offer.
  - Added unit tests for valid signature, tampered payload hash, wrong key, and signed manifest acceptance.
  - Peer verification-key exchange/resolution is still pending before runtime can accept real remote carrier manifests.

- [x] Full DTN Step 5 - Room-backed peer manifest key resolver
  - Added `dtn_peer_manifest_keys` Room table and migration `33 -> 34`.
  - Added `DtnPeerManifestKeyDao` and `DtnPeerManifestKeyEntity`.
  - Added `RoomDtnCarrierManifestKeyResolver` for transport verifier key lookup.
  - Wired `BleMeshRuntime` to use `HmacDtnCarrierManifestVerifier(RoomDtnCarrierManifestKeyResolver(...))`.
  - Runtime manifest offers now remain fail-closed until a peer key is present in the registry.
  - Peer key discovery/distribution is still pending.

- [x] Full DTN Step 6 - Manifest public-key exchange
  - Added `manifest_key` DTN control packet type and codec support.
  - Added local DTN manifest identity generation/storage in `SecureKeyStore`.
  - Added ECDSA P-256/SHA-256 manifest signing and verification helpers.
  - Added `EcdsaDtnCarrierManifestVerifier` and switched runtime manifest verification to public-key verification.
  - Added `RoomDtnPeerManifestKeyStore` to persist peer public keys learned from `manifest_key`.
  - `DtnSyncEngine` now includes the local manifest public key in HELLO, replies with `manifest_key`, and stores valid remote key announcements.
  - This step enables peer key discovery; actual carrier manifest creation/signing and opaque payload ingest are still pending.

- [x] Full DTN Step 7 - Carrier manifest offer sender path
  - Added `DtnCarrierManifestOfferSource`, `DtnCarrierOfferBatch`, and `DtnCarrierManifestSigner` abstractions.
  - Added `RoomDtnCarrierManifestOfferSource` to build manifest candidate batches from queued/retryable outbound app batches.
  - Added outbound-batch DAO query for DTN carrier candidates.
  - `DtnSyncEngine` now creates a signed `manifest_offer` on peer HELLO when carrier batches are available.
  - Runtime wires manifest offer source and ECDSA signer from `SecureKeyStore` manifest identity.
  - Payload transfer and opaque carrier ingest are still pending; this step only negotiates batch IDs/hash/size/TTL/quota.

- [x] Full DTN Step 8 - Compact carrier DATA sender path
  - Added `carrier_data` payload support to DTN `data` control packets.
  - Added `DtnCarrierDataItem` and `DtnCarrierDataSource` abstractions.
  - Added `RoomDtnCarrierDataSource` to load outbound app-batch payloads only for accepted batch IDs.
  - `DtnSyncEngine` now replies to `manifest_accept` with compact carrier DATA scoped to the accepted manifest and batch IDs.
  - Runtime wires carrier DATA loading from `outbound_batch`.
  - Receiver-side validation/storage into `opaque_dtn_capsules` is still pending.

- [x] Full DTN Step 9 - Carrier DATA receiver validation and opaque storage
  - Added `DtnCarrierDataReceiver` transport hook.
  - `DtnSyncEngine` now routes incoming `carrier_data` through the receiver hook and ACKs only accepted batch IDs.
  - Added `RoomDtnCarrierDataReceiver` to validate `manifest_id`, sender peer, gid hash, batch hash, batch size, and expiry.
  - Valid carrier payloads are stored in `opaque_dtn_capsules` without decrypting or rendering UI.
  - Runtime wires carrier DATA receiver to `dtn_carrier_manifest` and `opaque_dtn_capsules`.
  - Opaque capsule inventory/forwarding is still pending.

- [x] Full DTN Step 10 - Opaque capsule offer/data forwarding path
  - `RoomDtnCarrierManifestOfferSource` now includes valid `opaque_dtn_capsules` in carrier manifest offers.
  - `RoomDtnCarrierDataSource` can now load payloads from `opaque_dtn_capsules` as well as local outbound app batches.
  - Runtime wires opaque capsule DAO into both manifest offer and carrier DATA source paths.
  - This lets a carrier that already stored an opaque capsule offer and send it onward without group membership or decrypt/render.
  - Multi-scope discovery is still pending; the runtime still starts DTN encounters from the active group scope only.

- [x] Full DTN Step 11 - Multi-scope DTN hello discovery
  - Added multi-scope DTN gid provider support to default and Nordic BLE transports.
  - DTN HELLO throttling is now scoped by `(peer, gid)` instead of only peer id.
  - `BleMeshRuntime` now starts DTN encounters for the active member gid plus active carrier gids from `opaque_dtn_capsules`.
  - Carrier scopes are capped to avoid unbounded BLE hello fan-out.
  - This enables non-member carriers to continue advertising stored capsules after leaving the group UI state.

- [x] Full DTN Step 12 - Carrier hygiene guardrails
  - Added hop metadata to compact carrier DATA and codec round-trips.
  - Forwarded opaque capsules now increment hop count and stop when reaching `max_hops`.
  - `RoomDtnCarrierDataReceiver` now deletes expired capsules before ingest.
  - Receiver rejects over-hop carrier DATA and rate-limits active capsules per source peer/window.
  - Receiver prunes low-priority/old capsules to keep the opaque carrier store under a fixed byte cap.
  - This is enough for first physical validation; tuning caps/rates can follow real-device logs.

- [x] Carrier-only BLE mesh lifecycle patch
  - Decoupled BLE mesh lifecycle from active group membership for carrier-only operation.
  - `ActiveGroupManager.clearActiveGroup(...)` now keeps/restarts BLE mesh when permissions, location, Bluetooth, and advertiser are available.
  - `BleMeshWorker` no longer cancels safety work just because there is no active group; it can restart carrier mesh without group membership.
  - `MainActivity.onResume` can start mesh in `carrier_only_resume` mode when no active group exists.
  - App startup enqueues BLE mesh safety work unconditionally.
  - This fixes the observed C state where leaving all groups caused Network Status to stay `Searching` / `0 nearby` even with Bluetooth and location enabled.

- [x] Carrier-only manifest negotiation patch
  - `DtnSyncEngine.onManifestKey(...)` now saves the peer manifest key and immediately builds a signed `manifest_offer` for the packet gid/session when local outbound or opaque carrier batches exist.
  - This lets A/B proactively offer encrypted app batches to C after C receives their HELLO and replies with `manifest_key`.
  - C still does not need group membership or a group key; it only verifies manifest signature/quota/hash and stores accepted carrier DATA as opaque capsules.
  - This keeps `gid`/`gid_hash` as the routing and quota scope, but removes group membership as a prerequisite for carrier participation.

- [x] Opaque retry lifecycle patch - 2026-05-15 02:36 +07
  - Added ActivityLog diagnostics for opaque retry lifecycle:
    - `DTN opaque retry queued`
    - `DTN opaque retry skipped`
    - `DTN opaque retry mark_forwarded`
    - `DTN opaque retry send_complete`
    - `DTN opaque retry delete`
    - `DTN opaque retry retry_failed`
- [x] Branch transport hardening follow-ups - 2026-05-19
  - Increased `QUEUED_NO_PROGRESS_TIMEOUT_MS` from `20s` to `30s`.
  - Added `other active lane progress` grace for `queued_no_write_attempt_timeout`.
  - Narrow queue-to-connect fix: `maybeConnectNext()` now considers `held/staged` work, not only `pending`.
  - Re-tested source-side post-success cleanup:
    - delivery remained strong;
    - persistent outbound cleanup improved source DB behavior;
    - but transport-level multi-target attempts still produced some secondary timeout noise.
- [x] DTN debug report Phase 1 + Phase 2 - 2026-05-19
  - `scripts/debug-kit/mesh_debug.sh dtn-md` now emits:
    - `Delivery`
    - `Carrier`
    - `Source-side Noise`
    - `Queue Health`
    - `Transport Stability`
    - `Routing / Identity`
    - `Per-batch Verdict`
  - Spec is documented in:
    - `scripts/debug-kit/DTN_REPORT_SPEC.md`
  - `BleMeshTransport.drainOpaqueRetryStore()` no longer calls `markForwarded(...)` immediately after `broadcastEnvelopeToTargets(...)` returns queued.
  - Opaque capsules are now marked forwarded and deleted only after BLE batch send complete for the matching `batch_id`.
  - `queued_no_write_attempt_timeout` with `chunks=0/...` now leaves the stored opaque capsule retryable instead of putting it into the 5-minute forward cooldown.
  - Added `OpaqueRelayStore.markForwardedByBatchId(...)` / `deleteByBatchId(...)` to support send-complete cleanup by app batch id.
  - Kept `RoomOpaqueRelayStore` forward cooldown at 5 minutes for actual completed forwards, while failed queued attempts can retry early.
- [x] DTN tiered inventory priority baseline - 2026-06-12
  - Added `DtnInventoryTier` with three wire-visible tiers:
    - `high`: recent chat transport events created within the last hour (`CHAT.MSG`, `CHAT.REPLY`, `CHAT.EDIT`, `CHAT.DEL`)
    - `normal`: all non-ACK events outside the high-priority window, including `SYS.MEMBER.REMOVED`
    - `low`: `SYS.ACK`
  - `INV_HAVE` and `INV_WANT` now carry `inventory_tier` so both peers advance through the same tier order.
  - `DtnSyncEngine` now advertises inventory tier-by-tier in the order `high -> normal -> low`.
  - Empty `INV_WANT` for a tier advances the session to the next tier instead of stopping after the first pass.
  - High-priority transfer ordering is newest-first so fresh chat traffic is offered before old backlog.
  - Tier-specific cursor keys now use the existing sync-cursor store without a schema change.
  - Added codec/core unit coverage for tier round-trip and tier advancement behavior.
  - Important remaining hole:
    - the persisted sync cursor is still committed when `INV_HAVE` is advertised, not when the advertised window is fully confirmed delivered
    - an interrupted session can therefore still skip items on the next encounter even though the tiering logic is correct

- [ ] DTN-received chat payloads are not re-broadcast to third peers yet - implementation note for follow-up thread - 2026-06-12
  - Symptom observed in physical testing:
    - `A -> B` direct BLE send for a chat item can fail.
    - `B` can later ingest that same chat item from `A` via DTN repair.
    - after local ingest on `B`, the current runtime does not schedule a new BLE relay from `B -> C`, so a third peer still misses the item unless it later repairs directly.
  - Concrete evidence from the `M1` round:
    - source batch `1mQHDb8gNCu3` failed from `A` to both direct BLE targets:
      - `device_id=3D3FA1C865F3B265` (`B`)
      - `device_id=1C5CFAB31B3CD84F` (`C`)
    - `B` then ingested `M1` via DTN `DATA`, inserted it locally, rendered it, and only sent a DTN ACK back to `A`.
    - there was no follow-up `APP batch emit`, `BLE batch forward queued`, or delayed relay scheduling for the chat payload after DTN ingest.
  - Root cause:
    - the normal BLE inbound path preserves `EnvelopeReceiveResult` and runs relay scheduling in `BleMeshTransport` after `receiver.onEnvelopeReceivedWithMeta(...)`.
    - the DTN inbound path drops that relay metadata too early:
      - `DtnDataReceiver.accept(...)` returns only `Boolean`
      - `DtnSyncEngine.onData(...)` therefore only knows `accepted/not accepted`
      - `BleMeshRuntime` currently wires DTN ingest as `receiver.onDtnDataReceived(...).accepted`
    - because `relayEligible`, `relayOriginDeviceIds`, and related metadata are discarded, the transport layer cannot decide to forward the newly ingested DTN payload to other nearby peers.
  - Correct place to connect DTN ingest to BLE relay scheduling:
    - `BleMeshTransport.handleDtnControlPayload(blob, sourceAddress)`
    - reason:
      - this is the last place that still has both the accepted DTN payload and the BLE last-hop context (`sourceAddress`, remembered peer/device mapping, nearby candidate peers, throttles, delayed relay scheduler).
      - `EnvelopeReceiverImpl` should not own this because it has no BLE topology or last-hop awareness.
      - `DtnSyncEngine` should not own this because it is the DTN protocol/core layer, not the BLE relay scheduler.
  - Recommended implementation shape:
    - extend the DTN data-accept path so it can return relay metadata, not just `Boolean`.
    - likely change:
      - `DtnDataReceiver.accept(gid, item): Boolean`
      - to something like:
        - `EnvelopeReceiveResult`
        - or a small wrapper that includes both `accepted` and the original payload/event id
    - update `DtnSyncEngine.onData(...)` to:
      - keep existing ACK / suppression behavior based on `accepted`
      - also surface accepted-and-relay-eligible items back to the caller
    - update `BleMeshTransport.handleDtnControlPayload(...)` to:
      - receive the accepted DTN item results
      - for each accepted item, call a shared inbound-relay helper using the current `sourceAddress`
  - Important payload-shape note:
    - `DtnDataItem.envelope` is a single signed envelope, not an `AppBatchEnvelope`.
    - therefore DTN-to-third-peer forwarding should probably reuse the single-envelope relay path first, not assume `scheduleBatchForward(batch, ...)` always applies.
    - the most natural initial target is to reuse/extract the existing envelope relay flow around `maybeRelayCompensate(...)`, then optionally generalize batch/envelope scheduling behind one helper.
  - Preferred refactor direction:
    - extract the common "accepted inbound payload may need relay scheduling" block from the normal GATT inbound path in `BleMeshTransport`.
    - reuse that same helper for:
      - direct mesh inbound payloads
      - DTN `DATA` payloads accepted locally
    - this avoids duplicating candidate selection, throttle checks, delayed relay rules, and diagnostics.
  - Do not implement this by:
    - trying to trigger relay directly from `EnvelopeReceiverImpl`
    - adding relay policy inside `DtnSyncEngine`
    - blindly converting DTN single-envelope items into synthetic app batches unless a strong reason appears later
  - When this follow-up starts, re-read and inspect these files first:
    - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
    - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`
    - `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnSyncEngine.kt`
    - `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryModels.kt`
    - `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`

- [ ] DTN `inv_want` can stall after peer address rotation even though it reaches `staged_to_pending` - implementation note for follow-up thread - 2026-06-12
  - Symptom observed in physical testing:
    - peer `C` receives `inv_have` from `B`
    - `C` correctly computes a non-empty `inv_want`
    - log shows `Queued BLE targets ...` and `PIPELINE staged_to_pending ... pending_after > 0`
    - but there is no matching `DTN control wire start type=inv_want` and peer `B` never logs `DTN inv_want recv`
    - therefore the session never progresses to `want load` / `data sent`
  - Concrete round evidence:
    - `C` receives `B`'s `inv_have` and sends `inv_want ids=3`
    - immediately after, `C` logs `PIPELINE staged to pending ... pending_after=36`
    - `B` never logs any of:
      - `DTN inv_want recv`
      - `DTN want recv`
      - `DTN want load`
      - `DTN data sent`
  - Root cause at transport/arbitration level:
    - `PIPELINE staged_to_pending` only moves packets from `stagedEnqueueByAddress` to `pendingByDevice`.
    - actual `wire start` only happens when there is a connected client write lane and `writeNextChunk(...)` polls from `pendingByDevice`.
    - in the observed round, `C` was receiving inbound chunks from a newly rotated `B` address, but `C` did not yet have a corresponding outbound client connection on that new address.
    - the older client lane had already been torn down with `device_id_rotated`.
    - result:
      - server-side inbound receipt from `B` was alive
      - outbound client-side reply path from `C -> B` was not yet re-established
      - the `inv_want` packet stayed queued but never reached `wire start`
  - Important architectural nuance:
    - current DTN control replies assume a normal outbound client write path.
    - receiving DTN control on the local GATT server does not automatically guarantee an immediate reverse-direction write lane.
    - address rotation makes this more visible because inbound traffic can continue on the peer's new address while the local outbound connection state still points at a stale/closed address.
  - Relevant code path:
    - enqueue path stages DTN control into `stagedEnqueueByAddress`, then `PipelineLabel.ENQUEUE` moves it to `pendingByDevice`
    - `PipelineLabel.ENQUEUE` only calls `triggerWritePump(address)` when `connectState(address) == CONNECTED`
    - otherwise it posts `PipelineLabel.CONNECT`
    - `writeNextChunk(...)` is the point where a queued DTN control packet actually becomes `DTN control wire start`
    - if no client connection exists for the reply address, pending control packets can sit indefinitely without any `wire start`
  - Follow-up implementation ideas to evaluate in a separate thread:
    - prefer/force a DTN fast-path reply when handling inbound DTN control on an already active reverse-capable lane, if the transport/backend can safely support it
    - make the reply-address migration more aggressive after `device_id_rotated` so pending DTN control immediately follows the newest known address
    - explicitly prioritize establishing an outbound client lane for pending DTN control replies after inbound DTN control is accepted
    - add dedicated diagnostics when a DTN control packet reaches `pendingByDevice` but cannot start writing because no client lane exists
  - When this follow-up starts, inspect these areas first:
    - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
      - enqueue path / staged-to-pending
      - `PipelineLabel.ENQUEUE`
      - `writeNextChunk(...)`
      - address migration / `device_id_rotated`
      - DTN control reply routing

## Current Verification

- Passing:
  - `./gradlew :transport:compileDebugKotlin :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlRouterTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin`
  - `./gradlew :app:compileOfficialDebugKotlin`
  - `./gradlew :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlCodecTest --tests app.peermesh.transport.ble.dtn.DtnCarrierManifestCryptoTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlCodecTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlRouterTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlCodecTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :data:compileDebugKotlin :app:compileOfficialDebugKotlin`
  - `./gradlew :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest :app:compileOfficialDebugKotlin`
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnControlCodecTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest`

## Prioritization Update - 2026-05-23

- `Re-validate stale-target fallback under topology churn` is no longer treated as a current-phase gate.
- Decision:
  - postpone this task and move it closer to release-product hardening.
- Reason:
  - recent physical rounds have already proved the more common and higher-value behaviors:
    - strong mesh carrier relay through a moving peer,
    - DTN repair for missed delivery paths,
    - and better envelope continuity after prioritizing active mesh/app envelopes over DTN control.
  - in contrast, direct evidence for the rare
    `preferred_targets_unavailable -> auto selection`
    branch remains hard to trigger intentionally and is not a good day-to-day milestone blocker.
- Practical consequence:
  - continue current milestone work on:
    - opaque retry / success-side lifecycle cleanup,
    - explicit `mesh-first, DTN-repair-second` policy hardening,
    - relay contract fixes,
    - and release-facing transport stability.
  - reopen stale-target fallback re-validation later as a release-hardening / field-usability check.

## Physical Test Finding - 2026-05-13

Scenario tested:
- C left the shared group with A and B.
- A and B online: first 3 messages delivered to B.
- B Bluetooth off, A sent 3 messages, A Bluetooth off, B Bluetooth on: B did not receive from C.
- When A Bluetooth came back, B received the missing messages directly from A.
- Repeated for another 3 messages with the same result.

Log files:
- `logs/device-A_activity_log_live_20260513_224519-43b8e1cc.txt`
- `logs/device-B_activity_log_live_20260513_224517-6dcc8bcf.txt`
- `logs/device-C_activity_log_live_20260513_224513-a43d85d5.txt`

Key observations:
- DTN markers were present, but `hello` / `sync summary` showed `ids=0 data=0`.
- C later advertised/sent hello for another gid, not the shared A/B gid.
- No `inv_have`, `inv_want`, `data`, or `ack` transfer cycle appeared for the missing messages.

Root cause:
- Current DTN is member/event-store based.
- `RoomDtnInventoryStore` builds inventory from `events`.
- `BleMeshRuntime` scopes DTN hello to `ActiveGroupManager.getActiveGroup(...)`.
- The current leave-group cleanup removes the group row and event inventory for the gid, so C has no inventory to advertise after leaving even though test key material is retained.

Important conclusion:
- Keeping the group key is not enough for DTN carry-forward.
- Full DTN needs a carrier path that can store and advertise encrypted capsules independent of group membership/UI state.

## Physical Test Finding - 2026-05-14 19:25

Scenario tested:
- C left the shared group with A and B.
- BLE mesh was active on C after leaving; Network Status showed nearby peers and BLE traffic continued.
- A sent `Test 6: A #1..#10`.
- B did not receive A's messages unless A and B were simultaneously online.

Log files:
- `logs/device-A_activity_log_live_20260514_192506-c933cd4c.txt`
- `logs/device-B_activity_log_live_20260514_192500-c389a25a.txt`
- `logs/device-C_activity_log_live_20260514_192458-f99af97a.txt`

Key observations:
- Carrier-only BLE mesh lifecycle is fixed: C logs continuous `BLE scan callback` and `NEARBY_COUNT_BREAKDOWN` after leaving the group.
- C received raw GATT envelopes from peers and logged `GATT deliver envelope`.
- Each app batch received by C was rejected by normal ingest:
  - `BLE relay decision kind=app_batch accepted=false relay_eligible=false reason=not_accepted`
  - `BLE relay decision kind=envelope accepted=false relay_eligible=false reason=not_accepted_skip`
- B had no `Received message at` or `Rendered message at` entries for A's `Test 6` messages.
- DTN control did not progress past hello:
  - A/B logged `DTN hello sent` toward C.
  - C logged `DTN sync summary ... type=hello ... ids=0 data=0`.
  - No `DTN inv_have`, `DTN inv_want`, `DTN data`, `DTN ack`, `manifest_offer`, `manifest_accept`, or carrier DATA path appeared.

Root cause:
- The BLE carrier service now runs without group membership, but the ordinary APP_BATCH ingest path still requires the group key before returning `accepted=true`.
- Because C has no group key after leaving, `EnvelopeReceiverImpl` returns `accepted=false`; `BleMeshTransport` only considers a batch for legacy relay when `accepted && relayEligible`, so C drops the opaque encrypted batch instead of carrying it.
- The Full DTN path also remains asymmetric for carrier-only peers: C has no active gid and no stored opaque capsule yet, so its DTN sync scope list is empty and it does not initiate HELLO for the group. A/B HELLO packets to C only cause C to advertise C's empty inventory; they do not cause A/B to offer their pending outbound batches to C.

Important conclusion:
- Carrier-only scan/nearby is fixed.
- Store-and-forward is still blocked until non-member carriers can either learn a gid scope from incoming HELLO and initiate a reverse HELLO, or senders can proactively offer signed manifests to discovered carrier peers.

Follow-up implemented:
- Sender-side proactive offer is now implemented through the `manifest_key -> manifest_offer` response path.
- The next physical test should show `DTN manifest_offer sent/recv`, `DTN manifest_accept sent/recv`, `DTN data sent/recv`, and `DTN ack sent/recv` when A/B meet C.

## Remaining Steps

- [x] Full DTN carrier architecture
  - Add an opaque carrier store for encrypted envelopes/app batches. (schema done; runtime store path pending)
  - Use signed carrier session manifests to bind `batch_id` to `payload_sha256`, size, TTL, priority, session, and quota. (control model + Room persistence + ECDSA verifier + peer public-key exchange + sender offer path done)
  - Transfer compact DATA only after receiver accepts the manifest; never accept by `batch_id` alone.
  - Add carrier-gid / capsule inventory independent of `ActiveGroupManager`.
  - Teach DTN inventory/data sync to merge member event inventory and opaque carrier inventory.
  - Preserve non-member privacy: carrier must not render UI or require decrypt.
  - Add storage caps, TTL/hop enforcement, dedupe, and per-peer rate limits for carrier capsules.

- [ ] Physical-device validation
  - Re-run A -> C -> B partition bridge after carrier-only manifest negotiation patch.
  - Watch ActivityLog labels with prefix `DTN ` and `source=dtn`.
  - Expected cycle: `DTN hello`, `DTN manifest_offer`, `DTN manifest_accept`, `DTN data`, `DTN ack`, `DTN sync summary`.

## Branch Improvement Plan - 2026-05-19

Current focus branch:
- `fix/opaque-retry-bookkeeping`

Debug/reporting spec:
- [DTN_REPORT_SPEC.md](/home/truong/projects/disaster-app/scripts/debug-kit/DTN_REPORT_SPEC.md)

Patches applied on this branch:
- [x] Reduced false-positive `queued_no_write_attempt_timeout` by increasing:
  - `QUEUED_NO_PROGRESS_TIMEOUT_MS`
  - from `20_000L`
  - to `30_000L`
- [x] Added grace for `queued_no_write_attempt_timeout` when:
  - the batch is still queued/staged/held
  - and another peer lane is actively making recent progress
  - rationale: avoid timing out peer A's queued work on carrier C just because C is still draining peer B successfully
- [x] Narrow queue-to-connect fix:
  - `maybeConnectNext()` now treats `held/staged` work the same as `pending` for deciding whether an address deserves a fresh GATT connect attempt
  - scope is intentionally limited to connect scheduling only
  - no service-side dispatchability semantic change
  - no opaque retry fanout/scheduling change
- [x] Source-side secondary-attempt cleanup:
  - once a normal app batch gets `DELIVERED` to any one peer, the source-side persistent outbound queue now treats that as sufficient
  - remaining peer-delivery rows are dropped with the batch via DB cascade when the batch is deleted
  - rationale: once the batch is in the mesh via one peer, keeping source retries alive mostly creates redundant second-hop attempts and local timeout noise
- Scope is intentionally narrow:
  - no scheduler changes
  - no opaque retry policy changes
  - no anti-loop metadata changes

Reason for doing this first:
- Recent physical rounds show repeated attempt-level failures where:
  - batch/capsule is queued correctly
  - target device id is correct
  - first write never starts before watchdog expiry
  - later retry succeeds on another lane/address window
- This indicates a queue-to-first-write timing problem before it is a routing or device-id problem.

Completed branch-improvement work:
- [x] Re-tested the same churn-heavy scenarios that previously showed:
  - `queued_no_write_attempt_timeout`
  - `chunks=0/...`
  - later retry success
- [x] Measured whether the `30s` timeout reduces false attempt failures without hiding real stuck sends.
- [x] Measured whether the added `other active lane progress` grace reduces cross-peer watchdog noise on carrier C.
- [x] Re-tested whether source-side `queued_no_write_attempt_timeout` drops after stopping post-success secondary retries.
  - Result:
    - user-facing delivery stayed strong;
    - source-side persistent outbound retries were reduced;
    - but transport-level multi-target attempts still created some secondary timeout noise.
- [x] Phase 1 debug-kit reporting:
  - extended `scripts/debug-kit/mesh_debug.sh dtn-md`
  - added `Delivery`, `Carrier`, `Source-side Noise`, and `Per-batch Verdict`
- [x] Phase 2 debug-kit reporting:
  - added `Queue Health`
  - added `Transport Stability`
  - added `Routing / Identity`
- Latest physical round comparison:
  - `111500`: two-way delivery existed, but B and C still showed noisy `queued_no_write_attempt_timeout`.
  - `120708`: B-side improved, but C became very noisy:
    - `queued_no_write_attempt_timeout=12`
    - `write_failed_status_133=6`
  - `124148`: best overall of the three most recent rounds:
    - A-origin delivered `2/2`
    - B-origin delivered `1/1` from what the logs actually captured
    - C improved to:
      - `queued_no_write_attempt_timeout=3`
      - `write_failed_status_133=0`
      - `ble_send_complete=8`
- Current interpretation:
  - the `30s` watchdog plus `other active lane progress` grace appears to have reduced cross-peer false timeouts on carrier C
  - `queued_no_write_attempt_timeout` is no longer the dominant visible failure mode on this branch
- [x] Fixed the queue-to-connect gap:
  - specifically the `held/staged -> first usable lane -> first write` path
  - implemented as a small isolated patch, not by reintroducing the whole `46f7ed3` behavior bundle
- [ ] After first-write stability improves, revisit carrier-side relay smoothness on C.
- [ ] Only after runtime stability is acceptable, finish opaque retry bookkeeping/reporting cleanup.
- [ ] Keep `sid + rid` top-level capsule metadata as a later design-hardening step, not part of this narrow watchdog branch.

Recommendation after round `124148`:
- Do **not** keep digging `queued_no_write_attempt_timeout` in isolation right now.
- Keep the current watchdog patch set as the baseline for this branch.
- Move to the next planned step:
  - inspect the remaining `held/staged -> first usable lane -> first write` gap on C for the few retries that still need one fail before success
  - keep that follow-up patch isolated from scheduler/fanout policy changes.

## Next Recommended Step

Run physical validation:
1. Build/install this branch on A, B, and C.
2. Let C leave the group and keep Bluetooth/location enabled.
3. Send A messages while B Bluetooth is off, then let C meet B while A is offline.
4. Inspect ActivityLog for `DTN manifest_offer`, `DTN manifest_accept`, `DTN data`, `DTN ack`, and opaque capsule inventory on C.

## Debug Kit Update - 2026-05-15

- [x] Updated `scripts/debug-kit/mesh_debug.sh` for the Full DTN model.
- [x] Added `dtn` command for per-log DTN control-plane, opaque carrier, and queue/failure counters.
- [x] Added `dtn-md` command to write a DTN round report into `logs/analysis/`.
- [x] Extended quick metrics/classify to include `queued_no_write_attempt_timeout`, DTN control markers, and opaque retry markers.
- [x] Included DTN summary in `bundle --full` and `codex-bundle --full`.
- [x] Added `codex-bundle --type dtn` prompt focused on sender -> carrier -> receiver analysis, opaque retry lifecycle, and chunks=0 queue failures.
- [x] Updated `.vscode/tasks.json` with DTN summary tasks, DTN Markdown report task, `dtn` Codex prompt type, and pull+DTN workflows.
- [x] Extended `dtn-md` report with a `Batch Transport Matrix` and per-batch transport timeline showing origin peer, devices seen, target device ids, duration, success/fail status, and failure reasons.
- [x] Added an `Opaque Forwarding Gaps` section to `dtn-md` that groups `GATT deliver envelope -> opaque_candidate -> opaque_forward_skipped` windows without `batch_id`, then lists nearest candidate batches by payload byte size and timestamp to make opaque forwarding failures easier to trace.
- [x] Added a `BLE Peer Availability and Connections` section to `dtn-md`, including per-device scan windows, nearby counts, candidate/queued peers, GATT connection/service discovery/failure counts, and a connectivity timeline.
- [x] Added `reset-db [official|emergency|auto] <serial ...>` to clear selected app package data on explicitly named devices, launch once, print recreated local/DTN/advertised ids, and write a `PeerMeshClearCheck` logcat marker.

## Debug Patch - 2026-05-15

- [x] Temporarily locked outbound `SYS.ACK` generation and dispatch so physical tests could isolate `MSG` batch transport.
- [x] Historical note only: this lock was removed on `2026-05-21` after relay/runtime stabilized enough to validate ACK again.
- [x] While the lock was active, `MeshAckManager` logged `SYS ACK send locked` instead of creating received/seen ACK events.
- [x] While the lock was active, `OutboundBatchDispatcher` filtered ACK-priority outbound batches from normal drain and transport pull claims.
- [x] Added `DTN opaque retry store` ActivityLog markers for `store_missing`, `capsule_build_failed`, `store_saved`, and `store_rejected`, including `batch_id`, `payload_sha256`, `source_peer_id`, and `payload_bytes`.
- [x] Updated `dtn-md` report parsing/counters so opaque store lifecycle markers appear in the DTN summary, batch matrix, and per-batch detail timelines.

## Investigation - Stale Opaque Capsule `rf-A0pGQmDBD` - 2026-05-15

Scenario source:
- Original sender log: `logs/device-A_activity_log_live_20260515_000600-b670a784.txt`
- Batch: `rf-A0pGQmDBD`
- Event: `_YOE1_tvV7_HSKiOBS2XVnLSZ-psV5OaqSn4_8s0v7k`
- Message: `TEST 4: A #1`

Trace result:
- A created and emitted `rf-A0pGQmDBD` at `2026-05-15 00:06:43`.
- A sent it to C successfully at `00:06:47`, `chunks=5/5`, `bytes=820/820`.
- C forwarded the same opaque app batch to B successfully at `00:07:46`, `chunks=5/5`, `bytes=820/820`.
- B received, extracted, and rendered the message at `00:07:49`.
- Later reports still show `rf-A0pGQmDBD` being retried by C as old opaque backlog.

DB check on the three current devices:
- `192.168.110.212:38491` is A for this batch:
  - `events` contains the event with `source=local`.
  - `chat_messages` contains `TEST 4: A #1`.
  - `outbound_batch` no longer contains `rf-A0pGQmDBD`.
  - `opaque_dtn_capsules` does not contain `rf-A0pGQmDBD`.
- `192.168.110.114:42645` is B for this batch:
  - `events` contains the event with `source=mesh`.
  - `chat_messages` contains `TEST 4: A #1`.
  - `event_dedupe` contains the event.
  - `outbound_batch` no longer contains `rf-A0pGQmDBD`.
  - `opaque_dtn_capsules` does not contain `rf-A0pGQmDBD`.
- `251fd3e9` is C for this batch:
  - `events` / `chat_messages` do not contain the decoded event, as expected for a non-member opaque carrier.
  - `opaque_dtn_capsules` still contains one capsule:
    - `capsule_id=ble_7b0da9dc9bcb19fd2288020bd0cc36fa1bf111dd`
    - `batch_id=rf-A0pGQmDBD`
    - `kind=app_batch`
    - `payload_bytes=820`
    - `received_at=2026-05-15 00:06:42`
    - `last_forwarded_at=2026-05-15 02:38:06`
    - `source_peer_id=83EF05EE72E14E21`
    - `expires_at=NULL`
  - C currently has 32 active opaque capsules, all forwardable by the current cooldown query; `rf-A0pGQmDBD` ranks first because it has the oldest `received_at`.

Root cause:
- `rf-A0pGQmDBD` successfully reached B, but C still retains the opaque carrier capsule.
- Because `expires_at` is `NULL` and the forward cooldown has elapsed, the capsule remains eligible for future opaque retry.
- This creates old-batch traffic/backlog and can starve newer carrier batches during short A/C or C/B contact windows.
- Current cleanup only deletes local opaque rows when the sending transport instance calls `clearOpaqueCapsulesForBatch(...)` after a send-complete tied to a remembered opaque retry capsule. The old capsule survived earlier code paths/log eras and remains retryable.

Recommended fix:
- Add an opaque carrier ACK or delivery-suppression path so carriers can delete or tombstone a capsule after a destination/member peer confirms the underlying event/batch is already accepted.
- Until full ACK/tombstone exists, add a hygiene cleanup for stale opaque capsules:
  - delete or disable capsules whose `last_forwarded_at` is older than a bounded retention window after at least one successful forward;
  - set `expires_at` when storing opaque app batches instead of leaving it `NULL`;
  - make `listForwardable(...)` prefer recent not-yet-forwarded capsules over old already-forwarded capsules, so old backlog cannot dominate short contact windows;
  - emit ActivityLog markers when cleanup deletes or suppresses a stale opaque capsule.
- For immediate debugging, it is safe to clear `rf-A0pGQmDBD` from C's `opaque_dtn_capsules` because A and B both already have the decoded event/chat state.

## Full DTN Relevant Files

Transport/control plane:
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnControlModels.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnControlCodec.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnControlRouter.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnSyncEngine.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnForwardPolicy.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryModels.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryBuilder.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnDiffResolver.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnCarrierManifestPolicy.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnCarrierManifestCrypto.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnDiagnostics.kt`

Runtime/app wiring:
- `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
- `android/app/src/main/java/app/peermesh/ble/MeshAckManager.kt`
- `android/app/src/main/java/app/peermesh/OutboundBatchDispatcher.kt`
- `android/core/src/main/java/app/peermesh/core/transport/EnvelopeReceiver.kt`
- `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`

Room DTN/carrier adapters:
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnInventoryStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnSyncStateStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnCarrierManifestStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnCarrierManifestOfferSource.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnCarrierDataSource.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnCarrierDataReceiver.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnPeerManifestKeyStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnCarrierManifestKeyResolver.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/DtnEventEnvelopeFactory.kt`

Room schema/entities/DAOs:
- `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
- `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OpaqueDtnCapsuleEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnCarrierManifestDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/DtnCarrierManifestEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/DtnCarrierManifestBatchEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnPeerManifestKeyDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/DtnPeerManifestKeyEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnAdvertisedDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnPendingPullDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnPeerStateDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/DtnSyncCursorDao.kt`

Debug/reporting:
- `scripts/debug-kit/mesh_debug.sh`
- `.vscode/tasks.json`

Tests to extend when patching:
- `android/transport/src/test/java/app/peermesh/transport/ble/dtn/DtnSyncCoreTest.kt`
- `android/transport/src/test/java/app/peermesh/transport/ble/dtn/DtnControlCodecTest.kt`
- `android/transport/src/test/java/app/peermesh/transport/ble/dtn/DtnControlRouterTest.kt`
- `android/transport/src/test/java/app/peermesh/transport/ble/dtn/DtnCarrierManifestCryptoTest.kt`
- `android/data/src/test/java/app/peermesh/data/dtn/RoomDtnInventoryStoreTest.kt`

## Files To Patch For `rf-A0pGQmDBD` Stale Capsule Fix

Primary fix files:
- `android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt`
  - Add DAO methods for stale-forwarded cleanup, optional tombstone/delete by `payload_sha256` or `batch_id`, and ordering that prioritizes fresh not-yet-forwarded capsules over old already-forwarded capsules.
- `android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt`
  - Set a bounded `expires_at` for stored opaque app batches.
  - Expose cleanup/tombstone APIs through `OpaqueRelayStore`.
  - Enforce retention after successful forward.
- `android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt`
  - Extend the interface with cleanup/tombstone operations needed by transport.
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
  - Call cleanup during opaque retry drain and after send complete.
  - Avoid retrying old already-forwarded capsules ahead of new capsules.
  - Optionally suppress/delete local opaque capsules when receiving a duplicate/already-have signal from a member peer.
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
  - Add ActivityLog markers for stale opaque cleanup/tombstone/suppression.

Likely follow-up files:
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnControlModels.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnControlCodec.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnSyncEngine.kt`
  - Needed only if implementing a real carrier ACK/tombstone DTN control packet instead of local hygiene cleanup.
- `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`
- `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
  - Needed only if adding durable tombstone columns/tables or changing schema.
- `scripts/debug-kit/mesh_debug.sh`
  - Add report counters for stale cleanup/tombstone markers so future logs show whether old capsules were suppressed.

Debug caveat:
- Historical note only: the temporary `SYS.ACK` lock described above no longer applies after the `2026-05-21` ACK re-enable validation.

## Handoff Prompt For Next AI

Use this prompt when another AI needs to continue the Full DTN work:

```text
You are continuing work on the Peer Mesh Android project in /home/truong/projects/disaster-app.

Read docs/tech_notes/DTN_DEVELOPMENT_NOTES.md first, then inspect the code before changing anything. The active work is the Full DTN / opaque carrier path for BLE store-and-forward between A, B, and a non-member carrier C.

Historical debug state:
- SYS.ACK sending/dispatch was intentionally disabled for a period to isolate CHAT.MSG batch transport.
- That lock has now been removed and should not be assumed in current branches.
- ActivityLog markers with source=dtn and source=mesh are the main evidence source.
- scripts/debug-kit/mesh_debug.sh has dtn/dtn-md reports for DTN and opaque forwarding analysis.

Important investigated batch:
- Batch rf-A0pGQmDBD, event _YOE1_tvV7_HSKiOBS2XVnLSZ-psV5OaqSn4_8s0v7k, message TEST 4: A #1.
- It was created by A, forwarded by C, and rendered by B successfully.
- DB query showed A and B no longer have outbound/opaque rows for it and both have decoded event/chat state.
- C still has one opaque_dtn_capsules row for rf-A0pGQmDBD with expires_at=NULL and old last_forwarded_at, so it remains forwardable and creates stale retry backlog.

Primary problem to fix next:
- Opaque carrier capsules that already reached a destination can remain in C forever and retry again after cooldown.
- Old already-forwarded capsules can dominate short contact windows and starve new batches.

Preferred immediate fix:
1. Add bounded retention/expiry for opaque app-batch capsules instead of leaving expires_at NULL.
2. Add stale-forwarded cleanup/tombstone behavior for capsules with last_forwarded_at older than a retention window.
3. Change listForwardable ordering to prioritize not-yet-forwarded or recent capsules over old already-forwarded capsules.
4. Add ActivityLog markers for stale opaque cleanup/tombstone/suppression.
5. Update scripts/debug-kit/mesh_debug.sh to surface those markers in dtn-md.
6. Update docs/tech_notes/DTN_DEVELOPMENT_NOTES.md after the patch.

Primary files:
- android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt
- android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt
- android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt
- android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt
- android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt
- scripts/debug-kit/mesh_debug.sh

Only implement a new DTN carrier ACK/tombstone control packet if local hygiene cleanup is not enough. If adding a real control packet, update DtnControlModels.kt, DtnControlCodec.kt, DtnSyncEngine.kt, tests, and possibly Room schema/migrations.

Verification target:
- Build at least :app:compileOfficialDebugKotlin.
- Prefer targeted DTN unit tests if transport/control code changes.
- In physical logs, C should not keep retrying rf-A0pGQmDBD after cleanup, and newer MSG batches should not be starved by old opaque backlog.
```

## Opaque Per-Peer Delivery Scheduler Patch - 2026-05-15

* [x] Added per-peer opaque delivery scheduling model:

  * delivery state is now tracked by `(capsule_id, device_id)` instead of only `last_forwarded_at`.
  * forward ordering now prioritizes:

    1. newest capsules never sent to the target device;
    2. older capsules never sent to the target device;
    3. retryable failed capsules.
* [x] Added hard TTL policy for opaque capsules:

  * opaque app-batch capsules now expire after 24 hours.
  * expired capsules are removed from retry eligibility.
* [x] Added opaque retry lifecycle diagnostics:

  * `opaque_retry_queued`
  * `opaque_retry_failed`
  * `opaque_retry_send_complete`
  * `opaque_retry_delete`
  * `opaque_retry_skipped`
* [x] Added send-complete based opaque delivery marking:

  * opaque capsules are no longer marked forwarded immediately after queue.
  * delivery state changes only after actual BLE batch send complete.
* [x] Added BLE address rebind behavior:

  * if a peer rotates BLE address or disconnects before first write attempt,
    queued batches can rebind to the peer's latest known address instead of
    waiting for watchdog timeout.
* [x] Added relay no-echo suppression:

  * a node no longer relays a batch back to:

    * the immediate sender peer;
    * the original source peer.
  * this suppresses loops such as:
    `B -> C -> A -> C`
* [x] Added stale opaque capsule hygiene:

  * legacy capsules with `last_forwarded_at` but no delivery rows are now suppressed.
  * stale already-forwarded capsules no longer dominate contact windows.

## Investigation - Legacy Opaque Delivery State Gap - 2026-05-15

Root cause investigation result:

* The main stale retry issue was NOT caused by Room `REPLACE` cascade deletion.
* Actual root cause:

  * migration `34 -> 35` created `opaque_dtn_capsule_delivery`
    but did not backfill delivery rows for legacy capsules that already had
    `last_forwarded_at`.
* Result:

  * old capsules appeared as:
    `delivery_state = NULL`
  * `listForwardableForDevice(...)` interpreted them as "never delivered",
    causing infinite retry loops for stale batches such as:
    `rf-A0pGQmDBD`.

DB evidence:

* legacy capsules:

  * `last_forwarded_at != null`
  * `delivery_rows = 0`
* newer capsules:

  * proper delivery rows exist.

Follow-up mitigation implemented:

* legacy forwarded capsules without delivery rows are now suppressed from retry.
* stale forwarded capsules are cleaned up after a bounded retention window.

## Physical Test Finding - 2026-05-15 Retry Scheduling

Observed behavior after stale-backlog cleanup:

* stale opaque backlog was dramatically reduced.
* however some batches still failed during short contact windows.

Key findings:

* Example batches:

  * `PFSjqHW1lUUr`
  * `qUnfQhED6xbZ`
* A -> C delivery succeeded.
* C queued retry toward B.
* Failures were now dominated by:

  * `status_133`
  * `queued_no_write_attempt_timeout`
  * short BLE contact windows.

Important observation:

* Some failed opaque retries were not re-attempted quickly enough while the peer
  was still nearby.
* Incomplete batches could lose the contact window before retry resumed.

Follow-up implemented:

* Added "hot retry priority" behavior:

  * recently failed opaque retries are temporarily prioritized for the same target device.
  * partial/status_133 batches remain hot for a bounded retry window.
* Goal:

  * finish incomplete transfers before contact loss instead of starving them
    behind unrelated queued batches.

## Physical Test Finding - BLE Address Rotation / Rebind

Observed behavior:

* A batch could queue toward an old BLE address.
* The peer rotated address before first write attempt.
* Batch then failed with:

  * `queued_no_write_attempt_timeout chunks=0/...`

Root cause:

* Transport queue state was address-bound instead of device-bound.

Follow-up implemented:

* queued jobs that never wrote a chunk can now rebind to the latest known BLE
  address for the same `deviceId`.
* This reduced:

  * `chunks=0`
  * `queued_no_write_attempt_timeout`
  * stale held queues after address rotation.

## Physical Test Finding - 2026-05-16 Forward Queue / Chunks=0 Investigation

Scenario tested:

- A online
- B online
- C carrier-only
- A sent `#1..#5`
- B sent `#1..#5`

Observed result:

- B received only:
  - A `#2..#5`
  - missing `#1`
- A received:
  - none of B `#1..#5`

Log report summary:

A -> C -> B path:

- A sent multiple batches successfully to C.
- C forwarded most batches successfully to B.
- B received:
  - `#2`
  - `#3`
  - `#4`
  - `#5`
- A `#1` batch (`X_uHd_hGlhKA`) failed with:

  - `write_failed_status_-100`
  - `interrupted_transport_stop`
  - `broadcast_rejected`

B -> C -> A path:

- B successfully transmitted all batches toward C:

  - `hOy_gxSFF5eG`
  - `iFSHWo4lOz3W`
  - `9kgDyr1YlV0K`
  - `VsmcV8ponmQA`
  - `KTIgpTmE30G7`

- C stored and queued them correctly.
- Only the first batch reached A.
- Remaining batches failed with:

  - `queued_no_write_attempt_timeout`
  - `chunks=0/82`

Key observation:

Transport no longer appears blocked by:

- stale opaque capsules
- legacy delivery state
- carrier lifecycle
- missing manifest negotiation

Current dominant failure has moved to:

- queued jobs never reaching first write attempt
- BLE address/session rotation
- scheduler/dispatch timing during short contact windows

Important conclusion:

Current bottleneck is no longer inventory or opaque storage.

Current bottleneck is:

C queued retry -> target A:

queue created
↓
connection/service state changed
↓
first chunk never written
↓
watchdog timeout
↓
chunks=0

This is now primarily a transport dispatch/scheduling problem.

Recommended next work:

1. Detect queued jobs with:

   - `chunksWritten==0`

2. When peer reconnects or services become available:

   - immediately re-drain opaque retry queue

3. Rebuild packets after MTU becomes known instead of keeping old chunk state

4. Prioritize:

   - hot failed retries
   - currently connected target
   - current-round batches

before:

   - old backlog

5. Add diagnostics:

   - `retry_rebound`
   - `retry_redrain`
   - `retry_mtu_rebuild`
   - `retry_hot_priority`

Expected result:

- reduce:

  - `chunks=0`
  - `queued_no_write_attempt_timeout`

- increase completion during short A/C and C/B contact windows

---

## Handoff Update - Current State (2026-05-16)

Current status:

Completed and mostly validated:

✓ Carrier-only BLE lifecycle
✓ Manifest negotiation
✓ Opaque capsule storage
✓ Per-peer delivery scheduling
✓ Stale capsule cleanup
✓ Retry lifecycle tracking
✓ BLE address rebind
✓ Hot retry prioritization

Current dominant issue:

Transport scheduling after queue creation.

Observed pattern:

batch queued
↓
peer reconnects or rotates session
↓
write never starts
↓
queued_no_write_attempt_timeout chunks=0

Next thread should NOT focus on:

- stale capsule cleanup
- manifest architecture
- DTN inventory design

Next thread SHOULD focus on:

- queue dispatch lifecycle
- reconnect-triggered redrain
- MTU-aware packet rebuild
- hot retry ordering
- chunks=0 failures

Primary files to inspect:

- android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt
- android/transport/src/main/java/app/peermesh/transport/ble/DefaultBleMeshClient.kt
- android/transport/src/main/java/app/peermesh/transport/ble/NordicBleMeshClient.kt
- android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt
- android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt
- android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt
- scripts/debug-kit/mesh_debug.sh

---

## Resolution Note - `rf-A0pGQmDBD` Investigation CLOSED

Status:

CLOSED / DO NOT REOPEN unless new evidence appears.

Final outcome:

- Batch `rf-A0pGQmDBD` was successfully delivered:

  A
  → C
  → B

- B extracted and rendered the message correctly.
- Transport path itself worked.

Root cause was identified and mitigated:

1. Legacy capsules existed with:

   - `last_forwarded_at != null`
   - no rows in `opaque_dtn_capsule_delivery`

2. Migration `34 -> 35` created delivery tracking but did not backfill legacy state.

3. `listForwardableForDevice(...)` interpreted:

   `delivery=NULL`

   as:

   `never forwarded`

4. Result:

   stale capsules repeatedly re-entered retry queues and created backlog.

Implemented mitigation:

✓ per-peer delivery state `(capsule_id, device_id)`

✓ legacy delivery-row suppression

✓ stale forwarded cleanup

✓ bounded TTL (24h)

✓ forward ordering priority

✓ send-complete delivery marking

✓ stale capsules no longer dominate contact windows

Current evidence:

Later physical tests no longer show:

- runaway stale retry storms
- `rf-A0pGQmDBD` repeatedly dominating queues

Current failures are now mostly:

- `queued_no_write_attempt_timeout`
- `chunks=0`
- short BLE contact windows
- queue dispatch timing

Instruction for future AI:

DO NOT reopen `rf-A0pGQmDBD` or stale-capsule investigation unless:

1. a newly captured batch shows the same pattern; AND

2. evidence shows:

   delivery state exists but is still retried incorrectly.

Otherwise treat the issue as resolved and move to transport scheduling work.

## Physical Test Finding - 2026-05-16 Canonical Identity / Env Stop

Scenario tested:

- A, B, and C were reset with `mesh_debug.sh reset-db`.
- C acted as carrier.
- A and B exchanged multiple messages through C.

Observed result:

- A received only B `#2` and `#3`.
- B received only A `#1` and `#2`.
- Some multi-hop opaque forwarding succeeded:
  - `_8HvB_eJVUIw`
  - `t-3XfmFcdNFz`
- DTN opaque store/queue/resend path is working, but transport reliability is still unstable.

Key evidence:

- C stored and forwarded several opaque capsules successfully.
- Successful examples:
  - `_8HvB_eJVUIw`: B -> C -> A completed.
  - `t-3XfmFcdNFz`: B -> C -> A completed.
- Failed examples showed:
  - `queued_no_write_attempt_timeout`
  - `chunks=0/N`
  - `write_failed_status_-100`
  - `interrupted_transport_stop`
  - `device_id_rotated`

Important conclusion:

The current dominant issue is no longer stale opaque backlog or manifest negotiation.

The current failures are mainly transport/identity lifecycle issues:

1. stale/rotated peer identities can remain in candidate/target queues;
2. BLE service can stop too aggressively on transient environment changes while a send is in progress.

---

## Current Open Issue - Canonical Peer Identity

Status:

OPEN / P0

Symptoms:

- Candidate pools can contain both old and current identities for the same physical peer.
- Transport can queue sends to an obsolete device id.
- Obsolete queued targets then fail with:

  - `queued_no_write_attempt_timeout`
  - `chunks=0/N`

Example evidence:

- A saw both:
  - `371E54DA1D34C4D6`
  - `F6761AFFAEFF0D9E`

- C saw both:
  - `653FBD1389DE2382`
  - `082032382FBE8A15`

- Some sends completed to the correct DTN peer id, while parallel sends to the stale/local identity failed with `chunks=0`.

Root cause hypothesis:

There is still a split between:

- old local device identity / localDeviceId preference;
- current DTN peer id / stable advertised id;
- BLE session address/device mapping.

The transport queue is not fully canonicalized by stable peer identity.

Required fix:

Add or harden a canonical identity layer:

```text
BLE address / session id
        ↓
observed device id
        ↓
canonical DTN peer id / stable device id
```

Rules:

- 1. Every physical peer must have only one canonical target id.
- 2. When a peer identity rotates or is resolved to a better canonical id:
  - migrate queued jobs;
  - migrate inflight metadata;
  - migrate retry/backoff state;
  - remove the old id from candidate pools and pending target sets.
- 3. Do not enqueue a batch to an obsolete id if a canonical id is known.
- 4. Delivery state for opaque capsules should be keyed by canonical device_id, not transient BLE address or stale local identity.
- 5. Add diagnostics:
  - identity_alias_added
  - identity_canonicalized
  - queued_target_rewritten
  - stale_identity_suppressed

Expected result:

- Fewer duplicate queued targets for the same peer.
- Fewer queued_no_write_attempt_timeout chunks=0/N.
- No repeated sends to old localDeviceId_pref identities after DTN peer id is known.

Primary files to inspect:

- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt`
- `scripts/debug-kit/mesh_debug.sh`

---

### Current Open Issue - Environment Stop Debounce

Status:

OPEN / P1

Symptoms:

Some batches fail after partial write:

- `write_failed_status_-100`
- `interrupted_transport_stop`

Examples:

- `a26a5OkhHxKV`
- `PuO9MTI4gDRP`

Observed timeline pattern:

```
send_progress chunks=2/5 or 3/5
        ↓
bluetooth_state_10
        ↓
evaluate reason=env_blocked
        ↓
stopMeshAndService
        ↓
interrupted_transport_stop
```

Root cause hypothesis:

`BleForegroundService.evaluateMeshState()` stops the mesh immediately when it sees transient environment blocked state.

This may be too aggressive during:

- Android BLE adapter transitions;
- short Bluetooth state glitches;
- active GATT writes;
- reconnect windows.

Required fix:

Add debounce/recheck before stopping the mesh for `env_blocked`.

Suggested policy:

```
if env_blocked:
    if transport has active work:
        delay short grace window
        recheck bluetooth/location/permission state
        stop only if still blocked
    else:
        stop normally
```

Active work should include at least:

- active GATT connection;
- active batch send;
- queued/inflight write;
- opaque retry drain currently in progress.

Recommended grace window:

- start with 3-5 seconds;
- keep diagnostics explicit.

Add diagnostics:

- `env_blocked_debounce_start`
- `env_blocked_debounce_cancelled`
- `env_blocked_debounce_stop`
- include:
  - `bluetoothEnabled`
  - `locationEnabled`
  - `permissionsOk`
  - `inflightBatchCount`
  - `queuedBatchCount`

Expected result:

- Fewer `interrupted_transport_stop` failures.
- Better completion rate during short BLE instability.
- Still stops correctly when Bluetooth/location/permission is truly off.

Primary files to inspect:

- `android/app/src/main/java/app/peermesh/ble/BleForegroundService.kt`
- `android/app/src/main/java/app/peermesh/ble/StatusIndicatorUpdater.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`

---

### Branch Update - fix/opaque-retry-bookkeeping (2026-05-19)

Status:

IN PROGRESS

Observed mismatch:

- Carrier relay success is visible in logs and DTN reports:
  - `DTN opaque retry store`
  - `DTN opaque retry queued`
  - `BLE batch send complete`
- But bookkeeping markers remain zero:
  - `DTN opaque retry mark_forwarded`
  - `DTN opaque retry send_complete`
  - `DTN opaque retry retry_failed`

Current hypothesis:

- Runtime relay is succeeding, but terminal opaque bookkeeping is losing the `(batch_id, device_id)` match used to resolve remembered capsule ids or queued delivery rows.
- The most likely mismatch is between the `deviceId` remembered at queue time and the canonical/alias `deviceId` observed on send-complete or send-failed.

Current patch scope:

- Keep runtime transport unchanged.
- In opaque terminal bookkeeping only:
  - resolve canonical + alias-equivalent `deviceId` candidates;
  - merge remembered opaque capsule keys across those ids;
  - mark delivered/failed across all equivalent ids;
  - keep hot-retry token cleanup aligned with the same candidate set.

Latest validation result:

- Re-ran a strong carrier-success round:
  - `logs/analysis/dtn_round_20260519_180936_092825677_14123.md`
- Delivery remained strong:
  - `A -> B = 4/4`
  - `B -> A = 4/4`
- Carrier C remained strong:
  - `opaque_retry_store=8`
  - `opaque_retry_queued=7`
  - `ble_send_complete=7`
  - `queued_no_write_attempt_timeout=0`
- But bookkeeping markers were still zero:
  - `opaque_retry_mark_forwarded=0`
  - `opaque_retry_send_complete=0`
  - `opaque_retry_failed=0`

Updated conclusion:

- The alias/canonical `deviceId` mismatch patch did **not** explain the missing bookkeeping markers.
- In the latest successful round:
  - `identity_alias_added=0`
  - `identity_canonicalized=0`
  - `queued_target_rewritten=0`
- So the current failure is more likely inside opaque delivery row lifecycle itself, not in alias resolution.

Next recommended step:

- Do not patch transport behavior further here.
- Add narrow diagnostics around opaque delivery row lifecycle:
  - when `markDeliveryQueued(...)` is called;
  - inside `clearOpaqueCapsulesForBatch(...)`;
  - inside `handleOpaqueRetrySendFailed(...)`.
- Log at least:
  - `batch_id`
  - `device_id`
  - `deviceIdCandidates`
  - `rememberedCapsules.size`
  - `marked`
  - whether queue-time delivery row already existed and in which state

Expected outcome of next step:

- Distinguish which of these is actually failing:
  - remembered capsule set is empty at terminal time;
  - delivery rows are written under different `(capsule_id, device_id)` than terminal path expects;
  - delivery rows are already terminal before terminal handler runs;
  - batch-level fallback path is hitting zero matching capsule ids.

---

### Handoff for ChatGPT Web

Current branch:

- `fix/opaque-retry-bookkeeping`

Current code state:

- Transport runtime is intentionally unchanged.
- Only bookkeeping/reporting patch has been applied so far:
  - `BleMeshTransport.clearOpaqueCapsulesForBatch(...)`
  - `BleMeshTransport.handleOpaqueRetrySendFailed(...)`
  - hot-retry token cleanup/update now accepts alias-equivalent `deviceId` sets

What is already known:

- DTN report tooling is already upgraded:
  - `scripts/debug-kit/mesh_debug.sh`
  - `scripts/debug-kit/DTN_REPORT_SPEC.md`
- Recent good round for comparison:
  - `logs/analysis/dtn_round_20260519_173634_815462742_10549.md`
- Latest validation round:
  - `logs/analysis/dtn_round_20260519_180936_092825677_14123.md`
- In both rounds:
  - delivery is strong;
  - carrier C relays successfully;
  - opaque retry bookkeeping markers still stay zero.

Files to inspect next:

- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`

Immediate next task:

- Add diagnostics around opaque delivery row lifecycle instead of changing routing/scheduling behavior.
- Then run one more carrier-success round and inspect whether:
  - queued delivery rows are created;
  - remembered capsules are still present at terminal callback time;
  - DAO `markDeliveryDelivered*` / `markDeliveryFailed*` return zero because of missing rows, terminal rows, or wrong keys.

---

### Opaque Retry Bookkeeping Plan

Goal:

- Keep current strong DTN runtime behavior unchanged.
- Make opaque retry bookkeeping/reporting finally reflect reality.
- Specifically, in a carrier-success round, the report should no longer stay at:
  - `opaque_retry_mark_forwarded=0`
  - `opaque_retry_send_complete=0`
  - `opaque_retry_failed=0`

What "done" looks like:

- In a round where C clearly does:
  - `DTN opaque retry store`
  - `DTN opaque retry queued`
  - `BLE batch send complete`
- the report must also show:
  - non-zero `opaque_retry_mark_forwarded`
  - non-zero `opaque_retry_send_complete`
- and in a failure round, `opaque_retry_failed` must also become non-zero when appropriate.

#### Step 1 - Prove this is a bookkeeping bug, not a transport bug

Status:

DONE

What was established:

- Recent rounds already prove runtime relay is working.
- Example rounds:
  - `logs/analysis/dtn_round_20260519_173634_815462742_10549.md`
  - `logs/analysis/dtn_round_20260519_180936_092825677_14123.md`
- In both:
  - delivery is strong;
  - carrier C relays successfully;
  - opaque bookkeeping markers still remain zero.

Conclusion:

- The current problem is under-reporting / terminal state reconciliation.
- It is not a primary carrier scheduling problem.

#### Step 2 - Inspect the opaque retry terminal path

Status:

DONE

Files already inspected:

- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`

Important code paths already checked:

- queue time:
  - `drainOpaqueRetryStore()`
  - `rememberOpaqueRetryCapsule(...)`
  - `markDeliveryQueued(...)`
- terminal success:
  - `clearOpaqueCapsulesForBatch(...)`
- terminal failure:
  - `handleOpaqueRetrySendFailed(...)`

What was ruled out:

- Not a missing logger label issue.
- Diagnostics methods for `mark_forwarded`, `send_complete`, and `retry_failed` already exist.

#### Step 3 - Test the alias/canonical deviceId mismatch hypothesis

Status:

DONE, but not successful

What was attempted:

- Terminal bookkeeping was widened to:
  - resolve canonical + alias-equivalent `deviceId` candidates;
  - merge remembered capsule keys across those ids;
  - mark delivered/failed across all equivalent ids;
  - keep hot-retry token cleanup aligned with the same set.

Why this was tried:

- One plausible failure mode was:
  - queue-time delivery row remembered under one `deviceId`,
  - send-complete/fail arrives under a canonicalized or alias `deviceId`,
  - terminal mark therefore returns zero.

Validation result:

- Re-tested on:
  - `logs/analysis/dtn_round_20260519_180936_092825677_14123.md`
- Delivery stayed strong.
- Carrier C stayed strong.
- But opaque bookkeeping markers still stayed zero.
- Also, the round showed:
  - `identity_alias_added=0`
  - `identity_canonicalized=0`
  - `queued_target_rewritten=0`

Conclusion:

- Alias/canonical `deviceId` mismatch is not the main explanation for the current zero markers.

#### Step 4 - Instrument the opaque delivery row lifecycle directly

Status:

NEXT

Why this is the next step:

- At this point, further speculative patching is low-value.
- We need direct evidence from the queue-time and terminal-time delivery row lifecycle.

What should be instrumented:

1. Queue-time path:
   - `markDeliveryQueued(...)`
2. Terminal success path:
   - `clearOpaqueCapsulesForBatch(...)`
3. Terminal failure path:
   - `handleOpaqueRetrySendFailed(...)`

Minimum fields to log:

- `batch_id`
- `capsule_id` where available
- `device_id`
- `deviceIdCandidates`
- `rememberedCapsules.size`
- `marked`
- current or existing delivery row state, if available

What this step must answer:

- Are queued delivery rows actually being created?
- Is `rememberedCapsules` already empty by terminal time?
- Are terminal paths matching the wrong `(capsule_id, device_id)`?
- Are rows already terminal before terminal success/failure handlers run?
- Is batch-level fallback seeing zero capsule ids or zero markable rows?

#### Step 5 - Re-run a strong carrier round after instrumentation

Status:

BLOCKED by Step 4

Expected outcome:

- One new round should make the failure mode unambiguous.
- After that, the next patch should be evidence-driven rather than speculative.

#### Step 6 - Patch the real root cause, then verify report correctness

Status:

TODO

This step should only happen after Step 4 + Step 5 produce direct evidence.

Success criteria for the final patch:

- No regression in runtime relay behavior.
- Report now reflects opaque retry terminal states correctly.
- The same strong carrier rounds now show non-zero terminal opaque bookkeeping markers.

---

### Future Design Hardening - `sid + rid` Top-Level Capsule Metadata

Status:

NOT STARTED / LATER DESIGN STEP

Why this exists in the roadmap:

- Current opaque capsule metadata is still relatively thin.
- Today the durable capsule model mainly keeps:
  - `batch_id`
  - `event_id`
  - `kind`
  - `payload_sha256`
  - `source_peer_id`
  - hop/ttl/priority timing fields
- That is enough for basic retry and direct no-echo against the original source.
- It is **not** rich enough to describe the last relay hop explicitly in a durable, uniform way.

#### Proposed model

Use explicit top-level metadata on opaque carrier capsules:

- `sid`
  - source device id
  - the original producer of the payload/batch
- `rid`
  - relay device id
  - the most recent relay hop that handed this capsule to the current node

Optional companion fields if later needed:

- `path_hops`
  - compact ordered hop summary or bounded hop list
  - not required for the first version
- `sid_kind`
  - only if later needed to distinguish device-id class / migration cases
- `rid_t`
  - timestamp of the relay hop, only if later required for debugging or TTL heuristics

#### Proposed compact field names for on-wire / serialized capsule metadata

When this model is implemented, the intended compact field set is:

- `id`
  - capsule id
- `gid`
  - group id
- `eid`
  - event id for single-envelope capsules
- `bid`
  - batch id for app-batch capsules
- `k`
  - capsule kind
  - expected compact values include:
    - `env`
    - `batch`
- `sid`
  - original source device id
- `rid`
  - most recent relay device id
- `ps`
  - payload sha256
- `pb`
  - payload byte size
- `ct`
  - created-at seconds
- `rt`
  - received-at seconds
- `xt`
  - expires-at seconds
- `hc`
  - hop count
- `mh`
  - max hops
- `pr`
  - priority

Optional later fields:

- `mid`
  - manifest id
- `ph`
  - path hints / compact hop summary
- `rtt`
  - relay timestamp for the latest hop

#### Mapping from current model to the future compact model

Current `OpaqueRelayCapsule` fields map naturally as:

- `capsuleId` -> `id`
- `gid` -> `gid`
- `eventId` -> `eid`
- `batchId` -> `bid`
- `kind` -> `k`
- `payloadSha256` -> `ps`
- `payloadBytes` -> `pb`
- `createdAtSeconds` -> `ct`
- `receivedAtSeconds` -> `rt`
- `expiresAtSeconds` -> `xt`
- `hopCount` -> `hc`
- `maxHops` -> `mh`
- `priority` -> `pr`

Current gaps that would need explicit implementation:

- `sid`
  - today the nearest durable field is `source_peer_id`, but that is not yet a fully explicit original-source model
- `rid`
  - not currently stored as durable top-level opaque capsule metadata
- `mid`
  - would need to be promoted consistently if manifest-linked capsule tracing becomes important

#### Recommended v1 scope

For the first real implementation, the recommended minimum set is:

- `id`
- `gid`
- `eid`
- `bid`
- `k`
- `sid`
- `rid`
- `ps`
- `pb`
- `ct`
- `rt`
- `xt`
- `hc`
- `mh`
- `pr`

This is considered sufficient for:

- stronger anti-loop filtering
- clearer per-batch path inference
- better alignment between durable capsule state and runtime relay logic

without requiring the more optional future fields immediately.

#### Intended semantics

For a capsule stored on node `X`:

- `sid` answers:
  - who originated this payload?
- `rid` answers:
  - who most recently relayed it to `X`?

This makes the routing model explicit:

- source -> relay -> receiver

instead of relying only on:

- `source_peer_id`
- transient address state
- in-memory `originDeviceIds`
- payload-level heuristics

#### Why this is useful

1. Stronger anti-loop rules

With explicit `sid` and `rid`, a node can reject forwarding back to:

- the original source (`sid`)
- the immediate previous relay (`rid`)

This matches the intended behavior discussed earlier:

- allow:
  - `source -> target`
  - `source -> relay`
  - `relay -> target`
- reject:
  - `target -> source`
  - `target -> relay`
  - obvious bounce loops through the previous hop

2. Better report/path inference

With `sid` and `rid`, reports can infer per-batch path much more reliably:

- `A -> B`
- `A -> C -> B`
- `B -> C -> A`

without relying only on timing correlation between:

- store
- queued
- send complete

3. Better alignment between runtime relay logic and durable carrier state

Current runtime relay path already carries richer in-memory context in places such as:

- `originDeviceIds`
- `sourceDeviceId`
- `sender_relay_id` in app-batch related handling

But the durable opaque capsule currently does not preserve that full structure explicitly.

`sid + rid` would make durable stored state closer to the actual relay logic.

#### Current gap in the codebase

What exists today:

- durable capsule field:
  - `source_peer_id`
- runtime anti-loop context:
  - `originDeviceIds`
  - `sourceDeviceId`
  - app-batch relay fields such as `sender_relay_id` in some code paths

What is missing:

- a durable, uniform top-level representation of:
  - original source device id
  - most recent relay device id

So today:

- app-batch relay paths can make richer anti-loop decisions in-memory
- opaque retry store paths are comparatively less expressive and rely more heavily on `source_peer_id`

#### Suggested future implementation scope

When this design-hardening step is actually taken, it should include:

1. Capsule schema

- extend `OpaqueDtnCapsuleEntity` with:
  - `sid`
  - `rid`
- add migration support in Room

2. Build/store path

- when storing a new opaque capsule:
  - persist original source into `sid`
  - persist current incoming relay peer into `rid`

3. Forward filter path

- replace simple source-only suppression with:
  - do not forward to `sid`
  - do not forward to `rid`

4. Reporting/debug path

- expose `sid` and `rid` in diagnostics and DTN report path inference

#### Important scoping note

This is **not** the current priority.

It is intentionally kept as a later design-hardening step because recent debugging showed the more urgent issues were:

- queue-to-first-write timing
- carrier relay reliability
- opaque retry bookkeeping/reporting undercount

So `sid + rid` should only be implemented after:

- runtime transport remains stable,
- opaque retry bookkeeping is corrected,
- and the team wants stronger anti-loop + path observability at the durable capsule layer.

## BLE Header Control Update - 2026-05-19

Branch:

- `feature/ble-header-restart-hold-off`

Implemented on top of `feature/dtn-architecture`:

- single tap on the BLE header icon:
  - if BLE mesh is running:
    - stop BLE mesh immediately,
    - restart it after `3s`,
    - show a toast when restart completes
  - if BLE mesh is off:
    - start BLE mesh,
    - show a toast when start is triggered
- long-press behavior:
  - after `1s` press-and-hold:
    - show a toast hint telling the user to keep holding for `5s` to turn BLE mesh off
  - after `5s` press-and-hold:
    - stop BLE mesh fully,
    - cancel any pending restart,
    - mark BLE mesh user-enabled state as `false`,
    - show a toast confirming BLE mesh has been turned off

Implementation notes:

- this is a header/UI control change only
- no `BleMeshTransport` runtime logic was changed
- restart path uses delayed `Handler` scheduling in `StatusIndicatorUpdater`
- manual hold-to-stop path uses `manual_stop_hold`

### Branch Update - fix/opaque-retry-bookkeeping-instrumentation (2026-05-20)

Current focus:

- instrument opaque retry delivery-row lifecycle instead of guessing from outer relay logs

Added instrumentation scope:

- queue-time:
  - after `markDeliveryQueued(...)`
  - emit a row snapshot for `(capsule_id, device_id)`
- success terminal:
  - inside `clearOpaqueCapsulesForBatch(...)`
  - emit:
    - `deviceIdCandidates`
    - `rememberedCapsules.size`
    - `marked`
    - `mode = capsules | batch`
    - batch-level delivery-row summary for the target device
- failure terminal:
  - inside `handleOpaqueRetrySendFailed(...)`
  - emit the same probe fields as success terminal

New diagnostics markers:

- `DTN opaque retry queued_state`
- `DTN opaque retry bookkeeping_probe`
- `DTN opaque retry pre_mark_probe`
- `DTN opaque retry pre_mark_result`

How to read the newer pre-mark markers:

- `DTN opaque retry pre_mark_probe`
  - purpose:
    - logs the probe input/scope before the success/failure bookkeeping result is interpreted
  - compact fields:
    - `s` = stage (`send_complete` | `send_failed`)
    - `b` = batch id
    - `d` = device id from the terminal transport event
    - `c` = candidate device id actually probed/marked
    - `m` = mode (`batch` | `capsules`)
    - `n` = capsule count seen by the probe
    - `pu` = probe unavailable reason (`none` when probe data exists)

- `DTN opaque retry pre_mark_result`
  - purpose:
    - logs the per-candidate classification/result used to explain why a success/failure mark did or did not update rows
  - compact fields:
    - `s` = stage
    - `b` = batch id
    - `d` = terminal device id
    - `c` = candidate device id
    - `m` = mode
    - `n` = capsule count
    - `mk` = marked count
    - `mc` = `missing_capsule`
    - `ad` = `already_delivered`
    - `ff` = `failed_final`
    - `ez` = `eligible_but_zero`
    - `pu` = probe unavailable reason

Recommended Activity Log filters when debugging this branch:

1. `DTN opaque retry queued_state`
2. `DTN opaque retry pre_mark_probe`
3. `DTN opaque retry pre_mark_result`
4. `DTN opaque retry bookkeeping_probe`
5. `BLE batch send complete`
6. `BLE batch send failed`

Production note:

- this instrumentation is temporary and exists only to debug opaque retry bookkeeping
- it adds extra Room reads and diagnostic string building on terminal opaque paths
- when the root cause is confirmed and the fix is merged for production, remove or fully guard this instrumentation so it does not ship in release builds

Immediate next validation:

- run another strong carrier-success round using the current report workflow
- inspect whether:
  - queue-time delivery rows are created as expected
  - `rememberedCapsules` is empty or non-empty at terminal time
  - `marked` stays `0` because of key mismatch, missing rows, or already-terminal rows

Latest validation update:

- round:
  - `logs/analysis/dtn_round_20260520_162027_804432495_15890.md`
- this is now a strong steady-state carrier-success round:
  - `A -> B = 4/4`
  - `B -> A = 4/4`
  - C:
    - `opaque_retry_store=10`
    - `opaque_retry_queued=22`
    - `ble_send_complete=10`
    - `ble_send_failed=1`
    - `queued_no_write_attempt_timeout=0`
- the round confirms again:

### Opaque Retry Bookkeeping Status

Current status:

- PENDING as technical debt
- not a blocker for current BLE mesh runtime or delivery validation

What is already established:

- queue-time opaque bookkeeping is working well enough for diagnosis
- remembered capsule tracking is present on carrier-success paths
- failure-side timeout bookkeeping can mark terminal rows in some real rounds
- success-side carrier bookkeeping still often shows:
  - `remembered=1`
  - `marked=0`

What is not yet proven:

- whether success-side `marked=0` is caused by:
  - `missing_capsule`
  - `already_delivered`
  - `failed_final`
  - or a deeper DAO/store success-mark logic issue

Why this is being paused:

- recent rounds already show strong real-world value on the things that matter most now:
  - full or near-full delivery
  - strong carrier relay through C
  - better DTN report visibility for delivery, latency, and mesh-state stability
- the remaining opaque bookkeeping work is now primarily about observability correctness and cleanup semantics, not transport reliability

How to treat this issue for now:

- do **not** use `opaque_retry_mark_forwarded` / `opaque_retry_send_complete` as release-gating transport metrics yet
- continue using:
  - `Received message at` for delivery scoring
  - carrier store/queued/send-complete counters for relay health
  - BLE mesh state timeline for stability analysis

When to resume this task:

- when cleanup/tombstone correctness becomes important
- when release/ops reporting needs trustworthy opaque terminal metrics
- or before implementing deeper DTN lifecycle hardening such as:
  - ACK/tombstone cleanup policy
  - durable `sid/rid` metadata
  - production-grade opaque retry lifecycle reporting

### Recent Round Findings (2026-05-21)

These recent rounds matter more for current DTN/mesh confidence than the still-open opaque bookkeeping debt.

#### 1. Clean steady-state baseline

Round:

- `logs/analysis/dtn_round_20260521_120558_498542893_17537.md`

Established:

- delivery was fully clean:
  - `A -> B = 10/10`
  - `B -> A = 10/10`
- latency was stable and low for this test setup:
  - roughly `2.5s -> 5.0s`
- carrier C was strong:
  - `opaque_retry_store=20`
  - `opaque_retry_queued=40`
  - `ble_send_complete=20`
  - `ble_send_failed=0`
  - `queued_no_write_attempt_timeout=0`
- no BT/Location churn was visible in timeline

Interpretation:

- this is the current best steady-state baseline for BLE mesh health
- use this round as the clean comparison point for later noisy rounds

#### 2. BT off/on churn on A/B causes severe latency inflation

Round:

- `logs/analysis/dtn_round_20260521_121732_325060007_18639.md`

Established:

- delivery still completed:
  - `A -> B = 5/5`
  - `B -> A = 5/5`
- but latency degraded dramatically:
  - many messages rose into tens or hundreds of seconds
- BLE Mesh State Timeline showed repeated:
  - `bluetooth_state off`
  - `service_evaluate_decision`
  - `service_stop_mesh`
  - `onDestroy`
  - `mesh_transport_start`
  on A and B

Interpretation:

- this round is a good example of environment churn, not clean relay runtime
- the new timeline section is useful because it separates BT/Location/service churn from true transport bugs

#### 3. Relay topology validation with A and B separated by 13-14m and C in the middle

Round:

- `logs/analysis/dtn_round_20260521_132951_973827175_23094.md`

Test intent:

- keep BT on during the main part of the round
- place A and B about `13m - 14m` apart
- place C between them
- then intentionally turn BT off on C near the end and send `#11` to confirm whether A/B still have a usable direct path

Raw-log conclusion:

- `A -> B` was truly `10/10`
- `B -> A` was truly `9/10` by `Received`
  - `B #9` was the missing one
- when BT was intentionally turned off on C near the end:
  - `A #11` failed as expected
  - this supports the conclusion that A/B do not have a strong direct path at `13m - 14m` without C

Important observability correction:

- earlier report wording over-weighted `Rendered message at`
- direct raw-log review showed that A had several `Received message at` entries for B-origin messages without matching `Rendered message at`
- this happened because `Rendered message at` is tied to `GroupChatActivity` foreground/listener timing, not to durable delivery itself

Resulting report policy change:

- `Delivery` in `scripts/debug-kit/mesh_debug.sh` now uses:
  - `Received message at`
    as the primary delivery signal
- `Rendered message at` is now treated as:
  - UI-foreground evidence only
  - useful, but not authoritative for mesh delivery scoring

Interpretation:

- the topology test validates that relay through C is working well in a distance-constrained setup
- the tail BT-off test also validates that direct A/B range is not silently bypassing the carrier assumption

#### 4. Practical guidance for interpreting future rounds

For DTN round review going forward:

- treat `Received message at` as the authoritative delivery marker
- treat `Rendered message at` as a secondary UI marker
- use `BLE Mesh State Timeline` to decide whether a bad round is caused by:
  - BT/Location/service churn
  - or true relay/runtime instability
- use the clean baseline round above when comparing latency and carrier quality

### SYS.ACK Re-enable Status (2026-05-21)

Status:

- `SYS.ACK` generation has been re-enabled in `MeshAckManager`
- ACK-priority outbound dispatch has been re-enabled in `OutboundBatchDispatcher`
- Steady-state validation round succeeded:
  - `logs/analysis/dtn_round_20260521_174700_171357888_10837.md`

Scope of this change:

- restores normal creation and transport of:
  - `ack_type=received`
  - `ack_type=seen`
- does not change current DTN relay/runtime logic outside ACK traffic

Validated evidence:

- physical-device logs now show real `SYS.ACK` traffic again:
  - `Sent message at ... type=SYS.ACK`
  - `APP batch window armed ... type=ACK`
  - `APP batch flush start ... type=ACK`
- apply path is confirmed in steady-state logs:
  - `ACK applied delivered`
  - `ACK applied seen`
- `SYS ACK send locked` is absent from the validated round
- BLE mesh timeline for the validated round is `STEADY_STATE` on A/B/C
- ACK traffic did not reintroduce the old starvation/churn pattern strongly enough to invalidate delivery/runtime

Report/tooling follow-up completed with ACK validation:

- `scripts/debug-kit/mesh_debug.sh` now excludes `type=SYS.ACK` from user-visible `Delivery` scoring
- `dtn-md` now has a separate `ACK Activity` section so ACK control traffic can be reviewed without polluting chat delivery counts

### SYS.ACK Routing / Batching Semantics (2026-05-21)

- ACK types currently used:
  - `ack_type=received`
  - `ack_type=seen`
- Routing difference:
  - `received ACK` is directed back to the original message source device:
    - `targetDeviceId = core.actor.did`
  - `seen ACK` currently uses auto-target routing:
    - `targetDeviceId = null`
- `seen ACK` dedupe:
  - `MeshAckManager.seenSent` suppresses duplicate `seen ACK` sends for the same `targetEventId` within the current app session.
  - Practical effect:
    - repeated UI/view activity for the same event should not keep emitting new `seen ACK` traffic in one session.
- ACK batching:
  - `ACK_BATCH_WINDOW_MS = 10_000`
  - `ACK_TARGETS_PER_EVENT = 8`
  - `ACK_BATCH_MAX_TARGETS = 24`
  - Practical effect:
    - ACK is intentionally coalesced and can be delayed up to the batch window before flush.
    - one `SYS.ACK` event can cover multiple target event ids.
- ACK transport class:
  - `SYS_ACK -> BatchType.ACK`
  - `SYS_MEMBER_REMOVED -> BatchType.OTHER`
  - ACK uses `PRIORITY_ACK_BACKGROUND`
  - ACK is therefore background-priority control traffic, not a foreground chat/data batch.
- Current dispatch posture:
  - ACK is allowed through normal outbound dispatch again.
  - Data traffic is still preferred over ACK traffic when both are eligible.
  - The validated steady-state round indicates ACK did not reintroduce starvation strongly enough to break delivery/runtime.
- Important non-goal of the current ACK implementation:
  - ACK does **not** have a separate durable resend protocol.
  - If an ACK fails before it gets into the mesh, there is no ACK-specific store-and-retry loop that guarantees later delivery.
  - Reliability therefore comes from:
    - normal app-batch enqueue/dispatch,
    - and then mesh / carrier forwarding if the ACK makes it into the mesh.
- Legacy resend clarification:
  - `MeshAckManager.enqueuePending(...)` still exists, but chat traffic now logs `Mesh ACK legacy resend skipped`.
  - Practical effect:
    - chat-related ACK behavior should be understood as the new batched ACK path, not the older per-event pending resend path.

### SYS.ACK Retention / Relay Cleanup Semantics (2026-05-21)

- ACK generation and dispatch are now re-enabled, but ACK still uses the normal app-batch and relay paths; there is no separate durable ACK resend protocol.
- Source-side ACK cleanup:
  - ACK batches currently enter the persistent outbound queue with normal `source="mesh"` semantics, not `sys_metadata`.
  - Because of that, ACK follows the existing source-side rule in `OutboundBatchDispatcher.reconcileBatchAfterPeerUpdate(...)`:
    - one successful peer delivery is enough;
    - the source-side ACK batch is marked sent and deleted with `reason=first_peer_delivered`.
  - Practical effect:
    - once an ACK is successfully pushed from the source device to the first peer/carrier, the source device no longer keeps retrying that ACK batch.
- Relay-side ACK retention:
  - If the ACK has already been stored opaquely on a carrier, success does **not** immediately delete the opaque capsule.
  - `markDeliveryDelivered(...)` only writes per-device delivery state `DELIVERED` and updates `last_forwarded_at`; it does not delete the parent capsule row.
  - Cleanup therefore falls back to the current opaque-retention policy:
    - TTL expiry,
    - store-cap pruning,
    - orphan/legacy cleanup.
- Relay resend behavior after success:
  - once a per-device delivery row reaches `DELIVERED`, that row cannot transition back to `FAILED_RETRYABLE`;
  - `markDeliveryFailed(...)` explicitly returns `0` for existing `DELIVERED` or `FAILED_FINAL` rows.
  - `listForwardableForDevice(...)` only returns:
    - capsules with no delivery row for that target, or
    - `FAILED_RETRYABLE` rows whose retry time has arrived.
  - Practical effect:
    - after a relay successfully pushes an ACK to the intended source device and the success mark sticks, that relay should not forward the same ACK again to that same device.
- Current technical-debt caveat:
  - opaque success-side bookkeeping is still not fully closed on the carrier path.
  - In some success cases the row remains `IN_FLIGHT` instead of being marked `DELIVERED`.
  - Even in that state, the capsule usually does **not** re-enter normal retry for the same target, because `IN_FLIGHT` rows are not selected by `listForwardableForDevice(...)`.
  - The remaining risk is stale retention / under-cleanup, not an obvious ACK forward loop after success.

Opaque bookkeeping status remains unchanged:

- queue-time rows exist
- `rememberedCapsules` is non-empty on C success path
- success-side still reports `marked=0`
- failure-side can still report `marked=1`
- however, the newer `pre_mark_reason` counters are not yet trustworthy:
  - success probes show `mode=capsules` but `capsules=0`
  - while matching `bookkeeping_probe` still shows `remembered=1`
  - this strongly suggests the pre-mark helper is being neutralized by the current Room main-thread guard and is not yet giving reliable reason counts

Current conclusion:

- success-side opaque bookkeeping is still broken
- queue-time bookkeeping is correct
- the current blocker is no longer "whether rows exist" but "why delivered marking returns 0"
- before patching DAO success logic, the pre-mark instrumentation itself must be made reliable enough to distinguish:
  - `missing_capsule`
  - `already_delivered`
  - `failed_final`
  - `eligible`

### DTN Control Baseline and Chunk-Sizing Split (2026-05-22)

Status:

- The temporary DTN transport baseline should remain at:
  - `3f90488` `Skip held rebuild loop for DTN control writes`
- A separate investigation branch has been split from that baseline:
  - `feature/dtn-chunk-sizing-investigation`

Why `3f90488` is the current baseline:

- Physical rounds after later DTN write-path experiments showed worse transport stability than the reverted `3f90488` baseline.
- The reverted baseline still shows incomplete DTN control progression, but it keeps mesh app transport materially healthier:
  - app-level delivery and ACK remain usable;
  - the catastrophic queue-growth pattern seen later is absent;
  - transport debugging remains readable enough to isolate the next DTN bottleneck.

What was rejected as baseline-worthy after `3f90488`:

- Later experiments that modified DTN control write-path scheduling and rebuild behavior introduced a transport-wide failure mode:
  - repeated `selected=hello`
  - `poll_for_write`
  - `rebuild_inline`
  - pending queue counts rising into the thousands or tens of thousands
- In those rounds, queue collapse and retry storms made DTN conclusions unreliable because transport was already failing at a system level.
- Conclusion:
  - those patches were useful for diagnosis,
  - but they are not safe enough to keep as the temporary branch baseline.

Current DTN state on the `3f90488` baseline:

- DTN control clearly progresses beyond `hello`:
  - `hello recv`
  - `manifest_key sent`
  - `inv_have sent`
- Some manifest-path activity also appears:
  - `manifest_offer`
  - `manifest_accept` staging
- However, the baseline still does **not** reliably reach:
  - `inv_have recv`
  - `inv_want recv`
  - `DTN want load`
  - `DTN data lookup`
  - `DTN data`
  - `DTN ack`

What the latest raw-log review on the reverted baseline showed:

- Transport looked materially healthier than the newer DTN write-path experiments.
- The remaining DTN issue is now narrower:
  - DTN control packets such as `hello` and `inv_have` are being sent with very high chunk counts relative to their payload size.
  - Repeated `DTN control wire start` appears more often than `wire complete`.
  - Peer receive-side progression still stalls before the full `inv_have -> inv_want -> data -> ack` sequence.

Practical branch split:

- Keep `feature/prove-dtn-control-plane-beyond-hello` effectively anchored to the `3f90488` behavior while using it as the stable reference point.
- Use `feature/dtn-chunk-sizing-investigation` for further DTN-specific packetization work so chunk-sizing experiments do not contaminate the temporary transport baseline.

Next investigation focus on the chunk-sizing branch:

- determine why DTN control packets produce excessive chunk counts;
- determine why repeated `wire start` happens without corresponding `wire complete`;
- reduce actual DTN control packet chunk count before making further queue/write scheduling changes.

### Post-cleanup Physical Validation Status (2026-05-22)

Targeted DB cleanup performed on active test devices:

- carrier-side opaque backlog was cleared on one device:
  - `opaque_dtn_capsules`
  - `opaque_dtn_capsule_delivery`
  - `dtn_peer_state`
  - `dtn_sync_cursor`
- relay-seed transport backlog was cleared on the other two active devices:
  - `outbound_batch` rows with `source='relay_seed'` and `status='SENT'`
  - matching `outbound_batch_event`
  - matching `outbound_batch_peer_delivery`
  - `dtn_peer_state`
  - `dtn_sync_cursor`

What this changed:

- physical rounds immediately after cleanup showed materially better app transport:
  - app-level `Delivery` returned to `10/10` in both directions in a clean round;
  - source-side secondary-attempt noise disappeared;
  - opaque carrier counts returned to zero in the DTN report;
  - the test environment became good enough to separate DB residue from current runtime behavior.

Current DTN ceiling after cleanup:

- DTN progression is now confirmed through:
  - `hello`
  - `manifest_key`
  - `inv_have recv`
- Some rounds also reached:
  - `manifest_offer sent`
- But post-cleanup validation has **not** yet stabilized:
  - `inv_want sent`
  - `inv_want recv`
  - `DTN want load`
  - `DTN data lookup`
  - `DTN data`
  - `DTN ack`

Interpretation:

- stale DB backlog was a real source of transport/test noise;
- however, DB cleanup did **not** eliminate the remaining DTN control-plane blocker;
- the current blocker is now runtime behavior after `inv_have recv`, not old backlog residue.

Practical current baseline:

- Keep `3f90488` as the temporary transport baseline.
- Use post-cleanup rounds as the preferred comparison point when testing:
  - whether `inv_have recv` is stable,
  - whether `inv_want` is emitted consistently,
  - and whether anti-loop or packetization behavior is suppressing later DTN control packets.

Current most useful post-cleanup evidence:

- `logs/analysis/dtn_round_20260522_223856_204219975_16408.md`
  - app transport is again strong (`10/10` both directions),
  - queue health is clean on B and only lightly noisy on A,
  - DTN reaches `inv_have recv`,
  - but still does not reliably progress to `inv_want`.

### Physical Validation Findings - 2026-05-23

Two later physical rounds clarified how the current runtime behaves under churn and partition-like movement:

#### Round: `logs/analysis/dtn_round_20260523_145103_742297733_4653.md`

Scenario:

- A and B were separated.
- C moved between them and at times acted as the only reachable intermediate peer.
- This round was used to inspect whether stale-target recovery was clean and whether delivery was rescued by mesh relay, DTN sync, or both.

What the raw logs showed:

- Final user-visible delivery reached `A -> B = 6/6`, but the path was mixed.
- `Stale churn A #2` and `Stale churn A #3` went through ordinary mesh relay:
  - `A -> C -> B`
  - B received them as `source=mesh`.
- `Stale churn A #1` did not complete on the initial mesh relay attempt.
  - It later arrived at B through DTN DATA sync.
  - B received that message as `source=dtn`.

Interpretation:

- The round was a good proof that the system can salvage delivery across churn.
- However, it was not a clean proof that stale-target fallback alone solved the problem.
- Recovery came from a combination of:
  - mesh relay success for some batches,
  - DTN repair/sync for at least one missed batch.
- This round therefore supports a future runtime policy of:
  - mesh-first for fast nearby/relay delivery,
  - DTN-repair-second for missed batches and delayed reconciliation.

Important limit for the stale-target task:

- `drop_retry_exhausted = 0` was good.
- But stale-target-specific evidence remained incomplete:
  - `queued_target_rewritten = 0`
  - no direct proof yet that the preferred-target fallback branch itself engaged and rewrote a stale target.

#### Round: `logs/analysis/dtn_round_20260523_144633_755978410_3942.md`

Scenario:

- A and B stayed far apart for the whole round.
- C physically moved back and forth between A and B and acted as an actual carrier.

What the raw logs showed:

- User-visible delivery reached `A -> B = 6/6`.
- All six user messages reached B as `source=mesh`, not `source=dtn`.
- `DTN Payload Ingest` stayed empty on B for this round.
- C sent A's batch ids onward to B directly, and B later received those same batch ids as ordinary mesh payloads.

Interpretation:

- This round is strong evidence that C acted as a real human carrier:
  - receive from A while near A,
  - carry backlog physically,
  - later forward A's mesh batches to B while near B.
- The delayed `stale churn` latencies in the report are consistent with true store-carry-forward behavior.
- In this round, delivery succeeded through mesh carry/relay via C, not through DTN DATA ingest.

Important limit for the stale-target task:

- This was a strong physical-carrier success round.
- But it still did not directly prove stale-target fallback branch engagement:
  - `queued_target_rewritten = 0`
  - `broadcast_rejected` on A was non-zero
  - no explicit fallback marker yet tied the outcome to commit `3366000`

Current practical conclusion after these two rounds:

- The system already behaves like a hybrid transport:
  - mesh can act as the fast path and also support physical carry/relay through a moving peer;
  - DTN can repair or reconcile batches that mesh relay missed.
- That hybrid behavior is valuable and should be preserved.
- The stale-target re-validation task still needs one narrower piece of evidence:
  - a direct marker that the `preferred_targets_unavailable -> auto selection` fallback branch actually engaged during topology churn.

Current prioritization note for follow-up work:

- A formal `mesh-first, DTN-repair-second` runtime policy should be added.
- However, it should not be pulled ahead of the two nearer runtime tasks:
  - stale-target fallback re-validation under topology churn
  - opaque retry success-side lifecycle / bookkeeping cleanup
- Reason:
  - current rounds already show hybrid behavior in practice,
  - but policy work done too early would mix:
    - routing correctness,
    - duplicate suppression,
    - opaque success-side cleanup,
    - and DTN repair trigger rules,
  - making attribution harder while the lower-level transport/runtime evidence is still being closed.
- Practical sequencing:
  1. finish stale-target fallback proof,
  2. close opaque retry success-side lifecycle enough to reduce retry/duplicate noise,
  3. then codify explicit `mesh-first, DTN-repair-second` rules as a separate policy hardening task.

#### Deferred follow-up: `STALE CHURN A #3` / batch `c8C8eG4W1DXx`

Round reference:

- `logs/analysis/dtn_round_20260523_162411_979640959_16190.md`

What happened:

- `STALE CHURN A #3` was handed from A to C successfully.
- C then attempted to forward batch `c8C8eG4W1DXx` to B.
- That relay never completed.
- A later retried direct delivery to B, but that retry also failed with `queued_no_write_attempt_timeout`.
- Final outcome:
  - `STALE CHURN A #3` never reached B.

What the C-side raw log showed:

- C started forwarding `c8C8eG4W1DXx` to B using default geometry:
  - `83` chunks
  - `10` payload bytes per chunk
- The batch progressed only to:
  - `17/83` chunks
  - `170/825` bytes
- Then it stalled with watchdog snapshots showing:
  - queued chunks still present,
  - `active_packet=false`,
  - no forward progress resuming afterward.

## Opaque Retry Success Lifecycle Closed - 2026-05-24

Status:

- The task `opaque retry / success-side lifecycle cleanup` is now considered closed for the
  current milestone.

What was wrong:

- Failure-side bookkeeping already worked:
  - retry rows could be marked and kept retryable after `send_failed`.
- Success-side bookkeeping did not close the lifecycle reliably:
  - terminal cleanup ran in the wrong execution context,
  - `send_complete` probes on the carrier node could show `remembered=1` but `marked=0`,
  - parent capsule rows could linger after successful forward.

What was changed:

- Success-side opaque cleanup was moved onto the BLE mesh service worker thread.
- Success cleanup now:
  - probes the remembered capsule set for the `(batch_id, device_id)` pair,
  - marks the matching delivery rows terminal,
  - looks up the exact delivered `capsule_id` set,
  - deletes the corresponding parent rows from `opaque_dtn_capsules`,
  - emits explicit diagnostics for:
    - `send_complete`
    - `send_complete_cleanup`
    - `delete reason=success_cleanup`
    - `mark_forwarded`

Primary proof round:

- `logs/analysis/dtn_round_20260524_113610_380430825_16360.md`

What that round proved on carrier node C:

- opaque retry was exercised for real traffic:
  - `opaque_retry_store = 6`
  - `opaque_retry_queued = 14`
  - `opaque_retry_failed = 1`
- success-side lifecycle then closed correctly:
  - `opaque_retry_mark_forwarded = 6`
  - `opaque_retry_send_complete = 6`
  - `opaque_retry_delete = 6`
- success chains now showed:
  - `pre_mark_result ... mk=1`
  - `bookkeeping_probe stage=send_complete`
  - `bookkeeping_probe stage=send_complete_cleanup`
  - `DTN opaque retry delete reason=success_cleanup`
  - `DTN opaque retry mark_forwarded`
  - `DTN opaque retry send_complete`
- the old `pu=main_thread_or_unavailable` signal disappeared from the success path on C;
  the validated success path now logged `pu=none`.

Reinforcement round:

- `logs/analysis/dtn_round_20260524_121124_738342593_19274.md`

What it added:

- repeated success-side closure on multiple batches on C:
  - `opaque_retry_store = 5`
  - `opaque_retry_queued = 10`
  - `opaque_retry_mark_forwarded = 3`
  - `opaque_retry_send_complete = 3`
  - `opaque_retry_delete = 4`
- of the four deletes, three were `reason=success_cleanup` and one was unrelated `ttl_cleanup`,
  which confirms that success cleanup is now distinct and visible in logs.

Current conclusion:

- The opaque retry lifecycle now closes correctly on the carrier node in the validated runtime path:
  - `store -> queued -> retry_failed(optional) -> retry success -> success_cleanup delete`
- Remaining delivery failures in later rounds belong to other runtime concerns
  such as mesh relay timing, stale-target behavior, or scheduler policy;
  they are no longer evidence against opaque success-side bookkeeping.

### Mesh-first, DTN-repair-second validation status - 2026-05-24

Goal for this hardening pass:

- keep mesh as the primary fast path for nearby/direct and carrier relay delivery,
- let DTN repair run only when it still adds value,
- and suppress obvious duplicate DTN reconciliation after a mesh success is already known.

Observed progression:

- `logs/analysis/dtn_round_20260524_125810_478329401_24300.md`
  - validated the scheduler slice:
    - `DTN control queue decision ... reason=mesh_batch_first`
  - stale batches on `C -> B` were sent as contiguous mesh/app bursts instead of being cut apart by DTN control traffic.

- `logs/analysis/dtn_round_20260524_133523_360334243_31337.md`
  - validated first-stage repair suppression:
    - `DTN repair suppress`
  - this proved that one `inv_have -> inv_want` duplicate path on carrier node `C` could be blocked after mesh success.
  - a separate `inv_want ids=4` path still appeared on `B`, but raw-log review showed it behaved more like legitimate `A <-> B` reconcile traffic than a replay of the three stale messages just delivered through `C`.

- `logs/analysis/dtn_round_20260524_143234_385915231_36742.md`
  - validated the tighter peer-scoped policy:
    - receiver-side: if `B` already has payload `X`, `B` no longer asks any peer for `X`
    - sender/carrier-side: if `C` knows peer `B` already has `X`, `C` no longer advertises `X` to `B`
  - runtime outcome in that round:
    - `A -> B = 6/6`
    - `DTN Payload Ingest` remained empty
    - `inv_want_sent = 0` and `inv_want_recv = 0` across `A`, `B`, and `C`
    - a later `inv_have` emitted by `C` had `ids=0`
  - opaque retry success cleanup on `C` remained healthy:
    - `opaque_retry_mark_forwarded = 4`
    - `opaque_retry_send_complete = 4`
    - `opaque_retry_delete = 4`

Current conclusion for this policy work:

- The runtime now behaves much closer to the intended rule set:
  - `B has X` => `B` does not `inv_want X`
  - `C knows B has X` => `C` does not `inv_have X` to `B`
- This suppresses repair only for the relevant payload and peer scope.
- It does not disable DTN globally, and it does not regress:
  - mesh fast path,
  - carrier relay,
  - or opaque success-side lifecycle cleanup.
- If sharper attribution is needed later, add one narrow diagnostic at `buildHaveForPeer()` with:
  - `candidate_ids`
  - `peer_suppressed_ids`
  - `final_ids`

Later refinement and validation:

- `logs/analysis/dtn_round_20260524_223313_320791960_5476.md`
  - under heavy Bluetooth churn, delivery still held at `A -> B = 9/9`
  - but it exposed one remaining gap:
    - after `C` had already forwarded payload `X` to `B` and completed `success_cleanup`,
    - `C` could still later send `inv_want X` back to `A`
  - this showed that peer-scoped `B has X` suppression alone was not enough;
    carrier node `C` also needed a local forward-history rule.

- `logs/analysis/dtn_round_20260524_230928_643751526_8705.md`
  - validated the added rule:
    - if `C` has already `success-forwarded` payload `X` once,
      `C` does not `inv_want X` from any peer for the suppression TTL window
  - runtime outcome:
    - `C` kept healthy opaque lifecycle markers:
      - `opaque_store_saved = 3`
      - `opaque_retry_queued = 6`
      - `opaque_retry_mark_forwarded = 3`
      - `opaque_retry_send_complete = 3`
      - `opaque_retry_delete = 4`
    - `C` showed:
      - `inv_want_sent = 0`
      - `inv_want_recv = 0`
    - `B` also remained clean:
      - `inv_want_sent = 0`
    - user-visible delivery still stayed on the mesh path

Current suppress rule set is now:

1. `local already-have`
   - if a node has already accepted payload `X`, it will not `inv_want X` again
2. `peer already-has`
   - if node `C` knows peer `B` already has `X`, `C` will not `inv_have X` to `B`
3. `local forwarded-success`
   - if carrier node `C` already `success-forwarded X` once, `C` will not `inv_want X` from any peer again during the TTL window
4. `local pending relay`
   - if carrier node `C` is already holding payload `X` in opaque pending-relay state, `C` will not `inv_want X` again while that relay responsibility is still active

Observability refinement added after the forwarded-success rule:

- `DTN repair suppress` on the `inv_want` path now distinguishes:
  - `reason=local_already_have ...`
  - `reason=local_forwarded_success ...`
  - `reason=local_pending_relay ...`

TTL policy update:

- all suppress rules now use a unified `24h` TTL:
  - `local already-have`
  - `peer already-has`
  - `local forwarded-success`
  - `local pending relay`
- reason for choosing `24h`:
  - long enough to suppress duplicate reconcile across the same day of churn/reconnect activity,
  - short enough to avoid turning suppress state into a quasi-permanent anti-repair policy.

Latest validation rounds:

- `logs/analysis/dtn_round_20260525_000513_921443193_15030.md`
  - under alternating Bluetooth restarts, delivery still held at `A -> B = 9/9`
  - raw-log review with BT on/off timeline showed:
    - the remaining `C inv_want ids=1` happened only after later reconnect,
    - after stale carrier success had already completed,
    - and the requested sample mapped to `Seed A #1`, not the stale relay set
  - conclusion:
    - stale-payload suppression held,
    - and that want path was not evidence against `local pending relay`

- `logs/analysis/dtn_round_20260525_000513_921443193_15030.md`
  - also exposed the earlier gap that led to the `local pending relay` rule:
    - `C` could still ask for payloads again before success-cleanup state existed,
    - even though `C` had already stored them for relay responsibility

- `logs/analysis/dtn_round_20260525_002443_794654973_16257.md`
  - in the constrained 3-device small-room BT on/off scenario:
    - `A -> B = 6/6`
    - `B already-have` remained clean (`inv_want_sent = 0`)
    - `forwarded-success` suppression engaged explicitly on `C`
    - the only `C inv_want` happened early in the round before the later stale set had entered pending relay
  - conclusion:
    - no new counterexample against `local pending relay`

- `logs/analysis/dtn_round_20260525_000513_921443193_15030.md`
  - when `C` later received `inv_have ids=8`, it suppressed `7` and only `inv_want`ed `1`
  - the remaining id mapped to `Seed A #1`, which looked like a legitimate non-stale reconcile path

- `logs/analysis/dtn_round_20260525_000513_921443193_15030.md`
  - after adding `local pending relay`, the earlier stale duplicate path is no longer the dominant runtime issue;
    what remains is narrower non-stale reconcile that should be judged separately from stale carrier suppression

Current conclusion for `mesh-first, DTN-repair-second` hardening:

- the current policy is considered validated for the present milestone:
  - mesh stays the user-visible fast path,
  - opaque carrier relay and success cleanup remain healthy,
  - duplicate DTN reconcile is now suppressed across four layers:
    - `local already-have`
    - `peer already-has`
    - `local forwarded-success`
    - `local pending relay`
- remaining duplicate or reconcile traffic seen in later rounds should now be treated as:
  - either non-stale repair,
  - or a narrower follow-up outside the core stale-suppress policy gate.

Execution-priority checklist from the current state:

P1

- [ ] Fix mobile relay ingest to support HTTP `202 queued`
- [ ] Decide policy for relay `REQUEST_SIGNATURE_ENABLED=1`
- [ ] If signed mode is chosen, implement request signing in the app

P2

- [ ] Finish cleanup jobs / background workers
- [ ] Verify retention and tombstone cleanup behavior
- [ ] Re-check cleanup behavior after restart / reconnect / Bluetooth churn

P3

- [ ] Add Android unit / integration tests for the four suppress rules:
  - `local already-have`
  - `peer already-has`
  - `local forwarded-success`
  - `local pending relay`
- [ ] Add test coverage for `mesh_batch_first` scheduler behavior
- [ ] Add combined coverage for opaque retry success cleanup plus pending-relay suppress

P4

- [ ] Add top-level capsule metadata `sid + rid`
- [ ] Re-check anti-loop behavior and path inference after metadata hardening

P5

- [ ] Run duplicate-storm / noisy-mesh stability rounds
- [ ] Validate battery / crash / restart behavior
- [ ] Re-open stale-target fallback validation near release

### DTN Session Arbitration and Outstanding-Want Follow-up - 2026-05-26

Current runtime conclusion after the latest physical rounds:

- `A -> C -> B` delayed relay is functionally working end-to-end, but relay latency and per-peer lane stability are still weak.
- The strongest recent failure mode is no longer duplicate stale reconcile.
- The dominant failure mode is now:
  - peer-session lane contention,
  - especially when a relay/data burst and a large DTN control backlog share the same peer session.

What was added before the session-queue patch:

- `DtnSyncEngine` now tracks `outstanding want` by `event ID`, not by `batchId` or `capsuleId`.
- local outstanding wants can survive across later peer encounters inside runtime memory.
- after `hello`, a node can now advertise:
  - `i_have`
  - and also a local `i_want` if it already knows a missing `event ID` set.
- `want` traffic is now treated as higher priority than backlog offer traffic:
  - explicit `INV_WANT` should be served before broader `manifest_offer` / backlog sync.

Why this was needed:

- In real field-style rounds, a node can create data while alone, then meet a peer minutes later.
- In that case the source must advertise backlog on re-encounter:
  - effectively `A says: I have {eventIds}`
- Separately, a peer that already knows it is missing some `event IDs` should be able to say:
  - `I want {eventIds}`
- The protocol must not rely on `batchId` or `capsuleId` for this memory:
  - those are transport/container identities
  - `event ID` is the stable application payload identity

Latest runtime issue that triggered the new scheduler work:

- In the delayed relay round where `A` eventually delivered `DTN GAP A #1..#3` to `B` through `C`,
  the actual blocker was not `A -> C`.
- `C -> B` started and reached `3/11 chunks`, then stalled.
- Raw-log analysis showed:
  - `C` lost progress callback after chunk 3,
  - `C` then hit `stalled_write`,
  - and `BleMeshTransport` hard-reset that peer lane with `transport_stop`.
- Deeper review of `B` during the same window showed:
  - `status=133`,
  - `device_queued_or_held`,
  - large DTN control backlog,
  - and peer/address rotation.
- Conclusion:
  - the lane was being overloaded by concurrent control/data work for the same peer.

New runtime policy added to address that failure mode:

- `per-peer session task queue`
- `one active task at a time`
- disconnect / cleanup drops session-local queue state
- global DTN memory remains intact:
  - outstanding want by `event ID`
  - suppress state
  - opaque retry state

Implemented behavior:

- `DtnSyncEngine` now creates a peer-session task queue keyed by:
  - `peerId + gid + sessionId`
- only one control task is emitted at a time for that peer session.
- added priority order:
  1. `DATA`
  2. `ACK`
  3. `INV_WANT`
  4. `INV_HAVE`
  5. `MANIFEST_ACCEPT`
  6. `MANIFEST_KEY`
  7. `MANIFEST_OFFER`
- `BleMeshTransport` now notifies `DtnSyncEngine` on peer disconnect/cleanup so that:
  - session-local queued tasks are dropped immediately
  - later reconnect starts with a clean peer-session queue

What this patch is intended to prevent:

- a peer receiving app-data relay while also trying to flood:
  - `inv_have`
  - `manifest_key`
  - `manifest_offer`
  on the same short-lived session
- the expected effect is narrower:
  - less peer-session lane contention
  - less chance of stalled receive/write after a few chunks
  - better completion odds for app-data relay already in flight

Current verification state of the scheduler patch:

- Unit coverage updated and passing:
  - `./gradlew :transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest --rerun-tasks`
- The scheduler patch is committed on:
  - branch `feature/dtn-peer-session-task-queue`
  - commit `2560e05` `Add per-peer DTN session task queue`
- Follow-up hardening after that commit:
  - `INV_WANT` is now gated by peer-proven overlap from current `INV_HAVE`, and runtime session queues now advance via a real DTN control send-complete hook instead of test-only completion calls.

Current milestone interpretation:

- suppress-policy hardening is no longer the main open runtime risk.
- the current DTN/relay focus has shifted to:
  - peer-session arbitration,
  - relay lane stabilization,
  - and delayed-delivery completion under physical churn.
- the broader BLE mesh runtime issue is now orchestration, not protocol correctness:
  - local mesh runtime should remain independent from cloud relay / entitlement / background lifecycle churn,
  - app-data already in flight must outrank broad DTN control backlog on the same peer session,
  - and stalled recovery should prefer narrower retry/grace before full transport reset when a peer is still nearby.
- practical reading from recent physical rounds:
  - `A -> C -> B` delivery is functionally proven,
  - but completion can still degrade when peer-session contention and aggressive `stalled_write -> transport_stop` recovery interrupt short relay windows.

Related files for this task:

- Transport / runtime:
  - [android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt](/home/truong/projects/disaster-app/android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt)
  - [android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt)
  - [android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt)

- Opaque relay persistence / DAO layer:
  - [android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt](/home/truong/projects/disaster-app/android/transport/src/main/java/app/peermesh/transport/ble/OpaqueRelayStore.kt)
  - [android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/dtn/RoomOpaqueRelayStore.kt)
  - [android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/dao/OpaqueDtnCapsuleDao.kt)

### Relay Runtime Decouple Snapshot - 2026-05-27

Implementation status for the first runtime-stabilization priority:

- Goal:
  - keep BLE mesh-local runtime independent from cloud relay lifecycle churn, entitlement refresh noise, and app foreground/background orchestration
- Result:
  - relay lifecycle decisions are now centralized under `RelaySyncLifecycleCoordinator`
  - app/UI/process call sites no longer scatter direct relay `start/stop/enqueue/cancel` decisions
  - relay network transitions are debounced before service state changes
  - app-resume entitlement refresh is now throttled instead of firing on every resume
  - resume-worker flow is separated from explicit user-requested foreground sync semantics
  - relay service now prefers `pause in place` over rapid `start -> stop -> resume` loops on transient network loss
  - paused relay no longer keeps traffic timers and notification churn alive unnecessarily
  - relay status broadcasts are deduped so success-path push/pull activity does not spam process-wide status updates

Commits implementing this branch of work:

- `19e114a` `Centralize relay lifecycle orchestration`
- `220ae15` `Reduce relay stack orchestration churn`
- `82a16fa` `Debounce relay lifecycle transitions`
- `6365ffc` `Pause relay service in place on transient loss`
- `6a97885` `Reduce relay status broadcast noise`
- `262cf31` `Quiet relay runtime wakeups`

Current interpretation:

- this work does not modify core BLE mesh transport directly
- it reduces cloud-relay / background-service process noise around the mesh runtime
- after this decouple pass, the next primary runtime risk is no longer relay orchestration churn
- the next primary runtime risk is peer-session contention inside the mesh transport itself:
  - app-data already in flight vs DTN control backlog
  - delayed relay stalls after a few chunks of progress
  - `stalled_write -> transport_stop` being too aggressive once lane progress has already started

- Report / log tooling:
  - [scripts/debug-kit/mesh_debug.sh](/home/truong/projects/disaster-app/scripts/debug-kit/mesh_debug.sh)

Do not start with refactors. First add observability that exposes the row lifecycle directly.

Add or keep markers with enough detail to reconstruct the path:

- `DTN opaque retry queued_state`
- `DTN opaque retry bookkeeping_probe`
- `DTN opaque retry pre_mark_probe`
- `DTN opaque retry pre_mark_result`

If these markers already exist in part, extend them. They must log at least:

- `batch_id`
- `capsule_id`
- `device_id`
- `payload_sha256` if available
- remembered capsule count for the batch
- queued row count before mark/delete
- marked row count
- deleted row count
- terminal state / reason

The instrumentation should make it obvious whether the cleanup path is:

- missing rows,
- missing remembered capsule ids,
- mismatching device ids,
- or skipping because rows are already terminal.

### Suggested implementation order

1. **Instrument `markDeliveryQueued(...)`**
   - log exact row(s) queued for:
     - `batch_id`
     - `capsule_id`
     - `device_id`
   - include whether this was a new queued row or an update to an existing row

2. **Instrument success-side probe before delete**
   - in or just before `clearOpaqueCapsulesForBatch(...)`
   - log:
     - remembered capsule ids for the batch
     - candidate rows found in DB
     - candidate rows filtered by `device_id`

3. **Instrument success-side result**
   - after delete/terminal mark
   - log:
     - how many rows were marked/deleted
     - how many rows remained
     - if zero changed, why

4. **Instrument failure-side path**
   - in `handleOpaqueRetrySendFailed(...)`
   - log the same identifiers so failed paths can be compared with successful ones

5. **Only after logs are trustworthy, change lifecycle logic**
   - do not guess at cleanup behavior before the probes show the exact mismatch

### Round shape needed after instrumentation

The validation round for this task should **exercise opaque retry success**, not just ordinary mesh relay.

The round should aim for:

- a carrier node that stores opaque capsule data,
- later forwards that capsule successfully,
- then triggers the terminal success callback for the associated batch.

Success criteria for the round:

1. `opaque_retry_queued > 0`
2. `opaque_retry_mark_forwarded > 0` or equivalent terminal-success marker
3. `opaque_retry_send_complete > 0`
4. `opaque_retry_delete > 0`
5. DB inspection after the round shows the matching rows are gone or terminalized as intended

If the round succeeds at user-visible delivery but the opaque retry markers stay zero, that round
does not validate this task.

### Definition of done

This task is done only when all of the following are true:

1. A successful opaque forward path can be traced with concrete identifiers from:
   - queued row
   - in-flight batch
   - success callback
   - delete/terminal cleanup
2. The success-side markers and DB state agree with each other.
3. Failure paths leave rows retryable when appropriate.
4. Reporting no longer undercounts opaque terminal success because of missing bookkeeping.

### What not to do first

- Do not start by changing retry policy thresholds.
- Do not start by changing carrier selection or DTN handshake logic.
- Do not assume a sender-side mesh fix will resolve opaque bookkeeping.
- Do not close this task using only user-visible delivery evidence.

The task is specifically about **success-side opaque lifecycle correctness**, not just whether a
message eventually arrived.

Technical interpretation:

- This was not a stale-target-selection failure.
- It was a relay write-path failure on `C -> B`.
- The important precondition was that the batch was sent with default chunk geometry instead of being re-chunked to the current MTU-ready geometry before real write progress began.
- Successful neighboring rounds showed the healthier pattern:
  - chunk geometry update first,
  - then transmission in roughly `5-8` chunks instead of `83`.

Deferred solution direction:

- Keep the conservative default chunk geometry as a safe fallback.
- Do not allow large app/relay batches to begin real transmission on default geometry if the link is already far enough along to negotiate or apply a better chunk size.
- For large relay/app batches, add a dedicated gate:
  - if the batch is still using default geometry,
  - and the current link can support fewer chunks for the same payload,
  - then defer briefly or rebuild the queued group once before the first real write.
- Practical policy target:
  - one-shot rebuild before first real write for large relay/app batches,
  - rather than allowing `80+` tiny chunks to start and then stall mid-flight.

Status:

- This is intentionally deferred.
- It should become its own follow-up task after stale-target fallback re-validation is closed enough to avoid mixing root causes.

### Internet Relay Contract Review - 2026-05-23

App-side and relay-server-side contract was re-checked for the current internet relay path.

Files reviewed:

- app:
  - `android/core/src/main/java/app/peermesh/core/relay/RelayModels.kt`
  - `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt`
  - `android/data/src/main/java/app/peermesh/data/relay/RelayEventMapper.kt`
  - `android/data/src/main/java/app/peermesh/data/relay/RelayPushManager.kt`
  - `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
- server:
  - `server/relay/src/routes.js`
  - `server/relay/src/storage.js`
  - `server/relay/src/validation.js`
  - `server/relay/src/config.js`

What is already aligned:

- The basic ingest/fetch payload schema matches:
  - app sends `RelayEvent(eid, gid, ts, exp, body, sig)`
  - server validates the same fields on ingest
  - server fetch returns `events` + `next_cursor`
  - app pull path reads that shape correctly
- `eid` is compatible:
  - server requires UUID-like `eid`
  - app derives deterministic UUID-shaped relay ids via `RelayEventIdFactory`
- `gid` validation is compatible:
  - server accepts base32/base64url
  - app uses compatible gid strings
- `body` shape is compatible:
  - app sends serialized `EventEnvelope`
  - server stores it as opaque JSONB
  - app pull path decodes it back to `EventEnvelope`

Important current mismatches:

1. `202 queued` ingest response is not handled correctly by the app.

- Server behavior:
  - when ingest retry queue is enabled and the relay chooses to queue the request instead of fully processing it immediately, `/v1/events:ingest` can return:
    - HTTP `202`
    - body like `{ "status": "queued", "queued": <n> }`
- App behavior:
  - `RelayHttpClient.ingest()` currently treats any `2xx` as `RelayIngestResponse`
  - `RelayIngestResponse` expects only:
    - `accepted`
    - `rejected`
  - therefore a `202 queued` body can decode-fail and be treated as a client-side ingest failure

Practical impact:

- relay server may consider the request accepted-for-retry
- app may incorrectly consider the same request failed

2. Request-signature mode on the relay server is not currently supported by the app.

- Server behavior:
  - when `REQUEST_SIGNATURE_ENABLED=1`, ingest/fetch routes require signature headers and timestamp validation
- App behavior:
  - `RelayHttpClient` currently does not add:
    - `x-relay-signature`
    - `x-relay-timestamp`
  - and does not compute canonical signed request payloads

Practical impact:

- app and server interoperate only in unsigned/public relay mode today
- if request-signature mode is enabled on the relay server, mobile app push/pull will fail with `401 invalid_signature`

Current practical conclusion:

- Data schema is already compatible enough for app <-> relay communication.
- The remaining gap is not event schema; it is protocol/policy handling:
  - ingest `202 queued`
  - optional request-signature enforcement

Recommended follow-up order:

1. Patch app relay ingest handling to support explicit `202 queued` semantics.
2. Decide product policy for relay request signatures:
  - either document that current mobile app only supports unsigned/public relay mode
  - or implement request-signature headers in the app before enabling signed mode in deployment.

### Backlog - Relay Server Refactor To Match App Contract (2026-05-28)

Decision:

- Treat the mobile app relay contract as the source of truth.
- Refactor server behavior around that contract instead of pushing app-side schema/protocol changes first.

App contract that server must preserve:

- ingress payload:
  - `RelayIngestRequest { events: RelayEvent[] }`
- event shape:
  - `RelayEvent { eid, gid, ts, exp, body, sig }`
- `body` semantics:
  - opaque serialized `EventEnvelope`
  - no extra server-required top-level fields inside `body`
- fetch response:
  - `RelayFetchResponse { events, next_cursor }`
- current mobile client behavior:
  - no request-signature headers
  - expects normal ingest success to decode as `RelayIngestResponse { accepted, rejected }`

Server-side refactor backlog:

1. Remove the current mobile-path incompatibility with `REQUEST_SIGNATURE_ENABLED=1`.

- Current problem:
  - server can hard-require `X-Relay-Signature` and `X-Relay-Timestamp`
  - current mobile `RelayHttpClient` does not send them
- Refactor target:
  - make `/v1/events:ingest` and `/v1/events:fetch` compatible with current app contract by default
  - keep stricter request-signature enforcement only behind an explicit mode that is not applied to the current mobile path
- Practical options to evaluate:
  - exempt mobile relay routes from request-signature enforcement
  - add a separate server mode for unsigned mobile relay clients
  - reserve signed mode for server-to-server/federation or a future app revision

2. Make queued ingest responses compatible with the app contract.

- Current problem:
  - server may return `202 { status: "queued", queued: n }`
  - app success path currently expects `RelayIngestResponse { accepted, rejected }`
- Refactor target:
  - server should return an app-compatible success body for queued ingest
- Candidate server-side shapes:
  - normalize queued responses into a `RelayIngestResponse`-compatible body
  - or introduce a versioned relay response contract and keep current app routes on the legacy-compatible shape

3. Remove the server assumption that replay protection depends on `body.nonce`.

- Current problem:
  - server replay logic looks for top-level `event.body.nonce`
  - app `EventEnvelope` does not expose that field
- Refactor target:
  - base replay/duplicate semantics on fields that are actually present in app payloads:
    - `eid`
    - `sig`
    - or a canonical hash of the full relay event
- Constraint:
  - do not require app-side envelope schema changes just to satisfy server replay checks

4. Freeze `body` as opaque envelope storage/transport on the server.

- Current rule to preserve:
  - server stores and returns `body` without interpreting app event-type internals
- Follow-up work:
  - audit validation/storage/fetch code to ensure no hidden assumptions are added later about app event internals inside `body`
  - document that server compatibility target is `EventEnvelope`, not raw BLE DTN control packets

5. Separate cloud relay protocol concerns from BLE DTN carrier concerns in server docs and code comments.

- Current confusion risk:
  - "DTN data" can be misread as BLE carrier/control payloads
- Refactor target:
  - document clearly that the internet relay server handles app/cloud relay envelopes, not:
    - `manifest_offer`
    - `carrier_data`
    - opaque BLE capsules
    - raw DTN control packets

6. Add server tests that use app-shaped fixtures as the canonical compatibility suite.

- Add fixtures shaped exactly like app-produced `RelayEvent`
- Cover at least:
  - ingest success
  - fetch success
  - duplicate ingest
  - expired event rejection
  - queued ingest compatibility
  - unsigned mobile-client compatibility mode

7. Only after the above, decide whether app-side request signing is still worth implementing.

- This becomes a later security/product decision.
- It should not block server compatibility with the current mobile relay contract.

### Handoff - Relay Server Refactor For ChatGPT Web (2026-05-28)

Purpose:

- Hand this server refactor task off cleanly to ChatGPT web.
- Scope is the internet/cloud relay server only.
- Do not mix this task with BLE mesh DTN carrier work.

What ChatGPT should assume:

- The mobile app relay contract is the source of truth.
- Server must be refactored to match the app contract.
- Do not start by changing app payload schema.
- Do not start by changing BLE DTN control/data models.

Current app-side data model and behavior:

1. Relay payload model

- File:
  - `android/core/src/main/java/app/peermesh/core/relay/RelayModels.kt`
- Current model:
  - `RelayEvent`
    - `eid: String`
    - `gid: String`
    - `ts: Long`
    - `exp: Long`
    - `body: JsonElement`
    - `sig: String`
  - `RelayIngestRequest`
    - `events: List<RelayEvent>`
  - `RelayIngestResponse`
    - `accepted: List<String>`
    - `rejected: List<RelayRejectItem>`
  - `RelayFetchResponse`
    - `events: List<RelayEvent>`
    - `next_cursor: String?`

2. What `body` actually is

- Files:
  - `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`
  - `android/data/src/main/java/app/peermesh/data/relay/RelayEventMapper.kt`
- `body` is an opaque serialized `EventEnvelope`
- `EventEnvelope` currently contains:
  - `v`
  - `gid`
  - `mid`
  - `ts`
  - optional `ttl`
  - optional `dtn`
  - `enc`
  - optional `sig`
- There is no top-level `nonce` field in `EventEnvelope`

3. How app builds relay events

- File:
  - `android/data/src/main/java/app/peermesh/data/relay/RelayEventMapper.kt`
- Current behavior:
  - parse local `EventCore`
  - encrypt/sign into `EventEnvelope`
  - derive relay `eid` from `RelayEventIdFactory.fromEventId(core.id)`
  - set `sig = envelope.sig.mac`
  - set `body = EventEnvelope as JsonElement`

4. How app talks to server

- File:
  - `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt`
- Ingest:
  - POST `/v1/events:ingest`
  - sends JSON body only
  - does not send:
    - `X-Relay-Signature`
    - `X-Relay-Timestamp`
- Fetch:
  - GET `/v1/events:fetch?gid=...&cursor=...&limit=...`
  - does not send request-signature headers
- Success decode assumption:
  - any `2xx` ingest response is decoded as `RelayIngestResponse`

5. How app reads fetch results

- File:
  - `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
- Current behavior:
  - treat each fetched `RelayEvent.body` as `EventEnvelope`
  - decode `body` directly back into `EventEnvelope`
  - feed that into app ingest path

Current server-side behavior and issues:

1. Ingest/fetch shape is mostly aligned with app

- Files:
  - `server/relay/src/validation.js`
  - `server/relay/src/storage.js`
  - `server/relay/src/routes.js`
- Current behavior:
  - server accepts `events[]`
  - validates `eid/gid/ts/exp/body/sig`
  - stores full event row
  - fetch returns `events` and `next_cursor`

2. Request-signature enforcement is incompatible with the current app

- Files:
  - `server/relay/src/routes.js`
  - `server/relay/src/request_signatures.js`
  - `server/relay/src/config.js`
  - `server/.env`
- Current state:
  - `server/.env` currently sets `REQUEST_SIGNATURE_ENABLED=1`
  - server enforces signature on:
    - `POST /v1/events:ingest`
    - `GET /v1/events:fetch`
  - app does not send the required headers
- Result:
  - current server config can reject current mobile app with `401 invalid_signature`

3. Queued ingest response shape is incompatible with the current app

- File:
  - `server/relay/src/routes.js`
- Current behavior:
  - under retry-queue path, ingest may return:
    - HTTP `202`
    - `{ status: "queued", queued: <n> }`
- Problem:
  - app treats all `2xx` responses as `RelayIngestResponse`
  - this body is not `RelayIngestResponse`

4. Replay/nonce logic is based on a field the app does not send

- File:
  - `server/relay/src/storage.js`
- Current behavior:
  - replay cache checks `event.body.nonce`
- Problem:
  - app `EventEnvelope` does not have top-level `nonce`
  - this replay logic does not match the actual app payload model

5. Server currently stores `body` opaquely, which is good and should be preserved

- Files:
  - `server/relay/src/storage.js`
  - `server/relay/src/validation.js`
- Important constraint:
  - server should not start interpreting app event-type internals inside `body`

Required refactor goals for ChatGPT web:

1. Make server fully compatible with current app ingest/fetch behavior.
2. Keep app-side relay schema unchanged.
3. Keep `body` opaque as app-produced `EventEnvelope`.
4. Resolve server-side protocol mismatches:
  - request-signature enforcement on mobile path
  - `202 queued` response shape
  - `body.nonce` replay assumption
5. Add compatibility tests using app-shaped relay fixtures.

Recommended implementation order:

1. Decide and implement unsigned-compatible mobile relay mode on server.
2. Normalize queued ingest success responses so app can consume them safely.
3. Replace `body.nonce` replay logic with app-compatible semantics.
4. Add/expand tests using exact app `RelayEvent` fixture shape.
5. Update server docs to distinguish cloud relay envelopes from BLE DTN carrier/control payloads.

Files to provide to ChatGPT web:

App-side contract and behavior:

- `android/core/src/main/java/app/peermesh/core/relay/RelayModels.kt`
- `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`
- `android/core/src/main/java/app/peermesh/core/id/RelayEventIdFactory.kt`
- `android/data/src/main/java/app/peermesh/data/relay/RelayEventMapper.kt`
- `android/data/src/main/java/app/peermesh/data/relay/RelayPushManager.kt`
- `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
- `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt`

Server-side code to refactor:

- `server/relay/src/routes.js`
- `server/relay/src/validation.js`
- `server/relay/src/storage.js`
- `server/relay/src/request_signatures.js`
- `server/relay/src/config.js`
- `server/.env`
- `server/.env.example`

Helpful docs/context files:

- `server/relay/docs/relay_production_guide.md`
- `docs/tests/relay_security.md`
- `docs/milestones/Phase_3_Task_List.md`
- this development note section

What not to do:

- Do not redesign app relay payload schema.
- Do not convert server to understand BLE DTN control packets.
- Do not mix this refactor with mesh peer-session arbitration or first-contact stabilization.
