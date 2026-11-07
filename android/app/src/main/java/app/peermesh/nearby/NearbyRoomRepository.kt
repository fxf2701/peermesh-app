package app.peermesh.nearby

import android.content.Context
import app.peermesh.AvatarUtils
import app.peermesh.DeviceIdProvider
import app.peermesh.LocalUserProfile
import app.peermesh.LocalUserProfileStore
import app.peermesh.R
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.joinLinkForUserAction
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.core.json.CanonicalJson
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.BlockedPeerEntity
import app.peermesh.data.db.entity.ModerationActionEntity
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomInviteQuotaEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity
import app.peermesh.data.db.entity.NearbyRoomParticipantEntity
import app.peermesh.data.db.entity.NearbyRoomRelayOfferEntity
import app.peermesh.data.db.entity.NearbyRoomRelayWantEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.moderation.BlockedPeerScopes.GLOBAL_LOCAL
import app.peermesh.security.groupinvitation.AndroidKeystoreRecipientEncryptionIdentityProvider
import app.peermesh.security.groupinvitation.JcaPrivateRecipientCrypto
import java.util.UUID
import kotlinx.serialization.encodeToString

class NearbyRoomRepository(
    context: Context,
    private val db: AppDatabase = AppDatabaseProvider.get(context.applicationContext),
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val roomIdProvider: () -> String = { NearbyRoomBleCodec.newCompactRoomId() },
    private val messageIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val preparationTokenProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val appContext = context.applicationContext
    private val keyManager = NearbyRoomKeyManager(appContext)
    private val localUserProfileStore = LocalUserProfileStore(appContext)
    private val pendingPreparationLock = Any()
    private val pendingPreparations = linkedMapOf<String, IssuedNearbyGroupInvitationPreparation>()
    private val privateInvitationInspector = NearbyPrivateGroupInvitationInspector(
        JcaPrivateRecipientCrypto(
            AndroidKeystoreRecipientEncryptionIdentityProvider(appContext)
        )::decrypt
    )
    private val groupInvitationLocalVisibilityController =
        NearbyGroupInvitationLocalVisibilityController()
    private val publicGroupInvitationLocalVisibilityController =
        NearbyPublicGroupInvitationLocalVisibilityController()

    fun createRoom(
        name: String,
        ownerDisplayName: String? = null,
    ): NearbyRoomEntity {
        val sanitizedName = NearbyRoomPolicy.sanitizeRoomName(name)
        require(NearbyRoomPolicy.isValidRoomName(sanitizedName)) { "Room name must not be blank" }

        val nowMs = nowMsProvider()
        val resolvedOwnerDisplayName = ownerDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: localUserProfileStore.load()?.displayName
        val localProfile = localUserProfileStore.load()
        val existingOwnedRoom = db.nearbyRoomDao().findActiveLocalOwnedRoom(nowMs)
        if (existingOwnedRoom != null) {
            throw IllegalStateException("Active nearby room already exists: ${existingOwnedRoom.room_id}")
        }
        val roomId = roomIdProvider()
        val ownerDeviceId = localDeviceId()
        val room = NearbyRoomEntity(
            room_id = roomId,
            name = sanitizedName,
            owner_device_id = ownerDeviceId,
            owner_display_name = resolvedOwnerDisplayName,
            created_at_ms = nowMs,
            expires_at_ms = nowMs + NearbyRoomPolicy.ROOM_TTL_MS,
            closed_at_ms = null,
            state = NearbyRoomEntity.STATE_ACTIVE,
            participant_count = 1,
            is_local_owner = true,
            last_seen_at_ms = nowMs,
            source = NearbyRoomEntity.SOURCE_LOCAL,
            metadata_status = NearbyRoomEntity.METADATA_RESOLVED,
            metadata_last_fetched_at = nowMs,
            metadata_source_device_id = ownerDeviceId,
        )
        val ownerParticipant = NearbyRoomParticipantEntity(
            room_id = roomId,
            device_id = ownerDeviceId,
            display_name = resolvedOwnerDisplayName,
            avatar_id = localProfile?.avatarId,
            profile_updated_at_ms = localProfile?.updatedAt,
            joined_at_ms = nowMs,
            last_seen_at_ms = nowMs,
            role = NearbyRoomParticipantEntity.ROLE_OWNER,
            status = NearbyRoomParticipantEntity.STATUS_ACTIVE,
            invitation_blocked = false,
            recipient_encryption_algorithm = null,
            recipient_encryption_key_id = null,
            recipient_encryption_public_key = null,
        )

        db.runInTransaction {
            db.nearbyRoomDao().upsert(room)
            db.nearbyRoomParticipantDao().upsert(ownerParticipant)
        }
        keyManager.ensureOwnerKey(room)
        return room
    }

    fun getRoom(roomId: String): NearbyRoomEntity? {
        expireRooms()
        return db.nearbyRoomDao().findById(roomId)
    }

    fun listVisibleRooms(): List<NearbyRoomEntity> {
        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        return db.nearbyRoomDao().listVisible(nowMs)
    }

    fun listActiveRooms(): List<NearbyRoomEntity> {
        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        return db.nearbyRoomDao().listActive(nowMs)
    }

    fun listDiscoverySnapshots(): Map<String, NearbyRoomDiscoveryAccumulator.ExistingRoomSnapshot> {
        return listActiveRooms().associate { room ->
            room.room_id to NearbyRoomDiscoveryAccumulator.ExistingRoomSnapshot(
                roomId = room.room_id,
                roomName = room.name,
                expiresAtMs = room.expires_at_ms,
                participantCount = room.participant_count,
                protocolVersion = NearbyRoomBleCodec.PROTOCOL_VERSION,
                lastSeenAtMs = room.last_seen_at_ms,
                isStale = false,
                hasAuthoritativeMetadata = room.is_local_owner || room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED,
            )
        }
    }

    fun listParticipants(roomId: String): List<NearbyRoomParticipantEntity> {
        return db.nearbyRoomParticipantDao().listActiveParticipants(roomId)
    }

    fun upsertDiscoveredBleRoom(
        advertisement: NearbyRoomBleAdvertisement,
        sourceDeviceId: String,
        seenAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val nowMs = nowMsProvider()
        if (advertisement.expiresAtMs <= seenAtMs) return null
        val existing = db.nearbyRoomDao().findById(advertisement.roomId)
        if (existing?.is_local_owner == true) {
            val refreshed = existing.copy(
                last_seen_at_ms = maxOf(seenAtMs, existing.last_seen_at_ms),
                metadata_status = NearbyRoomEntity.METADATA_RESOLVED,
                metadata_source_device_id = existing.metadata_source_device_id ?: existing.owner_device_id,
            )
            db.nearbyRoomDao().upsert(refreshed)
            return refreshed
        }
        val roomNamePreview = NearbyRoomPolicy.sanitizeAdvertisementRoomName(advertisement.roomName)
        val fallbackName = existing?.name?.takeIf { it.isNotBlank() }
            ?: NearbyRoomPolicy.temporaryDiscoveryDisplayName(advertisement.roomId)
        val resolvedName = if (existing?.metadata_status == NearbyRoomEntity.METADATA_RESOLVED || existing?.is_local_owner == true) {
            existing.name
        } else {
            roomNamePreview.ifBlank { fallbackName }
        }
        val hasAuthoritativeMetadata = existing?.metadata_status == NearbyRoomEntity.METADATA_RESOLVED
        val mergedExpiresAtMs = if (hasAuthoritativeMetadata) {
            existing!!.expires_at_ms
        } else {
            advertisement.expiresAtMs
        }
        val mergedCreatedAtMs = if (hasAuthoritativeMetadata) {
            existing!!.created_at_ms
        } else {
            existing?.created_at_ms ?: 0L
        }
        val metadataStatus = when {
            existing?.is_local_owner == true -> NearbyRoomEntity.METADATA_RESOLVED
            existing?.metadata_status == NearbyRoomEntity.METADATA_RESOLVED -> NearbyRoomEntity.METADATA_RESOLVED
            existing?.metadata_status == NearbyRoomEntity.METADATA_FAILED -> NearbyRoomEntity.METADATA_FAILED
            else -> NearbyRoomEntity.METADATA_PENDING
        }
        val metadataSourceDeviceId = when {
            existing == null -> sourceDeviceId
            seenAtMs >= existing.last_seen_at_ms -> sourceDeviceId
            else -> existing.metadata_source_device_id ?: sourceDeviceId
        }
        val merged = NearbyRoomEntity(
            room_id = advertisement.roomId,
            name = resolvedName,
            owner_device_id = existing?.owner_device_id ?: advertisement.ownerDeviceId,
            owner_display_name = existing?.owner_display_name,
            created_at_ms = mergedCreatedAtMs,
            expires_at_ms = mergedExpiresAtMs,
            closed_at_ms = existing?.closed_at_ms,
            state = if (mergedExpiresAtMs <= nowMs) {
                NearbyRoomEntity.STATE_EXPIRED
            } else {
                NearbyRoomEntity.STATE_ACTIVE
            },
            participant_count = advertisement.participantCount ?: existing?.participant_count,
            is_local_owner = existing?.is_local_owner == true,
            last_seen_at_ms = maxOf(seenAtMs, existing?.last_seen_at_ms ?: 0L),
            source = existing?.source?.takeIf { it == NearbyRoomEntity.SOURCE_LOCAL } ?: NearbyRoomEntity.SOURCE_BLE,
            metadata_status = metadataStatus,
            metadata_last_fetched_at = existing?.metadata_last_fetched_at,
            metadata_source_device_id = metadataSourceDeviceId,
        )
        db.nearbyRoomDao().upsert(merged)
        return merged
    }

    fun shouldFetchMetadata(
        roomId: String,
        nowMs: Long = nowMsProvider(),
        retryIntervalMs: Long = METADATA_FETCH_RETRY_INTERVAL_MS,
        force: Boolean = false,
    ): Boolean {
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.is_local_owner) return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) return false
        if (room.metadata_source_device_id.isNullOrBlank()) return false
        if (force) return true
        val lastFetchedAt = room.metadata_last_fetched_at
        return when (room.metadata_status) {
            NearbyRoomEntity.METADATA_RESOLVED -> false
            NearbyRoomEntity.METADATA_PENDING,
            NearbyRoomEntity.METADATA_FAILED,
            -> lastFetchedAt == null || nowMs - lastFetchedAt >= retryIntervalMs
            else -> true
        }
    }

    fun markRoomMetadataPending(
        roomId: String,
        sourceDeviceId: String,
        fetchedAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val room = db.nearbyRoomDao().findById(roomId) ?: return null
        if (room.is_local_owner) return room
        val updated = room.copy(
            metadata_status = NearbyRoomEntity.METADATA_PENDING,
            metadata_last_fetched_at = fetchedAtMs,
            metadata_source_device_id = sourceDeviceId,
        )
        db.nearbyRoomDao().upsert(updated)
        return updated
    }

    fun markRoomMetadataResolved(
        roomId: String,
        roomName: String,
        createdAtMs: Long?,
        ownerDeviceId: String?,
        ownerDisplayName: String?,
        participantCount: Int?,
        sourceDeviceId: String?,
        fetchedAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val existing = db.nearbyRoomDao().findById(roomId) ?: return null
        val sanitizedName = NearbyRoomPolicy.sanitizeRoomName(roomName)
        if (sanitizedName.isBlank()) return null
        val authoritativeCreatedAtMs = when {
            existing.is_local_owner -> existing.created_at_ms
            createdAtMs != null && createdAtMs > 0L -> createdAtMs
            else -> existing.created_at_ms
        }
        val authoritativeExpiresAtMs = authoritativeCreatedAtMs + NearbyRoomPolicy.ROOM_TTL_MS
        val updated = existing.copy(
            name = sanitizedName,
            owner_device_id = ownerDeviceId?.takeIf { it.isNotBlank() } ?: existing.owner_device_id,
            owner_display_name = ownerDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: existing.owner_display_name,
            participant_count = participantCount ?: existing.participant_count,
            expires_at_ms = authoritativeExpiresAtMs,
            created_at_ms = authoritativeCreatedAtMs,
            state = if (authoritativeExpiresAtMs <= fetchedAtMs) {
                NearbyRoomEntity.STATE_EXPIRED
            } else {
                NearbyRoomEntity.STATE_ACTIVE
            },
            last_seen_at_ms = maxOf(existing.last_seen_at_ms, fetchedAtMs),
            metadata_status = NearbyRoomEntity.METADATA_RESOLVED,
            metadata_last_fetched_at = fetchedAtMs,
            metadata_source_device_id = sourceDeviceId ?: existing.metadata_source_device_id,
        )
        db.nearbyRoomDao().upsert(updated)
        val resolvedOwnerDeviceId = updated.owner_device_id.takeIf { it.isNotBlank() }
        if (resolvedOwnerDeviceId != null) {
            upsertParticipantPresence(
                roomId = roomId,
                participantDeviceId = resolvedOwnerDeviceId,
                participantDisplayName = updated.owner_display_name,
                participantAvatarId = null,
                profileUpdatedAtMs = null,
                seenAtMs = fetchedAtMs,
            )
            normalizeParticipantOwnerRole(roomId = roomId, ownerDeviceId = resolvedOwnerDeviceId)
        }
        if (!updated.is_local_owner && keyManager.load(roomId) != null) {
            materializeLocalParticipant(roomId = roomId, seenAtMs = fetchedAtMs)
        }
        return updated
    }

    fun markRoomMetadataFailed(
        roomId: String,
        sourceDeviceId: String?,
        fetchedAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val room = db.nearbyRoomDao().findById(roomId) ?: return null
        if (room.is_local_owner || room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED) return room
        val updated = room.copy(
            metadata_status = NearbyRoomEntity.METADATA_FAILED,
            metadata_last_fetched_at = fetchedAtMs,
            metadata_source_device_id = sourceDeviceId ?: room.metadata_source_device_id,
        )
        db.nearbyRoomDao().upsert(updated)
        return updated
    }

    fun syncRoomCreatedAtFromTransport(
        roomId: String,
        authoritativeCreatedAtMs: Long,
        ownerDeviceId: String? = null,
        seenAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val existing = db.nearbyRoomDao().findById(roomId) ?: return null
        if (authoritativeCreatedAtMs <= 0L) return existing
        val resolvedCreatedAtMs = if (existing.is_local_owner) {
            existing.created_at_ms
        } else {
            authoritativeCreatedAtMs
        }
        val resolvedExpiresAtMs = resolvedCreatedAtMs + NearbyRoomPolicy.ROOM_TTL_MS
        if (resolvedCreatedAtMs == existing.created_at_ms &&
            resolvedExpiresAtMs == existing.expires_at_ms &&
            existing.last_seen_at_ms >= seenAtMs
        ) {
            return existing
        }
        val updated = existing.copy(
            owner_device_id = ownerDeviceId?.takeIf { it.isNotBlank() } ?: existing.owner_device_id,
            created_at_ms = resolvedCreatedAtMs,
            expires_at_ms = resolvedExpiresAtMs,
            state = if (resolvedExpiresAtMs <= seenAtMs) {
                NearbyRoomEntity.STATE_EXPIRED
            } else {
                NearbyRoomEntity.STATE_ACTIVE
            },
            last_seen_at_ms = maxOf(existing.last_seen_at_ms, seenAtMs),
        )
        db.nearbyRoomDao().upsert(updated)
        return updated
    }

    fun listMessages(roomId: String, limit: Int = 100): List<NearbyRoomMessageEntity> {
        db.nearbyRoomMessageDao().deleteExpired(nowMsProvider())
        val fetchLimit = maxOf(limit, limit * 3)
        val nowMs = nowMsProvider()
        val blockedDeviceIds = db.blockedPeerDao().listGlobalMessageHiddenPeerIds(nowMs, scope = GLOBAL_LOCAL).toSet()
        return NearbyRoomLocalBlockPolicy.visibleMessages(
            messages = db.nearbyRoomMessageDao().listMessages(roomId, fetchLimit),
            blockedDeviceIds = blockedDeviceIds,
        ).takeLast(limit)
    }

    fun blockPeerLocally(
        roomId: String,
        targetDeviceId: String,
        reasonCode: String? = null,
    ): Boolean {
        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) {
            return false
        }
        val actorDeviceId = localDeviceId()
        if (targetDeviceId.isBlank() || targetDeviceId == actorDeviceId) {
            ActivityLog.add(
                label = "Nearby room local block skipped",
                source = "moderation",
                message = "room_id=$roomId actor=$actorDeviceId target=$targetDeviceId reason=invalid_target",
            )
            return false
        }
        db.runInTransaction {
            val existing = db.blockedPeerDao().findGlobal(
                blockedPeerId = targetDeviceId,
                nowMs = nowMs,
                scope = GLOBAL_LOCAL,
            )
            db.blockedPeerDao().upsert(
                BlockedPeerEntity(
                    id = existing?.id ?: 0L,
                    blockedPeerId = targetDeviceId,
                    scope = GLOBAL_LOCAL,
                    groupId = null,
                    hideMessages = true,
                    hideLocation = existing?.hideLocation == true,
                    hideTrail = existing?.hideTrail == true,
                    reasonCode = reasonCode ?: existing?.reasonCode,
                    createdAtMs = existing?.createdAtMs ?: nowMs,
                    updatedAtMs = nowMs,
                    expiresAtMs = null,
                )
            )
            db.moderationActionDao().upsert(
                ModerationActionEntity(
                    actionId = UUID.randomUUID().toString(),
                    groupId = roomId,
                    actorPeerId = actorDeviceId,
                    targetPeerId = targetDeviceId,
                    actionType = NearbyRoomLocalBlockPolicy.ACTION_NEARBY_ROOM_LOCAL_BLOCK,
                    createdAtMs = nowMs,
                    metadataJson = CanonicalJson.json.encodeToString(
                        NearbyRoomBlockActionMetadata(
                            roomId = roomId,
                            hideMessages = true,
                            reasonCode = reasonCode,
                        )
                    ),
                )
            )
        }
        ActivityLog.add(
            label = "Nearby room local block applied",
            source = "moderation",
            message = "room_id=$roomId actor=$actorDeviceId target=$targetDeviceId reason=${reasonCode ?: "none"}",
        )
        return true
    }

    fun mutePeerInRoom(
        roomId: String,
        targetDeviceId: String,
        eventCreatedAtMs: Long = nowMsProvider(),
    ): Boolean {
        return applyOwnerMuteStateInternal(
            roomId = roomId,
            targetDeviceId = targetDeviceId,
            actorDeviceId = localDeviceId(),
            muted = true,
            eventCreatedAtMs = eventCreatedAtMs,
            receivedAtMs = nowMsProvider(),
            source = "local_owner_action",
            requireLocalOwner = true,
        )
    }

    fun unmutePeerInRoom(
        roomId: String,
        targetDeviceId: String,
        eventCreatedAtMs: Long = nowMsProvider(),
    ): Boolean {
        return applyOwnerMuteStateInternal(
            roomId = roomId,
            targetDeviceId = targetDeviceId,
            actorDeviceId = localDeviceId(),
            muted = false,
            eventCreatedAtMs = eventCreatedAtMs,
            receivedAtMs = nowMsProvider(),
            source = "local_owner_action",
            requireLocalOwner = true,
        )
    }

    fun applyOwnerMuteStateEvent(
        roomId: String,
        targetDeviceId: String,
        actorDeviceId: String,
        muted: Boolean,
        eventCreatedAtMs: Long,
        receivedAtMs: Long = nowMsProvider(),
    ): Boolean {
        return applyOwnerMuteStateInternal(
            roomId = roomId,
            targetDeviceId = targetDeviceId,
            actorDeviceId = actorDeviceId,
            muted = muted,
            eventCreatedAtMs = eventCreatedAtMs,
            receivedAtMs = receivedAtMs,
            source = "transport_event",
            requireLocalOwner = false,
        )
    }

    fun isPeerMutedInRoom(
        roomId: String,
        targetDeviceId: String,
        nowMs: Long = nowMsProvider(),
    ): Boolean {
        if (targetDeviceId.isBlank()) return false
        return db.nearbyRoomMuteStateDao()
            .findActive(roomId = roomId, targetDeviceId = targetDeviceId, nowMs = nowMs)
            ?.is_muted == true
    }

    fun isLocalDeviceMutedInRoom(
        roomId: String,
        nowMs: Long = nowMsProvider(),
    ): Boolean = isPeerMutedInRoom(roomId, localDeviceId(), nowMs)

    fun listMutedPeerIdsInRoom(
        roomId: String,
        nowMs: Long = nowMsProvider(),
    ): Set<String> = db.nearbyRoomMuteStateDao().listMutedPeerIds(roomId, nowMs).toSet()

    fun ownerMuteActorDeviceId(
        roomId: String,
        targetDeviceId: String,
        nowMs: Long = nowMsProvider(),
    ): String? = db.nearbyRoomMuteStateDao()
        .findActive(roomId = roomId, targetDeviceId = targetDeviceId, nowMs = nowMs)
        ?.takeIf { it.is_muted }
        ?.actor_device_id

    fun ownerMuteUpdatedAtMs(
        roomId: String,
        targetDeviceId: String,
        nowMs: Long = nowMsProvider(),
    ): Long? = db.nearbyRoomMuteStateDao()
        .findActive(roomId = roomId, targetDeviceId = targetDeviceId, nowMs = nowMs)
        ?.updated_at_ms

    fun isGloballyMessageBlocked(targetDeviceId: String, nowMs: Long = nowMsProvider()): Boolean {
        if (targetDeviceId.isBlank()) return false
        return db.blockedPeerDao().findGlobal(
            blockedPeerId = targetDeviceId,
            nowMs = nowMs,
            scope = GLOBAL_LOCAL,
        )?.hideMessages == true
    }

    fun findMessage(roomId: String, messageId: String): NearbyRoomMessageEntity? {
        db.nearbyRoomMessageDao().deleteExpired(nowMsProvider())
        return db.nearbyRoomMessageDao().findMessage(roomId, messageId)
    }

    fun inspectPrivateGroupInvitation(
        roomId: String,
        messageId: String,
    ): NearbyPrivateGroupInvitationReceiveResult = privateInvitationInspector.inspectPersisted(
        roomId = roomId,
        messageId = messageId,
        messageLoader = db.nearbyRoomMessageDao()::findMessage,
        localParticipantId = localDeviceId(),
        nowMs = nowMsProvider(),
    )

    fun inspectPrivateGroupInvitationForTimeline(
        roomId: String,
        messageId: String,
    ): NearbyPrivateGroupInvitationTimelineInspection {
        val message = db.nearbyRoomMessageDao().findMessage(roomId, messageId)
            ?: return NearbyPrivateGroupInvitationTimelineInspection(
                receiveResult = NearbyPrivateGroupInvitationReceiveResult.MessageNotFound,
                context = null,
            )
        val localId = localDeviceId()
        val result = privateInvitationInspector.inspect(
            message = message,
            localParticipantId = localId,
            nowMs = nowMsProvider(),
        )
        if (result == NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant ||
            result == NearbyPrivateGroupInvitationReceiveResult.MessageNotFound ||
            result == NearbyPrivateGroupInvitationReceiveResult.Expired ||
            result == NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation ||
            result == NearbyPrivateGroupInvitationReceiveResult.NotPrivateInvitation
        ) {
            return NearbyPrivateGroupInvitationTimelineInspection(result, null)
        }

        val envelope = when (val decoded = GroupInvitationProtocol.decode(message.body)) {
            is GroupInvitationDecodeResult.Invitation ->
                (decoded.message as? GroupInvitationDecodedMessage.PrivateEncryptedInvitation)?.envelope
            else -> null
        } ?: return NearbyPrivateGroupInvitationTimelineInspection(result, null)
        if (GroupInvitationProtocol.encodePrivateEnvelope(envelope) != message.body) {
            return NearbyPrivateGroupInvitationTimelineInspection(result, null)
        }
        val logicalSenderId = NearbyPrivateGroupInvitationTimelineAuthority.authoritativeOwnerId(message)
            ?: return NearbyPrivateGroupInvitationTimelineInspection(
                NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext,
                null,
            )
        val audienceRole = when {
            localId == logicalSenderId -> NearbyPrivateGroupInvitationLocalAudienceRole.LOCAL_SENDER
            localId == envelope.targetParticipantId -> NearbyPrivateGroupInvitationLocalAudienceRole.INTENDED_RECIPIENT
            else -> return NearbyPrivateGroupInvitationTimelineInspection(
                NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant,
                null,
            )
        }
        val senderDisplayName = NearbyPrivateGroupInvitationTimelineAuthority.resolveSenderDisplayName(
            logicalSenderId = logicalSenderId,
            localParticipantId = localId,
            localDisplayName = localUserProfileStore.load()?.displayName,
            participantDisplayName = db.nearbyRoomParticipantDao()
                .findParticipant(roomId, logicalSenderId)
                ?.display_name,
            unknownSenderLabel = appContext.getString(R.string.nearby_private_invitation_unknown_sender),
        )
        val isRecipient = audienceRole == NearbyPrivateGroupInvitationLocalAudienceRole.INTENDED_RECIPIENT
        val room = db.nearbyRoomDao().findById(roomId)
        val canMutePeer = isRecipient && room?.is_local_owner == true &&
            !listMutedPeerIdsInRoom(roomId).contains(logicalSenderId)
        return NearbyPrivateGroupInvitationTimelineInspection(
            receiveResult = result,
            context = NearbyPrivateGroupInvitationTimelineContext(
                messageId = message.message_id,
                createdAtMs = message.created_at_ms,
                logicalSenderId = logicalSenderId,
                senderDisplayName = senderDisplayName,
                audienceRole = audienceRole,
                hiddenLocally = message.is_hidden_locally,
                moderationCapabilities = NearbyPrivateGroupInvitationModerationCapabilities(
                    canReportInvitation = isRecipient,
                    canReportPeer = isRecipient && logicalSenderId != localId,
                    canBlockPeer = isRecipient && logicalSenderId != localId,
                    canMutePeer = canMutePeer,
                ),
            ),
        )
    }

    fun inspectPublicGroupInvitationForTimeline(
        roomId: String,
        messageId: String,
    ): NearbyPublicGroupInvitationTimelineInspection {
        val message = db.nearbyRoomMessageDao().findMessage(roomId, messageId)
            ?: return NearbyPublicGroupInvitationTimelineInspection.MessageNotFound
        if (message.ttl_expires_at_ms <= nowMsProvider()) {
            return NearbyPublicGroupInvitationTimelineInspection.Expired
        }
        val decoded = when (val result = GroupInvitationProtocol.decode(message.body)) {
            GroupInvitationDecodeResult.NotGroupInvitation ->
                return NearbyPublicGroupInvitationTimelineInspection.NotGroupInvitation
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation -> {
                return if (GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)) {
                    NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation
                } else {
                    NearbyPublicGroupInvitationTimelineInspection.NotGroupInvitation
                }
            }
            is GroupInvitationDecodeResult.Invitation -> result.message
        }
        val payload = (decoded as? GroupInvitationDecodedMessage.PublicInvitation)?.payload
            ?: return NearbyPublicGroupInvitationTimelineInspection.NotPublicInvitation
        if (payload.cardId != message.message_id) {
            return NearbyPublicGroupInvitationTimelineInspection.MessageIdentityMismatch
        }
        if (runCatching { GroupInvitationProtocol.encodePublic(payload) }.getOrNull() != message.body) {
            return NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation
        }
        val logicalSenderId = message.owner_device_id?.trim()?.takeIf { it.isNotBlank() }
            ?: return NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation
        val localId = localDeviceId()
        val senderDisplayName = when {
            logicalSenderId == localId -> localUserProfileStore.load()?.displayName
            else -> db.nearbyRoomParticipantDao()
                .findParticipant(roomId, logicalSenderId)
                ?.display_name
        }?.trim()?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.nearby_public_invitation_unknown_sender)

        if (message.is_hidden_locally) {
            return NearbyPublicGroupInvitationTimelineInspection.HiddenLocally(
                NearbyPublicGroupInvitationPresentation(
                    messageId = message.message_id,
                    createdAtMs = message.created_at_ms,
                    logicalSenderId = logicalSenderId,
                    senderDisplayName = senderDisplayName,
                    title = payload.title,
                    shortDescription = payload.shortDescription,
                )
            )
        }

        return NearbyPublicGroupInvitationTimelineInspection.Invitation(
            NearbyPublicGroupInvitationPresentation(
                messageId = message.message_id,
                createdAtMs = message.created_at_ms,
                logicalSenderId = logicalSenderId,
                senderDisplayName = senderDisplayName,
                title = payload.title,
                shortDescription = payload.shortDescription,
            )
        )
    }

    /** Resolve only on an explicit Join click; never expose this value to presentation models. */
    fun resolvePublicInvitationJoinLink(roomId: String, messageId: String): String? {
        val message = db.nearbyRoomMessageDao().findMessage(roomId, messageId) ?: return null
        if (message.ttl_expires_at_ms <= nowMsProvider() || message.is_hidden_locally) return null
        val decoded = (GroupInvitationProtocol.decode(message.body) as? GroupInvitationDecodeResult.Invitation)
            ?.message as? GroupInvitationDecodedMessage.PublicInvitation ?: return null
        if (decoded.payload.cardId != message.message_id) return null
        if (runCatching { GroupInvitationProtocol.encodePublic(decoded.payload) }.getOrNull() != message.body) return null
        if (message.owner_device_id.isNullOrBlank()) return null
        val link = decoded.payload.joinLinkForUserAction()
        return link.takeIf { it.startsWith("peermesh://join?") }
    }

    fun setPublicGroupInvitationHiddenLocally(
        roomId: String,
        messageId: String,
        hidden: Boolean,
    ): NearbyGroupInvitationLocalVisibilityResult =
        publicGroupInvitationLocalVisibilityController.setHiddenLocally(
            roomId = roomId,
            messageId = messageId,
            hidden = hidden,
            messageLoader = db.nearbyRoomMessageDao()::findMessage,
            hiddenStateUpdater = db.nearbyRoomMessageDao()::setHiddenLocally,
        )

    fun setGroupInvitationHiddenLocally(
        roomId: String,
        messageId: String,
        hidden: Boolean,
    ): NearbyGroupInvitationLocalVisibilityResult =
        groupInvitationLocalVisibilityController.setHiddenLocally(
            roomId = roomId,
            messageId = messageId,
            hidden = hidden,
            localParticipantId = localDeviceId(),
            messageLoader = db.nearbyRoomMessageDao()::findMessage,
            hiddenStateUpdater = db.nearbyRoomMessageDao()::setHiddenLocally,
        )

    fun findMessageByCompactId(roomId: String, compactMessageId: String, limit: Int = 200): NearbyRoomMessageEntity? {
        return listMessages(roomId, limit)
            .asReversed()
            .firstOrNull { NearbyRoomRelayIdCodec.compactMessageId(it.message_id) == compactMessageId }
            ?: NearbyRoomHiddenMessageRelayCache.findByCompactId(roomId, compactMessageId, nowMsProvider())
    }

    fun prepareGroupInvitationContext(
        roomId: String,
        audience: NearbyGroupInvitationAudience,
    ): NearbyGroupInvitationPreparation? {
        val audienceKind = when (audience) {
            NearbyGroupInvitationAudience.Public -> "public"
            is NearbyGroupInvitationAudience.Private -> "private"
        }
        fun unavailable(reason: String): NearbyGroupInvitationPreparation? {
            ActivityLog.add(
                label = "Private invitation preparation",
                source = "nearby",
                message = "result=unavailable audience=$audienceKind reason=$reason",
            )
            return null
        }

        val preparedAtMs = nowMsProvider()
        val room = db.nearbyRoomDao().findById(roomId)
            ?: return unavailable("room_missing")
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || preparedAtMs >= room.expires_at_ms) {
            return unavailable("room_inactive_or_expired")
        }

        val senderId = localDeviceId()
        val recipientIdentity = when (audience) {
            NearbyGroupInvitationAudience.Public -> null
            is NearbyGroupInvitationAudience.Private -> {
                val target = db.nearbyRoomParticipantDao()
                    .findParticipant(roomId, audience.targetParticipantId)
                    ?.takeIf { it.status == NearbyRoomParticipantEntity.STATUS_ACTIVE }
                    ?: return unavailable("target_missing_or_inactive")
                val algorithm = PrivateInvitationEncryptionAlgorithm.entries.firstOrNull {
                    it.wireValue == target.recipient_encryption_algorithm
                } ?: return unavailable("recipient_algorithm_missing_or_unsupported")
                val keyId = target.recipient_encryption_key_id
                    ?: return unavailable("recipient_key_id_missing")
                val publicKey = target.recipient_encryption_public_key
                    ?: return unavailable("recipient_public_key_missing")
                NearbyRecipientEncryptionPublicIdentity(algorithm, keyId, publicKey)
                    .takeIf(NearbySelfInfoProtocol::isValidRecipientEncryptionIdentity)
                    ?: return unavailable("recipient_identity_invalid")
            }
        }
        return synchronized(pendingPreparationLock) {
            evictExpiredPreparationsLocked(preparedAtMs)
            while (pendingPreparations.size >= MAX_PENDING_PREPARATIONS) {
                pendingPreparations.remove(pendingPreparations.entries.first().key)
            }
            var token = preparationTokenProvider()
            while (pendingPreparations.containsKey(token)) token = UUID.randomUUID().toString()
            val preparation = IssuedNearbyGroupInvitationPreparation(
                token = token,
                roomId = roomId,
                messageId = messageIdProvider(),
                logicalSenderId = senderId,
                senderDisplayName = localUserProfileStore.load()?.displayName,
                createdAtMs = preparedAtMs,
                effectiveExpiryAtMs = room.expires_at_ms,
                audience = audience,
                recipientEncryptionIdentity = recipientIdentity,
                invalidAfterMs = preparedAtMs + PREPARATION_LIFETIME_MS,
            )
            pendingPreparations[token] = preparation
            ActivityLog.add(
                label = "Private invitation preparation",
                source = "nearby",
                message = "result=prepared audience=$audienceKind",
            )
            preparation
        }
    }

    fun insertPreparedGroupInvitation(
        preparation: NearbyGroupInvitationPreparation,
        finalizedOuterBody: String,
        localRenderSourceBody: String? = null,
    ): PreparedGroupInvitationInsertResult {
        val claimed = claimPreparation(preparation)
            ?: return PreparedGroupInvitationInsertResult.InvalidPreparation
        val decoded = validatePreparedGroupInvitationContent(
            preparation = claimed,
            finalizedOuterBody = finalizedOuterBody,
            localRenderSourceBody = localRenderSourceBody,
        ) ?: return PreparedGroupInvitationInsertResult.InvalidPreparedContent

        var result: PreparedGroupInvitationInsertResult? = null
        db.runInTransaction {
            val transactionNowMs = nowMsProvider()
            val room = db.nearbyRoomDao().findById(claimed.roomId)
            if (room == null) {
                result = PreparedGroupInvitationInsertResult.RoomUnavailable
                return@runInTransaction
            }
            if (room.expires_at_ms != claimed.effectiveExpiryAtMs) {
                result = PreparedGroupInvitationInsertResult.StalePreparation
                return@runInTransaction
            }
            if (room.state != NearbyRoomEntity.STATE_ACTIVE || transactionNowMs >= room.expires_at_ms) {
                result = PreparedGroupInvitationInsertResult.RoomUnavailable
                return@runInTransaction
            }
            val currentSenderId = localDeviceId()
            if (currentSenderId != claimed.logicalSenderId) {
                result = PreparedGroupInvitationInsertResult.SenderMismatch
                return@runInTransaction
            }
            val currentSender = db.nearbyRoomParticipantDao()
                .findParticipant(claimed.roomId, currentSenderId)
            if (db.nearbyRoomMuteStateDao()
                    .findActive(claimed.roomId, currentSenderId, transactionNowMs)
                    ?.is_muted == true
            ) {
                result = PreparedGroupInvitationInsertResult.SenderMuted
                return@runInTransaction
            }
            if (db.nearbyRoomMessageDao().findMessage(claimed.roomId, claimed.messageId) != null) {
                result = PreparedGroupInvitationInsertResult.MessageIdCollision
                return@runInTransaction
            }
            val quotaConsumed = db.nearbyRoomMessageDao()
                .listLogicalSenderCandidates(claimed.roomId, claimed.logicalSenderId)
                .any { candidate -> candidateConsumesPreparedQuota(candidate, decoded) }
            if (quotaConsumed) {
                result = PreparedGroupInvitationInsertResult.SendLimitReached
                return@runInTransaction
            }

            val message = NearbyRoomMessageEntity(
                room_id = claimed.roomId,
                message_id = claimed.messageId,
                reply_to = null,
                owner_device_id = claimed.logicalSenderId,
                sender_device_id = claimed.logicalSenderId,
                sender_display_name = claimed.senderDisplayName,
                body = finalizedOuterBody,
                created_at_ms = claimed.createdAtMs,
                received_at_ms = transactionNowMs,
                ttl_expires_at_ms = claimed.effectiveExpiryAtMs,
                delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
                edited_at_ms = null,
                deleted_at_ms = null,
                local_render_source_body = localRenderSourceBody,
                is_hidden_locally = false,
            )
            db.nearbyRoomMessageDao().insertPreparedMessage(message)
            currentSender?.let { participant ->
                db.nearbyRoomParticipantDao().upsert(
                    participant.copy(
                        display_name = claimed.senderDisplayName ?: participant.display_name,
                        last_seen_at_ms = transactionNowMs,
                    )
                )
            }
            db.nearbyRoomDao().upsert(room.copy(last_seen_at_ms = transactionNowMs))
            result = PreparedGroupInvitationInsertResult.Inserted(message)
        }
        return checkNotNull(result)
    }

    fun postLocalMessage(
        roomId: String,
        body: String,
        replyToMessageId: String? = null,
        senderDisplayName: String? = null,
    ): NearbyRoomMessageEntity? {
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(body)) return null
        val sanitizedBody = NearbyRoomPolicy.sanitizeMessageBody(body)
        require(NearbyRoomPolicy.isValidMessageBody(sanitizedBody)) { "Message body must not be blank" }

        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return null
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) {
            return null
        }
        val sanitizedReplyTo = replyToMessageId?.trim()?.takeIf { it.isNotBlank() }
        if (sanitizedReplyTo != null && db.nearbyRoomMessageDao().findMessage(roomId, sanitizedReplyTo) == null) {
            return null
        }
        if (isLocalDeviceMutedInRoom(roomId, nowMs)) {
            ActivityLog.add(
                label = "Nearby room send blocked",
                source = "moderation",
                message = "room_id=$roomId actor=${localDeviceId()} reason=owner_muted_local",
            )
            return null
        }

        val senderDeviceId = localDeviceId()
        val resolvedSenderDisplayName = senderDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: localUserProfileStore.load()?.displayName
        val message = NearbyRoomMessageEntity(
            room_id = roomId,
            message_id = messageIdProvider(),
            reply_to = sanitizedReplyTo,
            owner_device_id = senderDeviceId,
            sender_device_id = senderDeviceId,
            sender_display_name = resolvedSenderDisplayName,
            body = sanitizedBody,
            created_at_ms = nowMs,
            received_at_ms = nowMs,
            ttl_expires_at_ms = room.expires_at_ms,
            delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
            edited_at_ms = null,
            deleted_at_ms = null,
        )

        db.runInTransaction {
            db.nearbyRoomParticipantDao().findParticipant(roomId, senderDeviceId)?.let { existing ->
                db.nearbyRoomParticipantDao().upsert(
                    existing.copy(
                        display_name = resolvedSenderDisplayName ?: existing.display_name,
                        last_seen_at_ms = nowMs,
                    )
                )
            }
            db.nearbyRoomMessageDao().upsertMessage(message)
            db.nearbyRoomDao().upsert(room.copy(last_seen_at_ms = nowMs))
        }
        return message
    }

    fun editLocalMessage(
        roomId: String,
        messageId: String,
        body: String,
    ): NearbyRoomMessageEntity? {
        val sanitizedBody = NearbyRoomPolicy.sanitizeMessageBody(body)
        require(NearbyRoomPolicy.isValidMessageBody(sanitizedBody)) { "Message body must not be blank" }
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(sanitizedBody)) {
            logNearbyEditSkip(roomId, messageId, "immutable_group_invitation_target", localDeviceId())
            return null
        }

        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return null
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) {
            return null
        }
        if (isLocalDeviceMutedInRoom(roomId, nowMs)) {
            logNearbyEditSkip(roomId, messageId, "owner_muted_local", localDeviceId())
            return null
        }
        val existing = db.nearbyRoomMessageDao().findMessage(roomId, messageId) ?: return null
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(existing.body)) {
            logNearbyEditSkip(roomId, messageId, "immutable_group_invitation", localDeviceId())
            return null
        }
        if (existing.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) {
            logNearbyEditSkip(roomId, messageId, "system_message", localDeviceId())
            return null
        }
        if (existing.deleted_at_ms != null) {
            logNearbyEditSkip(roomId, messageId, "already_deleted", localDeviceId())
            return existing
        }
        val localDeviceId = localDeviceId()
        val messageOwnerDeviceId = existing.owner_device_id ?: existing.sender_device_id
        if (messageOwnerDeviceId != localDeviceId) {
            logNearbyEditSkip(roomId, messageId, "actor_mismatch", localDeviceId)
            return null
        }
        if (existing.body == sanitizedBody) {
            logNearbyEditSkip(roomId, messageId, "no_change", localDeviceId)
            return existing
        }

        val updated = existing.copy(
            body = sanitizedBody,
            received_at_ms = nowMs,
            delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
            edited_at_ms = maxOf(existing.edited_at_ms ?: 0L, nowMs),
            deleted_at_ms = null,
        )
        db.nearbyRoomMessageDao().upsertMessage(updated)
        ActivityLog.add(
            label = "Nearby room edit applied",
            source = "nearby",
            message =
                "room_id=$roomId message_id=$messageId actor=$localDeviceId local=true edited_at_ms=${updated.edited_at_ms}",
        )
        return updated
    }

    fun deleteLocalMessage(
        roomId: String,
        messageId: String,
    ): NearbyRoomMessageEntity? {
        val nowMs = nowMsProvider()
        expireRooms(nowMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return null
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) {
            return null
        }
        if (isLocalDeviceMutedInRoom(roomId, nowMs)) {
            logNearbyDeleteSkip(roomId, messageId, "owner_muted_local", localDeviceId())
            return null
        }
        val existing = db.nearbyRoomMessageDao().findMessage(roomId, messageId) ?: return null
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(existing.body)) {
            logNearbyDeleteSkip(roomId, messageId, "immutable_group_invitation", localDeviceId())
            return null
        }
        if (existing.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) {
            logNearbyDeleteSkip(roomId, messageId, "system_message", localDeviceId())
            return null
        }
        val localDeviceId = localDeviceId()
        val messageOwnerDeviceId = existing.owner_device_id ?: existing.sender_device_id
        if (messageOwnerDeviceId != localDeviceId) {
            logNearbyDeleteSkip(roomId, messageId, "actor_mismatch", localDeviceId)
            return null
        }
        if (existing.deleted_at_ms != null) {
            logNearbyDeleteSkip(roomId, messageId, "already_deleted", localDeviceId)
            return existing
        }

        val updated = existing.copy(
            body = NearbyRoomMessageEditReducer.DELETED_MESSAGE_BODY,
            received_at_ms = nowMs,
            delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
            edited_at_ms = null,
            deleted_at_ms = maxOf(existing.deleted_at_ms ?: 0L, nowMs),
        )
        db.nearbyRoomMessageDao().upsertMessage(updated)
        ActivityLog.add(
            label = "Nearby room delete applied",
            source = "nearby",
            message =
                "room_id=$roomId message_id=$messageId actor=$localDeviceId local=true deleted_at_ms=${updated.deleted_at_ms}",
        )
        return updated
    }

    fun markMessageSent(roomId: String, messageId: String): Boolean {
        val existing = db.nearbyRoomMessageDao().findMessage(roomId, messageId) ?: return false
        if (existing.delivery_state == NearbyRoomMessageEntity.DELIVERY_SENT) return true
        db.nearbyRoomMessageDao().upsertMessage(
            existing.copy(delivery_state = NearbyRoomMessageEntity.DELIVERY_SENT)
        )
        return true
    }

    fun ingestRemoteTransportMessage(
        envelope: NearbyRoomTransportEnvelope,
        body: String,
        receivedAtMs: Long = nowMsProvider(),
    ): NearbyRoomMessageEntity? {
        expireRooms(receivedAtMs)
        val room = db.nearbyRoomDao().findById(envelope.room_id) ?: return null
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= receivedAtMs) {
            return null
        }
        if (!NearbyRoomPolicy.isValidMessageBody(body)) return null

        val sanitizedBody = NearbyRoomPolicy.sanitizeMessageBody(body)
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(sanitizedBody) &&
            envelope.reply_to != null
        ) {
            ActivityLog.add(
                label = "Nearby room immutable group invitation rejected",
                source = "nearby",
                message = "room_id=${envelope.room_id} message_id=${envelope.message_id} reason=reply_to_forbidden",
            )
            return null
        }
        val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
        val participantDisplayName = envelope.sender_display_name?.trim()?.takeIf { it.isNotBlank() }
        val expiresAtMs = minOf(
            room.expires_at_ms,
            envelope.expires_at_ms.takeIf { it > 0L } ?: room.expires_at_ms,
        )
        val message = NearbyRoomMessageEntity(
            room_id = envelope.room_id,
            message_id = envelope.message_id ?: return null,
            reply_to = envelope.reply_to?.trim()?.takeIf { it.isNotBlank() },
            owner_device_id = originDeviceId,
            sender_device_id = originDeviceId,
            sender_display_name = participantDisplayName,
            body = sanitizedBody,
            created_at_ms = envelope.created_at_ms,
            received_at_ms = receivedAtMs,
            ttl_expires_at_ms = expiresAtMs,
            delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
            edited_at_ms = envelope.edited_at_ms,
            deleted_at_ms = envelope.deleted_at_ms,
        )

        var appliedMessage: NearbyRoomMessageEntity? = null
        db.runInTransaction {
            val participantUpsert = upsertActiveParticipantAndMaybeRecordJoinEvent(
                room = room,
                participantDeviceId = originDeviceId,
                participantDisplayName = participantDisplayName,
                participantAvatarId = null,
                participantProfileUpdatedAtMs = null,
                seenAtMs = receivedAtMs,
            )
            val activeParticipants = participantUpsert.activeParticipantCount
            if (isGloballyMessageBlocked(originDeviceId, receivedAtMs)) {
                NearbyRoomHiddenMessageRelayCache.store(message, receivedAtMs)
                appliedMessage = message
                ActivityLog.add(
                    label = "Nearby room message hidden",
                    source = "moderation",
                    message =
                        "room_id=${envelope.room_id} message_id=${message.message_id} sender=$originDeviceId " +
                            "reason=blocked_peer no_db_insert=true",
                )
            } else if (isPeerMutedInRoom(envelope.room_id, originDeviceId, receivedAtMs)) {
                ActivityLog.add(
                    label = "Nearby room message muted skip",
                    source = "moderation",
                    message =
                        "room_id=${envelope.room_id} message_id=${message.message_id} sender=$originDeviceId " +
                            "reason=owner_muted no_db_insert=true",
                )
            } else {
                when (val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(
                    existing = db.nearbyRoomMessageDao().findMessage(envelope.room_id, message.message_id),
                    incoming = message,
                )) {
                    is NearbyRoomMessageEditReducer.Decision.Apply -> {
                        appliedMessage = decision.message
                        db.nearbyRoomMessageDao().upsertMessage(decision.message)
                        ActivityLog.add(
                            label = "Nearby room message applied",
                            source = "nearby",
                            message =
                                "room_id=${envelope.room_id} message_id=${message.message_id} sender=$originDeviceId " +
                                    "reason=${decision.reason} participant_inserted=${participantUpsert.inserted} edited_at_ms=${decision.message.edited_at_ms} " +
                                    "deleted_at_ms=${decision.message.deleted_at_ms}",
                        )
                    }
                    is NearbyRoomMessageEditReducer.Decision.Skip -> {
                        ActivityLog.add(
                            label = "Nearby room message skipped",
                            source = "nearby",
                            message =
                                "room_id=${envelope.room_id} message_id=${message.message_id} sender=$originDeviceId reason=${decision.reason}",
                        )
                    }
                }
            }
            db.nearbyRoomDao().upsert(
                room.copy(
                    participant_count = activeParticipants,
                    last_seen_at_ms = receivedAtMs,
                )
            )
        }
        return appliedMessage
    }

    fun syncLocalUserProfile(profile: LocalUserProfile) {
        val nowMs = nowMsProvider()
        val localDeviceId = localDeviceId()
        val activeRooms = db.nearbyRoomDao().listActive(nowMs)
        db.runInTransaction {
            activeRooms.filter { it.is_local_owner }.forEach { room ->
                db.nearbyRoomDao().upsert(
                    room.copy(
                        owner_display_name = profile.displayName,
                        last_seen_at_ms = maxOf(room.last_seen_at_ms, profile.updatedAt),
                    )
                )
            }
            activeRooms.forEach { room ->
                db.nearbyRoomParticipantDao().findParticipant(room.room_id, localDeviceId)?.let { participant ->
                    db.nearbyRoomParticipantDao().upsert(
                        participant.copy(
                            display_name = profile.displayName,
                            avatar_id = profile.avatarId,
                            profile_updated_at_ms = profile.updatedAt,
                            last_seen_at_ms = maxOf(participant.last_seen_at_ms, profile.updatedAt),
                        )
                    )
                }
                db.nearbyRoomMessageDao().listMessages(room.room_id, Int.MAX_VALUE)
                    .filter { (it.owner_device_id ?: it.sender_device_id) == localDeviceId }
                    .forEach { message ->
                        db.nearbyRoomMessageDao().upsertMessage(
                            message.copy(sender_display_name = profile.displayName)
                        )
                    }
            }
        }
    }

    fun materializeLocalParticipant(
        roomId: String,
        seenAtMs: Long = nowMsProvider(),
    ): Boolean {
        expireRooms(seenAtMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= seenAtMs) {
            return false
        }
        if (!room.is_local_owner && keyManager.load(roomId) == null) {
            return false
        }
        val localDeviceId = localDeviceId()
        val localProfile = localUserProfileStore.load()
        var inserted = false
        db.runInTransaction {
            val upsertResult = upsertActiveParticipantAndMaybeRecordJoinEvent(
                room = room,
                participantDeviceId = localDeviceId,
                participantDisplayName = localProfile?.displayName,
                participantAvatarId = localProfile?.avatarId,
                participantProfileUpdatedAtMs = localProfile?.updatedAt,
                seenAtMs = seenAtMs,
            )
            inserted = upsertResult.inserted
            db.nearbyRoomDao().upsert(
                room.copy(
                    participant_count = upsertResult.activeParticipantCount,
                    last_seen_at_ms = maxOf(room.last_seen_at_ms, seenAtMs),
                )
            )
        }
        return inserted
    }

    fun recordParticipantJoinSuccess(
        roomId: String,
        participantDeviceId: String,
        seenAtMs: Long = nowMsProvider(),
    ): Boolean {
        expireRooms(seenAtMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= seenAtMs) {
            return false
        }
        var inserted = false
        db.runInTransaction {
            val upsertResult = upsertActiveParticipantAndMaybeRecordJoinEvent(
                room = room,
                participantDeviceId = participantDeviceId,
                participantDisplayName = null,
                participantAvatarId = null,
                participantProfileUpdatedAtMs = null,
                seenAtMs = seenAtMs,
            )
            inserted = upsertResult.inserted
            db.nearbyRoomDao().upsert(
                room.copy(
                    participant_count = upsertResult.activeParticipantCount,
                    last_seen_at_ms = maxOf(room.last_seen_at_ms, seenAtMs),
                )
            )
        }
        return inserted
    }

    fun upsertParticipantPresence(
        roomId: String,
        participantDeviceId: String,
        participantDisplayName: String?,
        participantAvatarId: String?,
        profileUpdatedAtMs: Long?,
        seenAtMs: Long = nowMsProvider(),
    ): Boolean {
        expireRooms(seenAtMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= seenAtMs) {
            return false
        }
        var inserted = false
        db.runInTransaction {
            val upsertResult = upsertActiveParticipantAndMaybeRecordJoinEvent(
                room = room,
                participantDeviceId = participantDeviceId,
                participantDisplayName = participantDisplayName,
                participantAvatarId = participantAvatarId,
                participantProfileUpdatedAtMs = profileUpdatedAtMs,
                seenAtMs = seenAtMs,
            )
            inserted = upsertResult.inserted
            db.nearbyRoomDao().upsert(
                room.copy(
                    participant_count = upsertResult.activeParticipantCount,
                    last_seen_at_ms = maxOf(room.last_seen_at_ms, seenAtMs),
                )
            )
        }
        return inserted
    }

    fun upsertParticipantSelfInfo(
        roomId: String,
        participantDeviceId: String,
        participantDisplayName: String?,
        participantAvatarId: String?,
        profileUpdatedAtMs: Long?,
        recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?,
        seenAtMs: Long = nowMsProvider(),
    ): NearbyParticipantSelfInfoResult {
        expireRooms(seenAtMs)
        val sanitizedDisplayName = participantDisplayName?.trim()?.takeIf { it.isNotBlank() }
        val sanitizedAvatarId = participantAvatarId?.trim()?.takeIf {
            it.isNotBlank() && AvatarUtils.loadAvatarIds(appContext).contains(it)
        }
        val result = db.runInTransactionWithResult {
            val room = db.nearbyRoomDao().findById(roomId)
            if (room == null ||
                room.state != NearbyRoomEntity.STATE_ACTIVE ||
                room.expires_at_ms <= seenAtMs
            ) {
                NearbyParticipantSelfInfoResult(
                    participantInserted = false,
                    recipientKeyPinResult = NearbyRecipientKeyPinResult.RoomUnavailable,
                )
            } else {
                val participantUpsert = upsertActiveParticipantAndMaybeRecordJoinEvent(
                    room = room,
                    participantDeviceId = participantDeviceId,
                    participantDisplayName = sanitizedDisplayName,
                    participantAvatarId = sanitizedAvatarId,
                    participantProfileUpdatedAtMs = profileUpdatedAtMs,
                    seenAtMs = seenAtMs,
                )
                db.nearbyRoomDao().upsert(
                    room.copy(
                        participant_count = participantUpsert.activeParticipantCount,
                        last_seen_at_ms = maxOf(room.last_seen_at_ms, seenAtMs),
                    )
                )
                NearbyParticipantSelfInfoResult(
                    participantInserted = participantUpsert.inserted,
                    recipientKeyPinResult = evaluateRecipientKeyPin(
                        roomId = roomId,
                        participantDeviceId = participantDeviceId,
                        incomingIdentity = recipientEncryptionIdentity,
                    ),
                )
            }
        }
        if (result.recipientKeyPinResult == NearbyRecipientKeyPinResult.Conflict ||
            result.recipientKeyPinResult == NearbyRecipientKeyPinResult.StoredPinInvalid
        ) {
            ActivityLog.add(
                label = "Nearby recipient key pin diagnostic",
                source = "nearby",
                message =
                    "room_id=$roomId participant=$participantDeviceId " +
                        "incoming_key_id=${recipientEncryptionIdentity?.keyId?.take(8) ?: "none"} " +
                        "result=${result.recipientKeyPinResult.javaClass.simpleName}",
            )
        }
        return result
    }

    fun leaveRoom(roomId: String): Boolean {
        val nowMs = nowMsProvider()
        val deviceId = localDeviceId()
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        val updated = db.runInTransactionWithResult {
            val changed = db.nearbyRoomParticipantDao().markLeft(roomId, deviceId, nowMs) > 0
            if (changed) {
                val activeParticipants = db.nearbyRoomParticipantDao().listActiveParticipants(roomId).size
                db.nearbyRoomDao().upsert(
                    room.copy(
                        participant_count = activeParticipants,
                        last_seen_at_ms = nowMs,
                    )
                )
            }
            changed
        }
        return updated
    }

    fun closeRoom(roomId: String): Boolean {
        val nowMs = nowMsProvider()
        return db.runInTransactionWithResult {
            val changed = db.nearbyRoomDao().markClosed(roomId, nowMs) > 0
            if (changed) {
                db.nearbyRoomMessageDao().deleteExpired(nowMs)
                db.nearbyRoomInviteQuotaDao().deleteByRoom(roomId)
            }
            changed
        }.also { changed ->
            if (changed) keyManager.clear(roomId)
        }
    }

    fun closeRoomFromTransport(
        roomId: String,
        closedAtMs: Long,
        seenAtMs: Long = nowMsProvider(),
    ): NearbyRoomEntity? {
        val existing = db.nearbyRoomDao().findById(roomId) ?: return null
        val existingClosedAtMs = existing.closed_at_ms
        if (existing.state == NearbyRoomEntity.STATE_CLOSED &&
            existingClosedAtMs != null &&
            existingClosedAtMs >= closedAtMs
        ) {
            return existing
        }
        val updated = existing.copy(
            state = NearbyRoomEntity.STATE_CLOSED,
            closed_at_ms = maxOf(existingClosedAtMs ?: 0L, closedAtMs),
            last_seen_at_ms = maxOf(existing.last_seen_at_ms, seenAtMs),
        )
        db.nearbyRoomDao().upsert(updated)
        keyManager.clear(roomId)
        return updated
    }

    fun purgeRoomLocalData(roomId: String): Boolean {
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        db.runInTransaction {
            db.nearbyRoomMessageDao().deleteByRoom(roomId)
            db.nearbyRoomMuteStateDao().deleteByRoom(roomId)
            db.nearbyRoomParticipantDao().deleteByRoom(roomId)
            db.nearbyRoomInviteQuotaDao().deleteByRoom(roomId)
            db.nearbyRoomDao().deleteByRoom(roomId)
        }
        keyManager.clear(roomId)
        return room.room_id == roomId
    }

    fun expireRooms(nowMs: Long = nowMsProvider()): Int {
        val expired = db.nearbyRoomDao().markExpired(nowMs)
        db.nearbyRoomMessageDao().deleteExpired(nowMs)
        db.nearbyRoomMuteStateDao().deleteExpired(nowMs)
        return expired
    }

    fun cleanupTerminalRoomData(
        nowMs: Long = nowMsProvider(),
        terminalRetentionMs: Long = TERMINAL_ROOM_RETENTION_MS,
    ): Int {
        expireRooms(nowMs)
        db.nearbyRoomRelayOfferDao().deleteExpired(nowMs)
        db.nearbyRoomRelayWantDao().deleteExpired(nowMs)
        val retentionCutoffMs = nowMs - terminalRetentionMs
        val roomIds = db.nearbyRoomDao().listTerminalRoomIdsForCleanup(retentionCutoffMs)
        roomIds.forEach { roomId ->
            purgeRoomLocalData(roomId)
        }
        return roomIds.size
    }

    fun upsertInviteQuota(quota: NearbyRoomInviteQuotaEntity) {
        db.nearbyRoomInviteQuotaDao().upsert(quota)
    }

    fun enqueueRelayOffers(
        roomId: String,
        fullMessageId: String,
        targetDeviceIds: Collection<String>,
        expiresAtMs: Long,
        offeredAtMs: Long = nowMsProvider(),
    ): Int {
        val compactMessageId = NearbyRoomRelayIdCodec.compactMessageId(fullMessageId)
        var inserted = 0
        targetDeviceIds
            .distinct()
            .forEach { targetDeviceId ->
                val existing = db.nearbyRoomRelayOfferDao().find(roomId, compactMessageId, targetDeviceId)
                if (existing == null) {
                    db.nearbyRoomRelayOfferDao().upsert(
                        NearbyRoomRelayOfferEntity(
                            room_id = roomId,
                            message_compact_id = compactMessageId,
                            target_device_id = targetDeviceId,
                            state = NearbyRoomRelayOfferEntity.STATE_PENDING,
                            offered_at_ms = offeredAtMs,
                            expires_at_ms = expiresAtMs,
                        )
                    )
                    inserted += 1
                }
            }
        return inserted
    }

    fun listPendingRelayOffers(limit: Int = 10, nowMs: Long = nowMsProvider()): List<NearbyRoomRelayOfferEntity> {
        db.nearbyRoomRelayOfferDao().deleteExpired(nowMs)
        return db.nearbyRoomRelayOfferDao().listByState(NearbyRoomRelayOfferEntity.STATE_PENDING, nowMs, limit)
    }

    fun markRelayOfferState(
        roomId: String,
        compactMessageId: String,
        targetDeviceId: String,
        state: String,
        updatedAtMs: Long = nowMsProvider(),
    ): Boolean {
        return db.nearbyRoomRelayOfferDao()
            .updateState(roomId, compactMessageId, targetDeviceId, state, updatedAtMs) > 0
    }

    fun getRelayWant(roomId: String, compactMessageId: String): NearbyRoomRelayWantEntity? {
        db.nearbyRoomRelayWantDao().deleteExpired(nowMsProvider())
        return db.nearbyRoomRelayWantDao().find(roomId, compactMessageId)
    }

    fun upsertRelayWant(
        roomId: String,
        compactMessageId: String,
        sourceDeviceId: String,
        ownerDeviceId: String?,
        fullMessageId: String? = null,
        state: String = NearbyRoomRelayWantEntity.STATE_PENDING,
        nowMs: Long = nowMsProvider(),
        expiresAtMs: Long,
    ): NearbyRoomRelayWantEntity {
        val existing = db.nearbyRoomRelayWantDao().find(roomId, compactMessageId)
        val candidates = mergeCandidateSources(existing?.candidate_source_device_ids, sourceDeviceId)
        val updated = NearbyRoomRelayWantEntity(
            room_id = roomId,
            message_compact_id = compactMessageId,
            state = state,
            candidate_source_device_ids = candidates,
            message_owner_device_id = ownerDeviceId ?: existing?.message_owner_device_id,
            full_message_id = fullMessageId ?: existing?.full_message_id,
            attempt_count = existing?.attempt_count ?: 0,
            last_requested_at_ms = existing?.last_requested_at_ms,
            next_source_index = existing?.next_source_index ?: 0,
            updated_at_ms = nowMs,
            expires_at_ms = expiresAtMs,
        )
        db.nearbyRoomRelayWantDao().upsert(updated)
        return updated
    }

    fun listRunnableRelayWants(limit: Int = 10, nowMs: Long = nowMsProvider()): List<NearbyRoomRelayWantEntity> {
        db.nearbyRoomRelayWantDao().deleteExpired(nowMs)
        return db.nearbyRoomRelayWantDao().listActiveByStates(
            states = listOf(
                NearbyRoomRelayWantEntity.STATE_PENDING,
                NearbyRoomRelayWantEntity.STATE_FAILED,
            ),
            nowMs = nowMs,
            limit = limit,
        )
    }

    fun markRelayWantRequesting(
        roomId: String,
        compactMessageId: String,
        nextSourceIndex: Int,
        nowMs: Long = nowMsProvider(),
    ): NearbyRoomRelayWantEntity? {
        val existing = db.nearbyRoomRelayWantDao().find(roomId, compactMessageId) ?: return null
        val updated = existing.copy(
            state = NearbyRoomRelayWantEntity.STATE_REQUESTING,
            attempt_count = existing.attempt_count + 1,
            last_requested_at_ms = nowMs,
            next_source_index = nextSourceIndex,
            updated_at_ms = nowMs,
        )
        db.nearbyRoomRelayWantDao().upsert(updated)
        return updated
    }

    fun markRelayWantResolved(
        roomId: String,
        compactMessageId: String,
        fullMessageId: String,
        nowMs: Long = nowMsProvider(),
    ): NearbyRoomRelayWantEntity? {
        val existing = db.nearbyRoomRelayWantDao().find(roomId, compactMessageId) ?: return null
        val updated = existing.copy(
            state = NearbyRoomRelayWantEntity.STATE_RESOLVED,
            full_message_id = fullMessageId,
            updated_at_ms = nowMs,
        )
        db.nearbyRoomRelayWantDao().upsert(updated)
        return updated
    }

    fun markRelayWantFailed(
        roomId: String,
        compactMessageId: String,
        nowMs: Long = nowMsProvider(),
    ): NearbyRoomRelayWantEntity? {
        val existing = db.nearbyRoomRelayWantDao().find(roomId, compactMessageId) ?: return null
        val updated = existing.copy(
            state = NearbyRoomRelayWantEntity.STATE_FAILED,
            updated_at_ms = nowMs,
        )
        db.nearbyRoomRelayWantDao().upsert(updated)
        return updated
    }

    fun localDeviceId(): String = DeviceIdProvider.get(appContext)

    private fun upsertActiveParticipantAndMaybeRecordJoinEvent(
        room: NearbyRoomEntity,
        participantDeviceId: String,
        participantDisplayName: String?,
        participantAvatarId: String?,
        participantProfileUpdatedAtMs: Long?,
        seenAtMs: Long,
    ): ParticipantUpsertResult {
        val participantDao = db.nearbyRoomParticipantDao()
        val existing = participantDao.findParticipant(room.room_id, participantDeviceId)
        val wasActive = existing?.status == NearbyRoomParticipantEntity.STATUS_ACTIVE
        val existingProfileUpdatedAtMs = existing?.profile_updated_at_ms ?: Long.MIN_VALUE
        val shouldReplaceProfile = participantProfileUpdatedAtMs != null &&
            participantProfileUpdatedAtMs >= existingProfileUpdatedAtMs
        val mergedParticipant = NearbyRoomParticipantEntity(
            room_id = room.room_id,
            device_id = participantDeviceId,
            display_name = when {
                shouldReplaceProfile -> participantDisplayName ?: existing?.display_name
                else -> existing?.display_name ?: participantDisplayName
            },
            avatar_id = when {
                shouldReplaceProfile -> participantAvatarId ?: existing?.avatar_id
                else -> existing?.avatar_id ?: participantAvatarId
            },
            profile_updated_at_ms = when {
                shouldReplaceProfile -> participantProfileUpdatedAtMs ?: existing?.profile_updated_at_ms
                else -> existing?.profile_updated_at_ms ?: participantProfileUpdatedAtMs
            },
            joined_at_ms = existing?.joined_at_ms ?: seenAtMs,
            last_seen_at_ms = maxOf(existing?.last_seen_at_ms ?: 0L, seenAtMs),
            role = if (participantDeviceId == room.owner_device_id) {
                NearbyRoomParticipantEntity.ROLE_OWNER
            } else {
                existing?.role ?: NearbyRoomParticipantEntity.ROLE_PARTICIPANT
            },
            status = NearbyRoomParticipantEntity.STATUS_ACTIVE,
            invitation_blocked = existing?.invitation_blocked ?: false,
            recipient_encryption_algorithm = existing?.recipient_encryption_algorithm,
            recipient_encryption_key_id = existing?.recipient_encryption_key_id,
            recipient_encryption_public_key = existing?.recipient_encryption_public_key,
        )
        participantDao.upsert(mergedParticipant)
        if (!wasActive &&
            participantDeviceId != room.owner_device_id &&
            participantDeviceId != localDeviceId()
        ) {
            db.nearbyRoomMessageDao().upsertMessage(
                NearbyRoomMessageEntity(
                    room_id = room.room_id,
                    message_id = "system-join-$participantDeviceId-$seenAtMs",
                    owner_device_id = room.owner_device_id,
                    sender_device_id = NearbyRoomMessageEntity.SYSTEM_SENDER_DEVICE_ID,
                    sender_display_name = null,
                    body = mergedParticipant.display_name?.let {
                        appContext.getString(R.string.nearby_room_system_joined_named, it)
                    } ?: appContext.getString(R.string.nearby_room_system_joined),
                    created_at_ms = seenAtMs,
                    received_at_ms = seenAtMs,
                    ttl_expires_at_ms = room.expires_at_ms,
                    delivery_state = NearbyRoomMessageEntity.DELIVERY_SYSTEM,
                    edited_at_ms = null,
                    deleted_at_ms = null,
                )
            )
        }
        return ParticipantUpsertResult(
            inserted = !wasActive,
            activeParticipantCount = participantDao.listActiveParticipants(room.room_id).size,
        )
    }

    private data class ParticipantUpsertResult(
        val inserted: Boolean,
        val activeParticipantCount: Int,
    )

    private fun evaluateRecipientKeyPin(
        roomId: String,
        participantDeviceId: String,
        incomingIdentity: NearbyRecipientEncryptionPublicIdentity?,
    ): NearbyRecipientKeyPinResult {
        if (incomingIdentity == null) return NearbyRecipientKeyPinResult.NoValidIdentity
        val participantDao = db.nearbyRoomParticipantDao()
        val updatedRows = participantDao.pinRecipientEncryptionIdentityIfAbsent(
            roomId = roomId,
            deviceId = participantDeviceId,
            algorithm = incomingIdentity.algorithm.wireValue,
            keyId = incomingIdentity.keyId,
            publicKey = incomingIdentity.publicKeySec1,
        )
        if (updatedRows == 1) return NearbyRecipientKeyPinResult.Pinned

        val persisted = participantDao.findParticipant(roomId, participantDeviceId)
            ?: return NearbyRecipientKeyPinResult.RoomUnavailable
        return classifyStoredRecipientKeyPin(
            storedAlgorithm = persisted.recipient_encryption_algorithm,
            storedKeyId = persisted.recipient_encryption_key_id,
            storedPublicKey = persisted.recipient_encryption_public_key,
            incomingIdentity = incomingIdentity,
        )
    }

    private fun claimPreparation(
        capability: NearbyGroupInvitationPreparation,
    ): IssuedNearbyGroupInvitationPreparation? {
        val issued = capability as? IssuedNearbyGroupInvitationPreparation ?: return null
        return synchronized(pendingPreparationLock) {
            val claimAtMs = nowMsProvider()
            evictExpiredPreparationsLocked(claimAtMs)
            pendingPreparations.remove(issued.token)?.takeIf { it === issued }
        }
    }

    private fun evictExpiredPreparationsLocked(nowMs: Long) {
        val iterator = pendingPreparations.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs >= iterator.next().value.invalidAfterMs) iterator.remove()
        }
    }

    private fun validatePreparedGroupInvitationContent(
        preparation: IssuedNearbyGroupInvitationPreparation,
        finalizedOuterBody: String,
        localRenderSourceBody: String?,
    ): GroupInvitationDecodedMessage? {
        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(finalizedOuterBody)) return null
        val decoded = (GroupInvitationProtocol.decode(finalizedOuterBody) as? GroupInvitationDecodeResult.Invitation)
            ?.message ?: return null
        return when {
            preparation.audience == NearbyGroupInvitationAudience.Public &&
                decoded is GroupInvitationDecodedMessage.PublicInvitation &&
                decoded.payload.cardId == preparation.messageId &&
                localRenderSourceBody == null &&
                GroupInvitationProtocol.encodePublic(decoded.payload) == finalizedOuterBody -> decoded

            preparation.audience is NearbyGroupInvitationAudience.Private &&
                decoded is GroupInvitationDecodedMessage.PrivateEncryptedInvitation -> {
                val audience = preparation.audience
                val identity = preparation.recipientEncryptionIdentity ?: return null
                val envelope = decoded.envelope
                val source = localRenderSourceBody ?: return null
                val inner = GroupInvitationProtocol.decodePrivateInnerPayload(source) ?: return null
                decoded.takeIf {
                    envelope.cardId == preparation.messageId &&
                        envelope.targetParticipantId == audience.targetParticipantId &&
                        envelope.algorithm == identity.algorithm &&
                        envelope.targetKeyId == identity.keyId &&
                        GroupInvitationProtocol.encodePrivateEnvelope(envelope) == finalizedOuterBody &&
                        isCanonicalBase64Url(envelope.targetKeyId) &&
                        isCanonicalBase64Url(envelope.ephemeralPublicKey) &&
                        isCanonicalBase64Url(envelope.nonce) &&
                        isCanonicalBase64Url(envelope.ciphertext) &&
                        GroupInvitationProtocol.encodePrivateInnerPayload(inner) == source
                }
            }

            else -> null
        }
    }

    private fun candidateConsumesPreparedQuota(
        candidate: NearbyRoomMessageEntity,
        prepared: GroupInvitationDecodedMessage,
    ): Boolean {
        val existing = decodeCanonicalQuotaEvidence(candidate) ?: return false
        return when {
            prepared is GroupInvitationDecodedMessage.PublicInvitation &&
                existing is GroupInvitationDecodedMessage.PublicInvitation -> true

            prepared is GroupInvitationDecodedMessage.PrivateEncryptedInvitation &&
                existing is GroupInvitationDecodedMessage.PrivateEncryptedInvitation ->
                prepared.envelope.targetParticipantId == existing.envelope.targetParticipantId

            else -> false
        }
    }

    private fun decodeCanonicalQuotaEvidence(
        candidate: NearbyRoomMessageEntity,
    ): GroupInvitationDecodedMessage? {
        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(candidate.body)) return null
        val existing = (GroupInvitationProtocol.decode(candidate.body) as? GroupInvitationDecodeResult.Invitation)
            ?.message ?: return null
        val existingCardId = when (existing) {
            is GroupInvitationDecodedMessage.PublicInvitation -> existing.payload.cardId
            is GroupInvitationDecodedMessage.PrivateEncryptedInvitation -> existing.envelope.cardId
        }
        if (existingCardId != candidate.message_id) return null
        return when {
            existing is GroupInvitationDecodedMessage.PublicInvitation &&
                GroupInvitationProtocol.encodePublic(existing.payload) == candidate.body -> existing

            existing is GroupInvitationDecodedMessage.PrivateEncryptedInvitation &&
                GroupInvitationProtocol.encodePrivateEnvelope(existing.envelope) == candidate.body &&
                isCanonicalBase64Url(existing.envelope.targetKeyId) &&
                isCanonicalBase64Url(existing.envelope.ephemeralPublicKey) &&
                isCanonicalBase64Url(existing.envelope.nonce) &&
                isCanonicalBase64Url(existing.envelope.ciphertext) -> existing

            else -> null
        }
    }

    private fun isCanonicalBase64Url(value: String): Boolean = runCatching {
        Base64Url.encode(Base64Url.decode(value)) == value
    }.getOrDefault(false)

    private fun normalizeParticipantOwnerRole(
        roomId: String,
        ownerDeviceId: String,
    ) {
        val participantDao = db.nearbyRoomParticipantDao()
        participantDao.listActiveParticipants(roomId).forEach { participant ->
            val expectedRole = if (participant.device_id == ownerDeviceId) {
                NearbyRoomParticipantEntity.ROLE_OWNER
            } else {
                NearbyRoomParticipantEntity.ROLE_PARTICIPANT
            }
            if (participant.role != expectedRole) {
                participantDao.updateRole(roomId, participant.device_id, expectedRole)
            }
        }
    }

    private fun logNearbyEditSkip(
        roomId: String,
        messageId: String,
        reason: String,
        actorDeviceId: String,
    ) {
        ActivityLog.add(
            label = "Nearby room edit skipped",
            source = "nearby",
            message = "room_id=$roomId message_id=$messageId actor=$actorDeviceId reason=$reason",
        )
    }

    private fun logNearbyDeleteSkip(
        roomId: String,
        messageId: String,
        reason: String,
        actorDeviceId: String,
    ) {
        ActivityLog.add(
            label = "Nearby room delete skipped",
            source = "nearby",
            message = "room_id=$roomId message_id=$messageId actor=$actorDeviceId reason=$reason",
        )
    }

    private fun applyOwnerMuteStateInternal(
        roomId: String,
        targetDeviceId: String,
        actorDeviceId: String,
        muted: Boolean,
        eventCreatedAtMs: Long,
        receivedAtMs: Long,
        source: String,
        requireLocalOwner: Boolean,
    ): Boolean {
        expireRooms(receivedAtMs)
        val room = db.nearbyRoomDao().findById(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= receivedAtMs) {
            logNearbyMuteMergeSkip(roomId, actorDeviceId, targetDeviceId, muted, "room_inactive", source)
            return false
        }
        if (requireLocalOwner && !room.is_local_owner) {
            logNearbyMuteMergeSkip(roomId, actorDeviceId, targetDeviceId, muted, "local_owner_required", source)
            return false
        }
        if (actorDeviceId != room.owner_device_id) {
            logNearbyMuteMergeSkip(roomId, actorDeviceId, targetDeviceId, muted, "actor_not_owner", source)
            return false
        }
        if (targetDeviceId.isBlank() || targetDeviceId == actorDeviceId || targetDeviceId == room.owner_device_id) {
            logNearbyMuteMergeSkip(roomId, actorDeviceId, targetDeviceId, muted, "invalid_target", source)
            return false
        }
        val incoming = NearbyRoomMuteStateEntity(
            room_id = roomId,
            target_device_id = targetDeviceId,
            actor_device_id = actorDeviceId,
            is_muted = muted,
            updated_at_ms = eventCreatedAtMs,
            expires_at_ms = room.expires_at_ms,
        )
        val existing = db.nearbyRoomMuteStateDao().findActive(roomId, targetDeviceId, receivedAtMs)
        return when (val decision = NearbyRoomMuteStateReducer.applyIncomingState(existing, incoming)) {
            is NearbyRoomMuteStateReducer.Decision.Apply -> {
                db.runInTransaction {
                    db.nearbyRoomMuteStateDao().upsert(decision.state)
                    db.moderationActionDao().upsert(
                        ModerationActionEntity(
                            actionId = UUID.randomUUID().toString(),
                            groupId = roomId,
                            actorPeerId = actorDeviceId,
                            targetPeerId = targetDeviceId,
                            actionType = if (muted) ACTION_NEARBY_ROOM_OWNER_MUTE else ACTION_NEARBY_ROOM_OWNER_UNMUTE,
                            createdAtMs = eventCreatedAtMs,
                            metadataJson = CanonicalJson.json.encodeToString(
                                NearbyRoomMuteActionMetadata(
                                    roomId = roomId,
                                    muted = muted,
                                )
                            ),
                        )
                    )
                }
                logNearbyMuteMergeApply(roomId, actorDeviceId, targetDeviceId, muted, decision.reason, source, eventCreatedAtMs)
                true
            }
            is NearbyRoomMuteStateReducer.Decision.Skip -> {
                logNearbyMuteMergeSkip(roomId, actorDeviceId, targetDeviceId, muted, decision.reason, source)
                false
            }
        }
    }

    private fun logNearbyMuteMergeApply(
        roomId: String,
        actorDeviceId: String,
        targetDeviceId: String,
        muted: Boolean,
        reason: String,
        source: String,
        updatedAtMs: Long,
    ) {
        ActivityLog.add(
            label = "Nearby room owner mute merge applied",
            source = "moderation",
            message =
                "room_id=$roomId actor=$actorDeviceId target=$targetDeviceId muted=$muted " +
                    "reason=$reason source=$source updated_at_ms=$updatedAtMs",
        )
    }

    private fun logNearbyMuteMergeSkip(
        roomId: String,
        actorDeviceId: String,
        targetDeviceId: String,
        muted: Boolean,
        reason: String,
        source: String,
    ) {
        ActivityLog.add(
            label = "Nearby room owner mute merge skipped",
            source = "moderation",
            message =
                "room_id=$roomId actor=$actorDeviceId target=$targetDeviceId muted=$muted " +
                    "reason=$reason source=$source",
        )
    }

    @kotlinx.serialization.Serializable
    private data class NearbyRoomBlockActionMetadata(
        val roomId: String,
        val hideMessages: Boolean,
        val reasonCode: String?,
    )

    @kotlinx.serialization.Serializable
    private data class NearbyRoomMuteActionMetadata(
        val roomId: String,
        val muted: Boolean,
    )

    companion object {
        const val METADATA_FETCH_RETRY_INTERVAL_MS = 15_000L
        const val TERMINAL_ROOM_RETENTION_MS = 30L * 60L * 1000L
        internal const val MAX_PENDING_PREPARATIONS = 64
        internal const val PREPARATION_LIFETIME_MS = 5L * 60L * 1000L
        private const val ACTION_NEARBY_ROOM_OWNER_MUTE = "nearby_room_owner_mute"
        private const val ACTION_NEARBY_ROOM_OWNER_UNMUTE = "nearby_room_owner_unmute"
    }
}

private class IssuedNearbyGroupInvitationPreparation(
    val token: String,
    override val roomId: String,
    override val messageId: String,
    override val logicalSenderId: String,
    override val senderDisplayName: String?,
    override val createdAtMs: Long,
    override val effectiveExpiryAtMs: Long,
    override val audience: NearbyGroupInvitationAudience,
    override val recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?,
    val invalidAfterMs: Long,
) : NearbyGroupInvitationPreparation

private fun mergeCandidateSources(existing: String?, newSource: String): String {
    val merged = linkedSetOf<String>()
    existing
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.forEach { merged += it }
    merged += newSource
    return merged.joinToString(",")
}

private inline fun <T> AppDatabase.runInTransactionWithResult(crossinline block: () -> T): T {
    var result: Result<T>? = null
    runInTransaction {
        result = runCatching(block)
    }
    return result!!.getOrThrow()
}
