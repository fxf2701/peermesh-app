package app.peermesh.core.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GidFactoryTest {
    @Test
    fun derive_isDeterministic() {
        val passphrase = "pass".toCharArray()
        val salt = byteArrayOf(1, 2, 3)
        val first = GidFactory.derive(passphrase, salt)
        val second = GidFactory.derive(passphrase, salt)
        assertEquals(first, second)
    }

    @Test
    fun derive_changesWithSalt() {
        val passphrase = "pass".toCharArray()
        val first = GidFactory.derive(passphrase, byteArrayOf(1, 2, 3))
        val second = GidFactory.derive(passphrase, byteArrayOf(9, 8, 7))
        assertNotEquals(first, second)
    }
}
