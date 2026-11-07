package app.peermesh

import android.content.Context
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.GroupEntity
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.KeyEntity
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.data.reducer.SysReducer
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.encodeToString

object SysEventWriter {
    fun updateUserProfile(
        context: Context,
        gid: String,
        displayName: String,
        avatarId: String?,
    ): Boolean {
        return try {
            val db = AppDatabaseProvider.get(context)
            val actorDid = DeviceIdProvider.get(context)
            if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) return false
            val nowSeconds = System.currentTimeMillis() / 1000L
            val lamport = System.currentTimeMillis()
            val nonce = EventIdFactory.generateNonce()
            val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, nonce)
            val core = EventFactory.sysUser(
                eventId = eventId,
                actorDid = actorDid,
                createdSeconds = nowSeconds,
                lamport = lamport,
                displayName = displayName,
                avatarId = avatarId,
            )
            val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
            val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
            val event = EventEntity(
                gid = gid,
                event_id = eventId,
                type = coreWithKid.type,
                source = "local",
                created_t = nowSeconds,
                time_lt = coreWithKid.time.lt ?: 0L,
                actor_did = coreWithKid.actor.did,
                ref_root = coreWithKid.ref.root,
                ref_parent = coreWithKid.ref.parent,
                ref_target = coreWithKid.ref.target,
                ttl_s = coreWithKid.meta?.ttl_s,
                payload_json = payloadJson,
                inserted_at = nowSeconds,
            )
            val profile = UserProfileEntity(
                gid = gid,
                actor_did = actorDid,
                display_name = displayName,
                avatar_id = avatarId,
                updated_at = nowSeconds,
                last_event_id = eventId,
                last_event_t = coreWithKid.time.t,
                last_event_lt = coreWithKid.time.lt ?: 0L,
                last_event_actor = coreWithKid.actor.did,
            )
            db.runInTransaction {
                db.eventsDao().insert(event)
                db.userProfileDao().upsert(profile)
            }
            enqueueMetadataSnapshot(context, gid, coreWithKid)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun updateGroup(
        context: Context,
        gid: String,
        name: String,
    ): Boolean {
        return try {
            val db = AppDatabaseProvider.get(context)
            val existing = db.groupDao().findGroup(gid) ?: return false
            val actorDid = DeviceIdProvider.get(context)
            if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) return false
            val nowSeconds = System.currentTimeMillis() / 1000L
            val updated = existing.copy(name = name, updated_at = nowSeconds)
            val coreWithKid = buildGroupCore(context, gid, updated, nowSeconds)
            val event = eventEntity(gid, coreWithKid, nowSeconds)
            db.runInTransaction {
                val result = db.eventsDao().insert(event)
                if (result != -1L) {
                    SysReducer(db.groupDao(), db.userProfileDao(), db.removedMemberDao()).apply(coreWithKid, gid)
                } else {
                    db.groupDao().upsert(updated)
                }
            }
            enqueueMetadataSnapshot(context, gid, coreWithKid)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun removeMember(
        context: Context,
        gid: String,
        targetPeerId: String,
    ): Boolean {
        return try {
            val db = AppDatabaseProvider.get(context)
            val group = db.groupDao().findGroup(gid) ?: return false
            val actorDid = DeviceIdProvider.get(context)
            if (group.owner_did != actorDid) return false
            if (targetPeerId.isBlank() || targetPeerId == actorDid || targetPeerId == group.owner_did) return false
            ActivityLog.add(
                label = "Member removal requested",
                source = "moderation",
                message = "gid=$gid actor=$actorDid target=$targetPeerId",
                gid = gid,
            )
            val nowSeconds = System.currentTimeMillis() / 1000L
            val core = EventFactory.sysMemberRemoved(
                eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce()),
                actorDid = actorDid,
                createdSeconds = nowSeconds,
                lamport = System.currentTimeMillis(),
                targetPeerId = targetPeerId,
            )
            val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
            val event = eventEntity(gid, coreWithKid, nowSeconds)
            var inserted = false
            db.runInTransaction {
                val result = db.eventsDao().insert(event)
                if (result != -1L) {
                    inserted = true
                    SysReducer(db.groupDao(), db.userProfileDao(), db.removedMemberDao()).apply(coreWithKid, gid)
                }
            }
            if (!inserted) return false
            val accepted = UserOutbox.enqueue(
                context = context,
                gid = gid,
                core = coreWithKid,
                source = "mesh",
                successLabel = "Sent member removal",
                successMessage = "target=$targetPeerId",
            )
            ActivityLog.add(
                label = "Member removal queued",
                source = "moderation",
                message = "gid=$gid actor=$actorDid target=$targetPeerId accepted=$accepted",
                eventId = coreWithKid.id,
                gid = gid,
            )
            if (accepted) {
                enqueueGroupRekeyDraft(
                    context = context,
                    gid = gid,
                    trigger = "member_removed",
                    targetPeerId = targetPeerId,
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun enqueueGroupRekeyDraft(
        context: Context,
        gid: String,
        trigger: String,
        targetPeerId: String,
    ) {
        val draft = GroupRekeyManager.createDraft(
            context = context,
            gid = gid,
            reason = "$trigger:$targetPeerId",
        ) ?: run {
            ActivityLog.add(
                label = "Group rekey draft failed",
                source = "moderation",
                message = "gid=$gid trigger=$trigger target=$targetPeerId",
                gid = gid,
            )
            return
        }
        val actorDid = DeviceIdProvider.get(context)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val core = EventFactory.sysGroupRekey(
            eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce()),
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = System.currentTimeMillis(),
            previousKid = draft.previousKid,
            newKid = draft.newKid,
            rotationReason = trigger,
            ttlSeconds = TtlHelper.DEFAULT_MEMBERSHIP_CONTROL_TTL_S,
        )
        val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
        ActivityLog.add(
            label = "Group rekey event prepared",
            source = "moderation",
            message = "gid=$gid trigger=$trigger previous_kid=${draft.previousKid ?: "-"} new_kid=${draft.newKid}",
            eventId = coreWithKid.id,
            gid = gid,
        )
        val accepted = UserOutbox.enqueue(
            context = context,
            gid = gid,
            core = coreWithKid,
            source = "moderation",
            successLabel = "Sent group rekey",
            successMessage = "trigger=$trigger new_kid=${draft.newKid}",
            failureLabel = "Group rekey queued",
        )
        ActivityLog.add(
            label = "Group rekey queued",
            source = "moderation",
            message = "gid=$gid trigger=$trigger accepted=$accepted new_kid=${draft.newKid}",
            eventId = coreWithKid.id,
            gid = gid,
        )
        runCatching {
            val db = AppDatabaseProvider.get(context.applicationContext)
            val group = db.groupDao().findGroup(gid)
            val resolution = GroupPeerRouteResolver.resolveActivePeerRoutes(
                db = db,
                gid = gid,
                ownerDid = group?.owner_did,
                currentActorDid = actorDid,
                excludedPeerIds = setOf(actorDid, targetPeerId),
            )
            ActivityLog.add(
                label = "Group rekey target routes",
                source = "moderation",
                message = "gid=$gid peers=${resolution.peerDeviceIds.size} device_targets=${resolution.peerDeviceIds.values.sumOf { it.size }} missing_peers=${resolution.missingPeerIds.joinToString(",").ifBlank { "none" }}",
                eventId = coreWithKid.id,
                gid = gid,
            )
            if (resolution.missingPeerIds.isEmpty()) {
                queueDirectedGroupKeyShares(
                    context = context,
                    gid = gid,
                    actorDid = actorDid,
                    draft = draft,
                    targetDeviceIds = resolution.peerDeviceIds.values.flatten().toSet(),
                )
                activateLocalGroupKeyVersion(
                    context = context,
                    gid = gid,
                    newKid = draft.newKid,
                    trigger = "owner_after_key_share",
                )
            } else {
                ActivityLog.add(
                    label = "Group rekey activation deferred",
                    source = "moderation",
                    message = "gid=$gid new_kid=${draft.newKid} missing_peers=${resolution.missingPeerIds.joinToString(",")}",
                    eventId = coreWithKid.id,
                    gid = gid,
                )
            }
        }
    }

    private fun queueDirectedGroupKeyShares(
        context: Context,
        gid: String,
        actorDid: String,
        draft: GroupRekeyManager.DraftRekey,
        targetDeviceIds: Set<String>,
    ) {
        if (targetDeviceIds.isEmpty()) return
        val keyStore = SecureKeyStore(context.applicationContext)
        val nextKey = keyStore.loadGroupKey(gid, draft.newKid) ?: return
        val nowSeconds = System.currentTimeMillis() / 1000L
        val core = EventFactory.sysGroupKeyShare(
            eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce()),
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = System.currentTimeMillis(),
            previousKid = draft.previousKid,
            newKid = draft.newKid,
            groupKeyBase64 = Base64Url.encode(nextKey),
            ttlSeconds = TtlHelper.DEFAULT_MEMBERSHIP_CONTROL_TTL_S,
        )
        val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
        val accepted = OutboundBatchDispatcher.enqueue(
            context = context,
            gid = gid,
            core = coreWithKid,
            source = "moderation",
            targetDeviceIds = targetDeviceIds,
        )
        ActivityLog.add(
            label = "Group key share queued",
            source = "moderation",
            message = "gid=$gid new_kid=${draft.newKid} device_targets=${targetDeviceIds.size} accepted=$accepted",
            eventId = coreWithKid.id,
            gid = gid,
        )
    }

    private fun activateLocalGroupKeyVersion(
        context: Context,
        gid: String,
        newKid: String,
        trigger: String,
    ): Boolean {
        val appContext = context.applicationContext
        val keyStore = SecureKeyStore(appContext)
        val keyBytes = keyStore.loadGroupKey(gid, newKid) ?: return false
        val db = AppDatabaseProvider.get(appContext)
        val group = db.groupDao().findGroup(gid) ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000L
        keyStore.storeGroupKey(gid, keyBytes)
        keyStore.clearJoinMaterial(gid)
        db.groupDao().upsert(group.copy(kid = newKid, updated_at = maxOf(group.updated_at, nowSeconds)))
        val existingKey = db.keyDao().findByGid(gid)
        db.keyDao().upsert(
            KeyEntity(
                gid = gid,
                kid = newKid,
                created_at = existingKey?.created_at ?: nowSeconds,
                updated_at = nowSeconds,
            )
        )
        ActivityLog.add(
            label = "Group key activated",
            source = "moderation",
            message = "gid=$gid new_kid=$newKid trigger=$trigger",
            gid = gid,
        )
        ActivityLog.add(
            label = "Group invite material cleared",
            source = "moderation",
            message = "gid=$gid trigger=$trigger reason=rekey",
            gid = gid,
        )
        return true
    }

    fun syncActiveGroupMetadata(context: Context, reason: String): Boolean {
        val gid = ActiveGroupManager.getActiveGroup(context) ?: return false
        return syncMetadataSnapshots(context, gid, reason)
    }

    fun syncMetadataSnapshots(context: Context, gid: String, reason: String): Boolean {
        return try {
            val db = AppDatabaseProvider.get(context)
            val group = db.groupDao().findGroup(gid)
            val actorDid = DeviceIdProvider.get(context)
            if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) return false
            val profile = db.userProfileDao().find(gid, actorDid)
            val nowSeconds = System.currentTimeMillis() / 1000L
            var sent = false
            if (group?.name?.isNotBlank() == true) {
                enqueueMetadataSnapshot(
                    context = context,
                    gid = gid,
                    core = buildGroupCore(context, gid, group, nowSeconds),
                    reason = reason,
                )
                sent = true
            }
            if (profile?.display_name?.isNotBlank() == true) {
                enqueueMetadataSnapshot(
                    context = context,
                    gid = gid,
                    core = buildUserCore(
                        context = context,
                        gid = gid,
                        displayName = profile.display_name,
                        avatarId = profile.avatar_id,
                        nowSeconds = nowSeconds,
                    ),
                    reason = reason,
                )
                sent = true
            }
            sent
        } catch (_: Exception) {
            false
        }
    }

    private fun buildGroupCore(
        context: Context,
        gid: String,
        group: GroupEntity,
        nowSeconds: Long,
    ): EventCore {
        val actorDid = DeviceIdProvider.get(context)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.sysGroup(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = System.currentTimeMillis(),
            name = group.name ?: "",
            gid = group.gid,
            ownerDid = group.owner_did,
            contextProfile = group.context_profile,
            groupCreatedAt = group.created_at,
            groupUpdatedAt = group.updated_at,
        )
        return KeyIdHelper.attachKeyId(context, gid, core)
    }

    private fun buildUserCore(
        context: Context,
        gid: String,
        displayName: String,
        avatarId: String?,
        nowSeconds: Long,
    ): EventCore {
        val actorDid = DeviceIdProvider.get(context)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.sysUser(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = System.currentTimeMillis(),
            displayName = displayName,
            avatarId = avatarId,
        )
        return KeyIdHelper.attachKeyId(context, gid, core)
    }

    private fun eventEntity(gid: String, core: EventCore, insertedAtSeconds: Long): EventEntity {
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), core)
        return EventEntity(
            gid = gid,
            event_id = core.id,
            type = core.type,
            source = "local",
            created_t = core.time.t,
            time_lt = core.time.lt ?: 0L,
            actor_did = core.actor.did,
            ref_root = core.ref.root,
            ref_parent = core.ref.parent,
            ref_target = core.ref.target,
            ttl_s = core.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = insertedAtSeconds,
        )
    }

    private fun enqueueMetadataSnapshot(
        context: Context,
        gid: String,
        core: EventCore,
        reason: String = "metadata_change",
    ) {
        val accepted = UserOutbox.enqueue(
            context = context,
            gid = gid,
            core = core,
            source = SOURCE_SYS_METADATA,
            successLabel = "Sent sys metadata snapshot",
            successMessage = "${core.type} reason=$reason",
        )
        ActivityLog.add(
            label = "SYS metadata snapshot",
            source = SOURCE_SYS_METADATA,
            message = "type=${core.type} accepted=$accepted reason=$reason",
            eventId = core.id,
            gid = gid,
        )
    }

    const val SOURCE_SYS_METADATA = "sys_metadata"
}
