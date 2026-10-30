package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountCaptureStateTest {
    private class Capture(
        decode: (String) -> String? = { "session=example" },
        complete: () -> Unit = {},
        persist: (Map<String, String>) -> Unit = {},
    ) {
        val writes = ArrayList<Map<String, String>>()
        val logs = ArrayList<String>()
        val warnings = ArrayList<String>()
        var decodes = 0
        var completions = 0
        val state = AccountCaptureState(
            persist = { writes.add(it.toMap()); persist(it) },
            log = { logs.add(it) },
            warn = { warnings.add(it) },
            decodeCookies = { decodes++; decode(it) },
            onComplete = { completions++; complete() },
        )
    }

    @Test fun missingCookiesDoNotPreventCompletion() {
        for (cookies in listOf(null, "", " \n\r\t")) {
            val capture = Capture()
            capture.state.capture(AccountCaptureState.Snapshot(
                token = "test-token", userId = "test-user", cookies = cookies,
            ))
            assertTrue(capture.state.completed)
            assertEquals("test-token", capture.state.authToken)
            assertEquals(1, capture.completions)
            assertEquals(listOf(mapOf("auth_token" to "test-token", "user_id" to "test-user")), capture.writes)
            assertEquals(1, capture.warnings.size)
            assertEquals(0, capture.decodes)
        }
    }

    @Test fun repeatedPartialCaptureDoesNotRepeatWritesOrLogs() {
        val capture = Capture()
        val partial = AccountCaptureState.Snapshot(token = "test-token", username = "test-name")
        capture.state.capture(partial)
        val logs = capture.logs.toList()
        repeat(10_000) { capture.state.capture(partial) }
        assertEquals(1, capture.writes.size)
        assertEquals(logs, capture.logs)
        assertEquals(1, capture.warnings.size)
        assertFalse(capture.state.completed)
        assertEquals(0, capture.completions)
    }

    @Test fun laterUserIdCompletesAPartialCapture() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot(token = "test-token"))
        capture.state.capture(AccountCaptureState.Snapshot(token = "test-token", userId = "test-user"))
        assertTrue(capture.state.completed)
        assertEquals(listOf(mapOf("auth_token" to "test-token"), mapOf("user_id" to "test-user")), capture.writes)
        assertEquals(1, capture.completions)
    }

    @Test fun blankValuesNeitherCompleteCaptureNorEraseCapturedValues() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot(userId = "test-user", token = " \t"))
        capture.state.capture(AccountCaptureState.Snapshot(userId = " ", token = ""))
        assertFalse(capture.state.completed)
        assertNull(capture.state.authToken)
        assertEquals(listOf(mapOf("user_id" to "test-user")), capture.writes)
        capture.state.capture(AccountCaptureState.Snapshot(token = "test-token"))
        assertTrue(capture.state.completed)
    }

    @Test fun onlyChangedValuesAreWrittenWhileCaptureIsIncomplete() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot(token = "first", username = "first-name"))
        capture.state.capture(AccountCaptureState.Snapshot(token = "second", username = "first-name"))
        capture.state.capture(AccountCaptureState.Snapshot(token = "second", username = "second-name"))
        assertEquals(listOf(
            mapOf("auth_token" to "first", "username" to "first-name"),
            mapOf("auth_token" to "second"),
            mapOf("username" to "second-name"),
        ), capture.writes)
        assertEquals("second", capture.state.authToken)
    }

    @Test fun validCookiesRemainAvailableAndAreDecodedOnlyWhenChanged() {
        val capture = Capture()
        val snapshot = AccountCaptureState.Snapshot(cookies = "[\n\t example\r]")
        repeat(1_000) { capture.state.capture(snapshot) }
        assertEquals(1, capture.decodes)
        assertEquals(listOf(mapOf("cookie_json" to "[   example ]", "cookie_http" to "session=example")), capture.writes)
        assertTrue(capture.warnings.isEmpty())
        assertFalse(capture.state.completed)
    }

    @Test fun malformedCookiesDoNotBlockCredentialsOrRepeatWarnings() {
        val capture = Capture(decode = { null })
        val snapshot = AccountCaptureState.Snapshot(cookies = "malformed", token = "test-token")
        repeat(1_000) { capture.state.capture(snapshot) }
        assertEquals(1, capture.decodes)
        assertEquals(1, capture.writes.size)
        assertEquals(1, capture.warnings.size)
        capture.state.capture(snapshot.copy(userId = "test-user"))
        assertTrue(capture.state.completed)
        assertEquals(1, capture.completions)
    }

    @Test fun completeCaptureBatchesAllAvailableFieldsInOneWrite() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot("test-token", "test-user", "test-name", "[]"))
        assertTrue(capture.state.completed)
        assertEquals(listOf(mapOf(
            "auth_token" to "test-token", "user_id" to "test-user", "username" to "test-name",
            "cookie_json" to "[]", "cookie_http" to "session=example",
        )), capture.writes)
    }

    @Test fun queuedCallbacksAfterCompletionAreInert() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot(token = "test-token", userId = "test-user"))
        val logs = capture.logs.toList()
        repeat(10_000) {
            capture.state.capture(AccountCaptureState.Snapshot("different", "different", "different", "different"))
        }
        assertEquals(1, capture.writes.size)
        assertEquals(logs, capture.logs)
        assertEquals(1, capture.warnings.size)
        assertEquals(1, capture.completions)
        assertEquals(0, capture.decodes)
        assertEquals("test-token", capture.state.authToken)
    }

    @Test fun concurrentCallbacksCompleteAndPersistOnlyOnce() {
        val capture = Capture()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..100).map {
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    capture.state.capture(AccountCaptureState.Snapshot(token = "test-token", userId = "test-user"))
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(capture.state.completed)
            assertEquals(1, capture.writes.size)
            assertEquals(1, capture.completions)
            assertEquals(1, capture.warnings.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun failedUnhookCannotRestartCapture() {
        val capture = Capture(complete = { error("Framework unhook failed") })
        val snapshot = AccountCaptureState.Snapshot(token = "test-token", userId = "test-user")
        assertTrue(runCatching { capture.state.capture(snapshot) }.isFailure)
        repeat(1_000) { capture.state.capture(snapshot) }
        assertTrue(capture.state.completed)
        assertEquals(1, capture.completions)
        assertEquals(1, capture.writes.size)
    }

    @Test fun persistenceFailureCannotCauseRepeatedWriteAttempts() {
        val capture = Capture(persist = { error("Storage unavailable") })
        val snapshot = AccountCaptureState.Snapshot(token = "test-token", userId = "test-user")
        assertTrue(runCatching { capture.state.capture(snapshot) }.isFailure)
        repeat(1_000) { capture.state.capture(snapshot) }
        assertEquals(1, capture.writes.size)
        assertEquals(1, capture.completions)
        assertTrue(capture.state.completed)
    }

    @Test fun diagnosticsNeverIncludeCapturedCredentialsOrAccountIdentifiers() {
        val capture = Capture()
        capture.state.capture(AccountCaptureState.Snapshot("secret-token", "secret-id", "secret-name", "secret-cookie"))
        assertFalse((capture.logs + capture.warnings).any { "secret-" in it })
    }
}
