# GI2E Local Hide/Unhide Persistence Decision

**Decision ID:** GI2E-PAD-02  
**Status:** APPROVED FOR GI2E1 REVISION 3.1

## Decision

Add one local-only field to the existing `NearbyRoomMessageEntity`:

```text
is_hidden_locally: Boolean = false
```

This is the smallest valid change because current source has no reusable per-message mechanism that provides:

```text
same message
same messageId
same ordering position
process-death persistence
reversible Unhide
no transport event
```

No new table, repository, entity, worker, lifecycle or placeholder message is authorized.

## Semantics

```text
false → normal safe projection
true  → “Lời mời này đã ẩn. [Bỏ ẩn]”
```

For private non-target peers, `AudienceHidden` always wins.

Hide/Unhide does not change:

- `body`;
- message identity or ordering;
- retry, relay or transport;
- quota evidence;
- invitation validity;
- another peer’s state.

## Ownership

Only local application/presentation logic may change this field.

Remote ingest:

- has no parameter for it;
- cannot set or clear it;
- cannot overwrite it.

Reducer, delivery-state updates and retry bookkeeping preserve it. Hard row/room purge removes it.

## Scope

GI2E authorizes this capability only for Group Invitation presentation. Normal-message Hide/Unhide requires a separate product decision.

## Migration

At reviewed Room baseline 54, expected additive column:

```sql
is_hidden_locally INTEGER NOT NULL DEFAULT 0
```

If another migration lands first, rebase. Require schema export, migration tests and no destructive fallback.

## Stop Conditions

Return to architecture discussion if implementation requires:

- a separate hidden-invitation table;
- per-profile state in a shared message database;
- cross-device synchronization;
- a hidden-items screen;
- a transport event;
- another message row.
