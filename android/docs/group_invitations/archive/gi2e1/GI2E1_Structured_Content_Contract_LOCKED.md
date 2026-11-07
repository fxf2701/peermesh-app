# GI2E1 Structured Content Contract

Revision: 3.2
Status: LOCKED
Lock authority: `GI2E1_Revision3_2_Lock_Record.md`

## Delta from Revision 3.1

Revision 3.2 does not change protocol, crypto, transport, retry, relay, ordering or persistence ownership.

Only the Hide/Unhide persistence authority is clarified.

### Hide/Unhide persistence

Approved field:

```text
NearbyRoomMessageEntity.is_hidden_locally
```

Assumption:

```text
One production local actor
↔
One peer_mesh.db
```

`is_hidden_locally` is installation-local presentation state.

Current debug actor switching is explicitly out of scope and is an accepted limitation.

If production later introduces multiple local actors sharing one database, this contract must not be implemented further until a new architecture review approves a viewer-keyed persistence model.

All other Revision 3.1 sections remain unchanged.
