package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

class ExternalBrowserPolicyTest {
    private val browser = "com.facebook.browser.litev2.lite.BrowserLiteDIActivity"
    private fun destination(url: String?, name: String = browser, pkg: String = "com.facebook.katana") =
        ExternalBrowserPolicy.destination(pkg, name, url)
    private fun shim(url: String) =
        "https://lm.facebook.com/l.php?u=${URLEncoder.encode(url, "UTF-8")}&h=signature"

    @Test fun normalWebLinksWorkInBothBrowsersAndTheirSubclasses() {
        for (name in listOf(
            browser,
            "com.facebook.browser.litev2.lite.BrowserLiteDITransparentActivity",
            "com.facebook.browser.lite.BrowserLiteActivity",
            "com.facebook.browser.lite.BrowserLiteInMainProcessBottomSheetActivity",
        )) {
            assertEquals("https://example.org/article?a=1#section", destination(
                "https://example.org/article?a=1#section", name))
        }
        assertEquals("http://example.org/", destination("http://example.org/"))
    }

    @Test fun unwrapsActualFacebook580LinkShimWithoutFbclid() {
        val url = "https://example.org/article?q=a+b&other=x%26y#section"
        assertEquals(url, destination(shim(url)))
        assertEquals(url, destination(shim(shim(url))))
    }

    @Test fun warningInterstitialUsesItsWebDestination() {
        assertEquals("https://example.org/", destination(
            "https://www.facebook.com/flx/warn/?u=https%3A%2F%2Fexample.org%2F"))
    }

    @Test fun internalPagesAndAuthenticationStayInFacebook() {
        for (host in listOf("facebook.com", "m.facebook.com", "FB.COM", "messenger.com",
            "www.meta.com", "www.facebook.com.")) {
            assertNull(destination("https://$host/login/"))
            assertNull(destination(shim("https://$host/login/")))
        }
        assertNull(destination("https://facebook.com/profile.php?u=https%3A%2F%2Fexample.org"))
    }

    @Test fun hostMatchingRequiresADomainBoundary() {
        assertEquals("https://notfacebook.com/", destination("https://notfacebook.com/"))
        assertEquals("https://facebook.com.example.org/", destination("https://facebook.com.example.org/"))
        // An external site's `u` query is not a Facebook link shim.
        val external = "https://example.org/l.php?u=https%3A%2F%2Ffacebook.com"
        assertEquals(external, destination(external))
    }

    @Test fun unrelatedActivitiesAndOtherAppsNeverRedirect() {
        assertNull(destination("https://example.org/", "com.facebook.katana.LoginActivity"))
        assertNull(destination("https://example.org/", "com.facebook.dma.dmabrowser.lite.DMASecureBrowserActivity"))
        assertNull(destination("https://example.org/", pkg = "another.app"))
        assertNull(ExternalBrowserPolicy.destination(null, null, "https://example.org/"))
    }

    @Test fun malformedAndNonWebLinksStayWithOriginalHandler() {
        for (url in listOf(null, "", "relative/path", "https://", "https://exa mple.org/",
            "javascript:alert(1)", "fb://profile/123", "mailto:test@example.org",
            "intent://example.org/#Intent;scheme=https;end", "https://facebook.com@example.org/")) {
            assertNull(destination(url))
        }
        assertNull(destination(shim("javascript:alert(1)")))
        assertNull(destination("https://facebook.com/l.php?u=%broken"))
        assertNull(destination("https://facebook.com/l.php?h=signature"))
    }

    @Test fun caseInsensitiveWebSchemesWork() {
        assertEquals("HTTPS://EXAMPLE.ORG/", destination("HTTPS://EXAMPLE.ORG/"))
        assertNull(destination("HTTPS://WWW.FACEBOOK.COM/"))
    }
}
