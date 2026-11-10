package tn.loukious.facebookappadsremover.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessFilterTest {
    @Test
    fun mainProcessIsHooked() {
        assertFalse(ProcessFilter.isSkipped("com.facebook.katana"))
        assertFalse(ProcessFilter.isSkipped(null))
    }

    @Test
    fun uiCapableChildProcessesAreHooked() {
        assertFalse(ProcessFilter.isSkipped("com.facebook.katana:quicksilver"))
        assertFalse(ProcessFilter.isSkipped("com.facebook.katana:adnw"))
        assertFalse(ProcessFilter.isSkipped("com.facebook.katana:mhe"))
    }

    @Test
    fun backgroundProcessesAreSkipped() {
        assertTrue(ProcessFilter.isSkipped("com.facebook.katana:fdidsync"))
        assertTrue(ProcessFilter.isSkipped("com.facebook.katana:fbns"))
        assertTrue(ProcessFilter.isSkipped("com.facebook.katana:notification"))
        assertTrue(ProcessFilter.isSkipped(
            "com.facebook.katana:zygote_warmer:com.facebook.browser.helium.preload.AppZygoteWarmerService"))
        assertTrue(ProcessFilter.isSkipped(
            "com.facebook.katana:sandboxed_process0:com.facebook.browser.helium.content.SandboxedProcessService"))
        assertTrue(ProcessFilter.isSkipped("com.facebook.katana:he_sandboxed_process1"))
    }

    @Test
    fun otherPackagesAreNotAffected() {
        assertFalse(ProcessFilter.isSkipped("com.facebook.orca:fdidsync"))
    }
}
