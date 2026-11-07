package app.peermesh.map.application.submission

import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class MapInteractionInputValidatorM3A3Test {
    private val validator = DefaultMapInteractionInputValidator()

    @Test fun `blank group is invalid`() = assertInvalid(
        ConfirmSituationCommand(target(groupId = " \t")), MapInteractionInputFailure.BLANK_GROUP_ID,
    )

    @Test fun `blank item is invalid`() = assertInvalid(
        ConfirmSituationCommand(target(itemId = "\n")), MapInteractionInputFailure.BLANK_ITEM_ID,
    )

    @Test fun `blank situation update is invalid`() = assertInvalid(
        AddSituationUpdateCommand(target(), "  ", MapSituationUpdateType.STILL_ACTIVE),
        MapInteractionInputFailure.BLANK_SITUATION_UPDATE_CONTENT,
    )

    @Test fun `blank coordination note is invalid`() = assertInvalid(
        AddCoordinationNoteCommand(target(), "\t"), MapInteractionInputFailure.BLANK_COORDINATION_NOTE_CONTENT,
    )

    @Test fun `optional blank note is preserved as valid input`() {
        assertEquals(MapInteractionInputValidationResult.Valid, validator.validate(
            ConfirmSituationCommand(target(), note = "  "),
        ))
        assertEquals(MapInteractionInputValidationResult.Valid, validator.validate(
            ResolveSituationCommand(target(), note = ""),
        ))
    }

    @Test fun `content has no invented dedicated action length limit and is not rewritten`() {
        val content = " x ".repeat(1_000)
        assertEquals(MapInteractionInputValidationResult.Valid, validator.validate(
            AddCoordinationNoteCommand(target(), content),
        ))
    }

    private fun assertInvalid(command: MapInteractionCommand, expected: MapInteractionInputFailure) {
        assertEquals(MapInteractionInputValidationResult.Invalid(expected), validator.validate(command))
    }

    private fun target(groupId: String = "group", itemId: String = "item") =
        MapItemIdentity(groupId, MapItemType.MARKER, itemId)
}
