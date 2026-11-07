package app.peermesh.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayLocalBlockPolicyTest {
    @Test
    fun `returns block reason for managed session mismatch codes`() {
        assertEquals(
            "managed_pairwise_session_abuse:grant_mismatch",
            RelayLocalBlockPolicy.blockReasonFor(403, "grant_mismatch"),
        )
        assertEquals(
            "managed_pairwise_session_abuse:client_session_token_mismatch",
            RelayLocalBlockPolicy.blockReasonFor(401, "client_session_token_mismatch"),
        )
        assertEquals(
            "managed_pairwise_session_abuse:client_session_expired",
            RelayLocalBlockPolicy.blockReasonFor(409, "client_session_expired"),
        )
    }

    @Test
    fun `ignores non abusive or unrelated errors`() {
        assertNull(RelayLocalBlockPolicy.blockReasonFor(403, "client_access_grant_required"))
        assertNull(RelayLocalBlockPolicy.blockReasonFor(500, "grant_mismatch"))
        assertNull(RelayLocalBlockPolicy.blockReasonFor(200, null))
    }

    @Test
    fun `maps managed pairwise abuse to registry report reason`() {
        assertEquals(
            "invalid_session_behavior",
            RelayLocalBlockPolicy.registryReportReasonFor("managed_pairwise_session_abuse:grant_mismatch"),
        )
        assertNull(RelayLocalBlockPolicy.registryReportReasonFor("other_reason"))
    }
}
