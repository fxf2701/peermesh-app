package app.peermesh.security.crypto

import app.peermesh.core.model.Actor
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeCryptoTest {
    @Test
    fun encryptAndDecrypt_roundTrip() {
        val groupKey = ByteArray(32) { 3 }
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1700000000L, lt = 1L),
            ref = EventRef(root = "thread", parent = null, target = null),
            meta = null,
            data = app.peermesh.core.json.CanonicalJson.json.parseToJsonElement("{\"text\":\"hi\"}")
        )

        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = "gid",
            mid = core.id,
            ts = 1700000000L,
            ttl = 6,
            dtn = DtnMeta(ttl_seconds = 6, hop_count = 0, max_hops = 20, priority = 3),
            core = core,
            groupKey = groupKey,
        )

        val decrypted = EnvelopeCrypto.verifyAndDecrypt(envelope, groupKey)
        assertNotNull(decrypted)
        assertEquals(core.id, decrypted?.id)
        assertEquals(core.type, decrypted?.type)
    }

    @Test
    fun verifyAndDecrypt_rejectsTamperedMac() {
        val groupKey = ByteArray(32) { 3 }
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1700000000L, lt = 1L),
            ref = EventRef(root = "thread", parent = null, target = null),
            meta = null,
            data = app.peermesh.core.json.CanonicalJson.json.parseToJsonElement("{\"text\":\"hi\"}")
        )

        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = "gid",
            mid = core.id,
            ts = 1700000000L,
            ttl = 6,
            dtn = DtnMeta(ttl_seconds = 6, hop_count = 0, max_hops = 20, priority = 3),
            core = core,
            groupKey = groupKey,
        )

        val tampered = envelope.copy(mid = "other")
        val decrypted = EnvelopeCrypto.verifyAndDecrypt(tampered, groupKey)
        assertNull(decrypted)
    }

    @Test
    fun verifyAndDecrypt_rejectsTamperedDtn() {
        val groupKey = ByteArray(32) { 3 }
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1700000000L, lt = 1L),
            ref = EventRef(root = "thread", parent = null, target = null),
            meta = null,
            data = app.peermesh.core.json.CanonicalJson.json.parseToJsonElement("{\"text\":\"hi\"}")
        )

        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = "gid",
            mid = core.id,
            ts = 1700000000L,
            ttl = 6,
            dtn = DtnMeta(ttl_seconds = 6, hop_count = 0, max_hops = 20, priority = 3),
            core = core,
            groupKey = groupKey,
        )

        val tampered = envelope.copy(dtn = envelope.dtn?.copy(priority = 0))
        val decrypted = EnvelopeCrypto.verifyAndDecrypt(tampered, groupKey)
        assertNull(decrypted)
    }
}
