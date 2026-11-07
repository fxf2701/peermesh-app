package app.peermesh

import app.peermesh.map.application.creation.MapCreationDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapCreationDeliveryFeedbackMapperM3D3Test {
    @Test fun `delivery feedback distinguishes missing key from enqueue and transport failures`() {
        assertNull(MapCreationDeliveryFeedbackMapper.messageFor(MapCreationDeliveryState.ENQUEUED))
        assertEquals(
            R.string.map_group_key_missing,
            MapCreationDeliveryFeedbackMapper.messageFor(MapCreationDeliveryState.MISSING_GROUP_KEY),
        )
        listOf(
            MapCreationDeliveryState.ENQUEUE_REJECTED,
            MapCreationDeliveryState.TRANSPORT_FAILURE,
        ).forEach { delivery ->
            assertEquals(
                R.string.map_creation_saved_delivery_warning,
                MapCreationDeliveryFeedbackMapper.messageFor(delivery),
            )
        }
    }
}
