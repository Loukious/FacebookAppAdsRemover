package tn.loukious.facebookappadsremover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRequestStateTest {
    @Test fun overlappingExportAndImportBothKeepTheirReplies() {
        val requests = SessionRequestState()
        requests.register("export")
        requests.register("import")

        assertTrue(requests.consume("export"))
        assertEquals(setOf("import"), requests.snapshot())
        assertTrue(requests.consume("import"))
        assertTrue(requests.snapshot().isEmpty())
    }

    @Test fun repliesMayArriveInTheOppositeOrder() {
        val requests = SessionRequestState()
        requests.register("export")
        requests.register("import")

        assertTrue(requests.consume("import"))
        assertEquals(setOf("export"), requests.snapshot())
        assertTrue(requests.consume("export"))
    }

    @Test fun consumedReplyCannotBeReplayedAgainstAnotherPendingRequest() {
        val requests = SessionRequestState(setOf("export", "import"))
        assertTrue(requests.consume("export"))
        assertFalse(requests.consume("export"))
        assertEquals(setOf("import"), requests.snapshot())
    }

    @Test fun missingAndUnknownNoncesLeaveRealRequestsPending() {
        val requests = SessionRequestState(setOf("export", "import"))
        for (nonce in listOf(null, "", "forged")) assertFalse(requests.consume(nonce))
        assertEquals(setOf("export", "import"), requests.snapshot())
    }

    @Test fun persistedRequestsSurviveRecreationAndRepliesStayConsumed() {
        val requests = SessionRequestState()
        requests.register("export")
        requests.register("import")
        val recreated = SessionRequestState(requests.snapshot())
        assertTrue(recreated.consume("import"))

        val afterReply = SessionRequestState(recreated.snapshot())
        assertFalse(afterReply.consume("import"))
        assertTrue(afterReply.consume("export"))
    }

    @Test fun cancellingAFailedBroadcastDoesNotCancelAnEarlierOperation() {
        val requests = SessionRequestState(setOf("export", "failed-import"))
        assertTrue(requests.consume("failed-import"))
        assertEquals(setOf("export"), requests.snapshot())
        assertTrue(requests.consume("export"))
    }

    @Test fun loadingStateDoesNotMutateTheStoredSet() {
        val stored = mutableSetOf("export", "import")
        val requests = SessionRequestState(stored)
        assertTrue(requests.consume("export"))
        assertEquals(setOf("export", "import"), stored)
    }

    @Test fun typedRepliesKeepTheirPurposeAcrossProcessRecreation() {
        val requests = SessionRequestState()
        requests.register("export", SessionRequestKind.EXPORT)
        requests.register("import", SessionRequestKind.IMPORT)
        requests.register("save", SessionRequestKind.DIAGNOSTICS_SAVE)
        requests.register("share", SessionRequestKind.DIAGNOSTICS_SHARE)
        val restored = SessionRequestState(requests.snapshot(), requests.kindsSnapshot())

        assertEquals(SessionRequestKind.DIAGNOSTICS_SHARE, restored.kindOf("share"))
        assertTrue(restored.kindOf("import")!!.requiresRestart(ok = true))
        assertFalse(restored.kindOf("import")!!.requiresRestart(ok = false))
        assertFalse(restored.kindOf("export")!!.requiresRestart(ok = true))
        assertFalse(restored.kindOf("save")!!.requiresRestart(ok = true))
        assertTrue(restored.consume("import"))
        assertEquals(SessionRequestKind.EXPORT, restored.kindOf("export"))
        assertEquals(null, restored.kindOf("import"))
    }

    @Test fun aLegacyUntypedReplyCannotBeMistakenForAnImport() {
        val requests = SessionRequestState(setOf("legacy"))
        assertEquals(SessionRequestKind.LEGACY, requests.kindOf("legacy"))
        assertFalse(requests.kindOf("legacy")!!.requiresRestart(ok = true))
    }
}
