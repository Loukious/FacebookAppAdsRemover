package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Provider

class AuthenticationStoreSourceTest {
    private interface Preferences {
        fun snapshot(): HashMap<String, Any?>
        fun getString(key: String, fallback: String?): String? = fallback
    }

    private interface Marker

    private class Holder(private val provider: Provider<*>)

    private class Store(private val holder: Holder, private val values: Map<String, Any?>) : Preferences, Marker {
        var reads = 0
        var failRead = false
        override fun snapshot(): HashMap<String, Any?> {
            reads++
            check(!failRead)
            return HashMap(values)
        }
    }

    private class Factory {
        lateinit var current: Preferences
        var reads = 0
        var failRead = false
        var beforeRead: (() -> Unit)? = null
        fun preferences(name: String): Preferences {
            assertEquals("authentication", name)
            reads++
            beforeRead?.invoke()
            check(!failRead)
            return current
        }
    }

    private class CapturedProvider(private val factory: Factory) : Provider<Any> {
        var reads = 0
        override fun get(): Any { reads++; error("Provider.get() is not an authority getter") }
    }

    private class Fixture(values: Map<String, Any?> = credentials("first", "user-a")) {
        val factory = Factory()
        val provider = CapturedProvider(factory)
        val store = Store(Holder(provider), values)
        val source = AuthenticationStoreSource()
        init { factory.current = store }
        fun bind() = source.observe(provider, Store::class.java)
        fun replace(values: Map<String, Any?>): Store = Store(Holder(CapturedProvider(factory)), values).also {
            factory.current = it
        }
    }

    companion object {
        private fun credentials(token: String, user: String): Map<String, Any?> =
            mapOf("access_token" to token, "uid" to user)
    }

    @Test fun observingConstructorCapturesPerformsNoAuthorityReads() {
        val f = Fixture()
        assertTrue(f.bind())
        assertEquals(0, f.factory.reads)
        assertEquals(0, f.provider.reads)
        assertEquals(0, f.store.reads)
    }

    @Test fun unboundAndUnsupportedSourcesAreUnknown() {
        val source = AuthenticationStoreSource()
        assertEquals(AccountCaptureState.Session.Unknown, source.read().session)
        assertFalse(source.observe(Any(), Store::class.java))
        assertFalse(source.observeStore(Any()))
        assertEquals(AccountCaptureState.Session.Unknown, source.read().session)
    }

    @Test fun everyReadReacquiresTheCanonicalReplacement() {
        val f = Fixture()
        assertTrue(f.bind())
        assertEquals(AccountCaptureState.Session.SignedIn("first", "user-a"), f.source.read().session)
        val fresh = f.replace(credentials("second", "user-b"))
        assertEquals(AccountCaptureState.Session.SignedIn("second", "user-b"), f.source.read().session)
        assertEquals(1, f.store.reads)
        assertEquals(1, fresh.reads)
        assertEquals(2, f.factory.reads)
    }

    @Test fun lateAttachFindsFactoryWithoutReadingOrRetainingTheOldStore() {
        val f = Fixture()
        assertTrue(f.source.observeStore(f.store))
        assertEquals(0, f.factory.reads)
        assertEquals(0, f.store.reads)
        f.replace(credentials("refreshed", "user-a"))
        assertTrue(f.source.observeStore(f.store))
        assertTrue(f.source.observe(CapturedProvider(f.factory), Store::class.java))
        assertEquals(AccountCaptureState.Session.SignedIn("refreshed", "user-a"), f.source.read().session)
        assertEquals(0, f.store.reads)
    }

    @Test fun differentFactoriesMakeAuthorityUnavailableInsteadOfSelectingTheLatestReceiver() {
        val first = Fixture()
        val second = Fixture(credentials("second", "user-b"))
        assertTrue(first.bind())
        assertFalse(first.source.observe(second.provider, Store::class.java))
        assertEquals(AccountCaptureState.Session.Unknown, first.source.read().session)
        assertFalse(first.bind())
        assertEquals(AccountCaptureState.Session.Unknown, first.source.read().session)
    }

    @Test fun ambiguousDirectFactoryCapturesAreRejected() {
        class Both(private val first: Factory, private val second: Factory)
        val source = AuthenticationStoreSource()
        assertFalse(source.observe(Both(Factory(), Factory()), Store::class.java))
        assertEquals(AccountCaptureState.Session.Unknown, source.read().session)
    }

    @Test fun ambiguousLateAttachHoldersAreRejected() {
        class BothStores(private val first: Holder, private val second: Holder) : Preferences {
            override fun snapshot(): HashMap<String, Any?> = error("Must not be read")
        }
        val first = Fixture()
        val second = Fixture()
        assertFalse(first.source.observeStore(BothStores(Holder(first.provider), Holder(second.provider))))
        assertEquals(AccountCaptureState.Session.Unknown, first.source.read().session)
    }

    @Test fun ambiguousFactoryAndSnapshotGetterShapesAreRejected() {
        class TwoGetters {
            fun first(name: String): Preferences = error(name)
            fun second(name: String): Preferences = error(name)
        }
        class Capture(private val factory: TwoGetters)
        class TwoSnapshots : Preferences {
            override fun snapshot(): HashMap<String, Any?> = error("Must not be read")
            fun otherSnapshot(): Map<String, Any?> = error("Must not be read")
        }
        val source = AuthenticationStoreSource()
        assertFalse(source.observe(Capture(TwoGetters()), Store::class.java))
        assertFalse(source.observe(Fixture().provider, TwoSnapshots::class.java))
        assertEquals(AccountCaptureState.Session.Unknown, source.read().session)
    }

    @Test fun markerInterfaceCannotIdentifyAPreferencesFactory() {
        class MarkerFactory { fun value(name: String): Marker = error(name) }
        class Capture(private val factory: MarkerFactory)
        val source = AuthenticationStoreSource()
        assertFalse(source.observe(Capture(MarkerFactory()), Store::class.java))
        assertEquals(AccountCaptureState.Session.Unknown, source.read().session)
    }

    @Test fun completeNormalSessionCarriesOnlyItsMatchingOptionalData() {
        val f = Fixture(credentials("current", "user-a") + mapOf(
            "username" to "name-a", "session_cookies_string" to "[cookies-a]",
        ))
        assertTrue(f.bind())
        val observation = f.source.read()
        assertEquals(AccountCaptureState.Session.SignedIn("current", "user-a"), observation.session)
        assertEquals(AccountCaptureState.Snapshot("current", "user-a", "name-a", "[cookies-a]"), observation.snapshot)
    }

    @Test fun logoutAndNewLoginAreVisibleOnFreshReads() {
        val f = Fixture()
        assertTrue(f.bind())
        assertEquals(AccountCaptureState.Session.SignedIn("first", "user-a"), f.source.read().session)
        f.replace(emptyMap())
        assertEquals(AccountCaptureState.Session.LoggedOut, f.source.read().session)
        f.replace(credentials("next", "user-b"))
        assertEquals(AccountCaptureState.Session.SignedIn("next", "user-b"), f.source.read().session)
    }

    @Test fun eitherMissingOrBlankNormalCredentialMatchesFacebookLoggedOutSentinel() {
        for (values in listOf(
            emptyMap(), mapOf("uid" to "user-a"), mapOf("access_token" to "token"),
            credentials("", "user-a"), credentials(" \t", "user-a"), credentials("token", ""),
        )) {
            val f = Fixture(values)
            assertTrue(f.bind())
            assertEquals(AccountCaptureState.Session.LoggedOut, f.source.read().session)
        }
    }

    @Test fun wrongCredentialOrOptionalTypesAreUnknown() {
        for (key in listOf("uid", "access_token", "username", "session_cookies_string")) {
            val f = Fixture(credentials("current", "user-a") + (key to 123))
            assertTrue(f.bind())
            assertEquals(AccountCaptureState.Session.Unknown, f.source.read().session)
        }
    }

    @Test fun pageSessionUsesAdminPairAndDropsPageOptionalData() {
        val f = Fixture(credentials("page-token", "page-id") + mapOf(
            "page_admin_uid" to "admin-id", "page_admin_access_token" to "admin-token",
            "username" to "page-name", "session_cookies_string" to "[page-cookies]",
        ))
        assertTrue(f.bind())
        val observation = f.source.read()
        assertEquals(AccountCaptureState.Session.SignedIn("admin-token", "admin-id"), observation.session)
        assertNull(observation.snapshot.username)
        assertNull(observation.snapshot.cookies)
        assertEquals("admin-token", observation.snapshot.token)
        assertEquals("admin-id", observation.snapshot.userId)
    }

    @Test fun leftoverAdminCredentialsCannotRestoreALoggedOutPrimarySession() {
        val admin = mapOf("page_admin_uid" to "admin-id", "page_admin_access_token" to "admin-token")
        for (values in listOf(admin, admin + ("uid" to "page-id"), admin + ("access_token" to "page-token"))) {
            val f = Fixture(values)
            assertTrue(f.bind())
            assertEquals(AccountCaptureState.Session.LoggedOut, f.source.read().session)
        }
        val badPrimary = Fixture(admin + mapOf("uid" to 123, "access_token" to "page-token"))
        assertTrue(badPrimary.bind())
        assertEquals(AccountCaptureState.Session.Unknown, badPrimary.source.read().session)
    }

    @Test fun incompleteOrMalformedAdminPairCannotFallBackToPageCredentials() {
        for (admin in listOf(
            mapOf("page_admin_uid" to "admin-id"), mapOf("page_admin_access_token" to "admin-token"),
            mapOf("page_admin_uid" to "", "page_admin_access_token" to "admin-token"),
            mapOf("page_admin_uid" to "admin-id", "page_admin_access_token" to 123),
        )) {
            val f = Fixture(credentials("page-token", "page-id") + admin)
            assertTrue(f.bind())
            assertEquals(AccountCaptureState.Session.Unknown, f.source.read().session)
        }
    }

    @Test fun factoryAndSnapshotReadFailuresAreUnknown() {
        val f = Fixture()
        assertTrue(f.bind())
        f.factory.failRead = true
        assertEquals(AccountCaptureState.Session.Unknown, f.source.read().session)
        f.factory.failRead = false
        f.store.failRead = true
        assertEquals(AccountCaptureState.Session.Unknown, f.source.read().session)
    }

    @Test fun incompatibleCanonicalStoreIsUnknown() {
        class Other : Preferences {
            override fun snapshot() = HashMap<String, Any?>()
        }
        val f = Fixture()
        assertTrue(f.bind())
        f.factory.current = Other()
        assertEquals(AccountCaptureState.Session.Unknown, f.source.read().session)
    }

    @Test fun conflictingObservationDuringReadCannotPublishTheOldAuthority() {
        val first = Fixture()
        val second = Fixture()
        assertTrue(first.bind())
        first.factory.beforeRead = {
            assertFalse(first.source.observe(second.provider, Store::class.java))
        }
        assertEquals(AccountCaptureState.Session.Unknown, first.source.read().session)
    }

    @Test fun factoryInvocationDoesNotHoldTheObservationMonitor() {
        val f = Fixture()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(f.bind())
            f.factory.beforeRead = {
                assertTrue(executor.submit<Boolean> { f.bind() }.get(5, TimeUnit.SECONDS))
            }
            assertEquals(AccountCaptureState.Session.SignedIn("first", "user-a"), f.source.read().session)
        } finally {
            executor.shutdownNow()
        }
    }
}
