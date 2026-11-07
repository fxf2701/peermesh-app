package app.peermesh

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object TrustedContactsStore {
    private const val PREFS_NAME = "trusted_contacts"
    private const val KEY_CONTACTS_PREFIX = "trusted_contacts_"

    fun getContacts(context: Context, gid: String): List<String> {
        val raw = prefs(context).getString(KEY_CONTACTS_PREFIX + gid, null)
        return parseContacts(raw)
    }

    fun setContacts(context: Context, gid: String, contacts: List<String>) {
        val normalized = contacts.distinct().filter { it.isNotBlank() }
        val json = buildJsonArray {
            normalized.forEach { add(JsonPrimitive(it)) }
        }.toString()
        prefs(context).edit().putString(KEY_CONTACTS_PREFIX + gid, json).apply()
    }

    fun count(context: Context, gid: String): Int {
        return getContacts(context, gid).size
    }

    private fun parseContacts(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            val arr = element as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
