package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class RepostMediaSelectorTest {
    @Test fun muxedHdBeatsHigherResolutionDashVideoOnly() {
        val selected = RepostMediaSelector.best(listOf(
            c("1080p video", "https://fb.test/1080.mp4", "mp4_no_audio"),
            c("720p video", "https://fb.test/720.mp4", "mp4_no_audio"),
            c("HD", "https://fb.test/hd-progressive.mp4", "mp4"),
            c("SD", "https://fb.test/sd-progressive.mp4", "mp4"),
        ))
        assertEquals("https://fb.test/hd-progressive.mp4", selected?.url)
    }

    @Test fun highestMuxedResolutionWins() {
        val selected = RepostMediaSelector.best(listOf(
            c("360p", "https://fb.test/360.mp4", "mp4"),
            c("720p", "https://fb.test/720.mp4", "mp4"),
            c("480p", "https://fb.test/480.mp4", "mp4"),
        ))
        assertEquals("https://fb.test/720.mp4", selected?.url)
    }

    @Test fun sdMuxedIsBetterThanSilentDashFallback() {
        val selected = RepostMediaSelector.best(listOf(
            c("1080p video", "https://fb.test/1080.mp4", "mp4_no_audio"),
            c("SD", "https://fb.test/video.mp4?tag=sve_sd", "mp4"),
        ))
        assertEquals("https://fb.test/video.mp4?tag=sve_sd", selected?.url)
    }

    @Test fun splitOnlyMediaFallsBackToHighestVideoQuality() {
        val selected = RepostMediaSelector.best(listOf(
            c("720p video", "https://fb.test/720.mp4", "mp4_no_audio"),
            c("1080p video", "https://fb.test/1080.mp4", "mp4_no_audio"),
            c("Audio", "https://fb.test/audio.m4a", "m4a"),
        ))
        assertEquals("https://fb.test/1080.mp4", selected?.url)
    }

    private fun c(label: String, url: String, tag: String) =
        RepostMediaSelector.Candidate(label, url, tag)
}
