# GI2E4 — Private Invitation Send Pipeline Independent Review

**Status:** PASS AFTER REQUIRED CORRECTION

## Finding corrected

The initial production constructor accepted a caller-supplied `PrivateRecipientCrypto`. That exposed a production path through which a caller could indirectly control nonce, ephemeral key, ciphertext, and encrypted metadata, contrary to the locked repository/crypto authority boundary.

The production constructor now always creates the single JCA-backed GI2D implementation from the Android Keystore recipient identity provider. The injectable encryption function remains limited to the internal test-oriented constructor.

The pipeline also rejects a successful GI2D result when its algorithm or target key ID differs from the repository-frozen preparation. Such a mismatch stops before prepared insertion and transport.

## Verification assessment

Focused JVM coverage verifies canonical inner and outer bodies, exact authenticated context, one encryption call, plaintext byte cleanup, transport-after-persistence, retry compatibility, failure ordering, semantic-only request fields, and rejection of inconsistent GI2D success metadata.

Existing Room instrumentation coverage already verifies private canonical source persistence, frozen outer identity, per-target quota, stale preparation, transaction ordering, mute behavior, and message-ID collision. No new Android-only seam or instrumentation architecture is justified for GI2E4.

## Verdict

- Architecture preservation: PASS
- Repository authority: PASS
- Cryptographic authority: PASS after correction
- Protocol compatibility: PASS
- Transaction safety: PASS
- Persistence safety: PASS
- Retry compatibility: PASS
- New instrumentation code required: NO

GI2E4 remains not CLOSED and not LOCKED until user verification and Manual Review pass.
