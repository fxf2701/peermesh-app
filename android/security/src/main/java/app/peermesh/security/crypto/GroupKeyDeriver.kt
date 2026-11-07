package app.peermesh.security.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.charset.StandardCharsets
import app.peermesh.core.id.GidFactory

object GroupKeyDeriver {
    private const val N = 1 shl 15
    private const val R = 8
    private const val P = 1

    fun deriveGroupKey(passphrase: CharArray, salt: ByteArray, keyLengthBytes: Int = 32): ByteArray {
        val passBytes = String(passphrase).toByteArray(StandardCharsets.UTF_8)
        return SCrypt.generate(passBytes, salt, N, R, P, keyLengthBytes)
    }

    fun deriveGroupId(passphrase: CharArray, salt: ByteArray): String {
        return GidFactory.derive(passphrase, salt)
    }
}
