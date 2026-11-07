package app.peermesh.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerMeshBetaProgramParsingTest {
    @Test
    fun `parses snake case registry response`() {
        val config = PeerMeshBetaProgram.parseRemoteConfig(
            """
            {
              "ok": true,
              "beta_program": {
                "enabled": true,
                "season": "Beta Season 1",
                "endsAt": null,
                "latestVersion": "0.9.3",
                "minimumSupportedVersion": "0.9.0",
                "changelog": ["Nearby Rooms", "Chat Moderation"]
              }
            }
            """.trimIndent()
        )

        requireNotNull(config)
        assertTrue(config.enabled)
        assertEquals("Beta Season 1", config.season)
        assertNull(config.endsAt)
        assertEquals("0.9.3", config.latestVersion)
        assertEquals(2, config.changelog.size)
    }
}
