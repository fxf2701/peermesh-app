package app.peermesh.relay

import app.peermesh.core.json.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class RelayLocalBlockStoreSerializationTest {
    @Test
    fun `blocked relay serializes and deserializes`() {
        val entry = RelayLocalBlockStore.BlockedRelay(
            relayUrl = "https://relay.peermesh.app",
            reason = "invalid_session_behavior",
            blockedAtMs = 1_000L,
            expiresAtMs = 2_000L,
        )

        val payload = CanonicalJson.json.encodeToString(
            ListSerializer(RelayLocalBlockStore.BlockedRelay.serializer()),
            listOf(entry),
        )
        val decoded = CanonicalJson.json.decodeFromString(
            ListSerializer(RelayLocalBlockStore.BlockedRelay.serializer()),
            payload,
        )

        assertEquals(1, decoded.size)
        assertEquals(entry, decoded.single())
        assertTrue(payload.contains("relay.peermesh.app"))
    }
}
