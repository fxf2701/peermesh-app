package app.peermesh.data.reducer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object JsonAccess {
    fun stringValue(data: JsonElement, key: String): String? {
        val obj = data as? JsonObject ?: return null
        val value = obj[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    fun doubleValue(data: JsonElement, key: String): Double? {
        val obj = data as? JsonObject ?: return null
        val value = obj[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content.toDoubleOrNull() else value.toString().toDoubleOrNull()
    }

    fun longValue(data: JsonElement, key: String): Long? {
        val obj = data as? JsonObject ?: return null
        val value = obj[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content.toLongOrNull() else value.toString().toLongOrNull()
    }

    fun elementValue(data: JsonElement, key: String): JsonElement? {
        val obj = data as? JsonObject ?: return null
        val value = obj[key]
        return if (value == JsonNull) null else value
    }

    fun stringListValue(data: JsonElement, key: String): List<String> {
        val obj = data as? JsonObject ?: return emptyList()
        val value = obj[key] as? JsonArray ?: return emptyList()
        return value.mapNotNull { item ->
            val primitive = item as? JsonPrimitive ?: return@mapNotNull null
            val text = if (primitive.isString) primitive.content else primitive.toString()
            text.trim().takeIf { it.isNotBlank() }
        }.distinct()
    }
}
