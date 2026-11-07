package app.peermesh.data.relay

import app.peermesh.core.model.EventCore
import app.peermesh.security.crypto.RelayPayloadCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RelayMapIdentityM2D2ContractTest {
    private val mapper = RelayEventMapper(
        groupKeyLoader = { gid -> M2D2RelayMapFixtures.groupKey.takeIf { gid == M2D2RelayMapFixtures.GID } },
        nowSeconds = { 1_800_000_000L },
    )

    @Test
    fun canonicalAndDedicatedMapEventsPreserveCompleteIdentityThroughRelayMapping() {
        val cores = listOf(
            M2D2RelayMapFixtures.markerAdd(),
            M2D2RelayMapFixtures.markerUpdate(),
            M2D2RelayMapFixtures.areaAdd(),
            M2D2RelayMapFixtures.confirmation(),
            M2D2RelayMapFixtures.situationUpdate(),
        )

        cores.forEachIndexed { index, original ->
            val relay = mapper.toRelayEvent(M2D2RelayMapFixtures.entity(original, rowId = index + 1L))
            val decoded = assertMappedAndDecrypt(relay)

            assertEquals(original, decoded)
            assertEquals(original.id, decoded.id)
            assertEquals(original.type, decoded.type)
            assertEquals(original.actor, decoded.actor)
            assertEquals(original.time, decoded.time)
            assertEquals(original.ref, decoded.ref)
            assertEquals(original.meta, decoded.meta)
            assertEquals(original.data, decoded.data)
        }
    }

    @Test
    fun relayMappingPreservesCanonicalSerializedSemanticsAndFingerprintInput() {
        val exact = M2D2RelayMapFixtures.markerAdd()
        val conflict = M2D2RelayMapFixtures.conflicting(exact)

        val exactDecoded = assertMappedAndDecrypt(mapper.toRelayEvent(M2D2RelayMapFixtures.entity(exact)))
        val conflictDecoded = assertMappedAndDecrypt(mapper.toRelayEvent(M2D2RelayMapFixtures.entity(conflict)))

        assertEquals(exact, exactDecoded)
        assertEquals(conflict, conflictDecoded)
        assertEquals(exact.id, conflictDecoded.id)
        assertNotEquals(exact.data, conflictDecoded.data)
    }

    @Test
    fun relayTransportExpiryDoesNotMutateMapDomainTemporalFields() {
        val core = M2D2RelayMapFixtures.markerAdd(timestamp = 1_700_000_100L)
        val relay = mapper.toRelayEvent(M2D2RelayMapFixtures.entity(core))!!
        val decoded = assertMappedAndDecrypt(relay)

        assertEquals(core.time, decoded.time)
        assertEquals(core.meta?.ttl_s, decoded.meta?.ttl_s)
        assertEquals(core.data, decoded.data)
        assertNotEquals(core.time.t, relay.exp)
    }

    @Test
    fun relayRetryProducesTheSameSemanticEventIdentity() {
        val core = M2D2RelayMapFixtures.confirmation()
        val entity = M2D2RelayMapFixtures.entity(core)

        val first = mapper.toRelayEvent(entity)
        val retry = mapper.toRelayEvent(entity)

        assertEquals(assertMappedAndDecrypt(first), assertMappedAndDecrypt(retry))
        assertEquals(first?.eid, retry?.eid)
        assertEquals(first?.gid, retry?.gid)
        assertEquals(first?.ts, retry?.ts)
        assertEquals(first?.exp, retry?.exp)
    }

    private fun assertMappedAndDecrypt(relay: app.peermesh.core.relay.RelayEvent?): EventCore {
        assertNotNull(relay)
        val verified = RelayPayloadCrypto.verifyAndDecrypt(relay!!, M2D2RelayMapFixtures.groupKey)
        assertNotNull(verified)
        return verified!!.core
    }
}
