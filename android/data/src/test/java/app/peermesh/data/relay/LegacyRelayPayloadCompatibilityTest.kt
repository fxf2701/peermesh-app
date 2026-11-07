package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.model.Sig
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyRelayPayloadCompatibilityTest {
    @Test
    fun `rejects legacy plaintext relay body when compatibility mode is off`() {
        val body = legacyEnvelopeJson()

        val decoded = LegacyRelayPayloadCompatibility.decodeLegacyEnvelope(
            body = body,
            allowPlaintextBody = false,
        )

        assertNull(decoded)
    }

    @Test
    fun `accepts legacy plaintext relay body only when compatibility mode is on`() {
        val body = legacyEnvelopeJson()

        val decoded = LegacyRelayPayloadCompatibility.decodeLegacyEnvelope(
            body = body,
            allowPlaintextBody = true,
        )

        assertNotNull(decoded)
    }

    private fun legacyEnvelopeJson() = CanonicalJson.json.parseToJsonElement(
        CanonicalJson.json.encodeToString(
            EventEnvelope.serializer(),
            EventEnvelope(
                v = 1,
                gid = "gid-1",
                mid = "mid-1",
                ts = 1700000000L,
                ttl = null,
                dtn = null,
                enc = EncPayload(ct = "ciphertext"),
                sig = Sig(mac = "mac"),
            ),
        ),
    )
}
