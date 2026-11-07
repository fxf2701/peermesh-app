# GI2C — Room-scoped Recipient Key Pinning Scope Contract

**Status:** SUPERSEDED BY `GI2C_Room_Scoped_Recipient_Key_Pinning_Contract_LOCKED.md`

**Implementation:** CLOSED — see `GI2C_Closure_Evidence.md`

## 1. Purpose

GI2C has one responsibility:

```text
Preserve the first valid recipient encryption key
for one participant inside one Nearby Room.
```

GI2C is not an Invitation Card milestone.

## 2. Key-Pinning Rules

```text
No pinned key + valid incoming key
→ pin the key

Same incoming key
→ accept / no change

Different incoming key
→ keep original pin and report conflict

Missing or unavailable incoming identity
→ no change
```

No silent replacement is allowed.

## 3. Scope

Pin identity by:

```text
(roomId, participantId)
```

Room lifecycle defines pin lifetime.

GI2C must use the smallest safe persistence model supported by the current participant architecture.

## 4. Invitation Independence

GI2C must not know about:

- public invitation;
- private invitation;
- Invitation Card;
- group selection;
- group name;
- description;
- Join;
- invitation send limits;
- invitation history.

GI2C must not disable or enable UI actions directly.

Later use cases may consume key-pinning results.

## 5. Invitation Rules Are Out of Scope

The only invitation-management rules are defined in Product Spec v2:

```text
1 public invitation per sender per room
1 private invitation per sender-recipient pair per room
```

Those rules belong to structured-chat send integration, not GI2C.

## 6. Persistence Prohibitions

GI2C must not create:

- invitation table;
- Invitation Card table;
- invitation state table;
- invitation history;
- generic trust framework without a second concrete use case.

A minimal participant-scoped key pin is preferred.

## 7. Security Output

GI2C may expose a narrow result:

```text
NoPinnedKey
Pinned
Conflict
```

This result describes recipient key continuity only.

It must not encode invitation business semantics.

## 8. Exit Criteria

- First valid key survives restart.
- Same key is accepted.
- Different key never overwrites the pin.
- Missing identity never removes the pin.
- Room purge removes room-scoped pin data.
- No invitation/card subsystem is introduced.
