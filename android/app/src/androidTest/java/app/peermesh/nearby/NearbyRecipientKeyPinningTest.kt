package app.peermesh.nearby

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyRecipientKeyPinningTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: NearbyRoomRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NearbyRoomRepository(context, db, nowMsProvider = { NOW_MS })
        insertActiveRoom()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun firstValidIdentityPinsExactTupleAndSameIdentityMatches() {
        val identity = validIdentity()

        val pinned = selfInfo(identity)
        val matched = selfInfo(identity, seenAtMs = NOW_MS + 1)

        assertEquals(NearbyRecipientKeyPinResult.Pinned, pinned.recipientKeyPinResult)
        assertEquals(NearbyRecipientKeyPinResult.Matched, matched.recipientKeyPinResult)
        assertPersistedIdentity(identity)
    }

    @Test
    fun conflictingIdentityDoesNotOverwriteFirstPin() {
        val first = validIdentity()
        val conflicting = validIdentity()
        selfInfo(first)

        val result = selfInfo(conflicting, seenAtMs = NOW_MS + 1)

        assertEquals(NearbyRecipientKeyPinResult.Conflict, result.recipientKeyPinResult)
        assertPersistedIdentity(first)
    }

    @Test
    fun sameKeyIdWithDifferentPublicKeyConflicts() {
        val first = validIdentity()
        val other = validIdentity()
        val conflicting = NearbyRecipientEncryptionPublicIdentity(
            algorithm = first.algorithm,
            keyId = first.keyId,
            publicKeySec1 = other.publicKeySec1,
        )
        selfInfo(first)

        assertEquals(
            NearbyRecipientKeyPinResult.Conflict,
            selfInfo(conflicting, seenAtMs = NOW_MS + 1).recipientKeyPinResult,
        )
        assertPersistedIdentity(first)
    }

    @Test
    fun samePublicKeyWithDifferentKeyIdConflicts() {
        val first = validIdentity()
        val conflicting = NearbyRecipientEncryptionPublicIdentity(
            algorithm = first.algorithm,
            keyId = "A".repeat(43),
            publicKeySec1 = first.publicKeySec1,
        )
        selfInfo(first)

        assertEquals(
            NearbyRecipientKeyPinResult.Conflict,
            selfInfo(conflicting, seenAtMs = NOW_MS + 1).recipientKeyPinResult,
        )
        assertPersistedIdentity(first)
    }

    @Test
    fun absentIdentityDoesNotMutatePin() {
        val first = validIdentity()
        selfInfo(first)

        val result = selfInfo(null, seenAtMs = NOW_MS + 1)

        assertEquals(NearbyRecipientKeyPinResult.NoValidIdentity, result.recipientKeyPinResult)
        assertPersistedIdentity(first)
    }

    @Test
    fun identityOnlySelfInfoPinsWithoutProfileFields() {
        val identity = validIdentity()

        val result = selfInfo(identity)

        assertEquals(NearbyRecipientKeyPinResult.Pinned, result.recipientKeyPinResult)
        assertPersistedIdentity(identity)
    }

    @Test
    fun presenceAndProfileUpdatesPreservePin() {
        val identity = validIdentity()
        selfInfo(identity)

        repository.upsertParticipantPresence(
            roomId = ROOM_ID,
            participantDeviceId = PARTICIPANT_ID,
            participantDisplayName = "Alice",
            participantAvatarId = null,
            profileUpdatedAtMs = 2L,
            seenAtMs = NOW_MS + 1,
        )
        repository.upsertParticipantSelfInfo(
            roomId = ROOM_ID,
            participantDeviceId = PARTICIPANT_ID,
            participantDisplayName = "Alice 2",
            participantAvatarId = null,
            profileUpdatedAtMs = 3L,
            recipientEncryptionIdentity = null,
            seenAtMs = NOW_MS + 2,
        )

        assertEquals("Alice 2", participant().display_name)
        assertPersistedIdentity(identity)
    }

    @Test
    fun conflictDoesNotBlockProfileUpdate() {
        val first = validIdentity()
        selfInfo(first, displayName = "Alice", profileUpdatedAtMs = 1L)

        val result = selfInfo(
            identity = validIdentity(),
            displayName = "Alice Updated",
            profileUpdatedAtMs = 2L,
            seenAtMs = NOW_MS + 1,
        )

        assertEquals(NearbyRecipientKeyPinResult.Conflict, result.recipientKeyPinResult)
        assertEquals("Alice Updated", participant().display_name)
        assertPersistedIdentity(first)
    }

    @Test
    fun concurrentCompetingFirstPinsPersistExactlyOneTuple() {
        val first = validIdentity()
        val second = validIdentity()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).mapIndexed { index, identity ->
                executor.submit<NearbyParticipantSelfInfoResult> {
                    start.await()
                    selfInfo(identity, seenAtMs = NOW_MS + index)
                }
            }
            start.countDown()
            val results = futures.map { it.get() }
            val persisted = participant()
            val winner = if (persisted.recipient_encryption_key_id == first.keyId) first else second

            assertEquals(1, results.count { it.recipientKeyPinResult == NearbyRecipientKeyPinResult.Pinned })
            assertEquals(1, results.count { it.recipientKeyPinResult == NearbyRecipientKeyPinResult.Conflict })
            assertPersistedIdentity(winner)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun partialAndProtocolInvalidStoredPinsFailClosed() {
        val first = validIdentity()
        selfInfo(first)
        db.nearbyRoomParticipantDao().upsert(
            participant().copy(recipient_encryption_key_id = null)
        )

        val partialResult = selfInfo(validIdentity(), seenAtMs = NOW_MS + 1)
        assertEquals(NearbyRecipientKeyPinResult.StoredPinInvalid, partialResult.recipientKeyPinResult)
        assertNull(participant().recipient_encryption_key_id)

        db.nearbyRoomParticipantDao().upsert(
            participant().copy(
                recipient_encryption_algorithm = "unsupported-algorithm",
                recipient_encryption_key_id = first.keyId,
                recipient_encryption_public_key = first.publicKeySec1,
            )
        )
        val invalidResult = selfInfo(validIdentity(), seenAtMs = NOW_MS + 2)
        assertEquals(NearbyRecipientKeyPinResult.StoredPinInvalid, invalidResult.recipientKeyPinResult)
        assertEquals("unsupported-algorithm", participant().recipient_encryption_algorithm)
    }

    @Test
    fun missingClosedAndExpiredRoomsAreUnavailableWithoutOrphanPin() {
        val identity = validIdentity()
        val missing = repository.upsertParticipantSelfInfo(
            roomId = "missing-room",
            participantDeviceId = PARTICIPANT_ID,
            participantDisplayName = null,
            participantAvatarId = null,
            profileUpdatedAtMs = null,
            recipientEncryptionIdentity = identity,
            seenAtMs = NOW_MS,
        )
        assertEquals(NearbyRecipientKeyPinResult.RoomUnavailable, missing.recipientKeyPinResult)
        assertNull(db.nearbyRoomParticipantDao().findParticipant("missing-room", PARTICIPANT_ID))

        db.nearbyRoomDao().upsert(room().copy(state = NearbyRoomEntity.STATE_CLOSED))
        assertEquals(
            NearbyRecipientKeyPinResult.RoomUnavailable,
            selfInfo(identity, seenAtMs = NOW_MS + 1).recipientKeyPinResult,
        )
        assertNull(db.nearbyRoomParticipantDao().findParticipant(ROOM_ID, PARTICIPANT_ID))

        db.nearbyRoomDao().upsert(room().copy(state = NearbyRoomEntity.STATE_ACTIVE, expires_at_ms = NOW_MS))
        assertEquals(
            NearbyRecipientKeyPinResult.RoomUnavailable,
            selfInfo(identity, seenAtMs = NOW_MS + 2).recipientKeyPinResult,
        )
        assertNull(db.nearbyRoomParticipantDao().findParticipant(ROOM_ID, PARTICIPANT_ID))
    }

    @Test
    fun purgeRemovesParticipantAndPin() {
        selfInfo(validIdentity())

        assertEquals(true, repository.purgeRoomLocalData(ROOM_ID))

        assertNull(db.nearbyRoomParticipantDao().findParticipant(ROOM_ID, PARTICIPANT_ID))
        assertNull(db.nearbyRoomDao().findById(ROOM_ID))
    }

    private fun selfInfo(
        identity: NearbyRecipientEncryptionPublicIdentity?,
        displayName: String? = null,
        profileUpdatedAtMs: Long? = null,
        seenAtMs: Long = NOW_MS,
    ): NearbyParticipantSelfInfoResult = repository.upsertParticipantSelfInfo(
        roomId = ROOM_ID,
        participantDeviceId = PARTICIPANT_ID,
        participantDisplayName = displayName,
        participantAvatarId = null,
        profileUpdatedAtMs = profileUpdatedAtMs,
        recipientEncryptionIdentity = identity,
        seenAtMs = seenAtMs,
    )

    private fun insertActiveRoom() {
        db.nearbyRoomDao().upsert(
            NearbyRoomEntity(
                room_id = ROOM_ID,
                name = "Room",
                owner_device_id = PARTICIPANT_ID,
                owner_display_name = null,
                created_at_ms = 0L,
                expires_at_ms = EXPIRES_AT_MS,
                closed_at_ms = null,
                state = NearbyRoomEntity.STATE_ACTIVE,
                participant_count = 0,
                is_local_owner = false,
                last_seen_at_ms = 0L,
                source = NearbyRoomEntity.SOURCE_BLE,
                metadata_status = NearbyRoomEntity.METADATA_RESOLVED,
                metadata_last_fetched_at = null,
                metadata_source_device_id = PARTICIPANT_ID,
            )
        )
    }

    private fun room(): NearbyRoomEntity = requireNotNull(db.nearbyRoomDao().findById(ROOM_ID))

    private fun participant() = requireNotNull(
        db.nearbyRoomParticipantDao().findParticipant(ROOM_ID, PARTICIPANT_ID)
    )

    private fun assertPersistedIdentity(identity: NearbyRecipientEncryptionPublicIdentity) {
        val participant = participant()
        assertEquals(identity.algorithm.wireValue, participant.recipient_encryption_algorithm)
        assertEquals(identity.keyId, participant.recipient_encryption_key_id)
        assertArrayEquals(identity.publicKeySec1, participant.recipient_encryption_public_key)
    }

    private fun validIdentity(): NearbyRecipientEncryptionPublicIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = P256Sec1PublicKeyCodec.encode(generator.generateKeyPair().public)
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        return NearbyRecipientEncryptionPublicIdentity(
            algorithm = algorithm,
            keyId = RecipientEncryptionKeyId.derive(algorithm, publicKey),
            publicKeySec1 = publicKey,
        )
    }

    companion object {
        private const val ROOM_ID = "room-gi2c"
        private const val PARTICIPANT_ID = "participant-a"
        private const val NOW_MS = 1_000L
        private const val EXPIRES_AT_MS = 100_000L
    }
}
