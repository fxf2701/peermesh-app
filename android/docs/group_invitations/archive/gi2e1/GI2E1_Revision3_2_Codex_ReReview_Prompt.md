# Codex Prompt — GI2E1 Revision 3.2

Review only the delta from Revision 3.1.

New authority:

- GI2E1 Revision 3.2 Amendment
- GI2E1 Structured Content Contract Revision 3.2

Primary task:

Verify that treating actor switching as a debug-only accepted limitation is consistent with current production assumptions.

Do not require viewer-keyed persistence unless you can prove multiple production local actors share one database.

Confirm:

1. Single-production-actor invariant is explicit.
2. `is_hidden_locally` remains the smallest valid persistence seam.
3. Future multi-actor production is protected by an explicit stop condition.
4. No regression to protocol, crypto, Nearby Room ownership or BLE.
