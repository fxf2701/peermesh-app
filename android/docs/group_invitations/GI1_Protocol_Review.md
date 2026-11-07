# GI1 Protocol Review — Recipient-Confidential Invitation Contract

Status: implemented for GI1 domain and protocol only

## Security contract

```text
ROOM:
normal Nearby Room encryption only

PARTICIPANT:
recipient-confidential inner encrypted envelope
inside the normal room-encrypted message
```

Public invitations carry their presentation data and opaque join credential in the existing
room-encrypted message body. Participant invitations never carry title, description, or credential
in their outer body. The sender retains its local plaintext card; the target decrypts the inner
payload; every non-target participant treats the encrypted envelope as opaque. Confidentiality must
never depend on a timeline visibility filter.

An actor, participant, or device identifier is never an encryption key. Identifiers are only routing,
key-resolution, AAD, or HKDF-context inputs.

## v1 payload shapes

Public (`a = r`):

```json
{"a":"r","c":"[credential]","d":"Short description","i":"card-id","n":"Group title","t":"gi","v":1}
```

`d` is optional. `to` and every recipient-encryption field are forbidden.

Private outer envelope (`a = p`):

```json
{"a":"p","alg":"p256-hkdf-sha256-a256gcm","ct":"[base64url-ciphertext]","epk":"[base64url-p256-point]","i":"card-id","iv":"[base64url-nonce]","kid":"target-key-id","t":"gi","to":"target-participant-id","v":1}
```

The private ciphertext plaintext is a separate compact JSON payload:

```json
{"c":"[credential]","d":"Short description","n":"Group title"}
```

Outer private envelopes reject all public fields (`n`, `d`, `c`); public payloads reject `to` and all
private fields. The codec uses canonical JSON ordering, recognizes only `t = gi`, and ignores unknown
additive fields in a valid v1 shape.

## Bounds and payload budgets

All protocol limits use UTF-8 bytes: card id, participant id, target key id: 128 each; title: 240;
description: 480; credential: 512. Domain also applies UX character limits of 80 title and 240
description characters. Credentials and diagnostic string output are redacted.

The v1 private key format is an uncompressed P-256 SEC1 point (65 bytes, 87 unpadded Base64URL
characters). The nonce is a 96-bit AES-GCM nonce (12 bytes, 16 Base64URL characters). Ciphertext is
bounded to 1,728 Base64URL characters, enough for a 1,280-byte inner plaintext plus 16-byte GCM tag.

| Budget | Limit | Rationale |
| --- | ---: | --- |
| Public wire payload | 1,536 bytes | Sized for representative compact, non-escaped bounded id/title/description/credential values and canonical JSON overhead. |
| Private inner plaintext | 1,280 bytes | Sized for the compact title/description/credential shape before AEAD encryption. |
| Private outer wire payload | 2,400 bytes | Sized for 1,728-byte ciphertext, 87-byte ephemeral key, 16-byte nonce, three bounded identifiers, and structural JSON overhead. |

These are conservative protocol limits, not a final BLE benchmark. GI2 must measure the Android/JCA
P-256 encoding and encrypted-message transport overhead before declaring the runtime budget closed.
Per-field limits prevent unbounded input, but do not guarantee every combination of individually valid
fields can be encoded together: canonical JSON escaping (for example quotes, backslashes, and control
characters) can increase the final wire size. `encodePublic`, `encodePrivateInnerPayload`, and
`encodePrivateEnvelope` therefore may reject a domain-valid combination when its final canonical
payload exceeds its budget. This is expected protocol behaviour, not an internal error.

## GI2 authenticated crypto context

GI2 must use the dedicated HKDF domain-separation label:

```text
peermesh/group-invitation/private/v1
```

The AES-GCM AAD must deterministically and unambiguously bind protocol version, encryption algorithm,
Nearby Room id, outer Nearby message id, card id, sender participant/actor id, target participant id,
target recipient-encryption key id, and effective Nearby Room expiry. Any changed value must cause
authentication failure or rejection. The outer room encryption is not sufficient binding for the inner
recipient-confidential payload.

GI2 must use a versioned canonical structured encoding or length-prefixed fields; it must not
concatenate variable-length strings ambiguously. Encrypt and decrypt must share this serialization and
test it. Binding room id, message id, card id, sender id, target id, and room expiry prevents safely
transplanting ciphertext into another room, message, sender, recipient, card, or expiry context. GI2
still owns runtime replay and duplicate-delivery handling.

## Compatibility and GI2 blockers

GI1 guarantees that normal text and unrelated JSON remain text; recognized malformed or future
invitations are never downgraded to text; and additive v1 fields are accepted. GI1 does not ensure an
old client will avoid displaying raw compact invitation JSON, negotiate room capabilities, persist a
dedicated Nearby message type, or filter timeline materialization. GI2 must check invitation capability
before sharing or define safe mixed-version behaviour.

GI2 must use Option A: a dedicated recipient-encryption keypair for every target, never a DTN manifest
signing key. The target public key is distributed in the Nearby participant/self-info flow. The first
valid key is pinned with room-scoped TOFU, and replacement during that room must not be silently
accepted. For each private card, the sender generates an ephemeral P-256 keypair, applies ECDH,
HKDF-SHA256, and AES-256-GCM, retains sender plaintext locally, and allows only the target to decrypt
and materialize the card.
