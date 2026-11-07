package app.peermesh

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalUserProfileStoreTest {
    @Test
    fun `save persists and load returns profile`() {
        val prefs = FakeSharedPreferences()
        val store = LocalUserProfileStore(prefs)

        val saved = store.save("  Signal Fox  ", "pet_fox", updatedAt = 42L)
        val loaded = store.load()

        assertEquals("Signal Fox", saved.displayName)
        assertEquals("pet_fox", loaded?.avatarId)
        assertEquals(42L, loaded?.updatedAt)
    }

    @Test
    fun `load returns null when profile is incomplete`() {
        val prefs = FakeSharedPreferences().apply {
            edit().putString("local_user_profile_display_name", " ").apply()
        }

        assertNull(LocalUserProfileStore(prefs).load())
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getString(key: String?, defValue: String?): String? {
            return values[key] as String? ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return values[key] as Long? ?: defValue
        }

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                values[key.orEmpty()] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                values[key.orEmpty()] = value
            }

            override fun apply() = Unit
            override fun commit(): Boolean = true
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }
            override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { values[key.orEmpty()] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { values[key.orEmpty()] = value }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { values[key.orEmpty()] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {}
        }
    }
}
