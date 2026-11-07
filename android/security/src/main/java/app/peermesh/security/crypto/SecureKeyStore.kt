package app.peermesh.security.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import app.peermesh.core.encoding.Base64Url
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

class SecureKeyStore(context: Context) {
    private val prefs = createPrefs(context)

    private fun createPrefs(context: Context) = try {
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences init failed; clearing keyset", e)
        runCatching { context.deleteSharedPreferences(PREF_NAME) }
        runCatching { context.deleteSharedPreferences("${PREF_NAME}__prefs") }
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun storeGroupKey(gid: String, groupKey: ByteArray) {
        prefs.edit().putString(keyKey(gid), Base64Url.encode(groupKey)).apply()
    }

    fun loadGroupKey(gid: String): ByteArray? {
        val encoded = prefs.getString(keyKey(gid), null) ?: return null
        return Base64Url.decode(encoded)
    }

    fun storeGroupKeyVersion(gid: String, kid: String, groupKey: ByteArray) {
        if (kid.isBlank()) return
        val currentIds = loadGroupKeyIds(gid).toMutableList()
        if (!currentIds.contains(kid)) currentIds += kid
        prefs.edit()
            .putString(keyVersionKey(gid, kid), Base64Url.encode(groupKey))
            .putString(keyIdsKey(gid), currentIds.joinToString(","))
            .apply()
    }

    fun loadGroupKey(gid: String, kid: String?): ByteArray? {
        if (kid.isNullOrBlank()) return loadGroupKey(gid)
        val encoded = prefs.getString(keyVersionKey(gid, kid), null)
            ?: return loadGroupKey(gid)
        return Base64Url.decode(encoded)
    }

    fun loadAllGroupKeys(gid: String): List<ByteArray> {
        val keys = LinkedHashMap<String, ByteArray>()
        loadGroupKey(gid)?.let { keys["current"] = it }
        loadGroupKeyIds(gid).forEach { kid ->
            val encoded = prefs.getString(keyVersionKey(gid, kid), null) ?: return@forEach
            val decoded = runCatching { Base64Url.decode(encoded) }.getOrNull() ?: return@forEach
            keys.putIfAbsent(kid, decoded)
        }
        return keys.values.toList()
    }

    fun hasGroupKeyVersion(gid: String, kid: String): Boolean {
        if (kid.isBlank()) return false
        return prefs.contains(keyVersionKey(gid, kid))
    }

    fun storeSalt(gid: String, salt: ByteArray) {
        prefs.edit().putString(saltKey(gid), Base64Url.encode(salt)).apply()
    }

    fun loadSalt(gid: String): ByteArray? {
        val encoded = prefs.getString(saltKey(gid), null) ?: return null
        return Base64Url.decode(encoded)
    }

    fun storePassphrase(gid: String, passphrase: String) {
        prefs.edit().putString(passphraseKey(gid), passphrase).apply()
    }

    fun loadPassphrase(gid: String): String? {
        return prefs.getString(passphraseKey(gid), null)
    }

    fun clearJoinMaterial(gid: String) {
        prefs.edit().remove(saltKey(gid)).remove(passphraseKey(gid)).apply()
    }

    fun clear(gid: String) {
        val editor = prefs.edit()
            .remove(keyKey(gid))
            .remove(saltKey(gid))
            .remove(passphraseKey(gid))
        loadGroupKeyIds(gid).forEach { kid ->
            editor.remove(keyVersionKey(gid, kid))
        }
        editor.remove(keyIdsKey(gid)).apply()
    }

    fun storeNearbyRoomKey(roomId: String, roomKey: ByteArray) {
        prefs.edit().putString(nearbyRoomKey(roomId), Base64Url.encode(roomKey)).apply()
    }

    fun loadNearbyRoomKey(roomId: String): ByteArray? {
        val encoded = prefs.getString(nearbyRoomKey(roomId), null) ?: return null
        return Base64Url.decode(encoded)
    }

    fun clearNearbyRoomKey(roomId: String) {
        prefs.edit().remove(nearbyRoomKey(roomId)).apply()
    }

    fun loadOrCreateDtnManifestIdentity(): DtnManifestIdentity {
        val publicKey = prefs.getString(KEY_DTN_MANIFEST_PUBLIC, null)
        val privateKey = prefs.getString(KEY_DTN_MANIFEST_PRIVATE, null)
        if (!publicKey.isNullOrBlank() && !privateKey.isNullOrBlank()) {
            val privateKeyBytes = Base64Url.decode(privateKey)
            val decodedPrivateKey = KeyFactory.getInstance(EC).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            return DtnManifestIdentity(publicKey = publicKey, privateKey = decodedPrivateKey)
        }
        val keyPair = KeyPairGenerator.getInstance(EC).run {
            initialize(ECGenParameterSpec(EC_P256))
            generateKeyPair()
        }
        val encodedPublicKey = Base64Url.encode(keyPair.public.encoded)
        val encodedPrivateKey = Base64Url.encode(keyPair.private.encoded)
        prefs.edit()
            .putString(KEY_DTN_MANIFEST_PUBLIC, encodedPublicKey)
            .putString(KEY_DTN_MANIFEST_PRIVATE, encodedPrivateKey)
            .apply()
        return DtnManifestIdentity(publicKey = encodedPublicKey, privateKey = keyPair.private)
    }

    private fun keyKey(gid: String) = "group_key:$gid"
    private fun keyVersionKey(gid: String, kid: String) = "group_key:$gid:$kid"
    private fun keyIdsKey(gid: String) = "group_key_ids:$gid"

    private fun saltKey(gid: String) = "group_salt:$gid"

    private fun passphraseKey(gid: String) = "group_passphrase:$gid"
    private fun nearbyRoomKey(roomId: String) = "nearby_room_key:$roomId"

    private fun loadGroupKeyIds(gid: String): List<String> {
        return prefs.getString(keyIdsKey(gid), null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    companion object {
        private const val PREF_NAME = "secure_keys"
        private const val TAG = "SecureKeyStore"
        private const val EC = "EC"
        private const val EC_P256 = "secp256r1"
        private const val KEY_DTN_MANIFEST_PUBLIC = "dtn_manifest_public_key"
        private const val KEY_DTN_MANIFEST_PRIVATE = "dtn_manifest_private_key"
    }
}

data class DtnManifestIdentity(
    val publicKey: String,
    val privateKey: PrivateKey,
)
