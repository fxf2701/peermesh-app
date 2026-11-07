# M2D3 compatibility fixture manifest

## Provenance

The six `legacy_*_v0.json` files are **manually transcribed** EventCore payloads derived from the historical
canonical writer in Git commit `3f0f4b8ed4ec4c0df4dfbcbbe6e5f0c1bf88cdce`, immediately before
M2A commit `f7e7bfc4`. The source is
`android/core/src/main/java/app/peermesh/core/factory/EventFactory.kt`; its `mapMarkerData`,
`mapAreaData`, ADD/UPD and DEL functions define the exact field set. Evidence classification:
**historical source-derived manual fixtures**. These are not captures from an APK or the network,
and therefore do not prove historical binary interoperability.

The four `domain_v1_*_for_old_peer.json` files are immutable domain-aware canonical examples
projected through the historical known-field contract. Evidence classification:
**documented contract-modeled old-peer projection**. They prove additive known-field readability,
not execution by an old APK.

`unknown_dedicated_action_for_old_peer.json` is a current dedicated action used with a
**contract-modeled** subset of the historical validator behavior. The model is hand-written and
is not historical validator/parser execution. The pre-M2 source documents the relevant
`EventTypes.isMap` acceptance and `else -> false` validation branch; M2D3 models only the needed
canonical, chat, and unknown-action outcomes. It proves the modeled reject-without-mutation seam,
not forwarding behavior of a released binary.

Before adding any fixture, inspect repository history, prior release artifacts, captured envelopes,
or accepted pre-M2 contract tests. Record provenance and exact historical shape here.

## Canonical legacy fixtures

- `legacy_marker_add_v0.json`
- `legacy_marker_update_v0.json`
- `legacy_marker_delete_v0.json`
- `legacy_area_add_v0.json`
- `legacy_area_update_v0.json`
- `legacy_area_delete_v0.json`

All intentionally omit `map_domain_v`, `item_category`, and `information_source`. Stable IDs,
actors, wall clocks, Lamport clocks, target IDs, confidence, geometry, removal fields and tombstone
events are literal and guarded by `M2D3CompatibilityFixtureIntegrityTest`.

### Historical source field matrix

This matrix is manually derived from `EventFactory.mapMarkerData`, `mapAreaData`, ADD/UPD, and
DEL in commit `3f0f4b8ed4ec4c0df4dfbcbbe6e5f0c1bf88cdce`.

| Shape | Required historical fields | Optional historical fields | M2-only fields absent |
|---|---|---|---|
| Marker ADD/UPD | `marker_id`, `layer_id`, `lat`, `lon`, `confidence` | `radius_m`, `label`, `note`, `kind`, removal fields | `map_domain_v`, `item_category`, `information_source` |
| Marker DEL | `marker_id` | none | all M2 domain fields |
| Area ADD/UPD | `area_id`, `layer_id`, `geometry`, `confidence` | `label`, `note`, `kind`, removal fields | `map_domain_v`, `item_category`, `information_source` |
| Area DEL | `area_id` | none | all M2 domain fields |

### Immutable fixture hashes

SHA-256 is calculated over the checked-in UTF-8 file bytes. The integrity test validates every
entry before interpreting fixture contents.

| Fixture | SHA-256 |
|---|---|
| `legacy_marker_add_v0.json` | `e5ae2f5e13e06aae8a699541e8f397edff9b2d1c8144c25cb5e18bd94f6b5a95` |
| `legacy_marker_update_v0.json` | `a2fc8e6dc5dc898e2fe4958536180624f1be0dbcd68718fb8ba768321faf00b2` |
| `legacy_marker_delete_v0.json` | `55140997a1e3b7af08fa04a01697823306c90c983bf442b835c32d7dcbbad5ae` |
| `legacy_area_add_v0.json` | `0d801fbddd541cbf15bfabf99564b3848014de6e1487928790416275c03ec327` |
| `legacy_area_update_v0.json` | `9e4fbe201e799d105faff33762a12a8e8aed4d59238864cf01b8561bfbf6765a` |
| `legacy_area_delete_v0.json` | `3675e322348c2e43bfcfb6ef1e77adff2017ca52f8cfc82dd16defc3878baef2` |
| `domain_v1_marker_add_for_old_peer.json` | `843cd87a54983b2c751ea1d89d3af3e7b8a825cebbb6186e7e4bca40a50457d7` |
| `domain_v1_marker_update_for_old_peer.json` | `40d89c409e979a5383326948759c1e92b1d16a9eab81e09f46e2f774124e8482` |
| `domain_v1_area_add_for_old_peer.json` | `bd311623ac96ee551021bab9ac23e8e7658b47a4fdeb1a66076d333f58ffe9d1` |
| `domain_v1_area_update_for_old_peer.json` | `81544753b3ae7398dafcf23fe1c06d06634536e4a580b4295731c627b3976b2e` |
| `unknown_dedicated_action_for_old_peer.json` | `f7f5142b6591b85beffc1c41031067a496c43947350432b282ae28bbe300cb14` |

## Planned legacy-target scenarios

Legacy target scenarios must be built from canonical legacy event histories or historical database
seeds with documented provenance. They are not fabricated `map_item_domain_state` snapshots and
must not bypass `MapItemKindCatalog` or `RoomMapEventProcessor` merely to make tests pass.

Suggested scenario folders:

- `legacy_situation_marker_active/`
- `legacy_situation_area_active/`
- `legacy_coordination_marker_active/`
- `legacy_coordination_area_active/`
- `legacy_unknown_kind_marker/`
- `legacy_deleted_target/`

Each scenario should contain the minimum historical canonical event sequence plus an
`expected_state.md` or manifest entry describing the production-derived target state.

## Old-peer projection fixtures

- `domain_v1_marker_add_for_old_peer.json`
- `domain_v1_marker_update_for_old_peer.json`
- `domain_v1_area_add_for_old_peer.json`
- `domain_v1_area_update_for_old_peer.json`
- `unknown_dedicated_action_for_old_peer.json`

The known-field projection ignores only `map_domain_v`, `item_category`, `information_source`,
`owner_edit_until`, and `interaction_until`. Required legacy canonical fields remain present.

## Mixed APP-batch scenarios

Mixed batches are assembled in tests by the production envelope encryption and APP-batch signing
path from the immutable event fixtures above. They are deliberately not static ciphertext: the
compatibility premise is immutable while the accepted M2D1 codec/crypto path owns transport shape.

## Required metadata per fixture

| Field | Required content |
|---|---|
| Provenance | Historical source, release/tag, captured payload, or accepted contract |
| Evidence strength | Historical binary/parser/capture or contract-modeled fixture |
| Sender contract | Legacy canonical, domain-aware canonical, or dedicated action |
| Intentionally absent fields | For example `map_domain_v`, `item_category`, `information_source` |
| Expected new-peer outcome | Decode/process/persist behavior |
| Expected old-peer outcome | Known-field projection, reject, drop, or ignore behavior |
| Expected persistence effects | Canonical, legacy history, domain state, and action history |
| Forbidden reinterpretation | Confidence→source, contribution→action, removal→lifecycle, etc. |
| Stable identity | Event ID, target ID, actor DID, wall clock, Lamport clock |
