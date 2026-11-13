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
}
