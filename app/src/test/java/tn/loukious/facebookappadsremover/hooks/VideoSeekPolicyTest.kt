package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSeekPolicyTest {
    @Test fun internalShortReelPositioningNeverCancelsSavedResume() {
        for (reason in listOf(
            "BY_AUTOPLAY",
            "BY_SHORT_FORM_VIDEO_INVISIBLE",
            "BY_SHORT_FORM_VIDEO_ONPAUSE",
            "BY_SHORT_FORM_VIDEO_ONRESUME",
            "BY_SHORT_FORM_VIDEO_FULLY_VISIBLE",
            "BY_INITIAL_FRAME_PERFECT_SEEK",
            "BY_VIDEO_CLIP_START_TIME",
            "BY_ABSOLUTE_SEEK_BY_TRANSITION",
            "BY_ABSOLUTE_SEEK_BY_TRANSITION_WITH_PREVIEW",
        )) {
            assertFalse(reason, VideoSeekPolicy.shouldObserveAsUserSeek(reason, 0, 0, true))
            assertFalse(reason, VideoSeekPolicy.shouldObserveAsUserSeek(reason, 7_000, 0, true))
        }
    }

    @Test fun invisibleShortReelCapturesPositionInsteadOfDeletingIt() {
        assertTrue(VideoSeekPolicy.isReelDeparture("BY_SHORT_FORM_VIDEO_INVISIBLE"))
        assertTrue(VideoSeekPolicy.isReelDeparture("BY_SHORT_FORM_VIDEO_ONPAUSE"))
        assertFalse(VideoSeekPolicy.isReelDeparture("BY_SEEKBAR_CONTROLLER"))
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek(
            "BY_SHORT_FORM_VIDEO_INVISIBLE", 0, 7_000, false))
        assertTrue(VideoSeekPolicy.isAutomaticReset("BY_SHORT_FORM_VIDEO_INVISIBLE", 0, 7_000))
        assertTrue(VideoSeekPolicy.isAutomaticReset("BY_AUTOPLAY", 0, 7_000))
        assertFalse(VideoSeekPolicy.isAutomaticReset("BY_AUTOPLAY", 7_000, 0))
        assertFalse(VideoSeekPolicy.isAutomaticReset("BY_SEEKBAR_CONTROLLER", 0, 7_000))
    }

    @Test fun autoplayReuseOfPooledReelsCannotCancelOrEraseSavedPosition() {
        // From live FB580 v1.19 logs: 02:45:10.779 / 02:45:18.158.
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek("BY_AUTOPLAY", 8437, 0, false))
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek("BY_AUTOPLAY", 0, 7211, false))
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek("BY_AUTOPLAY", 0, 841, false))
    }

    @Test fun genuineManualRewindStillCancelsEvenToBeginning() {
        for (reason in listOf(
            "BY_SEEKBAR_CONTROLLER", "BY_USER", "BY_USER_SWIPE", "BY_USER_GESTURE",
        )) {
            assertTrue(reason, VideoSeekPolicy.shouldObserveAsUserSeek(reason, 0, 0, true))
        }
        assertTrue(VideoSeekPolicy.shouldObserveAsUserSeek("BY_SEEK", 0, 8_000, true))
        assertTrue(VideoSeekPolicy.shouldObserveAsUserSeek("BY_SEEK", 0, 8_000, false))
        assertTrue(VideoSeekPolicy.shouldObserveAsUserSeek(null, 2_000, 0, true))
    }

    @Test fun noOpGenericStartupSeekIsNotAManualScrub() {
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek("BY_SEEK", 0, 0, true))
        assertFalse(VideoSeekPolicy.shouldObserveAsUserSeek(null, 300, 500, true))
        assertTrue(VideoSeekPolicy.shouldObserveAsUserSeek("BY_SEEK", 0, 0, false))
        assertTrue(VideoSeekPolicy.shouldObserveAsUserSeek("BY_SEEK", 0, null, true))
    }
}
