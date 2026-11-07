# GI2E Product Architecture Decision

## Invitation Immutability and Local Hide/Unhide

**Decision ID:** GI2E-PAD-01  
**Status:** APPROVED PRODUCT DECISION  
**Applies from:** GI2E1 Revision 3  
**Scope:** Group Invitation as Structured Nearby Chat Message

---

# 1. Decision

A Group Invitation becomes immutable once it has been successfully created as a Nearby Chat message.

It cannot be:

- edited;
- soft-deleted;
- recalled;
- unsent;
- replaced in place;
- converted into a normal text message under the same message ID.

Normal Nearby Chat retention and hard-purge ownership remain unchanged.

---

# 2. Product Model

A Group Invitation is treated like a letter or email that has already been delivered into the messaging system.

The sender may create and send it, but cannot later modify or withdraw the message from other peers.

The sender must therefore be warned before sending:

> Sau khi gửi, lời mời này không thể chỉnh sửa hoặc thu hồi. Hãy kiểm tra kỹ nội dung trước khi gửi.

For private invitation creation, the form should additionally clarify:

> Chỉ người nhận được chọn mới có thể xem nội dung lời mời riêng tư này.

---

# 3. Local Hide/Unhide

An authorized viewer may locally hide or unhide an invitation.

Hide/Unhide is presentation state only.

It does not:

- edit the message;
- delete the message;
- recall the message;
- send a transport event;
- alter the persisted GI1 wire body;
- change retry or relay behavior;
- reset send-limit evidence;
- change invitation validity;
- affect another user or device.

When hidden, the same message is projected as:

> Lời mời này đã ẩn. **[Bỏ ẩn]**

The placeholder is not persisted as another message.

---

# 4. Authorized Viewers

## Public invitation

The sender and every participant authorized to view the public invitation may independently:

- Hide invitation;
- Unhide invitation.

## Private invitation

Only:

- the logical sender;
- the intended target participant;

may see the invitation or its user-hidden placeholder.

A non-target peer remains audience-hidden and receives no visible timeline item or invitation actions.

---

# 5. Action Matrix

| Actor | Edit | Soft-delete | Recall | Hide | Unhide | Reply | Report invitation | Report peer |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Sender | No | No | No | Yes | Yes | No | No | Yes |
| Public receiver | No | No | No | Yes | Yes | No | Yes | Yes |
| Private target | No | No | No | Yes | Yes | No | Yes | Yes |
| Private non-target | Not visible | Not visible | Not visible | Not visible | Not visible | Not visible | Not visible | Not visible |

---

# 6. Reporting Wording

For rendered invitations:

```text
Report message
```

must be replaced by:

```text
Report invitation
```

`Report peer` remains available.

No reporting path may expose:

- sender-local private plaintext source;
- private join credential as report plaintext;
- raw malformed JSON;
- ciphertext labeled as ordinary message text.

---

# 7. Architectural Consequences

This decision removes the need to preserve an invitation envelope solely to recover from user soft-delete.

The persisted GI1 wire body remains immutable and continues to provide:

- invitation identity;
- public/private audience;
- private target identity;
- recipient key identity;
- retry/relay stability;
- send-limit evidence.

A sender-local private rendering source may still be required because the sender cannot decrypt the recipient envelope.

A separate local presentation preference is required for Hide/Unhide.

It must not become an Invitation repository, Invitation lifecycle, or second message store.

---

# 8. Superseded Rule

Any earlier statement that an invitation may be soft-deleted while preserving quota evidence is superseded by this decision.

The new rule is:

```text
Invitation messages cannot be user-soft-deleted.
```

System retention and hard purge remain normal Nearby Chat behavior.

---

# 9. Non-goals

This decision does not define:

- Join flow;
- invitation card visual design;
- form implementation;
- report backend behavior;
- hidden-items management screen;
- cross-device synchronization of local hide state.

Those require their own scoped implementation decisions.
