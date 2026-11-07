package app.peermesh.security.crypto

import app.peermesh.core.json.EnvelopeCanonicalizer
import app.peermesh.core.model.EventEnvelope
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.Mac
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.proto.HashType
import com.google.crypto.tink.proto.HmacKey
import com.google.crypto.tink.proto.HmacParams
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.shaded.protobuf.ByteString

object EnvelopeMac {
    fun computeMac(groupKey: ByteArray, envelope: EventEnvelope): ByteArray {
        val canonical = EnvelopeCanonicalizer.canonicalizeForMac(envelope)
        val mac = macPrimitive(groupKey)
        return mac.computeMac(canonical.toByteArray(Charsets.UTF_8))
    }

    fun verifyMac(groupKey: ByteArray, envelope: EventEnvelope, expectedMac: ByteArray): Boolean {
        val canonical = EnvelopeCanonicalizer.canonicalizeForMac(envelope)
        val mac = macPrimitive(groupKey)
        return try {
            mac.verifyMac(expectedMac, canonical.toByteArray(Charsets.UTF_8))
            true
        } catch (ex: Exception) {
            false
        }
    }

    private fun macPrimitive(groupKey: ByteArray): Mac {
        MacConfig.register()
        val hmacKey = HmacKey.newBuilder()
            .setVersion(0)
            .setParams(
                HmacParams.newBuilder()
                    .setHash(HashType.SHA256)
                    .setTagSize(32)
                    .build()
            )
            .setKeyValue(ByteString.copyFrom(groupKey))
            .build()
        val keyset = Keyset.newBuilder()
            .addKey(
                Keyset.Key.newBuilder()
                    .setKeyData(
                        KeyData.newBuilder()
                            .setTypeUrl("type.googleapis.com/google.crypto.tink.HmacKey")
                            .setValue(hmacKey.toByteString())
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
        return handle.getPrimitive(Mac::class.java)
    }
}
