package app.peermesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUserProfileValidatorTest {
    @Test
    fun `sanitizeDisplayName trims and collapses whitespace`() {
        assertEquals("River Fox", LocalUserProfileValidator.sanitizeDisplayName("  River   Fox  "))
    }

    @Test
    fun `validateDisplayName requires 2 to 32 chars after trim`() {
        assertEquals("required", LocalUserProfileValidator.validateDisplayName("   "))
        assertEquals("too_short", LocalUserProfileValidator.validateDisplayName("A"))
        assertEquals("too_long", LocalUserProfileValidator.validateDisplayName("a".repeat(33)))
        assertNull(LocalUserProfileValidator.validateDisplayName("Mesh Friend"))
    }

    @Test
    fun `isValidDisplayName matches validator`() {
        assertFalse(LocalUserProfileValidator.isValidDisplayName(" "))
        assertFalse(LocalUserProfileValidator.isValidDisplayName("X"))
        assertTrue(LocalUserProfileValidator.isValidDisplayName("Nearby Fox"))
    }
}
