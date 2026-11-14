package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tn.loukious.facebookappadsremover.hooks.AccountCaptureState.Session
import tn.loukious.facebookappadsremover.hooks.AccountCaptureState.Snapshot

class AccountCaptureStateTest {
    private class Capture(
        var session: Session = Session.Unknown,
        initialPrefs: Map<String, String> = emptyMap(),
        decode: (String) -> String? = { "session=example" },
        persist: (Map<String, String?>) -> Unit = {},
    ) {
        val writes = ArrayList<Map<String, String?>>()
        val stored = initialPrefs.toMutableMap()
        val logs = ArrayList<String>()
        val warnings = ArrayList<String>()
        var decodes = 0
        val state = AccountCaptureState(
            persist = { changed ->
                writes.add(changed.toMap())
                for ((key, value) in changed) if (value == null) stored.remove(key) else stored[key] = value
                persist(changed)
            },
            log = { logs.add(it) },
            warn = { warnings.add(it) },
            decodeCookies = { decodes++; decode(it) },
        )

        fun capture(snapshot: Snapshot) = state.capture(snapshot, session)
    }

    private fun signedIn(token: String = "test-token", userId: String = "test-user") =
        Session.SignedIn(token, userId)

    private fun snapshot(token: String = "test-token", userId: String = "test-user") =
        Snapshot(token = token, userId = userId)

    @Test fun missingCookiesDoNotPreventAuthoritativeCompletion() {
        for (cookies in listOf(null, "", " \n\r\t")) {
            val capture = Capture(session = signedIn())
            capture.capture(snapshot().copy(cookies = cookies))
            assertTrue(capture.state.completed)
            assertEquals("test-token", capture.state.authToken)
            assertEquals(mapOf("auth_token" to "test-token", "user_id" to "test-user"), capture.stored)
            assertEquals(1, capture.warnings.size)
            assertEquals(0, capture.decodes)
        }
    }

    @Test fun unknownAuthorityCannotAssemblePartialCredentials() {
        val capture = Capture()
        repeat(1_000) {
            capture.capture(Snapshot(token = "token-a", username = "name-a", cookies = "[a]"))
            capture.capture(Snapshot(userId = "user-b"))
        }
        assertTrue(capture.writes.isEmpty())
        assertTrue(capture.logs.isEmpty())
        assertEquals(0, capture.decodes)
        assertFalse(capture.state.completed)
        assertNull(capture.state.authToken)
    }

    @Test fun authoritativePairDoesNotDependOnAMatchingSample() {
        val capture = Capture(session = signedIn("token-b", "user-b"))
        capture.capture(Snapshot(token = "token-a", username = "name-a", cookies = "[a]"))
        assertEquals(mapOf("auth_token" to "token-b", "user_id" to "user-b"), capture.stored)
        assertTrue(capture.state.completed)
        assertEquals(0, capture.decodes)
    }

    @Test fun aCompleteMatchingSampleCanEnrichTheExistingPair() {
        val capture = Capture(session = signedIn())
        capture.capture(Snapshot())
        capture.capture(snapshot().copy(username = "test-name", cookies = "[test]"))
        assertEquals("test-name", capture.stored["username"])
        assertEquals("session=example", capture.stored["cookie_http"])
        assertEquals(2, capture.writes.size)
    }

    @Test fun partialSamplesCannotAttachOptionalFieldsToTheCurrentPair() {
        val capture = Capture(session = signedIn())
        capture.capture(snapshot())
        val writes = capture.writes.size
        repeat(1_000) {
            capture.capture(Snapshot(token = "test-token", username = "wrong-name", cookies = "[wrong]"))
            capture.capture(Snapshot(userId = "test-user", username = "wrong-name", cookies = "[wrong]"))
        }
        assertEquals(writes, capture.writes.size)
        assertEquals(mapOf("auth_token" to "test-token", "user_id" to "test-user"), capture.stored)
        assertEquals(0, capture.decodes)
    }

    @Test fun missingOptionalFieldsInTheCanonicalSnapshotRemoveEarlierValuesForTheSamePair() {
        val capture = Capture(session = signedIn())
        capture.capture(snapshot().copy(username = "test-name"))
        capture.capture(snapshot().copy(cookies = "[test]"))
        assertFalse(capture.stored.containsKey("username"))
        capture.capture(snapshot().copy(username = "", cookies = ""))
        assertFalse(capture.stored.containsKey("username"))
        assertFalse(capture.stored.containsKey("cookie_json"))
        assertFalse(capture.stored.containsKey("cookie_http"))
        assertEquals(3, capture.writes.size)
        assertEquals(mapOf("cookie_json" to null, "cookie_http" to null), capture.writes.last())
    }

    @Test fun validCookiesAreNormalizedAndDecodedOnlyWhenChanged() {
        val capture = Capture(session = signedIn())
        val sample = snapshot().copy(cookies = "[\n\t example\r]")
        repeat(1_000) { capture.capture(sample) }
        assertEquals(1, capture.decodes)
        assertEquals(1, capture.writes.size)
        assertEquals("[   example ]", capture.stored["cookie_json"])
        assertEquals("session=example", capture.stored["cookie_http"])
        assertTrue(capture.warnings.isEmpty())
    }

    @Test fun malformedNewCookiesRemoveTheOldHttpHeader() {
        val capture = Capture(session = signedIn(), decode = { if (it == "[valid]") "session=valid" else null })
        capture.capture(snapshot().copy(cookies = "[valid]"))
        repeat(1_000) { capture.capture(snapshot().copy(cookies = "malformed")) }
        assertEquals("malformed", capture.stored["cookie_json"])
        assertFalse(capture.stored.containsKey("cookie_http"))
        assertEquals(2, capture.decodes)
        assertEquals(2, capture.writes.size)
        assertEquals(1, capture.warnings.size)
        assertTrue(capture.state.completed)
    }

    @Test fun emptyCookieArrayCannotKeepAPreviousHttpSession() {
        val capture = Capture(session = signedIn(), decode = { if (it == "[]") null else "session=old" })
        capture.capture(snapshot().copy(cookies = "[valid]"))
        capture.capture(snapshot().copy(cookies = "[]"))
        assertFalse(capture.stored.containsKey("cookie_http"))
        assertEquals("[]", capture.stored["cookie_json"])
    }

    @Test fun cookieDecoderFailureCannotBlockTheAuthoritativePair() {
        val capture = Capture(session = signedIn(), decode = { error("bad cookie") })
        capture.capture(snapshot().copy(cookies = "malformed"))
        assertEquals("test-token", capture.state.authToken)
        assertTrue(capture.state.completed)
        assertFalse(capture.stored.containsKey("cookie_http"))
    }

    @Test fun aCompleteMatchingCaptureUsesOnePersistenceTransaction() {
        val capture = Capture(session = signedIn())
        capture.capture(snapshot().copy(username = "test-name", cookies = "[test]"))
        assertEquals(listOf(mapOf(
            "auth_token" to "test-token", "user_id" to "test-user", "username" to "test-name",
            "cookie_json" to "[test]", "cookie_http" to "session=example",
        )), capture.writes)
    }

    @Test fun sameUserRefreshFollowsAuthorityAndRejectsTheOldToken() {
        val capture = Capture(session = signedIn("old-token"))
        val old = snapshot("old-token").copy(username = "old-name", cookies = "[old]")
        capture.capture(old)
        capture.session = signedIn("new-token")
        capture.capture(old)
        repeat(1_000) { capture.capture(old) }
        assertEquals("new-token", capture.state.authToken)
        assertEquals(mapOf("auth_token" to "new-token", "user_id" to "test-user"), capture.stored)
        assertEquals(2, capture.writes.size)
    }

    @Test fun staleAccountContextCannotUndoAnAuthoritativeSwitch() {
        val capture = Capture(session = signedIn("token-a", "user-a"))
        val old = snapshot("token-a", "user-a").copy(username = "name-a", cookies = "[a]")
        capture.capture(old)
        capture.session = signedIn("token-b", "user-b")
        capture.capture(old)
        assertEquals(mapOf("auth_token" to "token-b", "user_id" to "user-b"), capture.stored)
        assertTrue(capture.logs.contains("authentication account changed"))
    }

    @Test fun legitimateReturnToAPreviousAccountRemainsAllowed() {
        val capture = Capture(session = signedIn("token-a", "user-a"))
        val a = snapshot("token-a", "user-a")
        val b = snapshot("token-b", "user-b")
        capture.capture(a)
        capture.session = signedIn("token-b", "user-b")
        capture.capture(b)
        capture.session = signedIn("token-a", "user-a")
        capture.capture(a)
        assertEquals(mapOf("auth_token" to "token-a", "user_id" to "user-a"), capture.stored)
        assertEquals(3, capture.writes.size)
    }

    @Test fun matchingNewOptionalValuesSurviveEvenWhenTheyEqualTheOldValues() {
        val capture = Capture(session = signedIn("token-a", "user-a"))
        capture.capture(snapshot("token-a", "user-a").copy(username = "same-name"))
        capture.session = signedIn("token-b", "user-b")
        capture.capture(snapshot("token-b", "user-b").copy(username = "same-name"))
        assertEquals("same-name", capture.stored["username"])
    }

    @Test fun firstPairRemovesUnseenOptionalValuesFromPreviousProcessPreferences() {
        val capture = Capture(session = signedIn(), initialPrefs = mapOf(
            "auth_token" to "old-token", "user_id" to "old-user", "username" to "old-name",
            "cookie_json" to "[old]", "cookie_http" to "session=old",
        ))
        capture.capture(snapshot())
        assertEquals(mapOf("auth_token" to "test-token", "user_id" to "test-user"), capture.stored)
        assertEquals(mapOf(
            "auth_token" to "test-token", "user_id" to "test-user",
            "username" to null, "cookie_json" to null, "cookie_http" to null,
        ), capture.writes.single())
    }

    @Test fun clearRemovesAllKnownPreferenceKeysEvenWithNoMemoryCapture() {
        val capture = Capture(initialPrefs = mapOf("auth_token" to "old-token", "cookie_http" to "session=old"))
        capture.state.clear()
        assertTrue(capture.stored.isEmpty())
        assertEquals(setOf("auth_token", "user_id", "username", "cookie_json", "cookie_http"), capture.writes.single().keys)
        assertTrue(capture.writes.single().values.all { it == null })
        capture.state.clear()
        assertEquals(1, capture.writes.size)
    }

    @Test fun staleCallbacksAfterLogoutCannotRestoreCredentials() {
        val capture = Capture(session = signedIn())
        val old = snapshot().copy(username = "old-name", cookies = "[old]")
        capture.capture(old)
        capture.session = Session.LoggedOut
        repeat(1_000) { capture.capture(old) }
        assertFalse(capture.state.completed)
        assertNull(capture.state.authToken)
        assertTrue(capture.stored.isEmpty())
        assertEquals(2, capture.writes.size)
    }

    @Test fun aNewAuthoritativeLoginCanFollowLogout() {
        val capture = Capture(session = signedIn())
        capture.capture(snapshot())
        capture.session = Session.LoggedOut
        capture.capture(snapshot())
        capture.session = signedIn("next-token", "next-user")
        capture.capture(snapshot("next-token", "next-user"))
        assertTrue(capture.state.completed)
        assertEquals("next-token", capture.state.authToken)
    }

    @Test fun unknownAuthorityDoesNotGuessLogoutOrAcceptACandidate() {
        val capture = Capture(session = signedIn())
        capture.capture(snapshot())
        capture.session = Session.Unknown
        capture.capture(snapshot("other-token", "other-user"))
        assertEquals("test-token", capture.state.authToken)
        assertEquals(1, capture.writes.size)
    }

    @Test fun concurrentSamplesAgainstTheSameAuthorityPersistOnlyOnce() {
        val capture = Capture(session = signedIn())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..100).map {
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    capture.capture(snapshot())
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(capture.state.completed)
            assertEquals(1, capture.writes.size)
            assertEquals(1, capture.warnings.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentFreshAndStaleSamplesCannotRollBackTheCurrentAuthority() {
        val capture = Capture(session = signedIn("new-token"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..100).map { index -> executor.submit {
                capture.capture(snapshot(if (index % 2 == 0) "new-token" else "old-token"))
            } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals("new-token", capture.state.authToken)
            assertEquals(1, capture.writes.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun persistenceFailureCannotCauseRepeatedWriteAttempts() {
        val capture = Capture(session = signedIn(), persist = { error("Storage unavailable") })
        assertTrue(runCatching { capture.capture(snapshot()) }.isFailure)
        repeat(1_000) { capture.capture(snapshot()) }
        assertEquals(1, capture.writes.size)
        assertTrue(capture.state.completed)
    }

    @Test fun diagnosticsNeverIncludeCapturedCredentialsOrAccountIdentifiers() {
        val capture = Capture(session = signedIn("secret-token", "secret-id"))
        capture.capture(Snapshot("secret-token", "secret-id", "secret-name", "secret-cookie"))
        assertFalse((capture.logs + capture.warnings).any { "secret-" in it })
    }
}
