package app.peermesh.core.id

import org.junit.Assert.assertNotEquals
import org.junit.Test

class EntityIdFactoryTest {
    @Test
    fun markerId_changesWithNonce() {
        val first = EntityIdFactory.markerId("gid", "device", 123L, ByteArray(16) { 1 })
        val second = EntityIdFactory.markerId("gid", "device", 123L, ByteArray(16) { 2 })
        assertNotEquals(first, second)
    }

    @Test
    fun areaId_changesWithNonce() {
        val first = EntityIdFactory.areaId("gid", "device", 123L, ByteArray(16) { 1 })
        val second = EntityIdFactory.areaId("gid", "device", 123L, ByteArray(16) { 2 })
        assertNotEquals(first, second)
    }
}
