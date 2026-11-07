package app.peermesh.map.presentation.detail

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.interaction.MapInteractionType

/** Transient form input only. It is never persisted or treated as accepted map state. */
sealed interface MapItemInteractionForm {
    val interaction: MapInteractionType

    data class ConfirmSituation(
        val informationSource: MapInformationSource? = null,
        val note: String = "",
    ) : MapItemInteractionForm {
        override val interaction: MapInteractionType = MapInteractionType.Situation.Confirm
    }

    data class AddSituationUpdate(
        val content: String = "",
        val disposition: MapSituationUpdateType? = null,
        val informationSource: MapInformationSource? = null,
    ) : MapItemInteractionForm {
        override val interaction: MapInteractionType = MapInteractionType.Situation.AddUpdate
    }

    data class ResolveSituation(
        val note: String = "",
        val informationSource: MapInformationSource? = null,
    ) : MapItemInteractionForm {
        override val interaction: MapInteractionType = MapInteractionType.Situation.MarkNoLongerActive
    }

    data object AcknowledgeCoordination : MapItemInteractionForm {
        override val interaction: MapInteractionType = MapInteractionType.Coordination.Acknowledge
    }

    data class AddCoordinationNote(
        val content: String = "",
    ) : MapItemInteractionForm {
        override val interaction: MapInteractionType = MapInteractionType.Coordination.AddNote
    }
}
