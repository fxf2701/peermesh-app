# M2D3 — Mixed-Version Compatibility Contract

## Status

**Externally reviewed and accepted. M2D3 is ready to close.** The focused verification record is
**43 JVM tests and 47 connected M2D3 tests**, all passing. Historical claims below are limited to
source-derived manual fixtures and contract models; no historical APK, parser execution, captured
traffic, or old-peer batch-loop execution was available.

## Contract interpretation

- A new peer accepts historical marker and area ADD/UPD/DEL shapes without `map_domain_v`,
  `item_category`, or `information_source`. Canonical confidence, owner, notes, votes, removal
  fields, clocks, and tombstones retain legacy meaning.
- A modeled old peer can retain the known fields of domain-aware marker/area ADD and UPD while
  ignoring the additive fields `map_domain_v`, `item_category`, `information_source`,
  `owner_edit_until`, and `interaction_until`.
- A contract-modeled subset of the pre-M2 validator rejects unknown dedicated `MAP.ITEM.*` types.
  The modeled old-peer state is unchanged. An old peer may reject, drop, ignore, or fail to forward
  an action it cannot decode or classify; no historical parser, batch loop, or binary was executed.
- `map_item_votes` and `map_item_actions` coexist without conversion. Contribution confidence and
  notes do not enter confirmation or acknowledgement aggregates, and the two action families do
  not enter each other's aggregates.
- Legacy target category comes from `MapItemKindCatalog`. Type-supported kinds accept the existing
  category-correct action policy; unknown and type-unsupported kinds remain unresolved; deleted
  targets reject actions. Legacy source remains unspecified, and legacy TTL/no-expiry rules remain.
- Existing APP-batch iteration preserves valid siblings and identity. Exact/conflicting duplicate
  semantics remain owned by `RoomMapEventProcessor`; `action_id == event_id` remains invariant.
- Durable rebuild, cross-target relay failure isolation, and public diagnostic stability remain
  M2D4. Capability-gated interaction controls remain M3.

Dedicated actions are never translated into canonical UPD events or legacy contributions. Legacy
contribution history remains visible through legacy behavior only. Mixed peers can therefore have
different action-history visibility while retaining canonical-state safety. This limitation does
not justify changing append-only event or action identity.

## Historical fixtures and provenance

| Fixture/model | Origin and classification | Proves | Does not prove |
|---|---|---|---|
| `legacy_*_v0.json` | Manually transcribed from pre-M2 commit `3f0f4b8ed4ec4c0df4dfbcbbe6e5f0c1bf88cdce`, source-derived field matrix and SHA-256 guarded | Current decode/validation compatibility against historical source shapes | Released APK, captured-network, or mechanically generated historical payload proof |
| `domain_v1_*_for_old_peer.json` | Documented contract-modeled known-field projection using the historical field contract | ADD/UPD known-field retention and independent ignoring of optional extensions | Execution by an old binary or its persistence implementation |
| `unknown_dedicated_action_for_old_peer.json` | Current action plus a contract-modeled pre-M2 validator subset and immutable legacy snapshot | Modeled reject outcome and no reducer/state mutation in that seam | Historical parser execution, relay forwarding, or release behavior |
| Legacy Room target histories | Live canonical events matching the historical source contract | Production catalog derivation, temporal policy, persistence, action acceptance/rejection | Migration of an external field database |
| Schema-51 migration seed | Existing `MapItemM2b1MigrationTest` accepted fixture | Backfill, confidence preservation, type-aware catalog derivation, no action backfill | Live old-peer wire ingest |

Immutable files live in `android/data/src/test/resources/map/m2d3/`; detailed per-fixture metadata is
in `FIXTURE_MANIFEST.md` there.

## Evidence-reuse matrix

Classification: **D** direct accepted evidence, **C** compositional proof with an M2D3 premise,
**N** new focused test, **H** historical fixture/source, **G** remaining gap.

| Roadmap theme/claim group | Earlier evidence owner | M2D3 evidence owner | Class | Gap |
|---|---|---|---|---|
| Legacy marker/area ADD, UPD, DEL readability and missing domain fields | M2A `EventValidatorTest`, `EventFactoryTest`; M2C `RoomMapEventProcessorTest` | `LegacyCanonicalMarkerCompatibilityTest`, `LegacyCanonicalAreaCompatibilityTest`, fixture integrity tests | H+N+C | None |
| Confidence, owner confidence, contribution notes/votes, removal, clocks, tombstones | M2B migration tests; M2C canonical processor/convergence tests | Confidence, contribution, removal, clock/tombstone Room tests | N+C | None |
| No confidence→source, contribution→confirmation/acknowledgement, removal→lifecycle reinterpretation | M2A compatibility policy; M2B independent schema; M2C reducers | Preservation and coexistence Room tests | N+C | None |
| Old-peer marker/area ADD/UPD known fields and independently ignorable extensions | Historical writer source field contract | `DomainAwareCanonicalOldPeerFixtureTest` | H+N | No old APK/capture; projection is modeled |
| Old-peer canonical retention without domain state, confidence corruption, or contribution creation | Historical canonical field contract | Known-field projection snapshot assertions | H+N | No old database execution; projection is modeled |
| Unknown action reject and marker/area/confidence/contribution/removal safety | Historical source documents relevant branch | `OldPeerDedicatedActionFailureSafetyTest` model | N | Hand-written model; forwarding unavailable |
| Unknown/malformed action sibling isolation, including non-map | M2D1 `EnvelopeReceiverM2D1ContractTest` per-item iteration | Current receiver tests plus modeled old-peer per-item premise | N+C | Old-peer batch loop not executed |
| Vote/action coexistence, notes, confidence, aggregate cross-exclusion, no backfill/rewrite | M2B tables/queries/migration; M2C projection tests | `LegacyNewHistoryCoexistenceM2D3InstrumentationTest` | N+C | None |
| Recognized Situation marker/area and Coordination marker actions | M2C policy/reducer/temporal evidence | Situation/Coordination Room compatibility tests | N+C | No catalog-supported Coordination area kind exists |
| Catalog-only derivation; unknown/type-unsupported unresolved | M2B migration catalog derivation | `UnresolvedLegacyCategoryRejectionM2D3InstrumentationTest` | N | None; M2D3 fixed live-ingest mismatch |
| TTL-derived, no-expiry, deleted target behavior | M2B backfill; M2C temporal/deletion policy | Temporal and deleted-target Room tests | N+C | None |
| Mixed canonical/action APP batches and identity | M2D1 v1/v2 envelope/APP batch tests | Mixed canonical/action batch and transport identity tests | N+C | None |
| Exact/conflicting duplicate behavior | M2D2 Room/transport duplicate tests | Immutable fixture fingerprint premise | C | None |
| Canonical queries and schema 52 | M2B schema tests; M2C query/integration tests | UI-query and schema-52 Room tests | N+C | None |
| Multi-target relay failure and stable public diagnostics | Deferred M2D2 items | None by design | G | M2D4-owned, not M2D3 acceptance |

For compositional rows, the earlier owner proves the production policy or path; the M2D3 test adds
the legacy/mixed-version fixture premise. Together they prove the compatibility claim without
duplicating the earlier full matrix.

## Verification record

- **43 focused JVM M2D3 tests:** fixture provenance and SHA-256 integrity, source-derived decode
  shapes, contract-modeled old-peer known-field projection and validator behavior, per-item batch
  premise, and transport/identity compatibility.
- **47 focused connected M2D3 tests:** Room production-path persistence and processing,
  preservation/coexistence, category enforcement, temporal/deleted behavior, canonical queries,
  and schema 52.
- The roadmap's positive Coordination-area scenario remains unchecked and is **not applicable to
  the current catalog**: it contains no supported Coordination area kind. If such a kind is added,
  it requires a focused positive acceptance test before that roadmap item can be checked.

## Production compatibility fix

Live legacy ADD derivation previously called `MapItemKindCatalog.categoryFor(kind)` without checking
whether the kind supports the target type. Schema-51 migration already performed the type check.
Consequently a marker-only `camp` area became Coordination rather than unresolved. The focused
Room test reproduced this. `RoomMapEventProcessor.insertDomainState` now applies
`supportsMarker`/`supportsArea` before accepting a legacy-derived category. No wire, reducer,
duplicate, identity, batching, persistence schema, migration, or relay behavior changed.
The exact baseline-to-current production hunk is recorded in `M2D3_ROOM_MAP_EVENT_PROCESSOR_DIFF.md`.

## Known mixed-version limitations

- No historical APK, release execution, parser execution, or captured historical traffic was found
  locally.
- Old-peer domain-aware behavior is a documented known-field projection, not binary proof.
- The old-peer validator and batch premises are hand-written contract models informed by historical
  source; they are not historical parser execution, old-peer batch-loop execution, APK execution,
  or network-capture execution.
- Old peers do not gain dedicated-action visibility. Capability-aware UI remains M3.
- The current catalog has no supported Coordination area kind. Therefore no positive
  Coordination-area action acceptance was executed; a focused positive test is required if one is
  added later.
- M2D4 still owns durable replay/rebuild, multi-target relay failure isolation, and stable public
  transport diagnostics.

## Verification owners

The focused JVM suite owns immutable fixture integrity, historical/model projections, old-action
failure safety, APP-batch compatibility, identity, and the mixed-version premise for duplicate
classification. The connected Room suite owns production persistence, preservation, coexistence,
catalog/category enforcement, temporal/deleted behavior, canonical queries, and schema 52.
