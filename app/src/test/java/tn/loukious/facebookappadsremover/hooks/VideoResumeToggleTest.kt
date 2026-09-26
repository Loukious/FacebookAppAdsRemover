package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import tn.loukious.facebookappadsremover.core.Settings
import tn.loukious.facebookappadsremover.ui.TOGGLE_SECTIONS

class VideoResumeToggleTest {
    @Test fun resumeIsOptInButBackgroundPlaybackRetainsIndependentDefault() {
        val video = TOGGLE_SECTIONS.single { it.title == "Video" }
        val resume = video.toggles.single { it.key == Settings.VIDEO_RESUME }
        val background = video.toggles.single { it.key == Settings.VIDEO_BACKGROUND }
        assertNotNull(resume)
        assertFalse(resume.default)
        assertEquals(false, background.default)
    }
}
