package app.peermesh.map.presentation.detail

import android.content.Intent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.R
import app.peermesh.core.map.MapItemType
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.submission.ConfirmSituationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemDetailActivityAcceptedConfirmRuntimeTest {
    @Test fun acceptedConfirmReloadsPersistedDetailRefreshesTimelineOnceAndReprojects() {
        val identity = MapItemIdentity("group", MapItemType.MARKER, "accepted-confirm")
        val intent = DefaultMapItemDetailNavigationArguments.put(
            Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext(), AcceptedConfirmRuntimeActivity::class.java),
            identity,
        )
        ActivityScenario.launch<AcceptedConfirmRuntimeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(1, activity.trace.detailLoads)
                assertEquals(1, activity.trace.timelineInitialLoads)
                assertEquals(0, activity.trace.timelineFirstPageRefreshes)
                assertEquals(0, activity.trace.persistedVerification.confirmationActorCount)
                assertEquals(
                    MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
                    activity.trace.persistedVerification.freshnessBasis,
                )
                assertTrue(activity.text(R.id.map_detail_verification_body)
                    .contains(activity.getString(R.string.map_detail_verification_no_evidence)))
            }

            onView(withId(R.id.map_detail_confirm))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .perform(click())
            onView(withText(R.string.map_detail_submit))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .perform(click())

            scenario.onActivity { activity ->
                assertEquals(
                    listOf(ConfirmSituationCommand(identity, informationSource = null, note = null)),
                    activity.trace.submittedCommands,
                )
                assertEquals(1, activity.trace.acceptedSubmissionCount)
                assertEquals(identity, activity.trace.persistedIdentity)
                assertEquals(1, activity.trace.persistedVerification.confirmationActorCount)
                assertEquals(
                    MapSituationConfirmationFreshnessBasis.Current(null),
                    activity.trace.persistedVerification.freshnessBasis,
                )
                assertEquals(2, activity.trace.detailLoads)
                assertEquals(1, activity.trace.timelineFirstPageRefreshes)
                assertTrue(activity.text(R.id.map_detail_verification_body)
                    .contains(activity.getString(R.string.map_detail_verification_current, 1)))
                val latestConfirmationLabel = activity
                    .getString(R.string.map_detail_verification_latest, "sentinel")
                    .substringBefore(":")
                    .trim()
                assertTrue(latestConfirmationLabel.isNotBlank())
                assertFalse(activity.text(R.id.map_detail_verification_body)
                    .contains(latestConfirmationLabel))
                assertEquals(0, activity.findViewById<RecyclerView>(R.id.map_detail_timeline_list).adapter!!.itemCount)
            }
        }
    }

    private fun AcceptedConfirmRuntimeActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
}
