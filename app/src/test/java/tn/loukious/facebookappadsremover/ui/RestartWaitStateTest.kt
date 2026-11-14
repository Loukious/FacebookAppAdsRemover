package tn.loukious.facebookappadsremover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tn.loukious.facebookappadsremover.ui.RestartWaitState.Completion

class RestartWaitStateTest {
    @Test fun preparationDoesNotConfirmTermination() {
        val state = RestartWaitState(10_000)
        assertNull(state.prepared(setOf("main"), 1_000))
        assertNull(state.arm(1_100))
        assertTrue(state.pending)
        assertEquals(Completion.CONFIRMED, state.died("main", 1_300))
        assertFalse(state.pending)
    }

    @Test fun waitsForEveryFacebookProcessAndIgnoresDuplicateOrUnknownDeaths() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main", "browser", "activity"), 1_000)
        state.arm(1_100)
        assertNull(state.died("browser", 1_300))
        assertNull(state.died("browser", 1_400))
        assertNull(state.died("unrelated", 1_500))
        assertNull(state.died("main", 1_600))
        assertEquals(Completion.CONFIRMED, state.died("activity", 1_700))
        assertNull(state.died("activity", 1_800))
    }

    @Test fun alreadyDeadProcessesCompleteOnlyAfterAllWatchesAreArmed() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main", "browser"), 1_000)
        assertNull(state.died("main", 1_010))
        assertNull(state.died("browser", 1_020))
        assertTrue(state.pending)
        assertEquals(Completion.CONFIRMED, state.arm(1_030))
    }

    @Test fun noReceiverAllowsOpeningWithoutClaimingChangesWereApplied() {
        val state = RestartWaitState(10_000)
        assertEquals(Completion.OPEN_ONLY, state.prepared(emptySet(), 1_000))
        assertNull(state.arm(1_100))
        assertNull(state.prepared(setOf("late-process"), 1_200))
    }

    @Test fun timeoutBeforePrepareMakesDelayedBroadcastRepliesInert() {
        val state = RestartWaitState(10_000)
        assertNull(state.timeout(9_999))
        assertEquals(Completion.FAILED, state.timeout(10_000))
        assertNull(state.prepared(setOf("main"), 10_100))
        assertNull(state.arm(10_200))
        assertNull(state.died("main", 10_300))
    }

    @Test fun delayedPrepareCannotBeatDeadlineEvenIfTimeoutCallbackHasNotRun() {
        val state = RestartWaitState(10_000)
        assertEquals(Completion.FAILED, state.prepared(setOf("main"), 10_001))
        assertNull(state.arm(10_002))
    }

    @Test fun deathAfterDeadlineCannotTriggerALateLaunch() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main"), 1_000)
        state.arm(1_100)
        assertEquals(Completion.FAILED, state.died("main", 10_001))
        assertNull(state.timeout(10_002))
    }

    @Test fun deadlineBetweenPreparationAndStopPreventsConfirmation() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main"), 9_990)
        state.died("main", 9_995)
        assertEquals(Completion.FAILED, state.arm(10_000))
    }

    @Test fun cancellationMakesPreparedDeathAndTimeoutCallbacksInert() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main"), 1_000)
        state.arm(1_100)
        state.cancel()
        assertFalse(state.pending)
        assertNull(state.died("main", 1_300))
        assertNull(state.timeout(10_000))
        assertNull(state.prepared(emptySet(), 10_100))
        assertNull(state.fail())
    }

    @Test fun cancellationBeforeDeliveryCannotTurnALatePrepareReplyIntoARestart() {
        val state = RestartWaitState(10_000)
        state.cancel()
        assertNull(state.prepared(setOf("main"), 1_000))
        assertNull(state.arm(1_100))
        assertNull(state.died("main", 1_300))
        assertFalse(state.pending)
    }

    @Test fun failureProducesOnlyOneResultAndCannotLaterBecomeSuccess() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main"), 1_000)
        assertEquals(Completion.FAILED, state.fail())
        assertNull(state.fail())
        assertNull(state.arm(1_100))
        assertNull(state.died("main", 1_300))
    }

    @Test fun duplicatePreparationCannotReplaceTheProcessesBeingWatched() {
        val state = RestartWaitState(10_000)
        state.prepared(setOf("main", "browser"), 1_000)
        assertNull(state.prepared(setOf("main"), 1_010))
        state.arm(1_100)
        assertNull(state.died("main", 1_300))
        assertEquals(Completion.CONFIRMED, state.died("browser", 1_400))
    }
}
