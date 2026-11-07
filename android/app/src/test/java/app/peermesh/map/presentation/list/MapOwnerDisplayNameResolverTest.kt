package app.peermesh.map.presentation.list

import org.junit.Assert.assertEquals
import org.junit.Test

class MapOwnerDisplayNameResolverTest {
    @Test
    fun `local owner is always shown as you`() {
        assertEquals(
            MapOwnerDisplayName.You,
            MapOwnerDisplayNameResolver.resolve(
                ownerActorDid = "local",
                activeActorDid = "local",
                projections = mapOf("local" to MapOwnerNameProjection("Mai", "Mai Settings")),
            ),
        )
    }

    @Test
    fun `group nickname takes precedence over profile name`() {
        assertEquals(
            MapOwnerDisplayName.Named("Mai in H-MfE3"),
            MapOwnerDisplayNameResolver.resolve(
                ownerActorDid = "peer",
                activeActorDid = "local",
                projections = mapOf("peer" to MapOwnerNameProjection(" Mai in H-MfE3 ", "Mai Settings")),
            ),
        )
    }

    @Test
    fun `profile name is used when group nickname is blank`() {
        assertEquals(
            MapOwnerDisplayName.Named("Mai Settings"),
            MapOwnerDisplayNameResolver.resolve(
                ownerActorDid = "peer",
                activeActorDid = "local",
                projections = mapOf("peer" to MapOwnerNameProjection("   ", " Mai Settings ")),
            ),
        )
    }

    @Test
    fun `missing names use the neutral group member fallback`() {
        assertEquals(
            MapOwnerDisplayName.GroupMember,
            MapOwnerDisplayNameResolver.resolve("peer", "local", emptyMap()),
        )
    }

    @Test
    fun `unicode and long names are preserved for the text view to ellipsize`() {
        val name = "🧭 Nguyễn Thị Mai — điều phối viên cứu hộ khu vực phía Nam"
        assertEquals(
            MapOwnerDisplayName.Named(name),
            MapOwnerDisplayNameResolver.resolve(
                ownerActorDid = "peer",
                activeActorDid = "local",
                projections = mapOf("peer" to MapOwnerNameProjection(groupNickname = name)),
            ),
        )
    }
}
