package app.peermesh.map.application.submission

fun interface MapInteractionInputValidator {
    fun validate(command: MapInteractionCommand): MapInteractionInputValidationResult
}

sealed interface MapInteractionInputValidationResult {
    data object Valid : MapInteractionInputValidationResult
    data class Invalid(val failure: MapInteractionInputFailure) : MapInteractionInputValidationResult
}

enum class MapInteractionInputFailure {
    BLANK_GROUP_ID,
    BLANK_ITEM_ID,
    BLANK_SITUATION_UPDATE_CONTENT,
    BLANK_COORDINATION_NOTE_CONTENT,
    INVALID_COMMAND_SHAPE,
}

/** Lightweight command-shape validation only; M2 remains semantic and structural authority. */
class DefaultMapInteractionInputValidator : MapInteractionInputValidator {
    override fun validate(command: MapInteractionCommand): MapInteractionInputValidationResult {
        if (command.target.groupId.isBlank()) return invalid(MapInteractionInputFailure.BLANK_GROUP_ID)
        if (command.target.itemId.isBlank()) return invalid(MapInteractionInputFailure.BLANK_ITEM_ID)
        return when (command) {
            is AddSituationUpdateCommand -> if (command.content.isBlank()) {
                invalid(MapInteractionInputFailure.BLANK_SITUATION_UPDATE_CONTENT)
            } else {
                MapInteractionInputValidationResult.Valid
            }
            is AddCoordinationNoteCommand -> if (command.content.isBlank()) {
                invalid(MapInteractionInputFailure.BLANK_COORDINATION_NOTE_CONTENT)
            } else {
                MapInteractionInputValidationResult.Valid
            }
            is ConfirmSituationCommand,
            is ResolveSituationCommand,
            is AcknowledgeCoordinationCommand,
            -> MapInteractionInputValidationResult.Valid
        }
    }

    private fun invalid(failure: MapInteractionInputFailure) =
        MapInteractionInputValidationResult.Invalid(failure)
}
