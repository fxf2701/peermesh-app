package app.peermesh.transport.ble.dtn

import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object DtnControlCodec {
    private val knownTypes = setOf(
        DtnControlTypes.HELLO,
        DtnControlTypes.INV_HAVE,
        DtnControlTypes.INV_WANT,
        DtnControlTypes.DATA,
        DtnControlTypes.ACK,
        DtnControlTypes.MANIFEST_KEY,
        DtnControlTypes.MANIFEST_OFFER,
        DtnControlTypes.MANIFEST_ACCEPT,
    )

    fun encode(packet: DtnControlPacket): ByteArray {
        val json = buildJsonObject {
            put("v", packet.v)
            put("type", packet.type)
            put("peer_id", packet.peerId)
            packet.gid?.let { put("gid", it) }
            packet.sessionId?.let { put("session_id", it) }
            packet.mode?.let { put("mode", it) }
            packet.inventoryTier?.let { put("inventory_tier", it) }
            if (packet.ids.isNotEmpty()) {
                put("ids", buildJsonArray {
                    packet.ids.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (packet.data.isNotEmpty()) {
                put("data", buildJsonArray {
                    packet.data.forEach { item ->
                        add(
                            buildJsonObject {
                                put("event_id", item.eventId)
                                put("envelope", item.envelope)
                            }
                        )
                    }
                })
            }
            if (packet.carrierData.isNotEmpty()) {
                put("carrier_data", buildJsonArray {
                    packet.carrierData.forEach { item ->
                        add(
                            buildJsonObject {
                                put("batch_id", item.batchId)
                                put("payload", item.payload)
                                put("payload_sha256", item.payloadSha256)
                                put("payload_bytes", item.payloadBytes)
                                put("hop_count", item.hopCount)
                                put("max_hops", item.maxHops)
                            }
                        )
                    }
                })
            }
            packet.manifestKey?.let { manifestKey ->
                put("manifest_key", manifestKey.toJson())
            }
            packet.manifest?.let { manifest ->
                put("manifest", manifest.toJson())
            }
            packet.manifestId?.let { put("manifest_id", it) }
            if (packet.acceptedBatchIds.isNotEmpty()) {
                put("accepted_batch_ids", buildJsonArray {
                    packet.acceptedBatchIds.forEach { add(JsonPrimitive(it)) }
                })
            }
            packet.cloudStatus?.let { cloudStatus ->
                put(
                    "cloud_status",
                    buildJsonObject {
                        put("state", cloudStatus.state)
                        cloudStatus.lastSuccessAtSeconds?.let { put("last_success_at", it) }
                    }
                )
            }
            put("ts", packet.ts)
        }
        return CanonicalJson.json.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8)
    }

    fun decode(blob: ByteArray): DtnControlPacket? {
        val json = runCatching { blob.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return decode(json)
    }

    fun decode(json: String): DtnControlPacket? {
        return try {
            val obj = CanonicalJson.json.parseToJsonElement(json).jsonObject
            val packet = DtnControlPacket(
                v = obj["v"]?.jsonPrimitive?.intOrNull ?: return null,
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null,
                peerId = obj["peer_id"]?.jsonPrimitive?.contentOrNull ?: return null,
                gid = obj["gid"]?.jsonPrimitive?.contentOrNull,
                sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull,
                mode = obj["mode"]?.jsonPrimitive?.contentOrNull,
                inventoryTier = obj["inventory_tier"]?.jsonPrimitive?.contentOrNull,
                ids = obj["ids"]?.asStringList().orEmpty(),
                data = obj["data"]?.asDataItems().orEmpty(),
                carrierData = obj["carrier_data"]?.asCarrierDataItems().orEmpty(),
                manifestKey = obj["manifest_key"]?.asManifestKey(),
                manifest = obj["manifest"]?.asManifest(),
                manifestId = obj["manifest_id"]?.jsonPrimitive?.contentOrNull,
                acceptedBatchIds = obj["accepted_batch_ids"]?.asStringList().orEmpty(),
                cloudStatus = obj["cloud_status"]?.asCloudStatus(),
                ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: return null,
            )
            packet.takeIf { it.v == 1 && it.type in knownTypes && it.peerId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asStringList(): List<String>? {
        return runCatching {
            jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.asDataItems(): List<DtnDataItem>? {
        return runCatching {
            jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val eventId = obj["event_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val envelope = obj["envelope"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                DtnDataItem(eventId = eventId, envelope = envelope)
            }
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.asCarrierDataItems(): List<DtnCarrierDataItem>? {
        return runCatching {
            jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                DtnCarrierDataItem(
                    batchId = obj["batch_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    payload = obj["payload"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    payloadSha256 = obj["payload_sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    payloadBytes = obj["payload_bytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    hopCount = obj["hop_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    maxHops = obj["max_hops"]?.jsonPrimitive?.intOrNull ?: DtnCarrierDataDefaults.DEFAULT_MAX_HOPS,
                )
            }
        }.getOrNull()
    }

    private fun DtnPeerManifestKey.toJson(): JsonObject {
        return buildJsonObject {
            put("peer_id", peerId)
            put("algorithm", algorithm)
            put("public_key", publicKey)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asManifestKey(): DtnPeerManifestKey? {
        return runCatching {
            val obj = jsonObject
            DtnPeerManifestKey(
                peerId = obj["peer_id"]?.jsonPrimitive?.contentOrNull ?: return null,
                algorithm = obj["algorithm"]?.jsonPrimitive?.contentOrNull ?: return null,
                publicKey = obj["public_key"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
        }.getOrNull()
    }

    private fun DtnCarrierManifest.toJson(): JsonObject {
        return buildJsonObject {
            put("manifest_id", manifestId)
            put("sender_peer_id", senderPeerId)
            receiverPeerId?.let { put("receiver_peer_id", it) }
            put("gid_hash", gidHash)
            put("max_batches", maxBatches)
            put("max_bytes", maxBytes)
            put("expires_at", expiresAt)
            put("nonce", nonce)
            put("batches", buildJsonArray {
                batches.forEach { batch ->
                    add(
                        buildJsonObject {
                            put("batch_id", batch.batchId)
                            put("payload_sha256", batch.payloadSha256)
                            put("payload_bytes", batch.payloadBytes)
                            put("kind", batch.kind)
                            put("expires_at", batch.expiresAt)
                            put("priority", batch.priority)
                        }
                    )
                }
            })
            put("signature", signature)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asManifest(): DtnCarrierManifest? {
        return runCatching {
            val obj = jsonObject
            DtnCarrierManifest(
                manifestId = obj["manifest_id"]?.jsonPrimitive?.contentOrNull ?: return null,
                senderPeerId = obj["sender_peer_id"]?.jsonPrimitive?.contentOrNull ?: return null,
                receiverPeerId = obj["receiver_peer_id"]?.jsonPrimitive?.contentOrNull,
                gidHash = obj["gid_hash"]?.jsonPrimitive?.contentOrNull ?: return null,
                maxBatches = obj["max_batches"]?.jsonPrimitive?.intOrNull ?: return null,
                maxBytes = obj["max_bytes"]?.jsonPrimitive?.longOrNull ?: return null,
                expiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull ?: return null,
                nonce = obj["nonce"]?.jsonPrimitive?.contentOrNull ?: return null,
                batches = obj["batches"]?.asManifestBatches().orEmpty(),
                signature = obj["signature"]?.jsonPrimitive?.contentOrNull ?: return null,
            )
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.asManifestBatches(): List<DtnCarrierManifestBatch>? {
        return runCatching {
            jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                DtnCarrierManifestBatch(
                    batchId = obj["batch_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    payloadSha256 = obj["payload_sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    payloadBytes = obj["payload_bytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    expiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                )
            }
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.asCloudStatus(): DtnCloudStatus? {
        return runCatching {
            val obj = jsonObject
            DtnCloudStatus(
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: return null,
                lastSuccessAtSeconds = obj["last_success_at"]?.jsonPrimitive?.longOrNull,
            )
        }.getOrNull()
    }
}
