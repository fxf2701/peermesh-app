package app.peermesh.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.relay.ClientRelaySessionEnvelope
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRelaySessionEnvelopeSerializationTest {

    @Test
    fun `decodes relay session envelope with managed session metadata`() {
        val payload = """
            {
              "ok": true,
              "session": {
                "session_id": "crs_test",
                "relay_id": "relay-01",
                "grant_id": "cg_test",
                "client_instance_id": "android-device-01",
                "allowed_gids": ["group-1"],
                "permissions": ["ingest", "fetch"],
                "issued_at": 1780722000,
                "expires_at": 1780722300,
                "relay_nonce": "relay-nonce",
                "session_binding_token": "binding-token",
                "relay_session_material": { "version": 1, "mode": "session_binding" },
                "client_session_material": { "version": 1, "mode": "session_binding" },
                "client_nonce": "client-nonce",
                "access_grant_expires_at": 1780722600,
                "access_grant_scope": {
                  "grant_id": "cg_test",
                  "policy_version": 1,
                  "allowed_gids": ["group-1"],
                  "permissions": ["ingest", "fetch"]
                },
                "access_grant_snapshot": {
                  "grant_id": "cg_test",
                  "issued_at": 1780721900,
                  "expires_at": 1780722600,
                  "policy_version": 1,
                  "client_instance_id": "android-device-01",
                  "candidate_relays": [
                    {
                      "relay_id": "relay-01",
                      "endpoint": "https://relay.peermesh.app",
                      "trust_level": "trusted",
                      "region": null
                    }
                  ],
                  "allowed_gids": ["group-1"],
                  "permissions": ["ingest", "fetch"],
                  "limits": {
                    "max_events_per_minute": 120,
                    "max_bytes_per_minute": 4096
                  },
                  "access_token": "access-token",
                  "connection_mode": "client_selects_and_relay_establishes_session",
                  "registry_signature": "registry-signature",
                  "network_hints": {
                    "region": "ap-southeast-1",
                    "transport": "https"
                  },
                  "created_at": 1780721900,
                  "updated_at": 1780721950,
                  "revoked_at": null
                },
                "created_at": 1780722000,
                "updated_at": 1780722001,
                "closed_at": null
              }
            }
        """.trimIndent()

        val envelope = CanonicalJson.json.decodeFromString<ClientRelaySessionEnvelope>(payload)
        val session = envelope.session

        assertTrue(envelope.ok)
        assertNotNull(session)
        assertEquals("crs_test", session?.session_id)
        assertEquals("session_binding", session?.relay_session_material?.mode)
        assertEquals("session_binding", session?.client_session_material?.mode)
        assertEquals("client-nonce", session?.client_nonce)
        assertEquals(1780722600L, session?.access_grant_expires_at)
        assertEquals("cg_test", session?.access_grant_scope?.grant_id)
        assertEquals("https://relay.peermesh.app", session?.access_grant_snapshot?.candidate_relays?.single()?.endpoint)
        assertEquals("https", session?.access_grant_snapshot?.network_hints?.transport)
        assertEquals(1780722001L, session?.updated_at)
        assertEquals(null, session?.closed_at)
    }
}
