# GI2D — Closure Evidence

**Milestone status:** CLOSED

**Closure date:** 2026-08-04

**Contract authority:** `GI2D_Private_Invitation_Cryptography_Contract_LOCKED.md`

## 1. Closure Decision

GI2D is closed for its locked production scope.

The implementation provides one narrow recipient-confidential cryptographic
primitive using ephemeral P-256 ECDH, HKDF-SHA256 and AES-256-GCM. It does not
own Invitation orchestration, Nearby Chat transport, persistence, retry, relay,
ordering, duplicate suppression, rendering or Join behavior.

## 2. Exact Files

Production:

- `security/src/main/java/app/peermesh/security/groupinvitation/PrivateRecipientCrypto.kt`;
- `security/src/main/java/app/peermesh/security/groupinvitation/JcaPrivateRecipientCrypto.kt`.

Tests:

- `security/src/test/java/app/peermesh/security/groupinvitation/PrivateRecipientCryptoTest.kt`;
- `security/src/androidTest/java/app/peermesh/security/groupinvitation/PrivateRecipientCryptoInstrumentedTest.kt`.

Documentation:

- `docs/group_invitations/GI2D_Private_Invitation_Cryptography_Contract_LOCKED.md`;
- `docs/group_invitations/GI2D_Closure_Evidence.md`;
- `docs/group_invitations/Group_Invitation_Implementation_Roadmap_v2.md`.

## 3. Architecture Review

Verdict: PASS.

- Security production imports no Invitation payload, card, credential, quota or
  business-policy model.
- The bounded existing algorithm-enum dependency is unchanged.
- No table, entity, DAO, repository, migration, replay cache or lifecycle was
  added.
- No Nearby Chat send, receive, retry, relay, ordering or duplicate behavior was
  changed.
- The existing Group Invitation protocol remains the wire adapter; GI2D
  production operates only on opaque bytes and neutral immutable context.
- GI2E orchestration remains deferred.

## 4. Security Review

Verdict: PASS after focused fixes.

- Canonical context uses the exact `PMGI` framing, fixed field order, strict
  UTF-8, big-endian integer encoding and locked expiry value.
- HKDF uses the exact zero salt, domain label, separator, context-length framing
  and 32-byte output.
- The canonical context is bound in both HKDF info and AES-GCM AAD.
- AES-GCM uses a fresh 12-byte nonce and returns ciphertext with its 16-byte tag.
- Every encryption creates a fresh ephemeral P-256 keypair.
- Decryption validates the encrypted primitive, including strict P-256 SEC1
  decoding, before expiry; expiry remains before private-key resolution or ECDH.
- Target key IDs require canonical 32-byte unpadded Base64URL form and exact
  payload/context/provider binding.
- Wrong recipients, replaced/deleted identities, malformed P-256 points and
  tampered context, nonce, ciphertext or tag fail closed.
- Mutable shared-secret, PRK, AES-key and internal plaintext/ciphertext arrays
  are cleared in `finally` paths.
- Public results and diagnostic strings do not expose private keys, shared
  secrets, AES keys, plaintext or encrypted primitive bytes.

Focused fixes made during independent review:

- reject malformed UTF-16 instead of using replacement UTF-8 bytes;
- reject non-canonical key-ID encodings;
- clear PRK when HKDF expansion fails before returning;
- map non-ECDH JCA failures to `CryptoUnavailable`;
- map provider `BadPaddingException` authentication failures to
  `AuthenticationFailed`;
- expand golden-vector, tamper, provider-state, protocol-adapter and Android
  Keystore coverage.

Final contract-alignment fixes:

- preserve `NoSuchAlgorithmException` from `KeyAgreement.getInstance("ECDH")`
  so it maps to `CryptoUnavailable`; ECDH `init`, `doPhase` and
  `generateSecret` failures remain `KeyAgreementFailed`;
- move encrypted-payload structural validation ahead of expiry, with algorithm
  consistency, target-key-ID consistency and private-key lookup still following
  expiry in the locked order;
- add real compressed-SEC1, 65-byte off-curve, recipient tuple-integrity,
  HKDF-context-separation, fixed-vector decrypt and plaintext defensive-copy
  regression coverage.

## 5. JVM Verification

```bash
./gradlew :security:testDebugUnitTest \
  --tests 'app.peermesh.security.groupinvitation.PrivateRecipientCryptoTest' \
  --no-parallel
```

Result: PASS — 17 tests, 0 failures.

```bash
./gradlew :security:testDebugUnitTest --no-parallel
```

Result: PASS — 46 tests, 0 skipped, 0 failures, 0 errors.

## 6. AndroidTest Compilation

```bash
./gradlew :security:compileDebugAndroidTestKotlin --no-parallel
```

Result: PASS.

The Security module compiles independently; unrelated app-level M3D2 tests do
not block this module-level GI2D suite and were not changed.

## 7. API 31+ Android Keystore Verification

Device:

```text
Model: SM-A047F
Android: 14
API: 34
ADB serial: 192.168.0.101:41091
```

Command:

```bash
ANDROID_SERIAL='192.168.0.101:41091' \
./gradlew :security:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.peermesh.security.groupinvitation.PrivateRecipientCryptoInstrumentedTest \
  --no-parallel
```

Result: PASS — 3 tests, 0 skipped, 0 failures, 0 errors.

Runtime evidence proves:

- full encrypt/decrypt using the non-exportable Android Keystore private key;
- no private encoded bytes are exposed;
- a replacement identity cannot decrypt a payload for the previous key;
- deletion of the target Keystore alias maps to the locked invalidated result
  without plaintext recovery.

## 8. Remaining Limitations

- GI2D provides authenticated context binding, not an exact-duplicate replay
  store. Exact duplicate ownership remains with Nearby Chat as required.
- GI2D does not orchestrate encrypt-once persistence or retry reuse; that remains
  GI2E work.
- API 21–30 remains unsupported for recipient encryption under the locked GI2A
  platform policy; no exportable software fallback exists.

No unresolved limitation blocks the locked GI2D production scope.

## 9. Final Verdict

```text
ARCHITECTURE REVIEW: PASS
SECURITY REVIEW: PASS
JVM VERIFICATION: PASS
ANDROIDTEST COMPILE: PASS
API 31+ KEYSTORE INSTRUMENTATION: PASS
GI2D: CLOSED
```
