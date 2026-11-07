package app.peermesh.data.ingest

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.core.transport.EnvelopeReceiveResult
import app.peermesh.core.validation.EventValidator
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.AppBatchItemV2
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EncPayload
import app.peermesh.core.relay.RelayEvent
import app.peermesh.core.types.EventTypes
import app.peermesh.data.ingest.EventSources
import app.peermesh.data.db.entity.KeyEntity
import app.peermesh.data.membership.GroupPeerRouteStore
import app.peermesh.security.crypto.EnvelopeCrypto
import app.peermesh.security.crypto.RelayPayloadCrypto
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.security.crypto.AppBatchCrypto
import app.peermesh.data.relay.LegacyRelayPayloadCompatibility
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import app.peermesh.data.debug.ActivityLog

class EnvelopeReceiverImpl(
    private val ingestor: EventIngestor,
    private val keyStore: SecureKeyStore,
    private val groupPeerRouteStore: GroupPeerRouteStore? = null,
    private val allowLegacyRelayPlaintext: Boolean = false,
) : EnvelopeReceiver {
    override fun onEnvelopeReceived(blob: ByteArray): Boolean {
        return onEnvelopeReceivedWithMeta(blob).accepted
    }

    override fun onEnvelopeReceivedWithMeta(blob: ByteArray): EnvelopeReceiveResult {
        return onEnvelopeReceivedWithMeta(blob, EventSources.MESH)
    }

    override fun onDtnDataReceived(blob: ByteArray): EnvelopeReceiveResult {
        return processEnvelopeBytes(blob, EventSources.DTN)
    }

    fun onEnvelopeReceived(blob: ByteArray, source: String): Boolean {
        return onEnvelopeReceivedWithMeta(blob, source).accepted
    }

    fun onRelayEventReceived(event: RelayEvent): Boolean {
        return onRelayEventReceivedWithMeta(event).accepted
    }

    fun onEnvelopeReceivedWithMeta(blob: ByteArray, source: String): EnvelopeReceiveResult {
        Log.i(TAG, "Received message at ${System.currentTimeMillis()} bytes=${blob.size} source=$source")
        val element = parseJson(blob) ?: run {
            Log.w(TAG, "Envelope parse failed")
            return EnvelopeReceiveResult(accepted = false)
        }
        if (isAppBatch(element)) {
            val batch = runCatching {
                CanonicalJson.json.decodeFromString(AppBatchEnvelope.serializer(), element.toString())
            }.getOrNull() ?: run {
                Log.w(TAG, "App batch parse failed")
                return EnvelopeReceiveResult(accepted = false)
            }
            if (!AppBatchCrypto.verify(batch)) {
                Log.w(TAG, "App batch signature invalid")
                return EnvelopeReceiveResult(accepted = false)
            }
            return processBatch(batch, source)
        }
        if (REQUIRE_APP_SIGNATURE && source != EventSources.RELAY) {
            Log.w(TAG, "Drop: app signature required")
            return EnvelopeReceiveResult(accepted = false)
        }
        return processEnvelopeBytes(blob, source)
    }

    fun onRelayEventReceivedWithMeta(event: RelayEvent): EnvelopeReceiveResult {
        val encryptedBody = runCatching {
            CanonicalJson.json.decodeFromString(
                app.peermesh.core.relay.RelayEncryptedBody.serializer(),
                event.body.toString(),
            )
        }.getOrNull()
        val groupKey = keyStore.loadGroupKey(event.gid, encryptedBody?.kid) ?: run {
            Log.w(TAG, "Relay group key missing gid=${event.gid}")
            return EnvelopeReceiveResult(accepted = false)
        }
        val verified = RelayPayloadCrypto.verifyAndDecrypt(event, groupKey)
        if (verified != null) {
            val core = verified.core
            val coreKid = core.meta?.kid
            if (coreKid != null && coreKid != verified.body.kid) {
                Log.w(TAG, "Relay payload kid mismatch gid=${event.gid} mid=${core.id}")
                return EnvelopeReceiveResult(accepted = false, opaqueForwardAllowed = false)
            }
            val envelope = EventEnvelope(
                v = verified.body.v,
                gid = event.gid,
                mid = core.id,
                ts = event.ts,
                ttl = null,
                dtn = null,
                enc = EncPayload(ct = verified.body.ct),
                sig = null,
            )
            return processVerifiedEnvelope(envelope, core, EventSources.RELAY)
        }

        val legacyEnvelope = decodeLegacyRelayEnvelope(event.body) ?: run {
            Log.w(TAG, "Relay payload parse/verify failed gid=${event.gid} eid=${event.eid}")
            return EnvelopeReceiveResult(accepted = false, opaqueForwardAllowed = false)
        }
        ActivityLog.add(
            label = "Relay legacy body accepted",
            source = EventSources.RELAY,
            message = "gid=${event.gid} eid=${event.eid} compatibility_mode=true",
        )
        val core = EnvelopeCrypto.verifyAndDecrypt(legacyEnvelope, groupKey) ?: run {
            Log.w(TAG, "Legacy relay envelope verify/decrypt failed gid=${event.gid} eid=${event.eid}")
            return EnvelopeReceiveResult(accepted = false, opaqueForwardAllowed = false)
        }
        return processVerifiedEnvelope(legacyEnvelope, core, EventSources.RELAY)
    }

    private fun processBatch(batch: AppBatchEnvelope, source: String): EnvelopeReceiveResult {
        if (batch.items.isEmpty() && batch.items_v2.isEmpty()) return EnvelopeReceiveResult(accepted = false)
        val batchEventIds = mutableListOf<String>()
        var accepted = false
        var relayEligible = true
        val relayOriginDeviceIds = LinkedHashSet<String>()
        batch.sender_device_id
            ?.takeIf { it.isNotBlank() }
            ?.let { relayOriginDeviceIds.add(it) }
        batch.sender_relay_id
            ?.takeIf { it.isNotBlank() }
            ?.let { relayOriginDeviceIds.add(it) }
        val senderDeviceIds = linkedSetOf<String>().apply {
            batch.sender_relay_id?.takeIf { it.isNotBlank() }?.let(::add)
            batch.sender_device_id?.takeIf { it.isNotBlank() }?.let(::add)
        }
        var firstAccepted: EnvelopeReceiveResult? = null
        var lastAccepted: EnvelopeReceiveResult? = null
        var acceptedCount = 0
        var decoded = false
        var opaqueForwardAllowed = true
        if (batch.v >= 2 && batch.items_v2.isNotEmpty()) {
            val gid = batch.gid ?: return EnvelopeReceiveResult(accepted = false)
            val groupKeys = keyStore.loadAllGroupKeys(gid)
            if (groupKeys.isEmpty()) return EnvelopeReceiveResult(accepted = false)
            batch.items_v2.forEach { item ->
                batchEventIds += item.mid
                val result = processBatchItemV2(
                    gid = gid,
                    ttl = batch.ttl,
                    dtn = batch.dtn,
                    item = item,
                    groupKeys = groupKeys,
                    source = source,
                )
                if (result.decoded) decoded = true
                if (!result.opaqueForwardAllowed) opaqueForwardAllowed = false
                if (result.accepted) {
                    accepted = true
                    acceptedCount += 1
                    recordPeerRoutes(
                        gid = gid,
                        actorDid = result.actorDid,
                        senderDeviceIds = senderDeviceIds,
                        source = source,
                    )
                    relayOriginDeviceIds.addAll(result.relayOriginDeviceIds)
                    if (firstAccepted == null) firstAccepted = result
                    lastAccepted = result
                    if (!result.relayEligible) relayEligible = false
                }
            }
        } else {
            batch.items.forEach { encoded ->
                val bytes = runCatching { Base64Url.decode(encoded) }.getOrNull() ?: return@forEach
                extractBatchEventId(bytes)?.let { batchEventIds += it }
                val result = processEnvelopeBytes(bytes, source)
                if (result.decoded) decoded = true
                if (!result.opaqueForwardAllowed) opaqueForwardAllowed = false
                if (result.accepted) {
                    accepted = true
                    acceptedCount += 1
                    recordPeerRoutes(
                        gid = extractGid(result.relayKey),
                        actorDid = result.actorDid,
                        senderDeviceIds = senderDeviceIds,
                        source = source,
                    )
                    relayOriginDeviceIds.addAll(result.relayOriginDeviceIds)
                    if (firstAccepted == null) firstAccepted = result
                    lastAccepted = result
                    if (!result.relayEligible) relayEligible = false
                }
            }
        }
        ActivityLog.add(
            label = "APP batch extract",
            source = source,
            message = "batch_id=${batchId(batch)} count=${batchEventIds.size} event_ids=${batchEventIds.joinToString(",")}",
        )
        if (!accepted) {
            return EnvelopeReceiveResult(
                accepted = false,
                relayOriginDeviceIds = relayOriginDeviceIds,
                decoded = decoded,
                opaqueForwardAllowed = opaqueForwardAllowed && !decoded,
            )
        }
        val first = firstAccepted
        val last = lastAccepted
        val relayKey = if (relayEligible && first?.relayKey != null && last?.relayKey != null) {
            "batch:${first.relayKey}:${last.relayKey}:count=$acceptedCount"
        } else {
            null
        }
        val relaySummary = if (relayEligible && first != null && last != null) {
            "mesh_batch gid=${extractGid(first.relayKey)} first=${extractEventId(first.relayKey)} last=${extractEventId(last.relayKey)} count=$acceptedCount"
        } else {
            null
        }
        return EnvelopeReceiveResult(
            accepted = true,
            relayEligible = relayEligible,
            relayKey = relayKey,
            relaySummary = relaySummary,
            relayOriginDeviceIds = relayOriginDeviceIds,
            decoded = true,
            opaqueForwardAllowed = false,
        )
    }

    private fun processBatchItemV2(
        gid: String,
        ttl: Int?,
        dtn: DtnMeta?,
        item: AppBatchItemV2,
        groupKeys: List<ByteArray>,
        source: String,
    ): EnvelopeReceiveResult {
        val core = groupKeys.firstNotNullOfOrNull { groupKey ->
            EnvelopeCrypto.decryptCiphertext(item.ct, groupKey)
        } ?: run {
            Log.w(TAG, "Batch v2 decrypt failed gid=$gid mid=${item.mid}")
            return EnvelopeReceiveResult(accepted = false, opaqueForwardAllowed = false)
        }
        val envelope = EventEnvelope(
            v = 2,
            gid = gid,
            mid = item.mid,
            ts = core.time.t,
            ttl = ttl,
            dtn = dtn,
            enc = EncPayload(ct = item.ct),
            sig = null,
        )
        return processVerifiedEnvelope(envelope, core, source)
    }

    private fun processEnvelopeBytes(blob: ByteArray, source: String): EnvelopeReceiveResult {
        val envelope = parseEnvelope(blob) ?: run {
            Log.w(TAG, "Envelope parse failed")
            return EnvelopeReceiveResult(accepted = false)
        }
        val groupKeys = keyStore.loadAllGroupKeys(envelope.gid)
        if (groupKeys.isEmpty()) {
            Log.w(TAG, "Group key missing gid=${envelope.gid}")
            return EnvelopeReceiveResult(accepted = false)
        }
        val core = groupKeys.firstNotNullOfOrNull { groupKey ->
            EnvelopeCrypto.verifyAndDecrypt(envelope, groupKey)
        } ?: run {
            Log.w(TAG, "Envelope verify/decrypt failed gid=${envelope.gid}")
            return EnvelopeReceiveResult(accepted = false, opaqueForwardAllowed = false)
        }
        return processVerifiedEnvelope(envelope, core, source)
    }

    private fun decodeLegacyRelayEnvelope(body: JsonElement): EventEnvelope? {
        return LegacyRelayPayloadCompatibility.decodeLegacyEnvelope(
            body = body,
            allowPlaintextBody = allowLegacyRelayPlaintext,
        )
    }

    private fun processVerifiedEnvelope(
        envelope: EventEnvelope,
        core: app.peermesh.core.model.EventCore,
        source: String,
    ): EnvelopeReceiveResult {
        if (!EventValidator.validate(core)) {
            Log.w(TAG, "Event validation failed id=${core.id}")
            ActivityLog.add(
                label = "Event validator reject",
                source = source,
                message = "id=${core.id} type=${core.type} actor=${core.actor.did}",
                eventId = core.id,
                gid = envelope.gid,
            )
            return EnvelopeReceiveResult(accepted = false, decoded = true, opaqueForwardAllowed = false)
        }
        if (EventTypes.isMembershipControl(core.type)) {
            ActivityLog.add(
                label = "Membership control event decoded",
                source = source,
                message = "id=${core.id} type=${core.type} actor=${core.actor.did}",
                eventId = core.id,
                gid = envelope.gid,
            )
        }
        if (core.type == EventTypes.SYS_GROUP_KEY_SHARE) {
            val ok = applyTransientGroupKeyShare(envelope.gid, core, source)
            return EnvelopeReceiveResult(
                accepted = ok,
                relayEligible = false,
                relayKey = "${envelope.gid}:${core.id}",
                relaySummary = "keyshare gid=${envelope.gid} eventId=${core.id}",
                actorDid = core.actor.did,
                decoded = true,
                opaqueForwardAllowed = false,
            )
        }
        ActivityLog.add(
            label = "Received message at",
            source = source,
            message = buildString {
                val text = runCatching { core.data as? JsonObject }
                    .getOrNull()
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.let { if (it.isString) it.content else null }
                if (!text.isNullOrBlank()) {
                    append("text=")
                    append(text.trim())
                    append(" ")
                }
                append("actor=")
                append(core.actor.did)
                append(" created_s=")
                append(core.time.t)
            },
            eventId = core.id,
            gid = envelope.gid,
        )
        val ok = ingestor.ingest(envelope, core, source)
        if (ok && core.type == EventTypes.SYS_GROUP_REKEY) {
            val payload = core.data as? JsonObject
            val newKid = payload?.get("new_kid")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (newKid.isNotBlank()) {
                maybeActivateGroupKey(gid = envelope.gid, newKid = newKid, trigger = "rekey_event")
            }
        }
        Log.i(TAG, "Ingest result id=${core.id} ok=$ok tMs=${System.currentTimeMillis()}")
        return EnvelopeReceiveResult(
            accepted = ok,
            relayEligible = ok && isMeshRelayEligible(core.type),
            relayKey = "${envelope.gid}:${core.id}",
            relaySummary = "chat gid=${envelope.gid} eventId=${core.id}",
            actorDid = core.actor.did,
            decoded = true,
            opaqueForwardAllowed = false,
        )
    }

    private fun applyTransientGroupKeyShare(
        gid: String,
        core: app.peermesh.core.model.EventCore,
        source: String,
    ): Boolean {
        val payload = core.data as? JsonObject ?: return false
        val newKid = payload["new_kid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val groupKeyBase64 = payload["group_key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (newKid.isBlank() || groupKeyBase64.isBlank()) return false
        val decodedKey = runCatching { Base64Url.decode(groupKeyBase64) }.getOrNull() ?: return false
        keyStore.storeGroupKeyVersion(gid, newKid, decodedKey)
        ActivityLog.add(
            label = "Group key share received",
            source = source,
            message = "gid=$gid actor=${core.actor.did} new_kid=$newKid bytes=${decodedKey.size}",
            eventId = core.id,
            gid = gid,
        )
        maybeActivateGroupKey(gid = gid, newKid = newKid, trigger = "key_share")
        return true
    }

    private fun maybeActivateGroupKey(
        gid: String,
        newKid: String,
        trigger: String,
    ): Boolean {
        if (!keyStore.hasGroupKeyVersion(gid, newKid)) return false
        val group = ingestor.db.groupDao().findGroup(gid) ?: return false
        val rekeyApplied = group.kid == newKid ||
            ingestor.db.eventsDao().listByTypeAndTarget(gid, EventTypes.SYS_GROUP_REKEY, newKid).isNotEmpty()
        if (!rekeyApplied) return false
        val keyBytes = keyStore.loadGroupKey(gid, newKid) ?: return false
        keyStore.storeGroupKey(gid, keyBytes)
        keyStore.clearJoinMaterial(gid)
        val nowSeconds = System.currentTimeMillis() / 1000L
        ingestor.db.groupDao().upsert(group.copy(kid = newKid, updated_at = maxOf(group.updated_at, nowSeconds)))
        val existingKey = ingestor.db.keyDao().findByGid(gid)
        ingestor.db.keyDao().upsert(
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

    private fun recordPeerRoutes(
        gid: String,
        actorDid: String?,
        senderDeviceIds: Set<String>,
        source: String,
    ) {
        if (gid.isBlank() || actorDid.isNullOrBlank() || senderDeviceIds.isEmpty()) return
        val nowSeconds = System.currentTimeMillis() / 1000L
        senderDeviceIds.forEach { deviceId ->
            val isNew = groupPeerRouteStore?.record(
                gid = gid,
                actorDid = actorDid,
                deviceId = deviceId,
                nowSeconds = nowSeconds,
            ) ?: false
            ActivityLog.add(
                label = "Group peer route observed",
                source = source,
                message = "gid=$gid actor=$actorDid device_id=$deviceId new=$isNew",
                gid = gid,
            )
        }
    }

    private fun isMeshRelayEligible(type: String): Boolean {
        return EventTypes.isChat(type) ||
            EventTypes.isLiveTrail(type) ||
            EventTypes.isMembershipControl(type)
    }

    private fun extractGid(relayKey: String?): String {
        return relayKey?.substringBefore(':').orEmpty()
    }

    private fun extractEventId(relayKey: String?): String {
        return relayKey?.substringAfter(':').orEmpty()
    }

    private fun parseJson(blob: ByteArray): JsonElement? {
        return try {
            val json = String(blob, Charsets.UTF_8)
            CanonicalJson.json.parseToJsonElement(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun isAppBatch(element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        val kind = obj["kind"] as? JsonPrimitive
        val value = if (kind?.isString == true) kind.content else kind?.toString()
        return value == "APP_BATCH"
    }

    private fun parseEnvelope(blob: ByteArray): EventEnvelope? {
        return try {
            val json = String(blob, Charsets.UTF_8)
            CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), json)
        } catch (ex: Exception) {
            null
        }
    }

    private fun extractBatchEventId(blob: ByteArray): String? {
        return parseEnvelope(blob)?.mid
    }

    private fun batchId(batch: AppBatchEnvelope): String {
        val mac = batch.app_sig?.mac
        if (!mac.isNullOrBlank()) return mac.take(12)
        return "ts:${batch.ts}"
    }

    companion object {
        private const val TAG = "EnvelopeReceiver"
        private const val REQUIRE_APP_SIGNATURE = true
    }
}
