package app.peermesh.core.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
object CanonicalJson {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun canonicalize(element: JsonElement): String {
        val normalized = normalize(element)
        return json.encodeToString(JsonElement.serializer(), normalized)
    }

    private fun normalize(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val sorted = element.entries.sortedBy { it.key }
                JsonObject(sorted.associate { it.key to normalize(it.value) })
            }
            is JsonArray -> JsonArray(element.map { normalize(it) })
            is JsonPrimitive -> element
            is JsonNull -> JsonNull
            else -> element
        }
    }
}
