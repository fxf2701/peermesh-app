package app.peermesh

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalUserProfileUiTest {
    @Test
    fun `resolveDisplayName prefers local profile for local user`() {
        val name = LocalUserProfileUi.resolveDisplayName(
            rawDisplayName = null,
            isLocalUser = true,
            localProfile = LocalUserProfile(displayName = "Trail Owl", avatarId = "bird_owl", updatedAt = 1L),
        )

        assertEquals("Trail Owl", name)
    }

    @Test
    fun `resolveDisplayName uses friendly fallback for missing remote name`() {
        assertEquals("Nearby User", LocalUserProfileUi.resolveDisplayName(rawDisplayName = " "))
    }
}
