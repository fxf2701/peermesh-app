# Group Invitation Specification v2 (Realigned)

## 1. Product Model

Group Invitation **không phải** là một transport protocol mới.

Group Invitation là **structured Nearby Chat message** được gửi qua
luồng Nearby Chat hiện có.

Nearby Chat tiếp tục chịu trách nhiệm:

-   persistence
-   relay
-   retry
-   delivery
-   ordering

UI chỉ nhận dạng structured payload và render thành Invitation Card.

------------------------------------------------------------------------

# 2. Send Entry Points

## Public Invitation

Room menu:

    Send invitation to all participants

Flow:

1.  Chọn Group
2.  Nhập Description
3.  Send

Rule:

-   Một sender chỉ được gửi **01 public invitation** trong một room.

------------------------------------------------------------------------

## Private Invitation

Participant context menu:

    Send Invitation

Flow:

1.  Chọn Group
2.  Nhập Description
3.  Send

Rule:

-   Một sender chỉ được gửi **01 private invitation** cho mỗi recipient
    trong một room.

------------------------------------------------------------------------

# 3. Invitation Form

## Group

Group được chọn từ danh sách hiện có.

Không cho phép sửa tên.

Nếu tên dài hơn giới hạn sẽ được truncate trước khi tạo payload.

### Group Name Limits

-   Maximum: **40 Unicode characters**
-   Maximum: **120 UTF-8 bytes**

Hint:

> Group names longer than 40 characters will be shortened in the
> invitation.

------------------------------------------------------------------------

## Description

User nhập description.

### Description Limits

-   Maximum: **60 Unicode characters**
-   Maximum: **180 UTF-8 bytes**

UI:

-   Character counter (ví dụ: 32/60)
-   Không cho nhập vượt giới hạn
-   Không truncate sau khi nhấn Send

Hint:

> Maximum 60 characters.

------------------------------------------------------------------------

# 4. Structured Message

Public invitation:

-   protocol version
-   structured type
-   message/invitation id
-   audience = room
-   group name
-   description
-   join credential

Private invitation:

Outer payload:

-   protocol version
-   structured type
-   message/invitation id
-   audience = private
-   recipient key id
-   ephemeral public key
-   nonce
-   ciphertext

Inner encrypted payload:

-   group name
-   description
-   join credential

Không tạo Nearby transport kind mới.

------------------------------------------------------------------------

# 5. Invitation Card

## Public

-   Group name
-   Description
-   Join

## Private

-   Group name
-   Description
-   Only you can see this invitation
-   Join

Không hiển thị:

-   ciphertext
-   protocol metadata
-   recipient key id
-   crypto fields

------------------------------------------------------------------------

# 6. Security

Public invitation:

-   không cần recipient encryption.

Private invitation:

-   recipient-only decryption
-   Android Keystore
-   ECDH
-   HKDF
-   AES-GCM
-   replay protection
-   expiry binding

Relay chỉ forward ciphertext.

Chỉ recipient materialize card.

------------------------------------------------------------------------

# 7. Persistence

Không tạo:

-   Invitation table
-   Invitation template table
-   Invitation card table
-   Invitation state table

Invitation được lưu như Nearby Chat message hiện có.

------------------------------------------------------------------------

# 8. Principles

-   Stability \> Correctness \> Maintainability \> Performance
-   Reuse Nearby Chat transport
-   Không tạo invitation transport family
-   UI chịu trách nhiệm render card
-   Security layer chịu trách nhiệm private invitation
