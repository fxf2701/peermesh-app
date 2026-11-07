package app.peermesh.data.map.compatibility

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Guards manually transcribed immutable fixtures, their source-derived field matrix, and hashes.
 */
class M2D3CompatibilityFixtureIntegrityTest {
    @Test
    fun legacyFixturesHaveRecordedProvenance() {
        val manifest = checkNotNull(javaClass.classLoader?.getResource("map/m2d3/FIXTURE_MANIFEST.md")).readText()
        assertTrue(manifest.contains("3f0f4b8ed4ec4c0df4dfbcbbe6e5f0c1bf88cdce"))
        assertTrue(manifest.contains("historical source-derived manual fixtures"))
        assertTrue(manifest.contains("contract-modeled old-peer projection"))
    }

    @Test
    fun legacyFixturesOmitM2OnlyFieldsByContract() {
        legacyNames.forEach { name ->
            val keys = (M2D3JvmHarness.fixture(name).data as JsonObject).keys
            assertTrue(name, keys.intersect(M2D3JvmHarness.optionalDomainFields).isEmpty())
        }
    }

    @Test
    fun everyImmutableFixtureMatchesRecordedSha256() {
        fixtureHashes.forEach { (name, expected) ->
            val url = checkNotNull(javaClass.classLoader?.getResource("map/m2d3/$name")) { name }
            val actual = MessageDigest.getInstance("SHA-256").digest(url.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun manuallyTranscribedLegacyFixturesMatchHistoricalFieldMatrix() {
        val marker = M2D3JvmHarness.fixture("legacy_marker_add_v0.json").data as JsonObject
        assertTrue(marker.keys.containsAll(setOf("marker_id", "layer_id", "lat", "lon", "confidence")))
        assertTrue(marker.keys.containsAll(setOf("removal_requested_t", "removal_cancel_t")))
        val area = M2D3JvmHarness.fixture("legacy_area_add_v0.json").data as JsonObject
        assertTrue(area.keys.containsAll(setOf("area_id", "layer_id", "geometry", "confidence")))
        assertEquals(setOf("marker_id"), (M2D3JvmHarness.fixture("legacy_marker_delete_v0.json").data as JsonObject).keys)
        assertEquals(setOf("area_id"), (M2D3JvmHarness.fixture("legacy_area_delete_v0.json").data as JsonObject).keys)
    }

    @Test
    fun fixtureIdentityValuesAreStable() {
        assertEquals("legacy-marker-add", M2D3JvmHarness.fixture(legacyNames[0]).id)
        assertEquals(11L, M2D3JvmHarness.fixture(legacyNames[0]).time.lt)
        assertEquals("legacy-area-del", M2D3JvmHarness.fixture(legacyNames.last()).id)
    }

    @Test
    fun mixedBatchFixturesContainTheDeclaredSiblingTypes() {
        val legacy = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        val domain = M2D3JvmHarness.fixture("domain_v1_area_add_for_old_peer.json")
        val action = M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json")
        assertEquals(listOf(legacy.id, domain.id, action.id), listOf(legacy, domain, action).map { it.id })
    }

    private val legacyNames = listOf(
        "legacy_marker_add_v0.json", "legacy_marker_update_v0.json", "legacy_marker_delete_v0.json",
        "legacy_area_add_v0.json", "legacy_area_update_v0.json", "legacy_area_delete_v0.json",
    )
    private val fixtureHashes = mapOf(
        "legacy_marker_add_v0.json" to "e5ae2f5e13e06aae8a699541e8f397edff9b2d1c8144c25cb5e18bd94f6b5a95",
        "legacy_marker_update_v0.json" to "a2fc8e6dc5dc898e2fe4958536180624f1be0dbcd68718fb8ba768321faf00b2",
        "legacy_marker_delete_v0.json" to "55140997a1e3b7af08fa04a01697823306c90c983bf442b835c32d7dcbbad5ae",
        "legacy_area_add_v0.json" to "0d801fbddd541cbf15bfabf99564b3848014de6e1487928790416275c03ec327",
        "legacy_area_update_v0.json" to "9e4fbe201e799d105faff33762a12a8e8aed4d59238864cf01b8561bfbf6765a",
        "legacy_area_delete_v0.json" to "3675e322348c2e43bfcfb6ef1e77adff2017ca52f8cfc82dd16defc3878baef2",
        "domain_v1_marker_add_for_old_peer.json" to "843cd87a54983b2c751ea1d89d3af3e7b8a825cebbb6186e7e4bca40a50457d7",
        "domain_v1_marker_update_for_old_peer.json" to "40d89c409e979a5383326948759c1e92b1d16a9eab81e09f46e2f774124e8482",
        "domain_v1_area_add_for_old_peer.json" to "bd311623ac96ee551021bab9ac23e8e7658b47a4fdeb1a66076d333f58ffe9d1",
        "domain_v1_area_update_for_old_peer.json" to "81544753b3ae7398dafcf23fe1c06d06634536e4a580b4295731c627b3976b2e",
        "unknown_dedicated_action_for_old_peer.json" to "f7f5142b6591b85beffc1c41031067a496c43947350432b282ae28bbe300cb14",
    )

}
