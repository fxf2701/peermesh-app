package app.peermesh.map.composition

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.RoomMapItemDomainStateRepository
import app.peermesh.data.map.canonical.RoomMapItemCanonicalBatchReadRepository
import app.peermesh.map.application.scene.DefaultLoadMapSceneItems
import app.peermesh.map.application.scene.LoadMapSceneItems
import app.peermesh.map.application.interaction.SystemMapAvailabilityClock
import app.peermesh.map.application.participation.DefaultMapCoordinationParticipationProjector
import app.peermesh.map.application.verification.DefaultMapItemVerificationProjector

object MapSceneReadFactory {
    fun create(database: AppDatabase): LoadMapSceneItems = DefaultLoadMapSceneItems(
        canonicalRepository = RoomMapItemCanonicalBatchReadRepository(database.mapDao()),
        domainStateRepository = RoomMapItemDomainStateRepository(database.mapItemDomainStateDao()),
        clock = SystemMapAvailabilityClock,
        verificationProjector = DefaultMapItemVerificationProjector(),
        participationProjector = DefaultMapCoordinationParticipationProjector(),
    )
}
