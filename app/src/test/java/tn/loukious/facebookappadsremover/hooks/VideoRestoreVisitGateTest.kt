package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRestoreVisitGateTest {
    @Test fun repeatingAnalyticsStopStartCannotCreateHalfSecondSeekLoop() {
        val gate = VideoRestoreVisitGate()
        val id = "2952638928420239"
        gate.observeStart(id, 100L)
        assertTrue(gate.markRestore(id, 300L))
        // Live FB580 v1.20 emitted automatic stop/start and another restored
        // seek every 200ms for this ID at 1521ms. None may re-arm.
        for (tick in 1..500) {
            val now = 300L + tick * 200L
            gate.observeStart(id, now)
            assertFalse("repeat $tick", gate.mayRestore(id, now))
            assertFalse("repeat $tick", gate.markRestore(id, now))
        }
    }

    @Test fun differentReelAndMeaningfulTimeAwayAllowsRevisit() {
        val gate = VideoRestoreVisitGate()
        gate.observeStart("A", 100L)
        assertTrue(gate.markRestore("A", 300L))
        gate.observeStart("B", 1_000L)
        assertTrue(gate.mayRestore("B", 1_100L))
        gate.observeStart("A", 2_000L)
        assertFalse(gate.mayRestore("A", 2_100L)) // not five seconds after restore
        // A new actual video visit (not the same video's analytics restart).
        gate.observeStart("B", 5_000L)
        gate.observeStart("A", 6_600L)
        assertTrue(gate.markRestore("A", 6_601L))
        assertFalse(gate.mayRestore("A", 7_000L))
    }

    @Test fun offscreenStaleRestorerCannotSeekAfterAnotherVideoStarts() {
        val gate = VideoRestoreVisitGate()
        gate.observeStart("A", 100L)
        gate.observeStart("B", 150L)
        assertFalse(gate.markRestore("A", 300L))
        assertTrue(gate.markRestore("B", 300L))
    }

    @Test fun differentVideoOnlyForMillisecondsIsNotEnoughToRearm() {
        val gate = VideoRestoreVisitGate()
        gate.observeStart("A", 100L)
        assertTrue(gate.markRestore("A", 300L))
        gate.observeStart("B", 5_500L)
        gate.observeStart("A", 5_650L)
        assertFalse(gate.mayRestore("A", 5_651L))
    }
}
