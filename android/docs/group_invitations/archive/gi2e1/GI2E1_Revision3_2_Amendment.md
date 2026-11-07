# GI2E1 Revision 3.2 Amendment

## Accepted Limitation — Single Production Actor Per Database

Status: APPROVED FOR REVISION 3.2

### Decision

GI2E1 assumes exactly one production local actor owns one `peer_mesh.db`.

`NearbyRoomMessageEntity.is_hidden_locally` remains the approved persistence mechanism.

### Rationale

Current actor switching exists for testing/debugging only and is not a production ownership model.

Therefore `is_hidden_locally` is defined as installation-local presentation state rather than actor-local state.

### Accepted limitation

Debug/test actor switching is outside Hide/Unhide isolation guarantees.

If production later supports multiple local actors sharing one database, GI2E persistence must be re-reviewed before release.

### Stop condition

Do not implement multi-actor production using the current boolean field.
A viewer-keyed persistence design must first be reviewed and approved.
