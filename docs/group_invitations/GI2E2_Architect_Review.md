# GI2E2 — Send-Limit Query and Enforcement

## Implementation Plan

```text
Milestone: GI2E2
Phase: Implementation Planning
Architecture Review: PASS
Implementation authorization: PENDING PLAN REVIEW
Production changes: NOT STARTED
```

---

# 1. Mục tiêu implementation

GI2E2 chỉ xây nền tảng dùng chung cần thiết cho các phase gửi Public và Private Invitation sau này:

```text
Prepared immutable message
+
authoritative send-limit enforcement
+
insert-only persistence
+
immutable Invitation protection
+
additive local-only persistence fields
```

GI2E2 không tạo invitation từ UI và không thực hiện full public/private orchestration.

Flow hoàn chỉnh sau GI2E2 sẽ có dạng:

```text
GI2E3 / GI2E4 prepares final immutable message
↓
GI2E2 validates prepared context
↓
GI2E2 opens one Room transaction
↓
rechecks room and sender eligibility
↓
queries existing sender invitation candidates
↓
checks exact public/private business key
↓
inserts once using ABORT
↓
returns explicit result
```

---

# 2. Constitution compliance

Plan này áp dụng trực tiếp các nguyên tắc:

```text
Reuse existing mechanism
>
Small extension
>
New abstraction
>
New subsystem
```

Do đó:

* `NearbyRoomRepository` tiếp tục sở hữu send transaction;
* `NearbyRoomMessageEntity` tiếp tục là message persistence authority;
* Room transaction tiếp tục là serialization boundary;
* existing Nearby retry/relay tiếp tục reload message row;
* không tạo Invitation repository;
* không tạo quota table;
* không tạo Invitation state machine;
* không tạo generic structured-content framework;
* không thay đổi BLE transport.

Điều này phù hợp Engineering Constitution về simplicity, ownership, stability và surface-area minimization.

---

# 3. Production files dự kiến thay đổi

## Data layer

```text
data/src/main/java/app/peermesh/data/db/entity/
    NearbyRoomMessageEntity.kt

data/src/main/java/app/peermesh/data/db/dao/
    NearbyRoomMessageDao.kt

data/src/main/java/app/peermesh/data/db/
    AppDatabase.kt
    DatabaseMigrations.kt

data/schemas/app.peermesh.data.db.AppDatabase/
    55.json
```

## Nearby application layer

```text
app/src/main/java/app/peermesh/nearby/
    NearbyRoomRepository.kt
    NearbyRoomMessageEditReducer.kt
```

## New narrowly scoped production files

Tên cuối cùng có thể điều chỉnh nhẹ theo package convention, nhưng không được mở rộng thành framework:

```text
app/src/main/java/app/peermesh/nearby/
    NearbyRoomPreparedMessage.kt
    NearbyRoomStructuredMessagePolicy.kt
```

Hai file này có trách nhiệm khác nhau:

```text
NearbyRoomPreparedMessage
    immutable prepared-send data and result contract

NearbyRoomStructuredMessagePolicy
    GI1 classification and immutable-field comparison
```

Nếu implementation cho thấy policy đủ nhỏ để đặt private trong repository/reducer, không bắt buộc tạo file thứ hai. Constitution ưu tiên giữ surface nhỏ nhất.

---

# 4. Database entity extension

Thêm đúng hai field cuối entity:

```kotlin
val local_render_source_body: String? = null,
val is_hidden_locally: Boolean = false,
```

Entity sau migration vẫn giữ nguyên:

```text
Primary key:
(room_id, message_id)

Existing indices:
(room_id, created_at_ms)
(room_id, ttl_expires_at_ms)
```

Không thêm index cho hai local field trong GI2E2 vì chưa có query use case yêu cầu.

## Field ownership

### `local_render_source_body`

```text
Nullable
Sender-local only
Never serialized
Never relayed
Never retried instead of body
Never accepted from remote input
```

GI2E2 chỉ tạo persistence seam. Private creation sẽ thực sự populate nó trong GI2E4.

### `is_hidden_locally`

```text
NOT NULL
Default false
Installation-local presentation state
Never serialized
Never accepted from remote input
```

Hide/Unhide write API và UI thuộc phase sau.

---

# 5. Room migration

## Version

```text
Current: 54
Target: 55
```

## Migration

Thêm:

```kotlin
val MIGRATION_54_55: Migration = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE nearby_room_messages
            ADD COLUMN local_render_source_body TEXT
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE nearby_room_messages
            ADD COLUMN is_hidden_locally INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}
```

Sau đó:

```text
AppDatabase.version = 55
DatabaseMigrations.ALL includes MIGRATION_54_55
Room schema 55.json exported
```

## Migration invariants

Migration không được:

* recreate message table;
* alter existing primary key;
* alter existing indices;
* rewrite existing body;
* change delivery state;
* infer invitation type;
* populate sender-local plaintext retroactively.

Existing rows phải nhận:

```text
local_render_source_body = null
is_hidden_locally = false
```

---

# 6. Prepared Message Context

## Proposed type

```kotlin
internal data class NearbyRoomPreparedMessage(
    val roomId: String,
    val messageId: String,
    val logicalSenderDeviceId: String,
    val senderDisplayName: String?,
    val body: String,
    val localRenderSourceBody: String?,
    val createdAtMs: Long,
    val receivedAtMs: Long,
    val ttlExpiresAtMs: Long,
    val sendLimitKey: SendLimitKey,
)
```

`replyTo`, edit/delete timestamps, delivery state và local hidden state không cần nhận từ caller vì GI2E2 phải cố định chúng:

```text
reply_to = null
delivery_state = LOCAL
edited_at_ms = null
deleted_at_ms = null
is_hidden_locally = false
```

Cách này giảm khả năng caller tạo một “prepared Invitation” nhưng truyền mutable metadata không hợp lệ.

## Send-limit key

```kotlin
internal sealed interface SendLimitKey {
    data object Public : SendLimitKey

    data class Private(
        val targetParticipantId: String,
    ) : SendLimitKey
}
```

Không cần đưa room hoặc sender vào `SendLimitKey`, vì chúng đã là field bắt buộc của prepared context.

## Vì sao không lưu decoded payload trực tiếp?

Prepared send cần persist exact finalized wire body.

Quota chỉ cần:

```text
Public
or
Private(targetParticipantId)
```

Giữ cả decoded domain object sẽ:

* duplicate body authority;
* tạo nguy cơ object và body lệch nhau;
* tăng coupling với GI2D/private payload;
* mở surface không cần thiết.

GI2E2 chỉ cần kiểm tra rằng finalized `body` decode thành đúng invitation và khớp với declared send-limit key.

---

# 7. Prepared context validation

Validation diễn ra trước transaction đối với invariant thuần dữ liệu:

```text
roomId not blank
messageId valid
logical sender not blank
body is valid GI1 invitation
cardId == messageId
reply prohibited by construction
createdAtMs >= 0
receivedAtMs >= createdAtMs
ttlExpiresAtMs > createdAtMs
local source conforms to audience rules
declared send-limit key matches decoded wire body
```

## Public

Required:

```text
decoded type = PublicInvitation
decoded cardId = messageId
sendLimitKey = Public
localRenderSourceBody = null
```

## Private

Required:

```text
decoded type = PrivateEncryptedInvitation
decoded cardId = messageId
decoded targetParticipantId = sendLimitKey.targetParticipantId
```

`localRenderSourceBody`:

* may be present for the sender-local projection;
* must not equal or replace authoritative wire `body`;
* must be validated only to the level authorized by the locked contract;
* GI2E2 must not decrypt or encrypt it.

GI2E4 remains responsible for constructing and validating the private local render source before invoking GI2E2.

## Invalid or unsupported invitation

Prepared insertion must reject:

```text
NotGroupInvitation
UnsupportedOrInvalidInvitation
```

This API is for prepared GI1 Invitation only, not a generic structured-message send API.

---

# 8. DAO changes

## 8.1 Insert-only seam

Add:

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT)
fun insertPreparedMessage(message: NearbyRoomMessageEntity)
```

Existing:

```kotlin
fun upsertMessage(message: NearbyRoomMessageEntity)
```

remains unchanged for legacy flow during GI2E2.

GI2E2 must not globally replace current `REPLACE` behavior because that would alter normal Nearby message semantics beyond milestone scope.

## 8.2 Candidate query

Add a focused query:

```kotlin
@Query(
    """
    SELECT * FROM nearby_room_messages
    WHERE room_id = :roomId
      AND delivery_state != 'SYSTEM'
      AND (
          owner_device_id = :logicalSenderDeviceId
          OR (
              owner_device_id IS NULL
              AND sender_device_id = :logicalSenderDeviceId
          )
      )
    ORDER BY created_at_ms ASC, message_id ASC
    """
)
fun listSenderMessageCandidates(
    roomId: String,
    logicalSenderDeviceId: String,
): List<NearbyRoomMessageEntity>
```

## Why not filter by deleted state?

For valid GI2E invitations:

```text
deleted_at_ms must remain null
```

However a legacy or malicious mutation must not silently release quota.

The Kotlin classifier should examine the row body and structured metadata rather than SQL excluding every deleted row.

If an existing valid GI1 body somehow has mutable timestamps, the row remains evidence and should also trigger the immutable-state audit path.

## Why no JSON SQL query?

SQLite must not know GI1 JSON semantics.

DAO owns row selection only.

Application policy owns:

```text
GI1 candidate classification
audience extraction
target extraction
business-key comparison
```

---

# 9. Repository API

## Proposed method

```kotlin
internal fun insertPreparedGroupInvitation(
    prepared: NearbyRoomPreparedMessage,
): PreparedInvitationInsertResult
```

## Proposed result

```kotlin
internal sealed interface PreparedInvitationInsertResult {
    data class Inserted(
        val message: NearbyRoomMessageEntity,
    ) : PreparedInvitationInsertResult

    data object RoomUnavailable : PreparedInvitationInsertResult
    data object SenderMuted : PreparedInvitationInsertResult
    data object SendLimitReached : PreparedInvitationInsertResult
    data object MessageIdCollision : PreparedInvitationInsertResult
    data object InvalidPreparedMessage : PreparedInvitationInsertResult
}
```

No generic exception hierarchy is necessary.

If existing repository conventions strongly prefer nullable results, GI2E2 still needs an explicit internal reason at least for:

```text
SendLimitReached
MessageIdCollision
```

Otherwise GI2E3/GI2E4 cannot safely distinguish product quota from persistence collision.

---

# 10. Exact transaction ordering

## Outside transaction

Only deterministic context validation:

```text
1. Validate prepared fields.
2. Decode exact finalized body.
3. Verify cardId/messageId.
4. Verify audience/sendLimitKey agreement.
5. Construct immutable entity candidate.
```

No room state or quota decision outside transaction is authoritative.

## Inside one `db.runInTransaction`

Exact order:

```text
1. Reload room by prepared.roomId.

2. Reject if room absent.

3. Reject if:
   room.state != ACTIVE
   or room.expires_at_ms <= transactionNowMs.

4. Verify prepared expiration:
   prepared.ttlExpiresAtMs == room.expires_at_ms
   or exactly matches the locked expiry rule used by preparation.

5. Verify frozen sender identity is still the current local sender.

6. Recheck local mute state using the transaction time.

7. Validate that no existing row occupies:
   (roomId, messageId).

8. Query sender candidate rows.

9. Decode/classify candidate rows.

10. Match exact send-limit business key.

11. If match exists:
    return SendLimitReached without writes.

12. Refresh existing local participant bookkeeping if applicable.

13. insertPreparedMessage(entity) using ABORT.

14. Refresh room last_seen_at_ms.

15. Commit.
```

## Important ordering choice

Collision check occurs before quota query result is returned to avoid ambiguous behavior when the same message ID already exists.

Recommended priority:

```text
same message ID exists
→ MessageIdCollision

different message ID but same quota key exists
→ SendLimitReached
```

This creates deterministic diagnostics.

## Transaction-local time

Use one `transactionNowMs` captured immediately before entering the transaction.

Do not repeatedly call `nowMsProvider()` within the transaction.

## No pre-transaction `expireRooms()`

The prepared insertion path should not perform broad cleanup before quota enforcement.

It should reload and validate the target room directly.

Normal retention remains owned by existing lifecycle mechanisms.

---

# 11. Atomic send-limit classifier

## Public existing evidence

A row blocks public send when:

```text
logical author == prepared logical sender
and
body decodes to valid GI1 PublicInvitation
and
the row belongs to the same room
```

## Private existing evidence

A row blocks private send when:

```text
logical author == prepared logical sender
and
body decodes to valid GI1 PrivateEncryptedInvitation
and
decoded targetParticipantId == prepared targetParticipantId
and
the row belongs to the same room
```

## Public/private independence

These do not collide:

```text
one public invitation
one private invitation to participant A
one private invitation to participant B
```

because the locked rules define different business keys.

## Candidate body/card mismatch

When a row decodes as valid GI1 but:

```text
payload.cardId != row.message_id
```

it is structurally inconsistent.

The safest GI2E2 behavior is:

```text
do not treat it as valid quota evidence
do not mutate it
record a diagnostic
```

GI2E2 must not silently repair legacy or malformed rows.

If the locked Revision 3.1 candidate contract requires a broader candidate discriminator than `GroupInvitationProtocol.decode`, implementation must reuse that exact classifier. It must not invent a second classifier in GI2E2.

---

# 12. Insert collision handling

Room’s `ABORT` insert may throw a SQLite constraint exception.

Repository should:

```text
catch only the expected primary-key collision
map to MessageIdCollision
allow unrelated database failures to propagate
```

Do not catch all `Throwable` or all database errors and label them collision.

Alternative, safer in this baseline:

```text
explicit findMessage check inside transaction
+
ABORT remains final race-safe protection
```

Even after a precheck, the insert must remain `ABORT`.

No automatic message-ID regeneration is allowed in this method.

Reason:

```text
messageId == cardId
```

and for private invitation the message ID is part of cryptographic context/AAD.

A new ID requires a newly prepared message and, for GI2E4, new encryption.

---

# 13. Authoritative immutable guard

## 13.1 Shared classification helper

Add a narrowly scoped helper:

```kotlin
internal fun classifyImmutableGroupInvitation(
    message: NearbyRoomMessageEntity,
): ImmutableInvitationClassification?
```

Classification should require at minimum:

```text
valid GI1 body
cardId == message_id
reply_to == null
```

Possible result:

```kotlin
internal sealed interface ImmutableInvitationClassification {
    data object Public : ImmutableInvitationClassification

    data class Private(
        val targetParticipantId: String,
    ) : ImmutableInvitationClassification
}
```

Do not expose plaintext, credentials or ciphertext through the classification result.

---

# 14. Local Edit guard

File:

```text
NearbyRoomRepository.kt
```

In `editLocalMessage()`:

```text
load existing
↓
system check
↓
immutable Invitation check
↓
deleted/ownership/no-change checks
↓
normal text mutation
```

The guard should occur before:

* comparing replacement body;
* updating timestamps;
* writing through `upsertMessage()`.

Result for immutable Invitation remains `null` under current API unless a broader result refactor is already justified.

Required diagnostic reason:

```text
structured_invitation_immutable
```

No invitation body or local render source should appear in the log.

---

# 15. Local Delete guard

In `deleteLocalMessage()`:

```text
load existing
↓
system check
↓
immutable Invitation check
↓
ownership/deleted checks
↓
normal text soft-delete
```

Invitation must never become:

```text
body = "Deleted"
deleted_at_ms != null
```

Required diagnostic reason:

```text
structured_invitation_immutable
```

---

# 16. Replace-in-place protection

Local edit/delete guards are insufficient because remote ingest and other repository flows can call `upsertMessage()`.

GI2E2 must protect at least every existing call site capable of replacing a user message row.

Current relevant message-table write locations include:

```text
postLocalMessage
editLocalMessage
deleteLocalMessage
markMessageSent
remote snapshot ingest
profile display-name synchronization
system join insertion
```

## Allowed update categories for an existing Invitation

### Delivery bookkeeping

Allowed:

```text
LOCAL → SENT
```

Must preserve:

```text
message_id
owner_device_id
sender_device_id
reply_to
body
created_at_ms
ttl_expires_at_ms
edited_at_ms
deleted_at_ms
local_render_source_body
is_hidden_locally
```

### Sender display-name projection

Current profile synchronization updates `sender_display_name`.

The product decision calls invitation content immutable, while display name is existing message metadata and may be treated as projection bookkeeping.

GI2E2 should preserve current profile-update ownership unless the locked Revision 3.1 contract explicitly freezes display name.

It must not change body, audience, target, timestamps or local fields.

### Local hide state

Future phase may update only:

```text
is_hidden_locally
```

No such API is implemented in GI2E2 unless required for migration tests.

---

# 17. Remote snapshot guard

File:

```text
NearbyRoomMessageEditReducer.kt
```

## New snapshot

When no row exists:

```text
existing = null
```

GI2E2 should not implement the full pre-sanitizer receive pipeline—that belongs to GI2E5.

A new incoming row may continue through current flow.

Its local-only fields must be initialized as:

```text
local_render_source_body = null
is_hidden_locally = false
```

## Existing immutable Invitation

When `existing` is a valid immutable Invitation:

Incoming snapshot may only update permitted bookkeeping.

Minimum immutable comparison:

```text
same logical author
same message ID
same reply_to
same exact body
same created_at_ms
same ttl_expires_at_ms
same edited_at_ms
same deleted_at_ms
```

For valid Invitation these expected fields are:

```text
reply_to = null
edited_at_ms = null
deleted_at_ms = null
```

Any violation returns `Decision.Skip`.

Suggested reasons:

```text
structured_invitation_actor_conflict
structured_invitation_content_conflict
structured_invitation_reply_forbidden
structured_invitation_edit_forbidden
structured_invitation_delete_forbidden
structured_invitation_created_at_conflict
structured_invitation_expiry_conflict
```

## Allowed merged fields

For exact immutable duplicate:

```text
received_at_ms = max(existing, incoming)
delivery_state = safe existing transition
sender_display_name = existing merge policy
```

Local-only fields must always come from `existing`:

```text
local_render_source_body = existing.local_render_source_body
is_hidden_locally = existing.is_hidden_locally
```

## TTL rule

Current reducer widens TTL using:

```text
maxOf(existing.ttl_expires_at_ms, incoming.ttl_expires_at_ms)
```

That behavior must not apply to an existing immutable Invitation.

For Invitation:

```text
incoming TTL != existing TTL
→ skip
```

Normal text behavior remains unchanged.

---

# 18. Remote local-field isolation

When constructing a remote `NearbyRoomMessageEntity`, explicitly set:

```kotlin
local_render_source_body = null,
is_hidden_locally = false,
```

Although defaults would produce the same values, explicit assignment at the trust boundary is preferable because it documents that transport cannot own these fields.

When merging an incoming snapshot with an existing row:

```text
never copy either local-only field from incoming
```

---

# 19. Existing retry and relay

No structural change is planned for:

```text
NearbyRoomOwnerMessageRetryCoordinator
transport envelope
relay queue
BLE runtime
```

Retry continues:

```text
roomId + messageId
↓
reload persisted entity
↓
send persisted body
```

GI2E2 adds regression tests proving retry/reload never substitutes:

```text
local_render_source_body
```

for:

```text
body
```

No Invitation-specific retry metadata is authorized.

---

# 20. Moderation/report pipeline

GI2E2 does not implement final safe invitation reporting projection—that belongs to GI2E5.

However GI2E2 must ensure new local-only source cannot accidentally leak through generic entity serialization or reporting.

Audit required:

```text
all readers of NearbyRoomMessageEntity.body
all diagnostics using message.toString()
all report payload builders
all debug export paths
```

Minimum GI2E2 rule:

```text
No new code logs or exports local_render_source_body.
```

If an existing generic entity logger serializes all fields automatically, that is a security blocker and must be fixed within GI2E2 because adding the column would otherwise introduce plaintext leakage.

---

# 21. Tests — production contract

## 21.1 Entity and migration tests

Likely files:

```text
data/src/androidTest/...
```

Required:

1. Migration 54→55 succeeds.
2. Existing message body remains unchanged.
3. Existing primary key remains unchanged.
4. Existing indices remain.
5. Existing rows receive:

   ```text
   local_render_source_body = null
   is_hidden_locally = false
   ```
6. Schema validation against `55.json` passes.

---

## 21.2 DAO insert-only tests

Required:

1. First insert succeeds.
2. Duplicate `(roomId, messageId)` throws constraint failure.
3. Original row remains byte-for-byte unchanged after collision.
4. `local_render_source_body` is not overwritten.
5. `is_hidden_locally` is not overwritten.
6. Existing generic `upsertMessage()` behavior remains available for normal-message regression coverage.

---

## 21.3 Prepared context validation tests

Required:

1. Valid public prepared message accepted.
2. Valid private prepared message accepted.
3. Non-GI1 body rejected.
4. Unsupported GI1 body rejected.
5. Public declared as private rejected.
6. Private declared as public rejected.
7. Private target mismatch rejected.
8. `cardId != messageId` rejected.
9. Public with local render source rejected.
10. Invalid expiry rejected.
11. Blank sender or room rejected.
12. Any Reply representation is impossible or rejected.

---

## 21.4 Public send-limit transaction tests

1. First public insertion succeeds.
2. Second public, same sender/room, different ID is rejected.
3. Same sender, different room is allowed.
4. Different sender, same room is allowed.
5. Existing private invitation does not consume public quota.
6. Existing normal text message does not consume public quota.
7. Existing malformed JSON does not consume quota.
8. Existing unsupported GI version behavior follows the locked candidate contract.
9. Hidden local state does not release quota.
10. Delivery-state change does not release quota.

---

## 21.5 Private send-limit transaction tests

1. First private sender→target A succeeds.
2. Second sender→target A is rejected.
3. Sender→target B is allowed.
4. Different sender→target A is allowed.
5. Existing public invitation does not consume target quota.
6. Sender-local render source does not affect quota classification.
7. Hidden local state does not affect quota.
8. Target participant is taken from exact persisted GI1 envelope.

---

## 21.6 Concurrency tests

Required authoritative proof:

### Public

Two concurrent prepared sends:

```text
same room
same sender
different message IDs
both public
```

Expected:

```text
exactly one Inserted
exactly one SendLimitReached
exactly one persisted public invitation
```

### Private

Two concurrent prepared sends:

```text
same room
same sender
same target
different message IDs
```

Expected identical one-winner behavior.

### Different targets

Two concurrent private sends to different targets should both succeed.

Tests must exercise repository transactions, not merely a fake mutex.

---

## 21.7 Transaction rollback tests

Simulate insert failure after candidate query.

Verify:

```text
no message inserted
participant row not partially updated
room last_seen not partially updated
```

Room’s transaction must roll back all writes.

---

## 21.8 Immutable local mutation tests

1. Edit public Invitation rejected.
2. Delete public Invitation rejected.
3. Edit private Invitation rejected.
4. Delete private Invitation rejected.
5. Normal text edit still works.
6. Normal text delete still works.
7. Guard rejection leaves complete original row unchanged.
8. Local fields remain unchanged.

---

## 21.9 Remote reducer tests

1. Exact Invitation duplicate accepted only as bookkeeping refresh.
2. Body replacement rejected.
3. Public→normal conversion rejected.
4. Private→normal conversion rejected.
5. Public→private replacement rejected.
6. Private target replacement rejected.
7. Reply addition rejected.
8. Edit timestamp rejected.
9. Delete timestamp rejected.
10. Deleted body rejected.
11. Created time replacement rejected.
12. TTL widening rejected.
13. TTL narrowing rejected.
14. Author change rejected.
15. Existing local render source preserved.
16. Existing hidden state preserved.
17. Normal text edit/delete reducer behavior remains unchanged.

---

## 21.10 Delivery/retry preservation tests

1. `markMessageSent()` changes only delivery state.
2. Exact wire `body` remains unchanged.
3. Local render source remains unchanged.
4. Hidden state remains unchanged.
5. Retry reload uses persisted wire body.
6. Retry does not invoke GI2D.
7. Retry does not allocate a new message ID.
8. Retry does not mutate quota evidence.

---

# 22. Logging and security rules

Logs may contain:

```text
room ID
message ID
logical sender ID
quota type: public/private
target participant ID where already normal operational metadata
result reason
```

Logs must not contain:

```text
invitation credential
private inner plaintext
local_render_source_body
ciphertext
nonce
ephemeral key
recipient public key
full wire body
```

Collision and quota failures need distinct log reasons.

Suggested labels:

```text
Nearby invitation prepared insert
Nearby invitation send-limit blocked
Nearby invitation immutable mutation blocked
Nearby invitation insert collision
```

---

# 23. Documentation updates

After implementation and tests, update or add:

```text
docs/group_invitations/GI2E2_Send_Limit_Query_and_Enforcement_Implementation.md
```

Document should record:

* exact production files;
* transaction ordering;
* result model;
* send-limit classifier;
* immutable guard coverage;
* migration version;
* tests executed;
* known limitations;
* independent-review verdict.

Roadmap may be updated only after independent review.

---

# 24. Implementation sequence

## Patch 1 — Persistence foundation

```text
Entity fields
Migration 54→55
Database version
Schema export
Migration tests
```

Stop if migration/schema verification fails.

## Patch 2 — DAO foundation

```text
insertPreparedMessage ABORT
candidate-row query
DAO tests
```

Stop if collision can replace an existing row.

## Patch 3 — Prepared contract

```text
Prepared message type
SendLimitKey
Result type
deterministic validation
validation tests
```

No repository transaction yet.

## Patch 4 — Atomic repository enforcement

```text
single transaction
room recheck
sender/mute recheck
candidate classification
quota check
insert-only write
participant/room bookkeeping
transaction tests
concurrency tests
```

Stop if concurrent same-key inserts can both succeed.

## Patch 5 — Immutable local guard

```text
edit rejection
delete rejection
replace-path audit
normal-message regression tests
```

## Patch 6 — Remote immutable reducer guard

```text
exact immutable comparison
safe bookkeeping merge
local-field preservation
TTL protection
reducer tests
```

## Patch 7 — Retry/security regression

```text
mark sent preservation
retry exact-body verification
logging leak audit
moderation/report reader audit
```

## Patch 8 — Focused verification and documentation

```text
unit tests
data migration instrumentation test
app focused tests
full relevant module tests
implementation report
```

---

# 25. Commands expected during implementation

Exact Gradle tasks should be confirmed from module configuration, but likely verification set includes:

```bash
./gradlew :core:test
./gradlew :data:test
./gradlew :app:testEmergencyDebugUnitTest
```

Migration instrumentation:

```bash
./gradlew :data:connectedAndroidTest
```

or the exact existing variant-specific task used by the project.

Before final review:

```bash
./gradlew test
```

A full connected suite is not automatically required if unrelated baseline instrumentation compilation failures exist, but every GI2E2-focused migration and transaction test must be runnable and reported separately.

---

# 26. Stop conditions

Implementation must stop and return to architecture review if any of these becomes necessary:

```text
New quota table
New Invitation repository
New Invitation transport kind
New retry/relay lifecycle
New BLE path
Second message persistence store
Viewer-keyed hidden-state model
Changing GI1 protocol
Changing GI2D AAD or encryption inputs
Sanitizing/re-encoding finalized prepared wire body
Automatically regenerating message ID after encrypted preparation
Replacing generic Nearby message semantics globally
```

Also stop if production has moved beyond database version 54 before patch application. Migration numbering must then be rebased.

---

# 27. Documentation consistency note

The supplied file is named:

```text
GI2E1_Structured_Content_Contract_LOCKED.md
```

but its internal metadata still says:

```text
Status: DRAFT FOR RE-REVIEW
```

The user-provided milestone authority and Independent Review establish that Revision 3.2 is already locked and ready for GI2E2.

This is not an implementation blocker, but the metadata should be corrected in a documentation-only change so future reviewers do not interpret the contract as unapproved.

No contract content should be changed while correcting that status.

---

# 28. Final plan verdict

```text
Architecture fit: PASS
Engineering Constitution fit: PASS
Protocol change required: NO
Crypto change required: NO
Transport change required: NO
New subsystem required: NO
Migration approach: ADDITIVE
Transaction authority: EXISTING ROOM DATABASE
Implementation risk: CONTROLLED
```

## Authorized implementation shape

```text
NearbyRoomRepository
+
immutable prepared GI1 context
+
focused candidate query
+
serialized query-and-insert transaction
+
ABORT insert
+
authoritative immutable mutation guards
+
additive message-row local fields
```

## Plan status

```text
GI2E2 IMPLEMENTATION PLAN: READY FOR REVIEW
PRODUCTION IMPLEMENTATION: NOT YET STARTED
```
