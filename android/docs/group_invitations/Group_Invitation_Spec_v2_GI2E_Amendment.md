# Group Invitation Spec v2 — GI2E Amendment

**Amendment ID:** GI2E-SPEC-A1  
**Status:** APPROVED FOR GI2E1 REVISION 3  
**Applies to:** `Group_Invitation_Spec_v2.md`

---

# 1. Purpose

This amendment records the Product Decision introduced before GI2E1 Revision 3.

Where this amendment conflicts with earlier soft-delete wording in the specification, this amendment wins.

---

# 2. Invitation Immutability

After a Group Invitation has been successfully created as a Nearby Chat message, it cannot be:

- edited;
- soft-deleted;
- recalled;
- unsent;
- replaced in place.

The existing message row remains immutable in content until normal system retention or hard purge removes it.

---

# 3. Send-Limit Evidence

The authorized send limits remain:

```text
Public invitation:
once per sender per room

Private invitation:
once per sender per recipient per room
```

Because invitation messages cannot be soft-deleted, send-limit evidence remains in the immutable existing Nearby message row.

No Invitation-specific quota table, repository, entity, or lifecycle may be introduced.

The authoritative send-limit recheck and prepared message insertion must occur in one serialized database transaction.

---

# 4. Local Hide/Unhide

Public and private invitations may be locally hidden by an authorized viewer.

Local hide state:

- changes presentation only;
- does not mutate the invitation message;
- is reversible;
- does not affect other peers;
- does not affect send limits;
- does not affect retry, relay, ordering, duplicate handling, or persistence ownership.

Hidden presentation:

> Lời mời này đã ẩn. **[Bỏ ẩn]**

---

# 5. Audience Rules

## Public

Every participant who can render the public invitation may independently Hide or Unhide it.

## Private

Only the logical sender and intended target may render or locally hide/unhide the invitation.

A non-target peer remains presentation-invisible.

---

# 6. Action Rules

Invitation actions are:

```text
Sender:
- Hide invitation
- Unhide invitation
- Report peer

Authorized receiver:
- Hide invitation
- Unhide invitation
- Report invitation
- Report peer
```

Invitation actions exclude:

```text
Edit
Delete
Recall
Reply
```

Authoritative rejection must exist below UI for Edit/Delete/Replace behavior.

---

# 7. Creation Warning

Before Send, the creation form must display:

> Sau khi gửi, lời mời này không thể chỉnh sửa hoặc thu hồi. Hãy kiểm tra kỹ nội dung trước khi gửi.

Private form additionally indicates that only the selected recipient can view the private content.

---

# 8. Retention

Normal Nearby Chat room/message retention and hard purge remain authorized.

Retention is not user recall and does not reset historical rules while the evidence row exists.

---

# 9. Compatibility

Older app versions may still expose generic message actions or raw content.

Upgraded implementations must enforce this amendment at authoritative application/repository boundaries and must never rely only on UI hiding.
