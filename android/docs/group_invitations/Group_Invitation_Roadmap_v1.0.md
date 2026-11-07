# Group Invitation Roadmap

Version: Draft v1.1

## GI1 — Domain & Protocol

Deliver:

* Invitation Template
* Card Instance
* Audience model
* Compact payload
* Backward compatibility

Done:

* Unit tests
* Protocol review

---

# GI2 — Nearby Room Integration

## GI2A — Recipient Encryption Identity

### GI2A1 — Current Architecture Contract

* [x] Review current security / keystore architecture
* [x] Identify integration boundaries
* [x] Lock architecture contract

### GI2A2 — Recipient Encryption Identity Contract

* [x] Dedicated identity contract
* [x] Lifecycle policy
* [x] Android Keystore contract
* [x] Key ID contract
* [x] Reset policy
* [x] Security review

### GI2A3 — Android Keystore Recipient Identity

- [x] Dedicated P-256 keypair
- [x] Android Keystore integration
- [x] Deterministic key ID
- [x] SEC1 public-key codec
- [x] Runtime ECDH probe
- [x] Metadata consistency
- [x] Reset state machine
- [x] Durable recovery marker
- [x] JVM tests
- [x] Android instrumentation
- [x] Phase closed (PASS WITH VERIFIED LIMITATION)

Accepted limitation:
- API 31+ runtime verification deferred to GI2G device validation.

---

## GI2B — Nearby Self Info Integration

### GI2B1 — Additive Self Info Protocol

* [ ] Extend Nearby Self Info payload
* [ ] Recipient encryption public key
* [ ] Algorithm identifier
* [ ] Key ID
* [ ] Additive compatibility review

### GI2B2 — Self Info Runtime Integration

* [ ] Publish identity when available
* [ ] Suppress capability when unavailable
* [ ] Existing client compatibility
* [ ] Unit tests
* [ ] Integration tests

---

## GI2C — Room-scoped TOFU

### GI2C1 — Trust Store

* [ ] Room-scoped trust model
* [ ] First-key pin
* [ ] Persistence

### GI2C2 — Runtime Validation

* [ ] Key lookup
* [ ] Conflict detection
* [ ] No silent replacement
* [ ] Trust reset policy

### GI2C3 — Tests

* [ ] Unit tests
* [ ] Integration tests
* [ ] TOFU conflict tests

---

## GI2D — Private Invitation Encryption

### GI2D1 — Cryptography

* [ ] Ephemeral sender keypair
* [ ] ECDH
* [ ] HKDF-SHA256
* [ ] AES-256-GCM

### GI2D2 — Protocol Binding

* [ ] Canonical AAD
* [ ] GI1 payload binding
* [ ] Replay scope
* [ ] HKDF label verification

### GI2D3 — Tests

* [ ] Crypto tests
* [ ] Negative tests
* [ ] Replay tests

---

## GI2E — Nearby Room Integration

### GI2E1 — Public Invitation

* [ ] Public invitation message
* [ ] Timeline rendering
* [ ] Sender local plaintext

### GI2E2 — Private Invitation

* [ ] Target decryption
* [ ] Opaque relay
* [ ] Target-only materialization
* [ ] Sender local plaintext

### GI2E3 — Integration

* [ ] Nearby Room message integration
* [ ] Visibility policy
* [ ] No DM
* [ ] No Rich Message framework

---

## GI2F — Capability & Mixed Version

### GI2F1 — Capability Negotiation

* [ ] Capability detection
* [ ] Feature gating

### GI2F2 — Mixed Version Behavior

* [ ] Legacy client handling
* [ ] Invitation suppression
* [ ] No raw invitation JSON exposure

### GI2F3 — Tests

* [ ] Mixed-version tests
* [ ] Compatibility tests

---

## GI2G — Verification

### GI2G1 — Unit & Integration

* [ ] Full unit suite
* [ ] Full integration suite

### GI2G2 — BLE Runtime

* [ ] Multi-device BLE tests
* [ ] Disconnect / reconnect
* [ ] Relay verification
* [ ] Replay verification

### GI2G3 — Regression

* [ ] Nearby Chat regression
* [ ] Nearby Room regression
* [ ] Runtime stability verification

---

## GI3 — Join Flow

Deliver:

* Invitation validation
* Join workflow
* Already member handling
* Expired handling

Done:

* End-to-end join succeeds
* Failure paths covered

---

## GI4 — UX Polish

Deliver:

* Card UI
* Group picker
* Share dialog
* Empty states
* Error states

Done:

* Consistent Nearby Room UX
* No intrusive dialogs

---

## GI5 — Reliability

Deliver:

* Multi-device BLE testing
* Disconnect/reconnect tests
* Duplicate delivery tests
* Mixed-version compatibility

Done:

* No regression to Nearby Chat
* Stable under BLE runtime conditions

---

## Test Strategy

Every phase requires:

* Unit tests
* Integration tests
* Real-device verification

GI2G additionally requires:

* Multi-device BLE relay tests
* Runtime regression tests
* Mixed-version verification

---

## Definition of Done

The milestone is complete when:

* Invitation identity is securely established.
* Nearby Self Info compatibility is verified.
* Room-scoped TOFU is enforced.
* Public/private invitation delivery works.
* Mixed-version behavior is safe.
* BLE compatibility is verified.
* Existing Nearby Room chat behavior remains unchanged.
