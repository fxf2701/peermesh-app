package app.peermesh.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupKeyDeriverTest {
    @Test
    fun deriveGroupKey_isDeterministic() {
        val passphrase = "secret".toCharArray()
        val salt = byteArrayOf(1, 2, 3, 4)
        val first = GroupKeyDeriver.deriveGroupKey(passphrase, salt)
        val second = GroupKeyDeriver.deriveGroupKey(passphrase, salt)
        assertEquals(first.toList(), second.toList())
    }
}
