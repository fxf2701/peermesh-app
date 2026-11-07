package app.peermesh.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayRegistryAbuseReportClientTest {
    @Test
    fun `returns structured error when registry base url is missing`() {
        val client = RelayRegistryAbuseReportClient()
        val result = client.submit(
            registryBaseUrl = "",
            request = RelayRegistryAbuseReportRequest(
                grant_id = "cg_test",
                relay_id = "relay-a",
                client_instance_id = "device-a",
                reason = "invalid_session_behavior",
                timestamp = 1716900000L,
                counters = mapOf("status_code" to 403),
            ),
            requestSignatureSecret = null,
        )

        assertFalse(result.success)
        assertEquals(0, result.statusCode)
        assertEquals("registry_base_url_missing", result.errorCode)
    }
}
