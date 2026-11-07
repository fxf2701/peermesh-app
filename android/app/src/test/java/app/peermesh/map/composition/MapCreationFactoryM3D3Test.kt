package app.peermesh.map.composition

import app.peermesh.LocalMapPostCommitTransport
import app.peermesh.map.application.creation.MapCreationDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class MapCreationFactoryM3D3Test {
    @Test fun `post commit results preserve distinct missing key and enqueue rejection states`() {
        assertEquals(
            MapCreationDeliveryState.MISSING_GROUP_KEY,
            with(MapCreationFactory) {
                LocalMapPostCommitTransport.Result.MissingGroupKey.toCreationDeliveryState()
            },
        )
        assertEquals(
            MapCreationDeliveryState.ENQUEUE_REJECTED,
            with(MapCreationFactory) {
                LocalMapPostCommitTransport.Result.Enqueued(
                    hasBlePermission = true,
                    bleReady = true,
                    enqueueResult = false,
                ).toCreationDeliveryState()
            },
        )
        assertEquals(
            MapCreationDeliveryState.TRANSPORT_FAILURE,
            with(MapCreationFactory) {
                LocalMapPostCommitTransport.Result.EnqueueFailed(
                    hasBlePermission = true,
                    bleReady = true,
                    cause = IllegalStateException("outbox"),
                ).toCreationDeliveryState()
            },
        )
    }
}
