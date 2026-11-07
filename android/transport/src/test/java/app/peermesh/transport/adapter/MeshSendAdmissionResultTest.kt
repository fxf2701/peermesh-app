package app.peermesh.transport.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshSendAdmissionResultTest {
    @Test
    fun resultKeepsForegroundDeferralDistinctFromRejection() {
        val deferred: MeshSendAdmissionResult = MeshSendAdmissionResult.DeferredForeground
        val rejected: MeshSendAdmissionResult = MeshSendAdmissionResult.Rejected("no_target")
        assertEquals(MeshSendAdmissionResult.DeferredForeground, deferred)
        assertEquals(MeshSendAdmissionResult.Rejected("no_target"), rejected)
    }
}
