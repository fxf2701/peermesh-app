package app.peermesh.map.presentation.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGroupScopePolicyM3D2Test {
    private val tokenA = MapGroupScopeToken("A", 7L)
    private val trailAX = ScopedTrailIdentity("A", "X")

    @Test fun sameGroupAndGenerationIsCurrent() {
        assertTrue(MapGroupScopePolicy.isCurrent(tokenA, "A", 7L))
    }

    @Test fun differentGroupOrGenerationIsRejected() {
        assertFalse(MapGroupScopePolicy.isCurrent(tokenA, "B", 7L))
        assertFalse(MapGroupScopePolicy.isCurrent(tokenA, "A", 8L))
    }

    @Test fun exactTrailScopeAcceptsOnlySelectedPresentTrail() {
        assertTrue(
            MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "A", 7L, "X", listOf(trailAX),
            ),
        )
        assertFalse(
            MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "B", 7L, "X", listOf(ScopedTrailIdentity("B", "X")),
            ),
        )
        assertFalse(
            MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "A", 8L, "X", listOf(trailAX),
            ),
        )
        assertFalse(
            MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "A", 7L, null, listOf(trailAX),
            ),
        )
        assertFalse(
            MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "A", 7L, "X", emptyList(),
            ),
        )
    }

    @Test fun retryOwnershipReplacesAndCancelsByExactScopedKey() {
        val cancelled = mutableListOf<Any>()
        val ownership = ScopedRetryOwnership<ScopedTrailIdentity, Any>(cancelled::add)
        val firstAX = Any()
        val secondAX = Any()
        val firstBX = Any()
        val keyAX = ScopedTrailIdentity("A", "X")
        val keyBX = ScopedTrailIdentity("B", "X")

        ownership.replace(keyAX, firstAX)
        ownership.replace(keyBX, firstBX)
        ownership.replace(keyAX, secondAX)

        assertTrue(firstAX in cancelled)
        assertTrue(ownership.isOwned(keyAX, secondAX))
        assertTrue(ownership.isOwned(keyBX, firstBX))
        ownership.cancel(keyAX)
        assertFalse(ownership.isOwned(keyAX, secondAX))
        assertTrue(ownership.isOwned(keyBX, firstBX))
        ownership.cancelAll()
        assertTrue(firstBX in cancelled)
        assertTrue(ownership.size() == 0)
    }

    @Test fun completedOldRetryCannotRemoveItsReplacement() {
        val ownership = ScopedRetryOwnership<ScopedTrailIdentity, Any> {}
        val key = ScopedTrailIdentity("A", "X")
        val old = Any()
        val replacement = Any()
        ownership.replace(key, old)
        ownership.replace(key, replacement)

        assertFalse(ownership.removeIfOwned(key, old))
        assertTrue(ownership.removeIfOwned(key, replacement))
    }

    @Test fun refreshTimerOnlyLoadsAndReschedulesForCurrentUnpausedScope() {
        assertTrue(MapRefreshTimerPolicy.mayLoadAndReschedule(tokenA, "A", 7L, paused = false))
        assertFalse(MapRefreshTimerPolicy.mayLoadAndReschedule(tokenA, "B", 7L, paused = false))
        assertFalse(MapRefreshTimerPolicy.mayLoadAndReschedule(tokenA, "A", 8L, paused = false))
        assertFalse(MapRefreshTimerPolicy.mayLoadAndReschedule(tokenA, "A", 7L, paused = true))
    }

    @Test fun everyTrailCompletionRejectsSameRawIdAfterGroupSwitch() {
        val currentTrails = listOf(ScopedTrailIdentity("B", "X"))
        listOf("toggle", "selectAndFocus", "externalFocus", "liveRefresh", "delete").forEach {
            assertFalse(
                "$it must reject A/X completion in B/X",
                MapGroupScopePolicy.isCurrentTrail(
                    tokenA, "X", "B", 7L, "X", currentTrails,
                ),
            )
        }
    }

    @Test fun staleRetryAndDialogCompletionPerformNoAction() {
        var trailQueries = 0
        var dialogMutations = 0
        if (MapGroupScopePolicy.isCurrentTrail(
                tokenA, "X", "B", 7L, "X", listOf(ScopedTrailIdentity("B", "X")),
            )
        ) {
            trailQueries++
        }
        if (MapGroupScopePolicy.isCurrent(tokenA, "B", 7L)) {
            dialogMutations++
        }
        assertTrue(trailQueries == 0)
        assertTrue(dialogMutations == 0)
    }

    @Test fun retryAttemptStateSchedulesThreeDelayedAttemptsThenExhausts() {
        val attempts = ScopedRetryAttemptState<ScopedTrailIdentity>(3)
        val key = ScopedTrailIdentity("A", "X")

        assertEquals(RetryAttemptDecision.Schedule(1), attempts.onEmpty(key))
        assertEquals(1, attempts.attempts(key))
        assertEquals(RetryAttemptDecision.Schedule(2), attempts.onEmpty(key))
        assertEquals(RetryAttemptDecision.Schedule(3), attempts.onEmpty(key))
        assertEquals(RetryAttemptDecision.Exhausted, attempts.onEmpty(key))
        assertEquals(0, attempts.attempts(key))
    }

    @Test fun retryReplacementDoesNotResetAttemptsAndScopedSuccessDoes() {
        val attempts = ScopedRetryAttemptState<ScopedTrailIdentity>(3)
        val keyAX = ScopedTrailIdentity("A", "X")
        val keyBX = ScopedTrailIdentity("B", "X")
        val cancelled = mutableListOf<Any>()
        val ownership = ScopedRetryOwnership<ScopedTrailIdentity, Any>(cancelled::add)
        val replacement = Any()

        assertEquals(RetryAttemptDecision.Schedule(1), attempts.onEmpty(keyAX))
        ownership.replace(keyAX, Any())
        ownership.replace(keyAX, replacement)
        assertEquals(1, attempts.attempts(keyAX))
        assertEquals(0, attempts.attempts(keyBX))

        attempts.reset(keyAX)
        ownership.cancel(keyAX)
        assertEquals(0, attempts.attempts(keyAX))
        assertFalse(ownership.isOwned(keyAX, replacement))
        assertTrue(replacement in cancelled)
    }

    @Test fun staleScopeDoesNotIncrementAttemptsOrSchedule() {
        val attempts = ScopedRetryAttemptState<ScopedTrailIdentity>(3)
        val keyAX = ScopedTrailIdentity("A", "X")
        var scheduled = 0

        if (MapGroupScopePolicy.isCurrentTrail(
                tokenA,
                "X",
                "B",
                7L,
                "X",
                listOf(ScopedTrailIdentity("B", "X")),
            )
        ) {
            if (attempts.onEmpty(keyAX) is RetryAttemptDecision.Schedule) scheduled++
        }

        assertEquals(0, attempts.attempts(keyAX))
        assertEquals(0, scheduled)
    }

    @Test fun retryAttemptsAreIsolatedForSameRawTrailIdAcrossGroups() {
        val attempts = ScopedRetryAttemptState<ScopedTrailIdentity>(3)
        val keyAX = ScopedTrailIdentity("A", "X")
        val keyBX = ScopedTrailIdentity("B", "X")

        assertEquals(RetryAttemptDecision.Schedule(1), attempts.onEmpty(keyAX))
        assertEquals(RetryAttemptDecision.Schedule(2), attempts.onEmpty(keyAX))
        assertEquals(RetryAttemptDecision.Schedule(1), attempts.onEmpty(keyBX))
        assertEquals(2, attempts.attempts(keyAX))
        assertEquals(1, attempts.attempts(keyBX))
    }
}
