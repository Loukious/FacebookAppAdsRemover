package tn.loukious.facebookappadsremover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRequestTest {
    @Test fun aSaveRequestStillRecognizesItsReplyAfterRecreation() {
        val request = DiagnosticsRequest("save", ReportAction.SAVE, 1234L)
        val restored = DiagnosticsRequest.decode(request.encode())!!
        assertTrue(restored.accepts("save", SessionRequestKind.DIAGNOSTICS_SAVE))
        assertFalse(restored.accepts("old", SessionRequestKind.DIAGNOSTICS_SAVE))
        assertFalse(restored.accepts("save", SessionRequestKind.DIAGNOSTICS_SHARE))
        assertFalse(restored.accepts("save", SessionRequestKind.IMPORT))
    }

    @Test fun anUnhandledShareReplyAndItsFileSurviveAProcessRestart() {
        val ready = DiagnosticsRequest("share", ReportAction.SHARE, 1234L, ReportPhase.READY, "report.txt")
        assertEquals(ready, DiagnosticsRequest.decode(ready.encode()))
        assertFalse(ready.accepts("share", SessionRequestKind.DIAGNOSTICS_SHARE))
        assertFalse(ready.expired(100_000L))
    }

    @Test fun aPendingSystemPickerRetainsOnlyTheReportFileReference() {
        val picker = DiagnosticsRequest("save", ReportAction.SAVE, 1234L, ReportPhase.PICKER, "report.txt")
        assertEquals(picker, DiagnosticsRequest.decode(picker.encode()))
        assertFalse(picker.expired(100_000L))
        assertTrue(picker.encode().length < 100)
    }

    @Test fun timeoutKeepsTheOriginalDeadlineAfterRecreation() {
        val request = DiagnosticsRequest.decode(DiagnosticsRequest("save", ReportAction.SAVE, 1000L).encode())!!
        assertFalse(request.expired(6999L))
        assertTrue(request.expired(7000L))
        assertTrue(request.expired(999L)) // Uptime reset after a reboot.
    }

    @Test fun malformedAndEscapingFileReferencesCannotBeRestored() {
        for (value in listOf(null, "", "nonce\tUNKNOWN\t0\tWAITING\t", "nonce\tSAVE\t0\tPICKER\t",
            "nonce\tSAVE\t0\tREADY\t../report.txt", "nonce\tSAVE\t0\tREADY\tother\\report.txt")) {
            assertNull(DiagnosticsRequest.decode(value))
        }
    }
}
