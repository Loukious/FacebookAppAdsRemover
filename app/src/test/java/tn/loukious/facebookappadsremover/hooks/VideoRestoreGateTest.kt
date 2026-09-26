package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRestoreGateTest {
    @Test fun savedPositionRestoresOnlyOnceAcrossRepeatedStartSignals() {
        val gate = VideoRestoreGate()
        assertTrue(gate.armOnce(45_000, 100L, 3_000L))
        assertFalse(gate.armOnce(45_000, 200L, 3_000L))
        assertEquals(45_000L, gate.consumeIfValid(1_000L, 900))
        assertFalse(gate.armOnce(45_000, 1_500L, 3_000L))
        assertNull(gate.consumeIfValid(1_501L, 1_000))
        gate.finishRestore()
        assertNull(gate.consumeIfValid(2_000L, 500))
    }

    @Test fun manualBackwardSeekCancelsPendingRestoreAndRepeatedStartsCannotRearm() {
        val gate = VideoRestoreGate()
        assertTrue(gate.armOnce(80_000L, 1_000L, 3_000L))
        assertTrue(gate.cancelForSeek(1_250L))
        assertNull(gate.consumeIfValid(1_900L, 3_000))
        assertFalse(gate.armOnce(80_000L, 1_950L, 3_000L))
        assertNull(gate.consumeIfValid(2_500L, 5_000))
    }

    @Test fun initialStartWithoutSavedPositionCannotRearmFromLaterScrub() {
        val gate = VideoRestoreGate()
        assertFalse(gate.armOnce(null, 100L, 3_000L))
        assertTrue(gate.cancelForSeek(500L))
        assertFalse(gate.armOnce(60_000L, 700L, 3_000L))
        assertNull(gate.consumeIfValid(1_000L, 2_000))
    }

    @Test fun initialRestoreDoesNotFightPlaybackNearOrPastStoredPosition() {
        val near = VideoRestoreGate()
        assertTrue(near.armOnce(40_000L, 100L, 3_000L))
        assertNull(near.consumeIfValid(800L, 39_500))
        assertNull(near.consumeIfValid(900L, 500))

        val past = VideoRestoreGate()
        assertTrue(past.armOnce(40_000L, 100L, 3_000L))
        assertNull(past.consumeIfValid(800L, 50_000))
    }

    @Test fun expiredRestoreTargetsNeverSeekButShortReelsCanRestoreFromOneSecond() {
        val expired = VideoRestoreGate()
        assertTrue(expired.armOnce(42_000L, 100L, 3_000L))
        assertNull(expired.consumeIfValid(3_101L, 500))
        val short = VideoRestoreGate()
        assertTrue(short.armOnce(1_200L, 100L, 3_000L))
        assertEquals(1_200L, short.consumeIfValid(300L, 200, 250L))
        short.finishRestore()
        assertNull(short.consumeIfValid(900L, 0, 250L))
        val alreadyNear = VideoRestoreGate()
        assertTrue(alreadyNear.armOnce(1_200L, 100L, 3_000L))
        assertNull(alreadyNear.consumeIfValid(300L, 1_000, 250L))
    }

    @Test fun stopAndRestartAfterManualSeekCannotRearmWithinCooldown() {
        val gate = VideoRestoreGate()
        assertTrue(gate.armOnce(55_000L, 100L, 3_000L))
        gate.cancelForSeek(1_000L)
        gate.markStopped()
        assertFalse(gate.mayBeginNewSession(2_000L, 5_000L))
        assertTrue(gate.mayBeginNewSession(6_001L, 5_000L))
        val withoutScrub = VideoRestoreGate()
        withoutScrub.armOnce(40_000L, 100L, 3_000L)
        withoutScrub.markStopped()
        assertTrue(withoutScrub.mayBeginNewSession(110L, 5_000L))
    }

    @Test fun internallyGeneratedRestoreSeekIsNotTreatedAsUserSeek() {
        val gate = VideoRestoreGate()
        gate.armOnce(50_000L, 100L, 3_000L)
        assertEquals(50_000L, gate.consumeIfValid(400L, 800))
        assertFalse(gate.cancelForSeek(401L))
        assertTrue(gate.isRestoring())
        gate.finishRestore()
        assertFalse(gate.isRestoring())
        assertNull(gate.consumeIfValid(700L, 800))
    }

    @Test fun immediateStopAfterBackwardSeekDoesNotSaveStaleForwardGetter() {
        val gate = VideoRestoreGate()
        gate.armOnce(null, 100L, 3_000L)
        assertTrue(gate.cancelForSeek(500L))
        gate.recordAppliedSeek(8_000, 550L)
        assertEquals(8_000, gate.positionToSave(84_000, 800L))
        // Once Facebook's getter catches up, save the actual position.
        assertEquals(8_400, gate.positionToSave(8_400, 1_100L))
        assertEquals(84_000, gate.positionToSave(84_000, 4_000L))
    }

    @Test fun seekingToBeginningClearsOldSavedPositionEvenIfGetterIsStillAhead() {
        val gate = VideoRestoreGate()
        gate.armOnce(80_000L, 100L, 3_000L)
        gate.cancelForSeek(500L)
        gate.recordAppliedSeek(0, 510L)
        assertEquals(0, gate.positionToSave(80_000, 700L))
    }

    @Test fun automatedStartupSeekMustNotImposeScrubCooldownOnRapidReelRevisit() {
        val gate = VideoRestoreGate()
        assertTrue(gate.wasJustStarted(10L, 1_250L).not())
        assertFalse(gate.armOnce(null, 100L, 3_000L))
        assertTrue(gate.wasJustStarted(250L, 1_250L))
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek(
            "BY_INITIAL_FRAME_PERFECT_SEEK", 0, 0, true))
        gate.markStopped()
        assertTrue(gate.mayBeginNewSession(500L, 5_000L))
    }

    @Test fun realAutoplayDepartureAllowsRapidReopenAfterManualSeek() {
        val gate = VideoRestoreGate()
        gate.armOnce(9_000L, 100L, 3_000L)
        gate.cancelForSeek(300L)
        gate.recordAppliedSeek(7_500, 301L)
        gate.markStopped()
        assertFalse(gate.mayBeginNewSession(400L, 5_000L))
        gate.recordAutomaticReset(7_600, 500L)
        assertTrue(gate.mayBeginNewSession(600L, 5_000L))
        // The auto reset to zero must not replace a real pre-reset position.
        assertEquals(7_500, gate.positionToSave(0, 550L))
    }

    @Test fun automaticResetPreventsSubsecondStopOverwritingResume() {
        val gate = VideoRestoreGate()
        gate.armOnce(null, 100L, 3_000L)
        gate.recordAutomaticReset(8_000, 1_000L)
        assertEquals(8_000, gate.positionToSave(0, 1_100L))
        assertEquals(8_000, gate.positionToSave(1_100, 1_700L))
        assertEquals(1_100, gate.positionToSave(1_100, 4_000L))
    }

    @Test fun manualRewindAfterAutomaticResetStillWins() {
        val gate = VideoRestoreGate()
        gate.armOnce(null, 100L, 3_000L)
        gate.recordAutomaticReset(8_000, 1_000L)
        gate.cancelForSeek(1_100L)
        gate.recordAppliedSeek(0, 1_101L)
        assertEquals(0, gate.positionToSave(8_000, 1_200L))
        assertEquals(0, gate.positionToSave(0, 1_500L))
        gate.markStopped()
        assertFalse(gate.mayBeginNewSession(1_600L, 5_000L))
    }
}
