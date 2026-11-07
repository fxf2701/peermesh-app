package app.peermesh.nearby

import android.content.Context
import app.peermesh.core.transport.EnvelopeReceiveResult
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels

class NearbyRoomTransportReceiver(
    context: Context,
    private val delegate: EnvelopeReceiver,
    private val repository: NearbyRoomRepository = NearbyRoomRepository(context.applicationContext),
) : EnvelopeReceiver {
    private val appContext = context.applicationContext
    private val transportManager = NearbyRoomTransportManager(appContext, repository)

    override fun onEnvelopeReceived(blob: ByteArray): Boolean {
        return onEnvelopeReceivedWithMeta(blob).accepted
    }

    override fun onEnvelopeReceivedWithMeta(blob: ByteArray): EnvelopeReceiveResult {
        val envelope = NearbyRoomTransportCodec.decode(blob)
        if (envelope == null) {
            return delegate.onEnvelopeReceivedWithMeta(blob)
        }

        val matchingRuntimeRoom = NearbyForegroundRuntimeCoordinator.acceptsRoomProtocol(envelope.room_id)
        if (!NearbyRoomReceiverPolicy.shouldProcess(envelope.kind, matchingRuntimeRoom)) {
            val runtime = NearbyForegroundRuntimeCoordinator.currentState()
            val decision = "message_type=${envelope.kind} packet_room_id=${envelope.room_id} runtime_state=${runtime.javaClass.simpleName} accepted=false retryable=true relay_eligible=false opaque_forward_allowed=false ui_publish=false dedupe_retained=false reason=no_matching_runtime_session"
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.RX_DECISION, source = "nearby", message = decision)
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.RX_WRONG_ROOM, source = "nearby", message = "$decision dedupe_released=true")
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.RX_RETRYABLE, source = "nearby", message = "packet_room_id=${envelope.room_id} reason=wrong_room")
            ActivityLog.add(
                label = "Nearby room receiver decision",
                source = "nearby",
                message = "kind=${envelope.kind} room_id=${envelope.room_id} decision=reject reason=no_matching_runtime_session",
            )
            return EnvelopeReceiveResult(
                accepted = false,
                relayEligible = false,
                decoded = true,
                opaqueForwardAllowed = false,
                retryable = true,
            )
        }

        when (envelope.kind) {
            NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1 -> {
                ActivityLog.add(
                    label = "Nearby room key request recv",
                    source = "nearby",
                    message = "room_id=${envelope.room_id} requester=${envelope.sender_device_id}",
                )
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_KEY_REQUEST_RECV, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(envelope.room_id))
                if (transportManager.shouldReplyToKeyRequest(
                        roomId = envelope.room_id,
                        requesterDeviceId = envelope.sender_device_id,
                    )
                ) {
                    transportManager.sendKeyBootstrap(
                        roomId = envelope.room_id,
                        targetDeviceId = envelope.sender_device_id,
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1 -> {
                ActivityLog.add(
                    label = "Nearby room key bootstrap recv",
                    source = "nearby",
                    message =
                        "room_id=${envelope.room_id} sender=${envelope.sender_device_id} created_at_ms=${envelope.created_at_ms} expires_at_ms=${envelope.expires_at_ms}",
                )
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_BOOTSTRAP_RECV, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(envelope.room_id))
                val targetDeviceId = envelope.target_device_id?.takeIf { it.isNotBlank() }
                val localDeviceId = repository.localDeviceId()
                if (targetDeviceId == null) {
                    ActivityLog.add(
                        label = "Nearby room key bootstrap dropped",
                        source = "nearby",
                        message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} reason=missing_target",
                    )
                    return EnvelopeReceiveResult(
                        accepted = true,
                        relayEligible = false,
                        decoded = true,
                        opaqueForwardAllowed = false,
                    )
                }
                if (targetDeviceId != localDeviceId) {
                    if (!transportManager.shouldAcceptWrongTargetKeyBootstrap(envelope)) {
                        ActivityLog.add(
                            label = "Nearby room key bootstrap receive skipped",
                            source = "nearby",
                            message =
                                "room_id=${envelope.room_id} sender=${envelope.sender_device_id} target=$targetDeviceId local=$localDeviceId reason=duplicate_wrong_target",
                        )
                        return EnvelopeReceiveResult(
                            accepted = true,
                            relayEligible = false,
                            decoded = true,
                            opaqueForwardAllowed = false,
                        )
                    }
                    if (envelope.sender_device_id == localDeviceId) {
                        ActivityLog.add(
                            label = "Nearby room key bootstrap bounce skipped",
                            source = "nearby",
                            message =
                                "room_id=${envelope.room_id} sender=${envelope.sender_device_id} target=$targetDeviceId local=$localDeviceId reason=self_origin_bounce",
                        )
                        return EnvelopeReceiveResult(
                            accepted = true,
                            relayEligible = false,
                            decoded = true,
                            opaqueForwardAllowed = false,
                        )
                    }
                    val relayed = if (transportManager.shouldRelayKeyBootstrap(envelope)) {
                        transportManager.relayKeyBootstrap(envelope)
                    } else {
                        false
                    }
                    ActivityLog.add(
                        label = if (relayed) {
                            "Nearby room key bootstrap forwarded"
                        } else {
                            "Nearby room key bootstrap forward skipped"
                        },
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} target=$targetDeviceId local=$localDeviceId" +
                                if (relayed) "" else " reason=wrong_target",
                    )
                    return EnvelopeReceiveResult(
                        accepted = true,
                        relayEligible = false,
                        decoded = true,
                        opaqueForwardAllowed = false,
                    )
                }
                val stored = transportManager.maybeStoreBootstrapKey(envelope)
                repository.syncRoomCreatedAtFromTransport(
                    roomId = envelope.room_id,
                    authoritativeCreatedAtMs = envelope.created_at_ms,
                    ownerDeviceId = envelope.owner_device_id,
                    seenAtMs = System.currentTimeMillis(),
                )
                if (stored) {
                    val ownerTargetDeviceId = envelope.owner_device_id?.takeIf { it.isNotBlank() }
                    ActivityLog.add(
                        label = "Nearby room post-join metadata refresh trigger",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} owner=${ownerTargetDeviceId ?: "unknown"} reason=bootstrap_stored",
                    )
                    NearbyRoomMetadataFetchCoordinator.enqueuePostJoinRefresh(
                        context = appContext,
                        roomId = envelope.room_id,
                        targetDeviceId = ownerTargetDeviceId ?: envelope.sender_device_id,
                    )
                    NearbyRoomPendingJoinIntentManager.maybeAutoOpenFromLateKey(
                        context = appContext,
                        roomId = envelope.room_id,
                        trigger = "bootstrap_stored",
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1 -> {
                ActivityLog.add(
                    label = "Nearby room metadata request recv",
                    source = "nearby",
                    message = "room_id=${envelope.room_id} requester=${envelope.sender_device_id}",
                )
                if (transportManager.shouldReplyToMetadataRequest(
                        roomId = envelope.room_id,
                        requesterDeviceId = envelope.sender_device_id,
                    )
                ) {
                    transportManager.sendMetadataResponse(
                        roomId = envelope.room_id,
                        targetDeviceId = envelope.sender_device_id,
                    )
                    transportManager.sendSelfInfo(
                        roomId = envelope.room_id,
                        targetDeviceId = envelope.sender_device_id,
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1 -> {
                val roomName = envelope.room_name
                if (roomName.isNullOrBlank()) {
                    ActivityLog.add(
                        label = "Nearby room metadata response ignored",
                        source = "nearby",
                        message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} reason=missing_name",
                    )
                } else {
                    repository.markRoomMetadataResolved(
                        roomId = envelope.room_id,
                        roomName = roomName,
                        createdAtMs = envelope.created_at_ms,
                        ownerDeviceId = envelope.owner_device_id,
                        ownerDisplayName = envelope.sender_display_name,
                        participantCount = envelope.participant_count,
                        sourceDeviceId = envelope.sender_device_id,
                        fetchedAtMs = System.currentTimeMillis(),
                    )
                    NearbyRoomMetadataFetchCoordinator.onMetadataResolved(appContext, envelope.room_id)
                    val ownerTargetDeviceId = envelope.owner_device_id?.takeIf { it.isNotBlank() }
                    if (ownerTargetDeviceId != null && transportManager.isChatAccessReady(envelope.room_id)) {
                        transportManager.sendJoinReady(envelope.room_id, ownerTargetDeviceId)
                        transportManager.sendSelfInfoToNearbyPeers(envelope.room_id)
                    }
                    ActivityLog.add(
                        label = "Nearby room metadata response recv",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} created_at_ms=${envelope.created_at_ms} expires_at_ms=${envelope.expires_at_ms}",
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1 -> {
                val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
                val inserted = repository.recordParticipantJoinSuccess(
                    roomId = envelope.room_id,
                    participantDeviceId = originDeviceId,
                    seenAtMs = System.currentTimeMillis(),
                )
                val firstSeen = transportManager.shouldHandleJoinReady(envelope)
                if (firstSeen) {
                    transportManager.sendSelfInfo(
                        roomId = envelope.room_id,
                        targetDeviceId = originDeviceId,
                    )
                }
                transportManager.relayJoinReady(envelope)
                ActivityLog.add(
                    label = "Nearby room join ready recv",
                    source = "nearby",
                    message =
                        "room_id=${envelope.room_id} sender=${envelope.sender_device_id} origin=$originDeviceId " +
                            "inserted=$inserted first_seen=$firstSeen",
                )
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_READY, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(envelope.room_id))
            }
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1 -> {
                val targetDeviceId = envelope.target_device_id?.takeIf { it.isNotBlank() }
                val localDeviceId = repository.localDeviceId()
                if (targetDeviceId != null && targetDeviceId != localDeviceId) {
                    val relayed = transportManager.relaySelfInfo(envelope)
                    ActivityLog.add(
                        label = "Nearby room self info relay recv",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} " +
                                "origin=${envelope.origin_device_id ?: envelope.sender_device_id} " +
                                "target=$targetDeviceId local=$localDeviceId relayed=$relayed",
                    )
                } else {
                    val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
                    val validatedSnapshot = NearbySelfInfoProtocol.validateReceived(envelope)
                    val selfInfoResult = repository.upsertParticipantSelfInfo(
                        roomId = envelope.room_id,
                        participantDeviceId = originDeviceId,
                        participantDisplayName = envelope.sender_display_name,
                        participantAvatarId = envelope.avatar_id,
                        profileUpdatedAtMs = envelope.profile_updated_at_ms,
                        recipientEncryptionIdentity = validatedSnapshot?.recipientEncryptionIdentity,
                        seenAtMs = System.currentTimeMillis(),
                    )
                    transportManager.sendSelfInfoAck(
                        roomId = envelope.room_id,
                        profileOwnerDeviceId = originDeviceId,
                        profileUpdatedAtMs = envelope.profile_updated_at_ms,
                        selfInfoRevision = validatedSnapshot?.revision,
                        targetDeviceId = originDeviceId,
                    )
                    if (selfInfoResult.participantInserted && originDeviceId != localDeviceId) {
                        transportManager.sendSelfInfo(
                            roomId = envelope.room_id,
                            targetDeviceId = originDeviceId,
                        )
                    }
                    transportManager.relaySelfInfo(envelope)
                    ActivityLog.add(
                        label = "Nearby room self info recv",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} origin=$originDeviceId " +
                            "target=${targetDeviceId ?: "broadcast"} inserted=${selfInfoResult.participantInserted} " +
                                "pin_result=${selfInfoResult.recipientKeyPinResult.javaClass.simpleName} " +
                                "profile_updated_at_ms=${envelope.profile_updated_at_ms ?: -1L}",
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1 -> {
                val targetDeviceId = envelope.target_device_id?.takeIf { it.isNotBlank() }
                val localDeviceId = repository.localDeviceId()
                if (targetDeviceId != null && targetDeviceId != localDeviceId) {
                    val relayed = transportManager.relaySelfInfoAck(envelope)
                    ActivityLog.add(
                        label = "Nearby room self info ack relay recv",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} " +
                                "origin=${envelope.origin_device_id ?: envelope.sender_device_id} " +
                                "target=$targetDeviceId local=$localDeviceId relayed=$relayed",
                    )
                } else {
                    transportManager.acknowledgeSelfInfo(
                        roomId = envelope.room_id,
                        targetDeviceId = envelope.sender_device_id,
                        profileUpdatedAtMs = envelope.profile_updated_at_ms,
                        selfInfoRevision = envelope.self_info_revision,
                    )
                    ActivityLog.add(
                        label = "Nearby room self info ack recv",
                        source = "nearby",
                        message =
                            "room_id=${envelope.room_id} sender=${envelope.sender_device_id} " +
                                "origin=${envelope.origin_device_id ?: envelope.sender_device_id} " +
                                "profile_updated_at_ms=${envelope.profile_updated_at_ms ?: -1L}",
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1 -> {
                val closedAtMs = envelope.closed_at_ms ?: envelope.created_at_ms
                val closedRoom = repository.closeRoomFromTransport(
                    roomId = envelope.room_id,
                    closedAtMs = closedAtMs,
                    seenAtMs = System.currentTimeMillis(),
                )
                ActivityLog.add(
                    label = "Nearby room closed recv",
                    source = "nearby",
                    message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} closed_at_ms=$closedAtMs",
                )
                val relayed = transportManager.relayRoomClosed(envelope)
                val purged = if (closedRoom != null) {
                    repository.purgeRoomLocalData(envelope.room_id)
                } else {
                    false
                }
                NearbyRoomUiNoticeStore.recordOwnerClosedNotice(appContext, envelope.room_id)
                ActivityLog.add(
                    label = "Nearby room closed cleanup",
                    source = "nearby",
                    message = "room_id=${envelope.room_id} sender=${envelope.sender_device_id} relayed=$relayed purged=$purged deferred_ui_exit=true",
                )
            }
            NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1,
            NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1,
            -> {
                val actorDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
                val targetDeviceId = envelope.target_device_id?.takeIf { it.isNotBlank() }
                if (targetDeviceId == null) {
                    ActivityLog.add(
                        label = "Nearby room owner mute recv skipped",
                        source = "moderation",
                        message = "room_id=${envelope.room_id} actor=$actorDeviceId reason=missing_target",
                    )
                } else {
                    val muted = envelope.kind == NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1
                    val applied = repository.applyOwnerMuteStateEvent(
                        roomId = envelope.room_id,
                        targetDeviceId = targetDeviceId,
                        actorDeviceId = actorDeviceId,
                        muted = muted,
                        eventCreatedAtMs = envelope.created_at_ms,
                        receivedAtMs = System.currentTimeMillis(),
                    )
                    val relayed = transportManager.relayOwnerMuteState(envelope)
                    ActivityLog.add(
                        label = "Nearby room owner mute recv",
                        source = "moderation",
                        message =
                            "room_id=${envelope.room_id} actor=$actorDeviceId target=$targetDeviceId " +
                                "muted=$muted applied=$applied relayed=$relayed",
                    )
                }
            }
            NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1 -> {
                NearbyRoomRelayRecoveryManager.onMessageOfferReceived(appContext, envelope)
            }
            NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1 -> {
                NearbyRoomRelayRecoveryManager.onMessageFetchReceived(appContext, envelope)
            }
            NearbyRoomTransportEnvelope.KIND_MESSAGE_V2 -> {
                val roomKey = NearbyRoomKeyManager(appContext).load(envelope.room_id)
                val messageId = envelope.message_id
                val ciphertext = envelope.ciphertext
                val nonce = envelope.nonce
                val keyVersion = envelope.key_version
                if (roomKey != null && messageId != null && ciphertext != null && nonce != null && keyVersion != null) {
                    val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
                    val body = NearbyRoomMessageCrypto.decrypt(
                        ciphertext = ciphertext,
                        nonce = nonce,
                        roomKey = roomKey,
                        aad = NearbyRoomMessageCrypto.messageAad(
                            roomId = envelope.room_id,
                            messageId = messageId,
                            senderDeviceId = originDeviceId,
                            createdAtMs = envelope.created_at_ms,
                            expiresAtMs = envelope.expires_at_ms,
                            keyVersion = keyVersion,
                        ),
                    )
                    if (body != null) {
                        repository.ingestRemoteTransportMessage(envelope, body)?.let { message ->
                            NearbyRoomRelayRecoveryManager.onMessageAvailable(
                                context = appContext,
                                message = message,
                                lastHopSenderDeviceId = envelope.sender_device_id,
                            )
                            ActivityLog.add(
                                label = "Nearby room message recv",
                                source = "nearby",
                                message =
                                    "room_id=${envelope.room_id} message_id=$messageId sender=${envelope.sender_device_id} " +
                                        "origin=$originDeviceId edited_at_ms=${envelope.edited_at_ms} " +
                                        "deleted_at_ms=${envelope.deleted_at_ms}",
                            )
                        }
                    } else {
                        ActivityLog.add(
                            label = "Nearby room message decrypt failed",
                            source = "nearby",
                            message = "room_id=${envelope.room_id} message_id=$messageId sender=${envelope.sender_device_id}",
                        )
                    }
                } else {
                    ActivityLog.add(
                        label = "Nearby room key request needed",
                        source = "nearby",
                        message = "room_id=${envelope.room_id} message_id=$messageId sender=${envelope.sender_device_id}",
                    )
                    transportManager.sendKeyRequest(envelope.room_id)
                }
            }
            NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1 -> {
                val roomKey = NearbyRoomKeyManager(appContext).load(envelope.room_id)
                val compactMessageId = envelope.message_compact_id
                val messageId = envelope.message_id
                val ciphertext = envelope.ciphertext
                val nonce = envelope.nonce
                val keyVersion = envelope.key_version
                if (roomKey != null &&
                    compactMessageId != null &&
                    messageId != null &&
                    ciphertext != null &&
                    nonce != null &&
                    keyVersion != null
                ) {
                    val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id
                    val body = NearbyRoomMessageCrypto.decrypt(
                        ciphertext = ciphertext,
                        nonce = nonce,
                        roomKey = roomKey,
                        aad = NearbyRoomMessageCrypto.messageAad(
                            roomId = envelope.room_id,
                            messageId = messageId,
                            senderDeviceId = originDeviceId,
                            createdAtMs = envelope.created_at_ms,
                            expiresAtMs = envelope.expires_at_ms,
                            keyVersion = keyVersion,
                        ),
                    )
                    if (body != null) {
                        repository.ingestRemoteTransportMessage(envelope, body)?.let { message ->
                            NearbyRoomRelayRecoveryManager.onMessageDataResolved(
                                context = appContext,
                                envelope = envelope,
                                message = message,
                            )
                            ActivityLog.add(
                                label = "Nearby room message data recv",
                                source = "nearby",
                                message =
                                    "room_id=${envelope.room_id} message_id=$messageId sender=${envelope.sender_device_id} " +
                                        "origin=$originDeviceId edited_at_ms=${envelope.edited_at_ms} " +
                                        "deleted_at_ms=${envelope.deleted_at_ms}",
                            )
                        }
                    }
                }
            }
        }
        ActivityLog.add(
            label = NearbyForegroundDiagnosticLabels.RX_DECISION,
            source = "nearby",
            message = "message_type=${envelope.kind} packet_room_id=${envelope.room_id} runtime_state=${NearbyForegroundRuntimeCoordinator.currentState().javaClass.simpleName} accepted=true retryable=false relay_eligible=false opaque_forward_allowed=false ui_publish=false dedupe_retained=true reason=processed",
        )
        return EnvelopeReceiveResult(
            accepted = true,
            relayEligible = false,
            decoded = true,
            opaqueForwardAllowed = false,
        )
    }

    override fun onDtnDataReceived(blob: ByteArray): EnvelopeReceiveResult {
        return delegate.onDtnDataReceived(blob)
    }
}
