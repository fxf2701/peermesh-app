package app.peermesh.core.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RelayEncryptedBody(
    val v: Int,
    val enc: String,
    val kid: String,
    val cty: String,
    val nonce: String,
    val ct: String,
    val tag: String,
)

@Serializable
data class RelayEvent(
    val eid: String,
    val gid: String,
    val ts: Long,
    val exp: Long,
    val body: JsonElement,
    val sig: String,
)

@Serializable
data class RelayIngestRequest(
    val events: List<RelayEvent>,
)

@Serializable
data class RelayIngestResponse(
    val accepted: List<String> = emptyList(),
    val rejected: List<RelayRejectItem> = emptyList(),
)

@Serializable
data class RelayRejectItem(
    val eid: String? = null,
    val reason: String? = null,
    val details: JsonElement? = null,
)

@Serializable
data class RelayFetchResponse(
    val events: List<RelayEvent> = emptyList(),
    val next_cursor: String? = null,
)
