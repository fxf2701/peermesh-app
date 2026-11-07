package app.peermesh

import androidx.annotation.StringRes
import app.peermesh.map.application.creation.MapCreationDeliveryState

/** Android presentation mapping for post-commit creation delivery feedback. */
internal object MapCreationDeliveryFeedbackMapper {
    @StringRes
    fun messageFor(delivery: MapCreationDeliveryState): Int? = when (delivery) {
        MapCreationDeliveryState.ENQUEUED -> null
        MapCreationDeliveryState.MISSING_GROUP_KEY -> R.string.map_group_key_missing
        MapCreationDeliveryState.ENQUEUE_REJECTED,
        MapCreationDeliveryState.TRANSPORT_FAILURE,
        -> R.string.map_creation_saved_delivery_warning
    }
}
