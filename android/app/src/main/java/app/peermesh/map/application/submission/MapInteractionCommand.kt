package app.peermesh.map.application.submission

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.detail.MapItemIdentity

/** UI-safe user intent. Trusted actor, event identity and ordering never enter these commands. */
sealed interface MapInteractionCommand {
    val target: MapItemIdentity
}

data class ConfirmSituationCommand(
    override val target: MapItemIdentity,
    val informationSource: MapInformationSource? = null,
    val note: String? = null,
) : MapInteractionCommand

data class AddSituationUpdateCommand(
    override val target: MapItemIdentity,
    val content: String,
    val disposition: MapSituationUpdateType,
    val informationSource: MapInformationSource? = null,
) : MapInteractionCommand

data class ResolveSituationCommand(
    override val target: MapItemIdentity,
    val note: String? = null,
    val informationSource: MapInformationSource? = null,
) : MapInteractionCommand

data class AcknowledgeCoordinationCommand(
    override val target: MapItemIdentity,
) : MapInteractionCommand

data class AddCoordinationNoteCommand(
    override val target: MapItemIdentity,
    val content: String,
) : MapInteractionCommand
