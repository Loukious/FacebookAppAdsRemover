package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedGuardCacheEntryTest {
    @Test fun everyPersistedRoleKeepsItsDelimiter() {
        for (role in listOf("csr:", "late:", "comp:", "wrap:")) {
            val parsed = FeedGuardCacheEntry.parse(role + "X.Obfuscated")!!
            assertEquals(role, parsed.role)
            assertEquals("X.Obfuscated", parsed.className)
        }
    }

    @Test fun malformedEntriesFailClosed() {
        assertNull(FeedGuardCacheEntry.parse("X.Obfuscated"))
        assertNull(FeedGuardCacheEntry.parse(":X.Obfuscated"))
        assertNull(FeedGuardCacheEntry.parse("csr:"))
    }
}
