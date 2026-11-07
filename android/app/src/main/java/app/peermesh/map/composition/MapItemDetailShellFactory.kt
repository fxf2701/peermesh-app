package app.peermesh.map.composition

import android.content.Context
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.map.application.detail.LoadMapItemDetail
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.submission.SubmitMapInteraction

/** Complete M3A4 dependency bundle. No DAO, reducer, entity or transport leaks to presentation. */
data class MapItemDetailShellDependencies(
    val loadMapItemDetail: LoadMapItemDetail,
    val evaluateMapItemInteractions: EvaluateMapItemInteractions,
    val submitMapInteraction: SubmitMapInteraction,
)

object MapItemDetailShellFactory {
    fun create(applicationContext: Context): MapItemDetailShellDependencies = create(
        applicationContext = applicationContext.applicationContext,
        database = AppDatabaseProvider.get(applicationContext.applicationContext),
    )

    fun create(
        applicationContext: Context,
        database: AppDatabase,
    ): MapItemDetailShellDependencies = MapItemDetailShellDependencies(
        loadMapItemDetail = MapItemDetailReadFactory.create(database),
        evaluateMapItemInteractions = MapInteractionAvailabilityFactory.create(
            context = applicationContext.applicationContext,
            database = database,
        ),
        submitMapInteraction = MapInteractionSubmissionFactory.create(
            applicationContext = applicationContext.applicationContext,
            database = database,
        ),
    )
}
