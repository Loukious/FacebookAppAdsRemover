package tn.loukious.facebookappadsremover.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportSanitizerTest {
    private fun log(tag: String, message: String) = "11-14 12:00:00.000 I/$tag(1234): $message"

    @Test fun preservesDiscoveryAndHookInstallationEvidence() {
        val lines = listOf(
            log("FacebookAppAdsRemover", "Discovery cache HIT: 36 target(s), installing without DexKit"),
            log("FBAR.Discovery", "FOUND key=feed target=X.0Abc.run/0"),
            log("FBAR.Download", "capture hook installed on X.0Abc: 4 method(s)"),
            log("FBAR.VideoResume", "video-resume hooks installed on X.0Def: 4 method(s)"),
            log("FBAR.Account", "field missing: mAuthToken"),
            log("FBAR.Settings", "toggle source: framework remote preferences"),
            log("FBAR.Filter", "health stage=20s pipeline=LITHO_RENDER state=REMOVING installed=1 invoked=8 inspected=8 aiRemoved=2"),
            log("FBAR.Session", "session backup receiver registered"),
        )
        for (line in lines) assertEquals(line, DiagnosticsReportSanitizer.sanitizeLine(line))
    }

    @Test fun omitsActualMediaPlaybackAndRepostProducerFormats() {
        val lines = listOf(
            log("FBAR.VideoResume", "saved 123456789012345 at 32000ms (mod log: stop-hook)"),
            log("FBAR.VideoResume", "SAVE_ON_PAUSE 123456789012345 at 32000ms"),
            log("FBAR.VideoResume", "restore seek failed for 123456789012345"),
            log("FBAR.Download", "captured video 123456789012345 via params-ctor (2 qualities)"),
            log("FBAR.Download", "PARAMS videoId=123456789012345 hd=true sd=true"),
            log("FBAR.Download", "VIDEO 123456789012345 HD video https://video.example/private?token=secret"),
            log("FBAR.Download", "THUMB 123456789012345: size=100x100"),
            log("FBAR.Repost", "posting videoId=123456789012345 quality=HD tag=video to page=987654321098765"),
            log("FBAR.Repost", "post complete: videoId=112233445566778 comment=true commentId=112233445566778_998877665544332"),
            log("FBAR.Repost", "comment post failed: private text from response"),
            log("FBAR.Download.Mix", "mix event: {\"url\":\"https://example.com/private\",\"title\":\"private title\"}"),
        )
        for (line in lines) assertNull(line, DiagnosticsReportSanitizer.sanitizeLine(line))
    }

    @Test fun omitsSessionNamesAndIntentExtrasIncludingMultilineContinuations() {
        val lines = listOf(
            log("FBAR.Session", "session exported: FBAR-Session-private.user-20261114-120000.json (authentication, logged_in_123456789012345)"),
            log("FBAR.Session", "import skip (no parent dir): app_light_prefs/dbl_local_auth_123456789012345"),
            log("FBAR.Activity", "Intent Information:"),
            log("FBAR.Activity", "Action: android.intent.action.VIEW"),
            log("FBAR.Activity", "Extras:"),
            log("FBAR.Activity", "  access_token=private-secret"),
            log("FBAR.Activity", "  title=private family photo"),
            log("FBAR.Activity", "  query=hook installed private search text"),
            log("FBAR.Activity", "Action: hooked-private-search"),
            log("FBAR.Activity", "hooked private extra value"),
            log("FBAR.Download", "VIDEO 123456789012345 hooked private title https://video.example/private"),
            log("FBAR.CleanUrl", "  to:   https://example.com/private?access_token=secret"),
        )
        assertTrue(lines.mapNotNull(DiagnosticsReportSanitizer::sanitizeLine).isEmpty())
    }

    @Test fun dropsExceptionMessagesAndStackPayloadButKeepsFailureType() {
        val lines = listOf(
            log("FBAR.Download", "hook failed on X.0Abc.run"),
            log("FBAR.Download", "java.lang.IllegalStateException: private title access_token=secret"),
            log("FBAR.Download", "\tat X.0Abc.run(PrivateFamilyPhoto.java:42)"),
            log("FBAR.Download", "Caused by: java.io.IOException: /storage/emulated/0/private-photo.jpg"),
        )
        val result = lines.mapNotNull(DiagnosticsReportSanitizer::sanitizeLine).joinToString("\n")
        assertTrue(result.contains("hook failed on X.0Abc.run"))
        assertTrue(result.contains("java.lang.IllegalStateException: <message omitted>"))
        assertTrue(result.contains("java.io.IOException: <message omitted>"))
        for (privateValue in listOf("private title", "secret", "PrivateFamilyPhoto", "private-photo.jpg")) {
            assertFalse(privateValue, result.contains(privateValue))
        }
    }

    @Test fun sanitizesInlineResolutionErrorsAndPrivatePaths() {
        val line = log("FBAR.Discovery", "RESOLVE_FAILED key=feed X.0Abc.run: java.lang.IllegalStateException: private title")
        assertEquals(
            log("FBAR.Discovery", "RESOLVE_FAILED key=feed X.0Abc.run: java.lang.IllegalStateException: <message omitted>"),
            DiagnosticsReportSanitizer.sanitizeLine(line),
        )
        val path = log("FacebookAppAdsRemover", "DexKit scanning: /data/user/0/com.facebook.katana/app_light_prefs/logged_in_123456789012345 -> 4/8 probe targets resolvable")
        val result = DiagnosticsReportSanitizer.sanitizeLine(path)!!
        assertTrue(result.contains("<private path>"))
        assertFalse(result.contains("123456789012345"))
        assertFalse(result.contains("app_light_prefs"))
    }

    @Test fun urlsRetainOnlyActualHostsWithoutCredentialsOrContent() {
        val source = "https://user:secret@example.com:8443/watch?v=123456789012345#private " +
            "file:///storage/emulated/0/private-photo.jpg " +
            "https://[2001:db8::1]:8443/private?token=secret " +
            "https://user:secret@invalid_host/private"
        assertEquals(
            "<link:example.com> <link> <link:[2001:db8::1]> <link>",
            DiagnosticsReportSanitizer.redactUrls(source),
        )
    }

    @Test fun rejectsOtherTagsMalformedLinesAndUnknownRuntimeMessages() {
        val lines = listOf(
            log("OtherApp", "FBAR.Discovery FOUND key=feed access_token=secret"),
            log("FBAR.FutureFeature", "hook installed private title"),
            "FBAR.Discovery FOUND private title",
            log("FBAR.Download", "unknown future payload=private title"),
            log("FBAR.Discovery", "  FOUND key=feed private title"),
        )
        for (line in lines) assertNull(line, DiagnosticsReportSanitizer.sanitizeLine(line))
    }
}
