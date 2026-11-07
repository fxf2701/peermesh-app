package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapItemAction

enum class MapInteractionFamily {
    SITUATION,
    COORDINATION,
}

/** Stable UI/application interaction vocabulary. Raw MAP.ITEM.* names stay below this boundary. */
sealed interface MapInteractionType {
    val family: MapInteractionFamily
    val m2Action: MapItemAction

    sealed interface Situation : MapInteractionType {
        override val family: MapInteractionFamily
            get() = MapInteractionFamily.SITUATION

        data object Confirm : Situation {
            override val m2Action: MapItemAction = MapItemAction.CONFIRM
        }

        data object AddUpdate : Situation {
            override val m2Action: MapItemAction = MapItemAction.ADD_UPDATE
        }

        data object MarkNoLongerActive : Situation {
            override val m2Action: MapItemAction = MapItemAction.UPDATE_STATUS
        }
    }

    sealed interface Coordination : MapInteractionType {
        override val family: MapInteractionFamily
            get() = MapInteractionFamily.COORDINATION

        data object Acknowledge : Coordination {
            override val m2Action: MapItemAction = MapItemAction.ACKNOWLEDGE
        }

        data object AddNote : Coordination {
            override val m2Action: MapItemAction = MapItemAction.ADD_NOTE
        }
    }

    companion object {
        val all: List<MapInteractionType> = listOf(
            Situation.Confirm,
            Situation.AddUpdate,
            Situation.MarkNoLongerActive,
            Coordination.Acknowledge,
            Coordination.AddNote,
        )
    }
}
