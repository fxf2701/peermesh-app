package app.peermesh.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class EventEnvelope(
    val v: Int,
    val gid: String,
    val mid: String,
    val ts: Long,
    val ttl: Int? = null,
    val dtn: DtnMeta? = null,
    val enc: EncPayload,
    val sig: Sig? = null,
)

@Serializable
data class DtnMeta(
    val ttl_seconds: Int,
    val hop_count: Int = 0,
    val max_hops: Int = 20,
    val priority: Int = 1,
)

@Serializable
data class EncPayload(
    val ct: String,
)

@Serializable
data class Sig(
    val mac: String,
)

@Serializable
data class AppBatchEnvelope(
    val v: Int,
    val kind: String = "APP_BATCH",
    val ts: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sender_device_id: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sender_relay_id: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val gid: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ttl: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dtn: DtnMeta? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val items: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val items_v2: List<AppBatchItemV2> = emptyList(),
    val app_sig: AppSig? = null,
)

@Serializable
data class AppSig(
    val mac: String,
)

@Serializable
data class AppBatchItemV2(
    val mid: String,
    val ct: String,
)

@Serializable
data class EventCore(
    val ev: Int,
    val id: String,
    val type: String,
    val actor: Actor,
    val time: EventTime,
    val ref: EventRef,
    val meta: EventMeta? = null,
    val data: JsonElement = JsonNull,
)

@Serializable
data class Actor(
    val did: String,
)

@Serializable
data class EventTime(
    // UTC epoch seconds.
    val t: Long,
    // Lamport time / logical clock (not timezone-based).
    val lt: Long? = null,
)

@Serializable
data class EventRef(
    val root: String? = null,
    val parent: String? = null,
    val target: String? = null,
)

@Serializable
data class EventMeta(
    val ttl_s: Long? = null,
    val prio: String? = null,
    val kid: String? = null,
)
