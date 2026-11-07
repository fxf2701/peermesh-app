package app.peermesh.nearby

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PrivateGroupInvitationInnerPayload
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.core.groupinvitation.PublicGroupInvitationPayload
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity
import app.peermesh.data.db.entity.NearbyRoomParticipantEntity
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyGroupInvitationPreparationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: NearbyRoomRepository
    private var nowMs = NOW_MS

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NearbyRoomRepository(context, db, nowMsProvider = { nowMs })
        insertRoom(ROOM_ID)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun repositoryGeneratesFrozenIdAndCapabilityIsOneShot() {
        val preparation = publicPreparation()
        assertTrue(preparation.messageId.isNotBlank())
        assertNotEquals(ROOM_ID, preparation.messageId)
        val body = publicBody(preparation)

        assertTrue(repository.insertPreparedGroupInvitation(preparation, body) is PreparedGroupInvitationInsertResult.Inserted)
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparation,
            repository.insertPreparedGroupInvitation(preparation, body),
        )
    }

    @Test
    fun callerImplementedCapabilityIsRejectedBeforeValidation() {
        val genuine = publicPreparation()
        val forged = object : NearbyGroupInvitationPreparation {
            override val roomId = genuine.roomId
            override val messageId = genuine.messageId
            override val logicalSenderId = genuine.logicalSenderId
            override val senderDisplayName = genuine.senderDisplayName
            override val createdAtMs = genuine.createdAtMs
            override val effectiveExpiryAtMs = genuine.effectiveExpiryAtMs
            override val audience = genuine.audience
            override val recipientEncryptionIdentity = genuine.recipientEncryptionIdentity
        }

        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparation,
            repository.insertPreparedGroupInvitation(forged, publicBody(genuine)),
        )
        assertTrue(
            repository.insertPreparedGroupInvitation(genuine, publicBody(genuine)) is
                PreparedGroupInvitationInsertResult.Inserted
        )
    }

    @Test
    fun sameTokenSubmittedConcurrentlyHasExactlyOneClaimant() {
        val preparation = publicPreparation()
        val body = publicBody(preparation)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<PreparedGroupInvitationInsertResult> {
                    start.await()
                    repository.insertPreparedGroupInvitation(preparation, body)
                }
            }
            start.countDown()
            val results = futures.map { it.get() }

            assertEquals(1, results.count { it is PreparedGroupInvitationInsertResult.Inserted })
            assertEquals(1, results.count { it == PreparedGroupInvitationInsertResult.InvalidPreparation })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun expiredAndCapacityEvictedCapabilitiesAreRejected() {
        val expired = publicPreparation()
        nowMs += NearbyRoomRepository.PREPARATION_LIFETIME_MS
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparation,
            repository.insertPreparedGroupInvitation(expired, publicBody(expired)),
        )

        nowMs = NOW_MS
        val oldest = publicPreparation()
        repeat(NearbyRoomRepository.MAX_PENDING_PREPARATIONS) { publicPreparation() }
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparation,
            repository.insertPreparedGroupInvitation(oldest, publicBody(oldest)),
        )
    }

    @Test
    fun publicCanonicalValidationRejectsVariantsWrongIdAndSource() {
        listOf<(NearbyGroupInvitationPreparation, String) -> String>(
            { _, canonical -> " $canonical" },
            { _, canonical -> canonical.replace("{\"a\":\"r\"", "{ \"a\":\"r\"") },
            { _, canonical -> canonical.dropLast(1) + ",\"x\":1}" },
            { preparation, _ -> publicBody(preparation, cardId = "wrong-id") },
        ).forEach { variant ->
            val preparation = publicPreparation()
            assertEquals(
                PreparedGroupInvitationInsertResult.InvalidPreparedContent,
                repository.insertPreparedGroupInvitation(preparation, variant(preparation, publicBody(preparation))),
            )
        }
        val withSource = publicPreparation()
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparedContent,
            repository.insertPreparedGroupInvitation(withSource, publicBody(withSource), "{}"),
        )
    }

    @Test
    fun noncanonicalPublicCandidatesDoNotConsumeQuota() {
        listOf<(String) -> String>(
            ::reorderedJson,
            { canonical -> " $canonical" },
            { canonical -> canonical.dropLast(1) + ",\"x\":1}" },
        ).forEachIndexed { index, variant ->
            val roomId = "room-public-noncanonical-$index"
            val preparation = publicPreparation(roomId)
            db.nearbyRoomMessageDao().upsertMessage(
                message(
                    messageId = "candidate-public-$index",
                    body = variant(publicBody(preparation, cardId = "candidate-public-$index")),
                    roomId = roomId,
                ).copy(owner_device_id = preparation.logicalSenderId)
            )

            assertTrue(
                repository.insertPreparedGroupInvitation(preparation, publicBody(preparation)) is
                    PreparedGroupInvitationInsertResult.Inserted
            )
        }
    }

    @Test
    fun publicQuotaIsAtomicAndIndependentAcrossRooms() {
        val first = publicPreparation()
        assertTrue(repository.insertPreparedGroupInvitation(first, publicBody(first)) is PreparedGroupInvitationInsertResult.Inserted)
        val second = publicPreparation()
        assertEquals(
            PreparedGroupInvitationInsertResult.SendLimitReached,
            repository.insertPreparedGroupInvitation(second, publicBody(second)),
        )

        insertRoom(OTHER_ROOM_ID)
        val other = requireNotNull(
            repository.prepareGroupInvitationContext(OTHER_ROOM_ID, NearbyGroupInvitationAudience.Public)
        )
        assertTrue(repository.insertPreparedGroupInvitation(other, publicBody(other)) is PreparedGroupInvitationInsertResult.Inserted)
    }

    @Test
    fun malformedAndUnsupportedExactGiRowsDoNotProvideQuotaEvidence() {
        val preparation = publicPreparation()
        db.nearbyRoomMessageDao().upsertMessage(
            message("malformed-gi", "{\"t\":\"gi\"}").copy(owner_device_id = preparation.logicalSenderId)
        )
        db.nearbyRoomMessageDao().upsertMessage(
            message("unsupported-gi", "{\"i\":\"unsupported-gi\",\"t\":\"gi\",\"v\":99}")
                .copy(owner_device_id = preparation.logicalSenderId)
        )

        assertTrue(
            repository.insertPreparedGroupInvitation(preparation, publicBody(preparation)) is
                PreparedGroupInvitationInsertResult.Inserted
        )
    }

    @Test
    fun concurrentSamePublicBusinessKeyPersistsExactlyOne() {
        val preparations = listOf(publicPreparation(), publicPreparation())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = preparations.map { preparation ->
                executor.submit<PreparedGroupInvitationInsertResult> {
                    start.await()
                    repository.insertPreparedGroupInvitation(preparation, publicBody(preparation))
                }
            }
            start.countDown()
            val results = futures.map { it.get() }
            assertEquals(1, results.count { it is PreparedGroupInvitationInsertResult.Inserted })
            assertEquals(1, results.count { it == PreparedGroupInvitationInsertResult.SendLimitReached })
            assertEquals(1, db.nearbyRoomMessageDao().listMessages(ROOM_ID, 10).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun privateCanonicalSourceAndPerTargetQuotaAreEnforced() {
        val identityA = insertTarget("target-a")
        insertTarget("target-b")
        val first = privatePreparation("target-a")
        assertEquals(identityA.keyId, first.recipientEncryptionIdentity?.keyId)
        assertTrue(
            repository.insertPreparedGroupInvitation(first, privateBody(first), privateSource()) is
                PreparedGroupInvitationInsertResult.Inserted
        )

        val sameTarget = privatePreparation("target-a")
        assertEquals(
            PreparedGroupInvitationInsertResult.SendLimitReached,
            repository.insertPreparedGroupInvitation(sameTarget, privateBody(sameTarget), privateSource()),
        )
        val otherTarget = privatePreparation("target-b")
        assertTrue(
            repository.insertPreparedGroupInvitation(otherTarget, privateBody(otherTarget), privateSource()) is
                PreparedGroupInvitationInsertResult.Inserted
        )
    }

    @Test
    fun privateRequiresCanonicalSourceAndFrozenOuterIdentity() {
        insertTarget("target-a")
        val nullSource = privatePreparation("target-a")
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparedContent,
            repository.insertPreparedGroupInvitation(nullSource, privateBody(nullSource), null),
        )
        val noncanonicalSource = privatePreparation("target-a")
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparedContent,
            repository.insertPreparedGroupInvitation(noncanonicalSource, privateBody(noncanonicalSource), " ${privateSource()}"),
        )
        val wrongTarget = privatePreparation("target-a")
        assertEquals(
            PreparedGroupInvitationInsertResult.InvalidPreparedContent,
            repository.insertPreparedGroupInvitation(
                wrongTarget,
                privateBody(wrongTarget, targetOverride = "target-b"),
                privateSource(),
            ),
        )
    }

    @Test
    fun noncanonicalPrivateCandidatesDoNotConsumeQuota() {
        repeat(3) { index ->
            val roomId = "room-private-noncanonical-$index"
            val target = "target-private-$index"
            insertRoom(roomId)
            insertTarget(target, roomId)
            val preparation = privatePreparation(target, roomId)
            val candidateId = "candidate-private-$index"
            val canonical = privateBody(preparation, cardId = candidateId)
            val candidateBody = when (index) {
                0 -> reorderedJson(canonical)
                1 -> " $canonical"
                else -> privateBody(
                    preparation,
                    cardId = candidateId,
                    ciphertextOverride = noncanonicalCiphertext(),
                )
            }
            db.nearbyRoomMessageDao().upsertMessage(
                message(
                    messageId = candidateId,
                    body = candidateBody,
                    roomId = roomId,
                ).copy(owner_device_id = preparation.logicalSenderId)
            )

            assertTrue(
                repository.insertPreparedGroupInvitation(preparation, privateBody(preparation), privateSource()) is
                    PreparedGroupInvitationInsertResult.Inserted
            )
        }
    }

    @Test
    fun staleExpiryMuteAndMessageIdCollisionHaveDistinctResults() {
        val stale = publicPreparation()
        db.nearbyRoomDao().upsert(requireNotNull(db.nearbyRoomDao().findById(ROOM_ID)).copy(expires_at_ms = EXPIRES_AT_MS - 1))
        assertEquals(
            PreparedGroupInvitationInsertResult.StalePreparation,
            repository.insertPreparedGroupInvitation(stale, publicBody(stale)),
        )

        db.nearbyRoomDao().upsert(requireNotNull(db.nearbyRoomDao().findById(ROOM_ID)).copy(expires_at_ms = EXPIRES_AT_MS))
        val muted = publicPreparation()
        db.nearbyRoomMuteStateDao().upsert(
            NearbyRoomMuteStateEntity(ROOM_ID, muted.logicalSenderId, "owner", true, nowMs, EXPIRES_AT_MS)
        )
        assertEquals(
            PreparedGroupInvitationInsertResult.SenderMuted,
            repository.insertPreparedGroupInvitation(muted, publicBody(muted)),
        )

        db.nearbyRoomMuteStateDao().upsert(
            NearbyRoomMuteStateEntity(ROOM_ID, muted.logicalSenderId, "owner", false, nowMs, EXPIRES_AT_MS)
        )
        val collision = publicPreparation()
        db.nearbyRoomMessageDao().upsertMessage(message(collision.messageId, "normal"))
        assertEquals(
            PreparedGroupInvitationInsertResult.MessageIdCollision,
            repository.insertPreparedGroupInvitation(collision, publicBody(collision)),
        )
        assertEquals("normal", db.nearbyRoomMessageDao().findMessage(ROOM_ID, collision.messageId)?.body)
    }

    @Test
    fun expiryMismatchWinsEvenWhenCurrentRoomIsExpired() {
        val preparation = publicPreparation()
        db.nearbyRoomDao().upsert(
            requireNotNull(db.nearbyRoomDao().findById(ROOM_ID)).copy(expires_at_ms = NOW_MS)
        )

        assertEquals(
            PreparedGroupInvitationInsertResult.StalePreparation,
            repository.insertPreparedGroupInvitation(preparation, publicBody(preparation)),
        )
    }

    @Test
    fun matchingExpiryButExpiredRoomIsUnavailable() {
        val preparation = publicPreparation()
        nowMs = EXPIRES_AT_MS

        assertEquals(
            PreparedGroupInvitationInsertResult.RoomUnavailable,
            repository.insertPreparedGroupInvitation(preparation, publicBody(preparation)),
        )
    }

    @Test
    fun missingRoomIsUnavailable() {
        val preparation = publicPreparation()
        db.nearbyRoomDao().deleteByRoom(ROOM_ID)

        assertEquals(
            PreparedGroupInvitationInsertResult.RoomUnavailable,
            repository.insertPreparedGroupInvitation(preparation, publicBody(preparation)),
        )
    }

    @Test
    fun claimSamplesTimeAfterWaitingForRegistryLock() {
        val clock = AtomicLong(NOW_MS)
        val claimNowRead = CountDownLatch(1)
        val allowClaimNow = CountDownLatch(1)
        repository = NearbyRoomRepository(
            context,
            db,
            nowMsProvider = {
                val observed = clock.get()
                if (Thread.currentThread().name == CLAIM_THREAD_NAME) {
                    claimNowRead.countDown()
                    allowClaimNow.await()
                }
                observed
            },
        )
        val preparation = publicPreparation()
        val lock = NearbyRoomRepository::class.java.getDeclaredField("pendingPreparationLock").let { field ->
            field.isAccessible = true
            field.get(repository)
        }
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val claimant = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CLAIM_THREAD_NAME) }
        try {
            val held = holder.submit {
                synchronized(lock) {
                    lockHeld.countDown()
                    releaseLock.await()
                }
            }
            assertTrue(lockHeld.await(1, TimeUnit.SECONDS))
            val result = claimant.submit<PreparedGroupInvitationInsertResult> {
                repository.insertPreparedGroupInvitation(preparation, publicBody(preparation))
            }

            assertFalse(claimNowRead.await(250, TimeUnit.MILLISECONDS))
            clock.set(EXPIRES_AT_MS)
            allowClaimNow.countDown()
            releaseLock.countDown()
            held.get()

            assertEquals(PreparedGroupInvitationInsertResult.InvalidPreparation, result.get())
        } finally {
            allowClaimNow.countDown()
            releaseLock.countDown()
            holder.shutdownNow()
            claimant.shutdownNow()
        }
    }

    @Test
    fun insertOnlyDaoAbortsWithoutReplacingOriginalWhileLegacyUpsertStillReplaces() {
        val original = message("same-id", "first")
        db.nearbyRoomMessageDao().insertPreparedMessage(original)
        val failure = runCatching {
            db.nearbyRoomMessageDao().insertPreparedMessage(original.copy(body = "second"))
        }
        assertTrue(failure.isFailure)
        assertEquals("first", db.nearbyRoomMessageDao().findMessage(ROOM_ID, "same-id")?.body)

        db.nearbyRoomMessageDao().upsertMessage(original.copy(body = "legacy replacement"))
        assertEquals("legacy replacement", db.nearbyRoomMessageDao().findMessage(ROOM_ID, "same-id")?.body)
    }

    @Test
    fun legacySendEditAndDeleteCannotCreateOrMutateExactGi() {
        val exactGi = "{\"t\":\"gi\",\"v\":99}"
        assertNull(repository.postLocalMessage(ROOM_ID, exactGi))
        val local = message("immutable-id", exactGi).copy(owner_device_id = repository.localDeviceId())
        db.nearbyRoomMessageDao().upsertMessage(local)

        assertNull(repository.editLocalMessage(ROOM_ID, local.message_id, "replacement"))
        assertNull(repository.deleteLocalMessage(ROOM_ID, local.message_id))
        assertEquals(exactGi, db.nearbyRoomMessageDao().findMessage(ROOM_ID, local.message_id)?.body)
        assertNull(db.nearbyRoomMessageDao().findMessage(ROOM_ID, local.message_id)?.deleted_at_ms)
    }

    private fun publicPreparation(roomId: String = ROOM_ID): NearbyGroupInvitationPreparation {
        if (roomId != ROOM_ID) insertRoom(roomId)
        return requireNotNull(
            repository.prepareGroupInvitationContext(roomId, NearbyGroupInvitationAudience.Public)
        )
    }

    private fun privatePreparation(
        target: String,
        roomId: String = ROOM_ID,
    ): NearbyGroupInvitationPreparation = requireNotNull(
        repository.prepareGroupInvitationContext(roomId, NearbyGroupInvitationAudience.Private(target))
    )

    private fun publicBody(preparation: NearbyGroupInvitationPreparation, cardId: String = preparation.messageId): String =
        GroupInvitationProtocol.encodePublic(
            PublicGroupInvitationPayload(cardId, "Group", "Description", InvitationCredential.of("credential"))
        )

    private fun privateSource(): String = GroupInvitationProtocol.encodePrivateInnerPayload(
        PrivateGroupInvitationInnerPayload("Group", "Description", InvitationCredential.of("credential"))
    )

    private fun privateBody(
        preparation: NearbyGroupInvitationPreparation,
        targetOverride: String? = null,
        cardId: String = preparation.messageId,
        ciphertextOverride: String = Base64Url.encode(ByteArray(32) { 3 }),
    ): String {
        val identity = requireNotNull(preparation.recipientEncryptionIdentity)
        return GroupInvitationProtocol.encodePrivateEnvelope(
            PrivateGroupInvitationEnvelope(
                cardId = cardId,
                targetParticipantId = targetOverride
                    ?: (preparation.audience as NearbyGroupInvitationAudience.Private).targetParticipantId,
                algorithm = identity.algorithm,
                targetKeyId = identity.keyId,
                ephemeralPublicKey = Base64Url.encode(validPublicKey()),
                nonce = Base64Url.encode(ByteArray(12) { 2 }),
                ciphertext = ciphertextOverride,
            )
        )
    }

    private fun insertTarget(
        targetId: String,
        roomId: String = ROOM_ID,
    ): NearbyRecipientEncryptionPublicIdentity {
        val publicKey = validPublicKey()
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        val identity = NearbyRecipientEncryptionPublicIdentity(
            algorithm,
            RecipientEncryptionKeyId.derive(algorithm, publicKey),
            publicKey,
        )
        db.nearbyRoomParticipantDao().upsert(
            NearbyRoomParticipantEntity(
                roomId, targetId, targetId, null, null, NOW_MS, NOW_MS,
                NearbyRoomParticipantEntity.ROLE_PARTICIPANT,
                NearbyRoomParticipantEntity.STATUS_ACTIVE,
                false,
                identity.algorithm.wireValue,
                identity.keyId,
                identity.publicKeySec1,
            )
        )
        return identity
    }

    private fun validPublicKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return P256Sec1PublicKeyCodec.encode(generator.generateKeyPair().public)
    }

    private fun insertRoom(roomId: String) {
        db.nearbyRoomDao().upsert(
            NearbyRoomEntity(
                roomId, "Room", "owner", null, 0L, EXPIRES_AT_MS, null,
                NearbyRoomEntity.STATE_ACTIVE, 1, false, NOW_MS, NearbyRoomEntity.SOURCE_BLE,
                NearbyRoomEntity.METADATA_RESOLVED, null, "owner",
            )
        )
    }

    private fun message(messageId: String, body: String, roomId: String = ROOM_ID) = NearbyRoomMessageEntity(
        roomId, messageId, null, "sender", "sender", "Sender", body,
        NOW_MS, NOW_MS, EXPIRES_AT_MS, NearbyRoomMessageEntity.DELIVERY_RECEIVED, null, null,
    )

    private fun reorderedJson(canonical: String): String {
        val members = canonical.removePrefix("{").removeSuffix("}").split(',')
        return members.reversed().joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    private fun noncanonicalCiphertext(): String {
        val canonical = Base64Url.encode(ByteArray(32) { 3 })
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val lastIndex = alphabet.indexOf(canonical.last())
        val alternate = canonical.dropLast(1) + alphabet[(lastIndex / 4) * 4 + ((lastIndex + 1) % 4)]
        check(Base64Url.decode(alternate).contentEquals(Base64Url.decode(canonical)))
        return alternate
    }

    companion object {
        private const val ROOM_ID = "room-gi2e2"
        private const val OTHER_ROOM_ID = "room-gi2e2-other"
        private const val NOW_MS = 1_000L
        private const val EXPIRES_AT_MS = 1_000_000L
        private const val CLAIM_THREAD_NAME = "gi2e2-claim-thread"
    }
}
