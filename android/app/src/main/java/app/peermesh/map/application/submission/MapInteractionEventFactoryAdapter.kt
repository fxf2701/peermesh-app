package app.peermesh.map.application.submission

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapCoordinationAcknowledgement
import app.peermesh.core.map.MapCoordinationNote
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationStatusUpdate
import app.peermesh.core.map.MapSituationUpdate
import app.peermesh.core.model.EventCore

fun interface MapInteractionEventFactoryAdapter {
    fun create(
        command: MapInteractionCommand,
        context: MapEventConstructionContext,
    ): EventCore
}

/** Narrow adapter over the accepted M2 EventFactory dedicated-action functions. */
class DefaultMapInteractionEventFactoryAdapter : MapInteractionEventFactoryAdapter {
    override fun create(
        command: MapInteractionCommand,
        context: MapEventConstructionContext,
    ): EventCore {
        val metadata = MapActionMetadata(
            actionId = context.eventId,
            itemId = command.target.itemId,
            actorDid = context.actorDid,
            timestampSeconds = context.createdSeconds,
            eventLamport = context.lamport,
        )
        return when (command) {
            is ConfirmSituationCommand -> EventFactory.mapConfirmation(
                command.target.itemType,
                MapConfirmation(metadata, command.informationSource, command.note),
            )
            is AddSituationUpdateCommand -> EventFactory.mapSituationUpdate(
                command.target.itemType,
                MapSituationUpdate(metadata, command.content, command.informationSource, command.disposition),
            )
            is ResolveSituationCommand -> EventFactory.mapSituationStatus(
                command.target.itemType,
                MapSituationStatusUpdate(
                    metadata,
                    MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    command.note,
                    command.informationSource,
                ),
            )
            is AcknowledgeCoordinationCommand -> EventFactory.mapAcknowledgement(
                command.target.itemType,
                MapCoordinationAcknowledgement(metadata),
            )
            is AddCoordinationNoteCommand -> EventFactory.mapCoordinationNote(
                command.target.itemType,
                MapCoordinationNote(metadata, command.content),
            )
        }
    }
}
