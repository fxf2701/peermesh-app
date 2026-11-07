# GI2E6-P7 — UX Amendment 2

**Status:** LOCKED  
**Applies after:** `GI2E6_P7_UX_Amendment_1_LOCKED.md`

## Decision

Public Invitation local hiding is recoverable directly from the Nearby Room timeline.

When a Public Invitation is hidden locally:

```text
message chrome remains visible
(sender/avatar/timestamp)
+
"You have hidden this invitation."
+
Unhide
```

The canonical invitation content remains concealed in the chat timeline while hidden. `Unhide` uses the existing Repository-owned local visibility mutation and MUST NOT parse protocol data in UI code.

The room invitation index remains a read-oriented invitation surface. Every visible invitation item exposes:

```text
Join
```

and MUST NOT expose:

```text
Hide
```

`Join` remains presentation-only in GI2E6-P7. It does not authorize credential consumption, membership mutation, Accept/Reject, or any Group Invitation lifecycle change.

The Public Invitation detail viewer continues to expose Join + Hide as established by UX Amendment 1.

## Architecture Preservation

This amendment does not change:

- Group Invitation protocol;
- canonical public send semantics;
- Repository send authority;
- credential boundaries;
- Nearby transport/retry/relay;
- database schema;
- Private Invitation P1–P6 semantics.

Hidden Public Invitation timeline presentation MUST be derived from a Repository-authoritative typed inspection result. The UI MUST NOT reconstruct sender identity or invitation validity from raw message bodies.
