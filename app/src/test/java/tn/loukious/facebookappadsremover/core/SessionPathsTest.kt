package tn.loukious.facebookappadsremover.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun acceptsExportedSessionFiles() {
        val data = tmp.newFolder("data")
        for (rel in listOf(
            "app_light_prefs/com.facebook.katana/authentication",
            "app_light_prefs/com.facebook.katana/logged_in_123",
            "app_light_prefs/com.facebook.katana/dbl_local_auth_123",
            "app_config_experiment/logged_in_user_ids",
        )) {
            assertEquals(rel, File(data, rel), SessionPaths.sessionTarget(data, rel))
        }
    }

    @Test
    fun rejectsTraversalAbsoluteAndForeignFiles() {
        val data = tmp.newFolder("data")
        for (rel in listOf(
            "",
            "/etc/authentication",
            "../authentication",
            "app_light_prefs/../../authentication",
            "app_light_prefs/./authentication",
            "app_light_prefs//authentication",
            "lib/libevil.so",
            "dex/z-123.zip",
            "shared_prefs/prefs.xml",
        )) {
            assertNull(rel, SessionPaths.sessionTarget(data, rel))
        }
    }
}
