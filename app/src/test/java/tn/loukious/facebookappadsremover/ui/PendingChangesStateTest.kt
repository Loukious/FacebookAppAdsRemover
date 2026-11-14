package tn.loukious.facebookappadsremover.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingChangesStateTest {
    @Test fun recreatedPendingStateCanApplyTheSameRevision() {
        val changed = PendingChangesState().changed()
        val restored = PendingChangesState(changed.revision, changed.pending)
        assertTrue(restored.pending)
        assertFalse(restored.applied(changed.revision).pending)
    }

    @Test fun anEditDuringRestartRemainsPending() {
        val changed = PendingChangesState().changed()
        val restartingRevision = changed.revision
        val editedDuringRestart = changed.changed()
        assertTrue(editedDuringRestart.applied(restartingRevision).pending)
        assertFalse(editedDuringRestart.applied(editedDuringRestart.revision).pending)
    }

    @Test fun anImportDuringRestartAlsoPreservesPendingChanges() {
        val request = PendingChangesState().changed()
        val afterSuccessfulImport = request.changed()
        val restored = PendingChangesState(afterSuccessfulImport.revision, afterSuccessfulImport.pending)
        assertTrue(restored.applied(request.revision).pending)
    }
}
