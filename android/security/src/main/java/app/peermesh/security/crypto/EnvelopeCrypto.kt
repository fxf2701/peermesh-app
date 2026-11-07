package app.peermesh.security.crypto

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.model.Sig
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.proto.AesGcmKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.shaded.protobuf.ByteString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object EnvelopeCrypto {
    fun encryptAndSign(
        gid: String,
        mid: String,
        ts: Long,
        ttl: Int?,
        dtn: DtnMeta? = null,
        core: EventCore,
        groupKey: ByteArray,
    ): EventEnvelope {
        val plaintext = CanonicalJson.json.encodeToString(EventCore.serializer(), core)
        val ct = Base64Url.encode(aeadPrimitive(groupKey).encrypt(plaintext.toByteArray(Charsets.UTF_8), ByteArray(0)))
        val unsigned = EventEnvelope(
            v = 1,
            gid = gid,
            mid = mid,
            ts = ts,
            ttl = ttl,
            dtn = dtn,
            enc = EncPayload(ct = ct),
            sig = null,
        )
        val macBytes = EnvelopeMac.computeMac(groupKey, unsigned)
        val sig = Sig(mac = Base64Url.encode(macBytes))
        return unsigned.copy(sig = sig)
    }

    fun encryptCore(core: EventCore, groupKey: ByteArray): EncPayload {
        val plaintext = CanonicalJson.json.encodeToString(EventCore.serializer(), core)
        val ct = Base64Url.encode(aeadPrimitive(groupKey).encrypt(plaintext.toByteArray(Charsets.UTF_8), ByteArray(0)))
        return EncPayload(ct = ct)
    }

    fun verifyAndDecrypt(envelope: EventEnvelope, groupKey: ByteArray): EventCore? {
        val sig = envelope.sig ?: return null
        val expected = Base64Url.decode(sig.mac)
        val unsigned = envelope.copy(sig = null)
        if (!EnvelopeMac.verifyMac(groupKey, unsigned, expected)) return null

        return decryptCiphertext(envelope.enc.ct, groupKey)
    }

    fun decryptCiphertext(ciphertext: String, groupKey: ByteArray): EventCore? {
        return runCatching {
            val ct = Base64Url.decode(ciphertext)
            val plaintext = aeadPrimitive(groupKey).decrypt(ct, ByteArray(0))
            CanonicalJson.json.decodeFromString(EventCore.serializer(), String(plaintext, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun aeadPrimitive(groupKey: ByteArray): Aead {
        AeadConfig.register()
        val aesKey = AesGcmKey.newBuilder()
            .setVersion(0)
            .setKeyValue(ByteString.copyFrom(groupKey))
            .build()
        val keyset = Keyset.newBuilder()
            .addKey(
                Keyset.Key.newBuilder()
                    .setKeyData(
                        KeyData.newBuilder()
                            .setTypeUrl("type.googleapis.com/google.crypto.tink.AesGcmKey")
                            .setValue(aesKey.toByteString())
                            .setKeyMaterialType(KeyData.KeyMaterialType.SYMMETRIC)
                            .build()
                    )
                    .setKeyId(1)
                    .setStatus(KeyStatusType.ENABLED)
                    .setOutputPrefixType(OutputPrefixType.RAW)
                    .build()
            )
            .setPrimaryKeyId(1)
            .build()
        val handle = CleartextKeysetHandle.fromKeyset(keyset)
        return handle.getPrimitive(Aead::class.java)
    }
}
