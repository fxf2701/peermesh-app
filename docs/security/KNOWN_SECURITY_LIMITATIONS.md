# Known Security Limitations — Research Baseline

> **Status:** Experimental research disclosure; **not a penetration-test report or security certification**.  
> **Baseline:** `peermesh-app.zip` supplied for documentation alignment on 2026-11-07.  
> **Change policy:** This document describes current risks; it does **not** imply code fixes have been made.  
> **Use restriction advisory:** Do **not** rely on this baseline for sensitive, adversarial, safety-critical, or life-critical communication.

## How to read the evidence

- **Direct code observation:** confirmed from a named source file in this baseline.
- **Audit-reported:** identified in the maintainer-provided Codex audit summary; requires complete call-path and adversarial test verification before treating the scenario as proven exploitable.
- **Design requirement:** desired behavior stated in protocol documents; not proof of runtime enforcement.

These distinctions matter: a weakness in batch authentication **does not by itself establish that encrypted group messages can be decrypted**.

## SEC-01 — Static shared application-batch HMAC key

**Evidence:** Direct code observation: `android/security/src/main/java/app/peermesh/security/crypto/AppMac.kt` contains `APP_HMAC_KEY_B64` as a constant, used by `computeMac` and `verifyMac` for HMAC-SHA256 over a canonicalized batch.

**Consequence:** Anyone with the source or a distributed build can obtain the shared key and generate a MAC accepted by this verifier. A valid batch MAC is therefore **not proof of a trusted sender or device identity**. It should not be used as an authorization boundary.

**Scope:** This finding concerns the application-batch MAC. It does not establish compromise of separate group-encryption keys or plaintext disclosure.

**Status:** Known limitation; no implementation change in this documentation phase.

## SEC-02 — Owner-only actions lack independent cryptographic authority proof

**Evidence:** Audit-reported: the group-removal path compares `actor.did` to the recorded owner, while event authentication uses group-shared key material. Exact end-to-end enforcement and other possible checks need independent verification.

**Consequence:** If a malicious group member can create an otherwise accepted event with an arbitrary `actor.did`, a customized client may impersonate the owner for privileged actions. A DID field alone is not cryptographic proof that the owner authorized an operation.

**Scope:** This is a conditional threat scenario, **not** a claim that every group-management flow has been exploited. Group-key possession must not be conflated with owner identity.

**Status:** Audit-reported known risk; no implementation change in this documentation phase.

## SEC-03 — Group removal does not establish cryptographic revocation

**Evidence:** Audit-reported: a key-share after removal is still encrypted with the existing group key; selecting a destination device does not itself create recipient-specific confidentiality.

**Consequence:** A removed member retaining the old group key and obtaining the rekey ciphertext may be able to recover a new key. A recipient list, transport routing choice, or UI membership change is **not equivalent to cryptographic exclusion**.

**Scope:** This does not assert that every removed member will receive a rekey packet, or that all key epochs are affected. It means strong forward exclusion against malicious former members is **not guaranteed** by the reviewed mechanism.

**Status:** Audit-reported known risk; no implementation change in this documentation phase.

## SEC-04 — Registry v1 signed-request replay

**Evidence:** Audit-reported: a legacy/v1 registry request path accepts previously signed requests when replayed. The design requirement in `docs/SECURITY.md` calls for bounded replay protection; that requirement should not be mistaken for implementation evidence.

**Consequence:** A valid signature may prove that bytes were signed, but not that the request is fresh. Replays may repeat operations depending on endpoint semantics and any additional server-side checks.

**Scope:** This does not establish that every registry endpoint lacks replay protection or that replay always causes a state change.

**Status:** Audit-reported known limitation; no implementation change in this documentation phase.

## SEC-05 — Relay plaintext acceptance in compatibility path

**Evidence:** Audit-reported: a relay validator accepts a plaintext object in a path where the security design requires encrypted application content for production. See `docs/SECURITY.md` and `docs/specs/RELAY_PROTOCOL_SPEC.md` for intended envelope requirements.

**Consequence:** Do not claim that all relay-ingested application bodies are necessarily encrypted or that relay operators can never encounter plaintext. Protection depends on client behavior, server path, and deployment configuration.

**Scope:** Acceptance of a plaintext object does **not** mean all clients transmit plaintext or that encrypted messages are decrypted by relays.

**Status:** Audit-reported known limitation; no implementation change in this documentation phase.

## Cross-cutting limitations

- Metadata such as network addresses, timing, group/routing identifiers, and traffic patterns may be visible to intermediate services or nearby observers depending on transport.
- Group membership, device identity, encryption, authorization, replay resistance, and delivery reliability are distinct properties; passing one check does not establish the others.
- Local deletion or TTL expiration does not erase copies already delivered to other devices, nor guarantee secure erasure from storage.
- The baseline has not undergone a comprehensive independent security audit. BLE behavior on physical devices, full registry integration, and all deployment configurations have not been verified in this documentation review.
- Do not use PeerMesh as the only channel for emergency communication.

## Research and reporting

These issues are disclosed to help reviewers assess the baseline. A future change to the implementation should be evaluated separately and documented with tests and a specific source revision; updating this document alone does not fix any weakness.

For questions or additional findings, contact `hello@peermesh.app`. Avoid publishing credentials, private keys, personal data, or unpatched exploit details in public issues.

Related: [Security Overview](../SECURITY.md), [Disclaimer](../DISCLAIMER.md), [Repository README](../../README.md).
