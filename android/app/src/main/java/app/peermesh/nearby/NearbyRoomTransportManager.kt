package app.peermesh.nearby

import android.content.Context
import app.peermesh.LocalUserProfileStore
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.transport.adapter.MeshTrafficHint
import app.peermesh.security.groupinvitation.AndroidKeystoreRecipientEncryptionIdentityProvider
import app.peermesh.security.groupinvitation.RecipientEncryptionIdentityProvider
import app.peermesh.security.groupinvitation.RecipientEncryptionIdentityStatus
import java.util.concurrent.ConcurrentHashMap

class NearbyRoomTransportManager(
    context: Context,
    private val repository: NearbyRoomRepository = NearbyRoomRepository(context.applicationContext),
    private val keyManager: NearbyRoomKeyManager = NearbyRoomKeyManager(context.applicationContext),
    private val recipientEncryptionIdentityProvider: RecipientEncryptionIdentityProvider =
        AndroidKeystoreRecipientEncryptionIdentityProvider(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val localUserProfileStore = LocalUserProfileStore(appContext)
    private val keyBootstrapCooldownByRequester = ConcurrentHashMap<String, Long>()
    private val keyRequestHistoryByRoomAndTarget = ConcurrentHashMap<String, Long>()
    private val metadataReplyCooldownByRequester = ConcurrentHashMap<String, Long>()
    private val relayedCloseNoticeIds = ConcurrentHashMap<String, Long>()
    private val relayedJoinReadyNoticeIds = ConcurrentHashMap<String, Long>()
    private val relayedOwnerMuteNoticeIds = ConcurrentHashMap<String, Long>()
    private val relayedSelfInfoNoticeIds = ConcurrentHashMap<String, Long>()
    private val relayedSelfInfoAckNoticeIds = ConcurrentHashMap<String, Long>()
    private val relayedKeyBootstrapNoticeIds = ConcurrentHashMap<String, Long>()
    private val seenWrongTargetKeyBootstrapNoticeIds = ConcurrentHashMap<String, Long>()
    private val joinReadySentAtByRoomId = ConcurrentHashMap<String, Long>()
    private val selfInfoBroadcastSentAtByRoomId = ConcurrentHashMap<String, Long>()
    private val selfInfoReplySentAtByRoomAndTarget = ConcurrentHashMap<String, Long>()
    private val handledJoinReadyNoticeIds = ConcurrentHashMap<String, Long>()

    fun sendEncryptedMessage(
        message: NearbyRoomMessageEntity,
        targetDeviceIds: Set<String>? = null,
    ): Boolean {
        val room = repository.getRoom(message.room_id) ?: return false
        val roomKey = keyManager.load(room.room_id) ?: return false
        val encrypted = NearbyRoomMessageCrypto.encrypt(
            plaintext = message.body,
            roomKey = roomKey,
            aad = NearbyRoomMessageCrypto.messageAad(
                roomId = message.room_id,
                messageId = message.message_id,
                senderDeviceId = message.sender_device_id,
                createdAtMs = message.created_at_ms,
                expiresAtMs = message.ttl_expires_at_ms,
                keyVersion = KEY_VERSION,
            ),
        )
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
                room_id = message.room_id,
                sender_device_id = message.sender_device_id,
                sender_display_name = message.sender_display_name,
                origin_device_id = message.owner_device_id ?: message.sender_device_id,
                created_at_ms = message.created_at_ms,
                expires_at_ms = message.ttl_expires_at_ms,
                edited_at_ms = message.edited_at_ms,
                deleted_at_ms = message.deleted_at_ms,
                message_id = message.message_id,
                reply_to = message.reply_to,
                message_compact_id = NearbyRoomRelayIdCodec.compactMessageId(message.message_id),
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce,
                key_version = KEY_VERSION,
            )
        )
        if (payload.isEmpty()) return false
        val sent = if (targetDeviceIds.isNullOrEmpty()) {
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_DATA,
            )
        } else {
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                targetDeviceIds,
                MeshTrafficHint.NEARBY_ROOM_DATA,
            )
        }
        ActivityLog.add(
            label = "Nearby room message send",
            source = "nearby",
            message =
                "room_id=${message.room_id} message_id=${message.message_id} sender=${message.sender_device_id} " +
                    "edited_at_ms=${message.edited_at_ms} deleted_at_ms=${message.deleted_at_ms} " +
                    "targets=${targetDeviceIds?.size ?: -1} sent=$sent",
        )
        return sent
    }

    fun sendKeyBootstrap(roomId: String, targetDeviceId: String): Boolean {
        if (targetDeviceId.isBlank()) return false
        val room = repository.getRoom(roomId) ?: return false
        val roomKey = if (room.is_local_owner) {
            keyManager.ensureOwnerKey(room)
        } else {
            keyManager.load(roomId)
        } ?: return false
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                owner_device_id = room.owner_device_id,
                sender_display_name = room.owner_display_name,
                created_at_ms = room.created_at_ms,
                expires_at_ms = room.expires_at_ms,
                key_version = KEY_VERSION,
                room_key = Base64Url.encode(roomKey),
                target_device_id = targetDeviceId,
            )
        )
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP,
            )
        ActivityLog.add(
            label = "Nearby room key bootstrap send",
            source = "nearby",
            message =
                "room_id=$roomId sender=${repository.localDeviceId()} target=$targetDeviceId owner=${room.is_local_owner} sent=$sent created_at_ms=${room.created_at_ms} expires_at_ms=${room.expires_at_ms}",
        )
        ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_BOOTSTRAP_SEND, source = "nearby", message = "${NearbyForegroundRuntimeCoordinator.joinTimingFields(roomId)} target=$targetDeviceId sent=$sent")
        return sent
    }

    fun sendKeyRequest(roomId: String): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (hasRoomKey(roomId)) {
            clearPendingKeyRequestWait(
                roomId = roomId,
                reason = "key_ready",
            )
            ActivityLog.add(
                label = "Nearby room key request wait skip",
                source = "nearby",
                message = "room_id=$roomId reason=key_ready",
            )
            return false
        }
        val nowMs = System.currentTimeMillis()
        val waitState = keyRequestAwaitByRoomId[roomId]
        if (waitState != null) {
            if (nowMs < waitState.expiresAtMs) {
                ActivityLog.add(
                    label = "Nearby room key request wait skip",
                    source = "nearby",
                    message =
                        "room_id=$roomId target=${waitState.targetDeviceId ?: "broadcast"} " +
                            "remaining_ms=${waitState.expiresAtMs - nowMs}",
                )
                return false
            }
            if (keyRequestAwaitByRoomId.remove(roomId, waitState)) {
                ActivityLog.add(
                    label = "Nearby room key request wait timeout",
                    source = "nearby",
                    message =
                        "room_id=$roomId target=${waitState.targetDeviceId ?: "broadcast"} " +
                            "started_at_ms=${waitState.startedAtMs} expired_at_ms=${waitState.expiresAtMs}",
                )
            }
        }
        val candidate = selectKeyRequestTarget(room, nowMs)
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                created_at_ms = nowMs,
                expires_at_ms = room.expires_at_ms,
                key_version = KEY_VERSION,
                target_device_id = candidate?.deviceId,
            )
        )
        val sent = if (payload.isEmpty()) {
            false
        } else if (candidate == null) {
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        } else {
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(candidate.deviceId),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        }
        if (sent && candidate != null) {
            keyRequestHistoryByRoomAndTarget[keyRequestHistoryKey(roomId, candidate.deviceId)] = nowMs
        }
        if (sent) {
            keyRequestAwaitByRoomId[roomId] = KeyRequestAwaitState(
                targetDeviceId = candidate?.deviceId,
                startedAtMs = nowMs,
                expiresAtMs = nowMs + KEY_REQUEST_AWAIT_BOOTSTRAP_MS,
            )
            ActivityLog.add(
                label = "Nearby room key request wait start",
                source = "nearby",
                message =
                    "room_id=$roomId target=${candidate?.deviceId ?: "broadcast"} " +
                        "wait_ms=$KEY_REQUEST_AWAIT_BOOTSTRAP_MS started_at_ms=$nowMs",
            )
        }
        ActivityLog.add(
            label = "Nearby room key request send",
            source = "nearby",
            message =
                "room_id=$roomId sender=${repository.localDeviceId()} sent=$sent target=${candidate?.deviceId ?: "broadcast"} " +
                    "target_rssi=${candidate?.rssi ?: -1} target_seen_at_ms=${candidate?.seenAtMs ?: -1}",
        )
        ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_KEY_REQUEST_SEND, source = "nearby", message = "${NearbyForegroundRuntimeCoordinator.joinTimingFields(roomId)} target=${candidate?.deviceId ?: "broadcast"} sent=$sent")
        return sent
    }

    fun sendMetadataRequest(roomId: String, targetDeviceId: String): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                created_at_ms = System.currentTimeMillis(),
                expires_at_ms = room.expires_at_ms,
                target_device_id = targetDeviceId,
            )
        )
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        ActivityLog.add(
            label = "Nearby room metadata request send",
            source = "nearby",
            message = "room_id=$roomId sender=${repository.localDeviceId()} target=$targetDeviceId sent=$sent",
        )
        return sent
    }

    fun sendMetadataResponse(roomId: String, targetDeviceId: String): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (!room.canServeMetadataResponse()) return false
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                owner_device_id = room.owner_device_id,
                sender_display_name = room.owner_display_name,
                created_at_ms = room.created_at_ms,
                expires_at_ms = room.expires_at_ms,
                target_device_id = targetDeviceId,
                room_name = room.name,
                participant_count = room.participant_count,
            )
        )
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        ActivityLog.add(
            label = "Nearby room metadata response send",
            source = "nearby",
            message =
                "room_id=$roomId sender=${repository.localDeviceId()} target=$targetDeviceId sent=$sent created_at_ms=${room.created_at_ms} expires_at_ms=${room.expires_at_ms}",
        )
        return sent
    }

    fun sendSelfInfo(
        roomId: String,
        targetDeviceId: String? = null,
        enqueueRetry: Boolean = true,
    ): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return false
        val localProfile = localUserProfileStore.load() ?: return false
        val nowMs = System.currentTimeMillis()
        repository.materializeLocalParticipant(roomId = roomId, seenAtMs = nowMs)
        val localDeviceId = repository.localDeviceId()
        val sanitizedTargetDeviceId = targetDeviceId?.trim()?.takeIf { it.isNotBlank() && it != localDeviceId }
        if (sanitizedTargetDeviceId == null) {
            val previousSentAtMs = selfInfoBroadcastSentAtByRoomId[roomId] ?: 0L
            if (nowMs - previousSentAtMs < SELF_INFO_BROADCAST_COOLDOWN_MS) {
                return false
            }
        } else {
            val cooldownKey = selfInfoReplyCooldownKey(roomId, sanitizedTargetDeviceId)
            val previousSentAtMs = selfInfoReplySentAtByRoomAndTarget[cooldownKey] ?: 0L
            if (nowMs - previousSentAtMs < SELF_INFO_REPLY_COOLDOWN_MS) {
                return false
            }
        }
        val recipientIdentity = when (val status = recipientEncryptionIdentityProvider.getOrCreateIdentity()) {
            is RecipientEncryptionIdentityStatus.Available -> NearbyRecipientEncryptionPublicIdentity(
                algorithm = status.identity.algorithm,
                keyId = status.identity.keyId,
                publicKeySec1 = status.identity.publicKeySec1,
            )
            RecipientEncryptionIdentityStatus.UnsupportedPlatform,
            is RecipientEncryptionIdentityStatus.Invalidated,
            is RecipientEncryptionIdentityStatus.TemporarilyUnavailable -> null
        }
        val baseEnvelope = NearbyRoomTransportEnvelope(
            kind = NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1,
            room_id = roomId,
            sender_device_id = localDeviceId,
            origin_device_id = localDeviceId,
            sender_display_name = localProfile.displayName,
            avatar_id = localProfile.avatarId,
            profile_updated_at_ms = localProfile.updatedAt,
            created_at_ms = nowMs,
            expires_at_ms = room.expires_at_ms,
            target_device_id = sanitizedTargetDeviceId,
        )
        val snapshot = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = localDeviceId,
            senderDisplayName = localProfile.displayName,
            avatarId = localProfile.avatarId,
            profileUpdatedAtMs = localProfile.updatedAt,
            recipientEncryptionIdentity = recipientIdentity,
        )
        val envelope = NearbySelfInfoProtocol.applySnapshot(baseEnvelope, snapshot)
        val sent = sendSelfInfoSnapshot(envelope)
        ActivityLog.add(
            label = "Nearby room self info send",
            source = "nearby",
            message =
                "room_id=$roomId sender=$localDeviceId target=${sanitizedTargetDeviceId ?: "broadcast"} " +
                    "profile_updated_at_ms=${localProfile.updatedAt} sent=$sent",
        )
        if (!sent) return false
        if (sanitizedTargetDeviceId == null) {
            selfInfoBroadcastSentAtByRoomId[roomId] = nowMs
        } else {
            selfInfoReplySentAtByRoomAndTarget[selfInfoReplyCooldownKey(roomId, sanitizedTargetDeviceId)] = nowMs
            if (enqueueRetry) {
                NearbyRoomSelfInfoRetryCoordinator.enqueue(
                    context = appContext,
                    snapshotEnvelope = envelope,
                )
            }
        }
        return true
    }

    fun sendSelfInfoToNearbyPeers(roomId: String): Int {
        val localDeviceId = repository.localDeviceId()
        val targetDeviceIds = NearbyRoomPeerSnapshotStore
            .nearbyDeviceIdsForRoom(roomId, limit = MAX_SELF_INFO_TARGETS)
            .filterNot { it == localDeviceId }
            .distinct()
        var sentCount = 0
        targetDeviceIds.forEach { targetDeviceId ->
            if (sendSelfInfo(roomId = roomId, targetDeviceId = targetDeviceId)) {
                sentCount += 1
            }
        }
        ActivityLog.add(
            label = "Nearby room self info fanout",
            source = "nearby",
            message = "room_id=$roomId sender=$localDeviceId targets=${targetDeviceIds.size} sent_count=$sentCount",
        )
        return sentCount
    }

    fun sendSelfInfoAck(
        roomId: String,
        profileOwnerDeviceId: String,
        profileUpdatedAtMs: Long?,
        selfInfoRevision: String?,
        targetDeviceId: String,
    ): Boolean {
        if (targetDeviceId.isBlank() || targetDeviceId == repository.localDeviceId()) return false
        val room = repository.getRoom(roomId) ?: return false
        val envelope = NearbyRoomTransportEnvelope(
            kind = NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1,
            room_id = roomId,
            sender_device_id = repository.localDeviceId(),
            origin_device_id = profileOwnerDeviceId,
            profile_updated_at_ms = profileUpdatedAtMs,
            self_info_revision = selfInfoRevision?.takeIf(NearbySelfInfoProtocol::isValidRevision),
            created_at_ms = System.currentTimeMillis(),
            expires_at_ms = room.expires_at_ms,
            target_device_id = targetDeviceId,
        )
        val payload = NearbyRoomTransportCodec.encode(envelope)
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        ActivityLog.add(
            label = "Nearby room self info ack send",
            source = "nearby",
            message =
                "room_id=$roomId sender=${repository.localDeviceId()} target=$targetDeviceId " +
                    "origin=$profileOwnerDeviceId profile_updated_at_ms=${profileUpdatedAtMs ?: -1L} " +
                    "revision=${selfInfoRevision?.take(8) ?: "legacy"} sent=$sent",
        )
        return sent
    }

    fun sendJoinReady(roomId: String, targetDeviceId: String): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (room.is_local_owner || !isChatAccessReady(roomId)) return false
        val nowMs = System.currentTimeMillis()
        repository.materializeLocalParticipant(roomId = roomId, seenAtMs = nowMs)
        val previousSentAtMs = joinReadySentAtByRoomId[roomId] ?: 0L
        if (nowMs - previousSentAtMs < JOIN_READY_SEND_COOLDOWN_MS) {
            return false
        }
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                origin_device_id = repository.localDeviceId(),
                owner_device_id = room.owner_device_id,
                created_at_ms = nowMs,
                expires_at_ms = room.expires_at_ms,
            )
        )
        val targetDeviceIds = NearbyRoomPeerSnapshotStore
            .nearbyDeviceIdsForRoom(roomId, limit = MAX_JOIN_READY_TARGETS)
            .filterNot { it == repository.localDeviceId() }
            .distinct()
        if (payload.isEmpty() || targetDeviceIds.isEmpty()) return false
        val sent = BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
            payload,
            targetDeviceIds.toSet(),
            MeshTrafficHint.NEARBY_ROOM_CONTROL,
        )
        ActivityLog.add(
            label = "Nearby room join ready send",
            source = "nearby",
            message = "room_id=$roomId sender=${repository.localDeviceId()} origin=${repository.localDeviceId()} targets=${targetDeviceIds.size} owner=$targetDeviceId sent=$sent",
        )
        if (sent) {
            joinReadySentAtByRoomId[roomId] = nowMs
        }
        return sent
    }

    fun relayJoinReady(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (!shouldRelayJoinReady(envelope)) {
            ActivityLog.add(
                label = "Nearby room join ready relay skipped",
                source = "nearby",
                message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} origin=${envelope.origin_device_id ?: envelope.sender_device_id} reason=duplicate",
            )
            return false
        }
        val room = repository.getRoom(envelope.room_id) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return false
        val localDeviceId = repository.localDeviceId()
        val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
        val targetDeviceIds = NearbyRoomPeerSnapshotStore
            .nearbyDeviceIdsForRoom(envelope.room_id, limit = MAX_JOIN_READY_TARGETS)
            .filterNot { target ->
                target == localDeviceId ||
                    target == originDeviceId ||
                    target == room.owner_device_id ||
                    target == envelope.sender_device_id
            }
        if (targetDeviceIds.isEmpty()) return false
        val forwarded = envelope.copy(
            sender_device_id = localDeviceId,
            origin_device_id = originDeviceId,
            target_device_id = null,
        )
        val payload = NearbyRoomTransportCodec.encode(forwarded)
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                targetDeviceIds.toSet(),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        ActivityLog.add(
            label = "Nearby room join ready relay send",
            source = "nearby",
            message = "room_id=${envelope.room_id} sender=$localDeviceId origin=$originDeviceId targets=${targetDeviceIds.size} sent=$sent",
        )
        return sent
    }

    fun shouldHandleJoinReady(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1) return false
        val noticeId = joinReadyNoticeId(envelope)
        return handledJoinReadyNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun shouldRelaySelfInfo(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1) return false
        val noticeId = selfInfoNoticeId(envelope)
        return relayedSelfInfoNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun relaySelfInfo(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (!shouldRelaySelfInfo(envelope)) return false
        val forwarded = envelope.copy(
            origin_device_id = envelope.origin_device_id ?: envelope.sender_device_id,
        )
        val payload = NearbyRoomTransportCodec.encode(forwarded)
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        if (sent) {
            relayedSelfInfoNoticeIds[selfInfoNoticeId(forwarded)] = System.currentTimeMillis()
        }
        return sent
    }

    fun shouldRelaySelfInfoAck(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1) return false
        val noticeId = selfInfoAckNoticeId(envelope)
        return relayedSelfInfoAckNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun relaySelfInfoAck(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (!shouldRelaySelfInfoAck(envelope)) return false
        val forwarded = envelope.copy(
            origin_device_id = envelope.origin_device_id ?: envelope.sender_device_id,
        )
        val payload = NearbyRoomTransportCodec.encode(forwarded)
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
        if (sent) {
            relayedSelfInfoAckNoticeIds[selfInfoAckNoticeId(forwarded)] = System.currentTimeMillis()
        }
        return sent
    }

    fun acknowledgeSelfInfo(
        roomId: String,
        targetDeviceId: String,
        profileUpdatedAtMs: Long?,
        selfInfoRevision: String?,
    ) {
        NearbyRoomSelfInfoRetryCoordinator.acknowledge(
            roomId = roomId,
            targetDeviceId = targetDeviceId,
            profileUpdatedAtMs = profileUpdatedAtMs,
            selfInfoRevision = selfInfoRevision,
        )
    }

    internal fun sendSelfInfoSnapshot(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1) return false
        val payload = NearbyRoomTransportCodec.encode(envelope)
        return payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
    }

    fun sendMessageOffer(
        roomId: String,
        messageCompactId: String,
        targetDeviceId: String,
        messageOwnerDeviceId: String?,
    ): Boolean {
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                origin_device_id = messageOwnerDeviceId,
                message_compact_id = messageCompactId,
                created_at_ms = System.currentTimeMillis(),
                expires_at_ms = System.currentTimeMillis() + 5_000L,
                target_device_id = targetDeviceId,
            )
        )
        return payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
    }

    fun sendMessageFetch(
        roomId: String,
        messageCompactId: String,
        sourceDeviceId: String,
    ): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1,
                room_id = roomId,
                sender_device_id = repository.localDeviceId(),
                message_compact_id = messageCompactId,
                created_at_ms = System.currentTimeMillis(),
                expires_at_ms = room.expires_at_ms,
                target_device_id = sourceDeviceId,
            )
        )
        return payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(sourceDeviceId),
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
    }

    fun sendMessageData(
        message: NearbyRoomMessageEntity,
        targetDeviceId: String,
    ): Boolean {
        val roomKey = keyManager.load(message.room_id) ?: return false
        val encrypted = NearbyRoomMessageCrypto.encrypt(
            plaintext = message.body,
            roomKey = roomKey,
            aad = NearbyRoomMessageCrypto.messageAad(
                roomId = message.room_id,
                messageId = message.message_id,
                senderDeviceId = message.owner_device_id ?: message.sender_device_id,
                createdAtMs = message.created_at_ms,
                expiresAtMs = message.ttl_expires_at_ms,
                keyVersion = KEY_VERSION,
            ),
        )
        val messageOwnerDeviceId = message.owner_device_id ?: message.sender_device_id
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1,
                room_id = message.room_id,
                sender_device_id = messageOwnerDeviceId,
                sender_display_name = message.sender_display_name,
                origin_device_id = messageOwnerDeviceId,
                created_at_ms = message.created_at_ms,
                expires_at_ms = message.ttl_expires_at_ms,
                edited_at_ms = message.edited_at_ms,
                deleted_at_ms = message.deleted_at_ms,
                message_id = message.message_id,
                message_compact_id = NearbyRoomRelayIdCodec.compactMessageId(message.message_id),
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce,
                key_version = KEY_VERSION,
                target_device_id = targetDeviceId,
            )
        )
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_DATA,
            )
        ActivityLog.add(
            label = "Nearby room message data send",
            source = "nearby",
            message =
                "room_id=${message.room_id} message_id=${message.message_id} sender=$messageOwnerDeviceId " +
                    "edited_at_ms=${message.edited_at_ms} deleted_at_ms=${message.deleted_at_ms} " +
                    "target=$targetDeviceId sent=$sent",
        )
        return sent
    }

    fun sendRoomClosed(roomId: String, closedAtMs: Long = System.currentTimeMillis()): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        val envelope = NearbyRoomTransportEnvelope(
            kind = NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1,
            room_id = roomId,
            sender_device_id = repository.localDeviceId(),
            owner_device_id = room.owner_device_id,
            sender_display_name = room.owner_display_name,
            created_at_ms = closedAtMs,
            expires_at_ms = room.expires_at_ms,
            closed_at_ms = closedAtMs,
        )
        val sent = sendRoomClosedEnvelope(envelope)
        rememberRelayedCloseNotice(envelope)
        ActivityLog.add(
            label = "Nearby room closed send",
            source = "nearby",
            message = "room_id=$roomId sender=${repository.localDeviceId()} closed_at_ms=$closedAtMs sent=$sent",
        )
        return sent
    }

    fun sendOwnerMuteState(
        roomId: String,
        targetDeviceId: String,
        muted: Boolean,
        eventCreatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (!room.is_local_owner) return false
        val envelope = NearbyRoomTransportEnvelope(
            kind = if (muted) {
                NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1
            } else {
                NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1
            },
            room_id = roomId,
            sender_device_id = repository.localDeviceId(),
            owner_device_id = room.owner_device_id,
            sender_display_name = room.owner_display_name,
            origin_device_id = room.owner_device_id,
            created_at_ms = eventCreatedAtMs,
            expires_at_ms = room.expires_at_ms,
            target_device_id = targetDeviceId,
        )
        val sent = sendOwnerMuteEnvelope(envelope)
        rememberRelayedOwnerMuteNotice(envelope)
        ActivityLog.add(
            label = "Nearby room owner mute send",
            source = "moderation",
            message =
                "room_id=$roomId actor=${repository.localDeviceId()} target=$targetDeviceId " +
                    "muted=$muted created_at_ms=$eventCreatedAtMs sent=$sent",
        )
        return sent
    }

    fun relayOwnerMuteState(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (!shouldRelayOwnerMuteState(envelope)) {
            ActivityLog.add(
                label = "Nearby room owner mute relay skipped",
                source = "moderation",
                message =
                    "room_id=${envelope.room_id} sender=${envelope.sender_device_id} " +
                        "origin=${envelope.origin_device_id ?: envelope.sender_device_id} " +
                        "target=${envelope.target_device_id ?: "missing"} reason=duplicate",
            )
            return false
        }
        val forwarded = envelope.copy(
            sender_device_id = repository.localDeviceId(),
            origin_device_id = envelope.origin_device_id ?: envelope.sender_device_id,
        )
        val sent = sendOwnerMuteEnvelope(forwarded)
        rememberRelayedOwnerMuteNotice(forwarded)
        ActivityLog.add(
            label = "Nearby room owner mute relay send",
            source = "moderation",
            message =
                "room_id=${forwarded.room_id} sender=${forwarded.sender_device_id} " +
                    "origin=${forwarded.origin_device_id ?: forwarded.sender_device_id} " +
                    "target=${forwarded.target_device_id ?: "missing"} muted=${forwarded.kind == NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1} sent=$sent",
        )
        return sent
    }

    fun relayRoomClosed(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (!shouldRelayRoomClosed(envelope)) {
            ActivityLog.add(
                label = "Nearby room closed relay skipped",
                source = "nearby",
                message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} closed_at_ms=${envelope.closed_at_ms ?: envelope.created_at_ms} reason=duplicate",
            )
            return false
        }
        val sent = sendRoomClosedEnvelope(envelope)
        rememberRelayedCloseNotice(envelope)
        ActivityLog.add(
            label = "Nearby room closed relay send",
            source = "nearby",
            message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} closed_at_ms=${envelope.closed_at_ms ?: envelope.created_at_ms} sent=$sent",
        )
        return sent
    }

    fun maybeStoreBootstrapKey(packet: NearbyRoomTransportEnvelope): Boolean {
        if (packet.kind != NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1) return false
        if (repository.getRoom(packet.room_id) == null) {
            ActivityLog.add(
                label = "Nearby room key bootstrap store skipped",
                source = "nearby",
                message = "room_id=${packet.room_id} reason=room_missing sender=${packet.sender_device_id}",
            )
            return false
        }
        val roomKey = packet.room_key?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
        if (roomKey == null) {
            ActivityLog.add(
                label = "Nearby room key bootstrap store skipped",
                source = "nearby",
                message = "room_id=${packet.room_id} reason=invalid_key sender=${packet.sender_device_id}",
            )
            return false
        }
        keyManager.store(packet.room_id, roomKey)
        clearPendingKeyRequestWait(
            roomId = packet.room_id,
            reason = "bootstrap_stored",
        )
        ActivityLog.add(
            label = "Nearby room key bootstrap stored",
            source = "nearby",
            message = "room_id=${packet.room_id} sender=${packet.sender_device_id}",
        )
        ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_KEY_READY, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(packet.room_id))
        return true
    }

    fun shouldRelayKeyBootstrap(packet: NearbyRoomTransportEnvelope): Boolean {
        if (packet.kind != NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1) return false
        val targetDeviceId = packet.target_device_id?.takeIf { it.isNotBlank() } ?: return false
        if (targetDeviceId == repository.localDeviceId()) return false
        val noticeId = keyBootstrapNoticeId(packet)
        return relayedKeyBootstrapNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun relayKeyBootstrap(packet: NearbyRoomTransportEnvelope): Boolean {
        val targetDeviceId = packet.target_device_id?.takeIf { it.isNotBlank() } ?: return false
        val payload = NearbyRoomTransportCodec.encode(packet)
        val sent = payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeToDeviceIdsWithHint(
                payload,
                setOf(targetDeviceId),
                MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP,
            )
        if (sent) {
            relayedKeyBootstrapNoticeIds[keyBootstrapNoticeId(packet)] = System.currentTimeMillis()
        }
        return sent
    }

    fun shouldAcceptWrongTargetKeyBootstrap(packet: NearbyRoomTransportEnvelope): Boolean {
        if (packet.kind != NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1) return false
        val targetDeviceId = packet.target_device_id?.takeIf { it.isNotBlank() } ?: return false
        if (targetDeviceId == repository.localDeviceId()) return false
        val noticeId = keyBootstrapNoticeId(packet)
        return seenWrongTargetKeyBootstrapNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun clearRoomSecurity(roomId: String) {
        keyManager.clear(roomId)
    }

    fun hasRoomKey(roomId: String): Boolean = keyManager.load(roomId) != null

    fun isChatAccessReady(roomId: String): Boolean {
        val room = repository.getRoom(roomId) ?: return false
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return false
        if (room.is_local_owner) return true
        return hasRoomKey(roomId) && room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED
    }

    fun shouldRelayRoomClosed(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1) return false
        val noticeId = closeNoticeId(envelope)
        return relayedCloseNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun shouldRelayOwnerMuteState(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1 &&
            envelope.kind != NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1
        ) {
            return false
        }
        val noticeId = ownerMuteNoticeId(envelope)
        return relayedOwnerMuteNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun shouldRelayJoinReady(envelope: NearbyRoomTransportEnvelope): Boolean {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1) return false
        val noticeId = joinReadyNoticeId(envelope)
        return relayedJoinReadyNoticeIds.putIfAbsent(noticeId, System.currentTimeMillis()) == null
    }

    fun shouldReplyToKeyRequest(roomId: String, requesterDeviceId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (requesterDeviceId == repository.localDeviceId()) {
            ActivityLog.add(
                label = "Nearby room key reply skipped",
                source = "nearby",
                message = "room_id=$roomId requester=$requesterDeviceId reason=self_request",
            )
            return false
        }
        if (!hasRoomKey(roomId)) {
            ActivityLog.add(
                label = "Nearby room key reply skipped",
                source = "nearby",
                message = "room_id=$roomId requester=$requesterDeviceId reason=missing_key",
            )
            return false
        }
        val cooldownKey = "$roomId|$requesterDeviceId"
        val previous = keyBootstrapCooldownByRequester[cooldownKey] ?: 0L
        val remainingMs = KEY_BOOTSTRAP_REPLY_COOLDOWN_MS - (nowMs - previous)
        if (remainingMs > 0L) {
            ActivityLog.add(
                label = "Nearby room key reply skipped",
                source = "nearby",
                message = "room_id=$roomId requester=$requesterDeviceId reason=cooldown remaining_ms=$remainingMs",
            )
            return false
        }
        keyBootstrapCooldownByRequester[cooldownKey] = nowMs
        ActivityLog.add(
            label = "Nearby room key reply ready",
            source = "nearby",
            message = "room_id=$roomId requester=$requesterDeviceId",
        )
        return true
    }

    fun shouldReplyToMetadataRequest(
        roomId: String,
        requesterDeviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (requesterDeviceId == repository.localDeviceId()) {
            return false
        }
        val room = repository.getRoom(roomId) ?: return false
        if (!room.canServeMetadataResponse()) {
            return false
        }
        val cooldownKey = "$roomId|$requesterDeviceId"
        val previous = metadataReplyCooldownByRequester[cooldownKey] ?: 0L
        val remainingMs = METADATA_REPLY_COOLDOWN_MS - (nowMs - previous)
        if (remainingMs > 0L) {
            ActivityLog.add(
                label = "Nearby room metadata reply skipped",
                source = "nearby",
                message = "room_id=$roomId requester=$requesterDeviceId reason=cooldown remaining_ms=$remainingMs",
            )
            return false
        }
        metadataReplyCooldownByRequester[cooldownKey] = nowMs
        return true
    }

    fun isLocalOwner(roomId: String): Boolean = repository.getRoom(roomId)?.is_local_owner == true

    private fun sendRoomClosedEnvelope(envelope: NearbyRoomTransportEnvelope): Boolean {
        val payload = NearbyRoomTransportCodec.encode(envelope)
        return payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
    }

    private fun sendOwnerMuteEnvelope(envelope: NearbyRoomTransportEnvelope): Boolean {
        val payload = NearbyRoomTransportCodec.encode(envelope)
        return payload.isNotEmpty() &&
            BleMeshRuntime.get(appContext).broadcastEnvelopeWithHint(
                payload,
                MeshTrafficHint.NEARBY_ROOM_CONTROL,
            )
    }

    private fun rememberRelayedCloseNotice(envelope: NearbyRoomTransportEnvelope) {
        relayedCloseNoticeIds[closeNoticeId(envelope)] = System.currentTimeMillis()
    }

    private fun rememberRelayedOwnerMuteNotice(envelope: NearbyRoomTransportEnvelope) {
        relayedOwnerMuteNoticeIds[ownerMuteNoticeId(envelope)] = System.currentTimeMillis()
    }

    private fun closeNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return "${envelope.room_id}|${envelope.sender_device_id}|${envelope.closed_at_ms ?: envelope.created_at_ms}"
    }

    private fun joinReadyNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return "${envelope.room_id}|${envelope.origin_device_id ?: envelope.sender_device_id}|${envelope.created_at_ms}"
    }

    private fun ownerMuteNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return buildString {
            append(envelope.room_id)
            append('|')
            append(envelope.origin_device_id ?: envelope.sender_device_id)
            append('|')
            append(envelope.target_device_id ?: "missing")
            append('|')
            append(envelope.kind)
            append('|')
            append(envelope.created_at_ms)
        }
    }

    private fun selfInfoNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return buildString {
            append(envelope.room_id)
            append('|')
            append(envelope.origin_device_id ?: envelope.sender_device_id)
            append('|')
            append(envelope.target_device_id ?: "broadcast")
            append('|')
            append(envelope.self_info_revision?.takeIf(NearbySelfInfoProtocol::isValidRevision)
                ?: envelope.profile_updated_at_ms
                ?: -1L)
        }
    }

    private fun selfInfoAckNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return buildString {
            append(envelope.room_id)
            append('|')
            append(envelope.origin_device_id ?: envelope.sender_device_id)
            append('|')
            append(envelope.target_device_id ?: "missing")
            append('|')
            append(envelope.self_info_revision?.takeIf(NearbySelfInfoProtocol::isValidRevision)
                ?: envelope.profile_updated_at_ms
                ?: -1L)
        }
    }

    private fun keyBootstrapNoticeId(envelope: NearbyRoomTransportEnvelope): String {
        return "${envelope.room_id}|${envelope.sender_device_id}|${envelope.target_device_id ?: "missing"}|${envelope.created_at_ms}"
    }

    private fun selfInfoReplyCooldownKey(roomId: String, targetDeviceId: String): String {
        return "$roomId|$targetDeviceId"
    }

    private fun selectKeyRequestTarget(
        room: NearbyRoomEntity,
        nowMs: Long,
    ): NearbyRoomPeerSnapshotStore.PeerSnapshot? {
        val localDeviceId = repository.localDeviceId()
        val roomPeers = NearbyRoomPeerSnapshotStore.nearbyPeersForRoom(room.room_id, limit = KEY_REQUEST_TARGET_LIMIT)
        val fallbackPeers = NearbyRoomPeerSnapshotStore.nearbyPeers(limit = KEY_REQUEST_TARGET_LIMIT)
        val orderedCandidates = LinkedHashMap<String, NearbyRoomPeerSnapshotStore.PeerSnapshot>()

        room.owner_device_id
            .takeIf { !it.isNullOrBlank() && it != localDeviceId }
            ?.let { ownerDeviceId ->
                (roomPeers + fallbackPeers).firstOrNull { it.deviceId == ownerDeviceId }?.let { orderedCandidates[it.deviceId] = it }
            }
        (roomPeers + fallbackPeers)
            .filterNot { it.deviceId == localDeviceId }
            .forEach { orderedCandidates.putIfAbsent(it.deviceId, it) }

        if (orderedCandidates.isEmpty()) {
            ActivityLog.add(
                label = "Nearby room key request target select",
                source = "nearby",
                message = "room_id=${room.room_id} action=broadcast reason=no_candidates",
            )
            return null
        }

        val freshCandidate = orderedCandidates.values.firstOrNull { candidate ->
            val lastRequestedAtMs = keyRequestHistoryByRoomAndTarget[keyRequestHistoryKey(room.room_id, candidate.deviceId)] ?: 0L
            nowMs - lastRequestedAtMs >= KEY_REQUEST_TARGET_REUSE_COOLDOWN_MS
        }
        if (freshCandidate != null) {
            ActivityLog.add(
                label = "Nearby room key request target select",
                source = "nearby",
                message =
                    "room_id=${room.room_id} action=target target=${freshCandidate.deviceId} reason=fresh_candidate " +
                        "rssi=${freshCandidate.rssi} seen_at_ms=${freshCandidate.seenAtMs}",
            )
            return freshCandidate
        }

        val reusedCandidate = orderedCandidates.values.minByOrNull { candidate ->
            keyRequestHistoryByRoomAndTarget[keyRequestHistoryKey(room.room_id, candidate.deviceId)] ?: 0L
        }
        ActivityLog.add(
            label = "Nearby room key request target select",
            source = "nearby",
            message =
                "room_id=${room.room_id} action=target target=${reusedCandidate?.deviceId ?: "none"} reason=reuse_oldest " +
                    "candidate_count=${orderedCandidates.size}",
        )
        return reusedCandidate
    }

    private fun keyRequestHistoryKey(roomId: String, targetDeviceId: String): String {
        return "$roomId|$targetDeviceId"
    }

    private fun clearPendingKeyRequestWait(
        roomId: String,
        reason: String,
    ) {
        val removed = keyRequestAwaitByRoomId.remove(roomId) ?: return
        ActivityLog.add(
            label = "Nearby room key request wait cleared",
            source = "nearby",
            message =
                "room_id=$roomId target=${removed.targetDeviceId ?: "broadcast"} " +
                    "reason=$reason started_at_ms=${removed.startedAtMs}",
        )
    }

    private data class KeyRequestAwaitState(
        val targetDeviceId: String?,
        val startedAtMs: Long,
        val expiresAtMs: Long,
    )

    companion object {
        const val KEY_VERSION = 1
        private const val KEY_BOOTSTRAP_REPLY_COOLDOWN_MS = 15_000L
        private const val KEY_REQUEST_AWAIT_BOOTSTRAP_MS = 15_000L
        private const val KEY_REQUEST_TARGET_LIMIT = 8
        private const val KEY_REQUEST_TARGET_REUSE_COOLDOWN_MS = 60_000L
        private const val METADATA_REPLY_COOLDOWN_MS = 5_000L
        private const val JOIN_READY_SEND_COOLDOWN_MS = 15_000L
        private const val SELF_INFO_BROADCAST_COOLDOWN_MS = 15_000L
        private const val SELF_INFO_REPLY_COOLDOWN_MS = 1_000L
        private const val MAX_JOIN_READY_TARGETS = 10
        private const val MAX_SELF_INFO_TARGETS = 10
        private val keyRequestAwaitByRoomId = ConcurrentHashMap<String, KeyRequestAwaitState>()
    }
}

private fun NearbyRoomEntity.canServeMetadataResponse(): Boolean {
    return state == NearbyRoomEntity.STATE_ACTIVE &&
        name.isNotBlank() &&
        (is_local_owner || metadata_status == NearbyRoomEntity.METADATA_RESOLVED)
}
