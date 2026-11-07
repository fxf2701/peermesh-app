package app.peermesh.nearby

import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyForegroundDiagnosticLabelsTest {
    @Test
    fun labelsAreUniqueAndUseSearchableNamespaces() {
        assertEquals(
            NearbyForegroundDiagnosticLabels.ALL.size,
            NearbyForegroundDiagnosticLabels.ALL.toSet().size,
        )
        assertTrue(
            NearbyForegroundDiagnosticLabels.ALL.all { label ->
                label.startsWith("NEARBY_FG_") ||
                    label.startsWith("NEARBY_JOIN_") ||
                    label.startsWith("NEARBY_RX_")
            },
        )
    }

    @Test
    fun requiredManualTestMarkersAreRegistered() {
        val labels = NearbyForegroundDiagnosticLabels.ALL.toSet()
        assertTrue(NearbyForegroundDiagnosticLabels.TRANSITION in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.ADMISSION_DEFERRED in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.DRAIN_START in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.QUEUE_RESUMED in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.WORKER_STALE in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.RX_DECISION in labels)
        assertTrue(NearbyForegroundDiagnosticLabels.JOIN_BOOTSTRAP_RECV in labels)
    }
}
