package app.peermesh.security.groupinvitation

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

object P256Sec1PublicKeyCodec {
    const val ENCODED_BYTES = 65
    private const val COORDINATE_BYTES = 32
    private const val UNCOMPRESSED_PREFIX: Byte = 0x04
    private val ZERO = BigInteger.ZERO

    fun encode(publicKey: PublicKey): ByteArray {
        val ecPublicKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Recipient public key must be an EC public key")
        require(isP256(ecPublicKey.params)) { "Recipient public key is not P-256" }
        require(ecPublicKey.w != ECPoint.POINT_INFINITY) { "P-256 point at infinity is not encodable" }
        val x = encodeCoordinate(ecPublicKey.w.affineX)
        val y = encodeCoordinate(ecPublicKey.w.affineY)
        return (byteArrayOf(UNCOMPRESSED_PREFIX) + x + y).also(::validate)
    }

    fun decode(encoded: ByteArray): ECPublicKey {
        validate(encoded)
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val spec = p256Spec()
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    fun validate(encoded: ByteArray) {
        require(encoded.size == ENCODED_BYTES) { "P-256 SEC1 public key must be 65 bytes" }
        require(encoded[0] == UNCOMPRESSED_PREFIX) { "P-256 SEC1 public key must be uncompressed" }
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val spec = p256Spec()
        val field = spec.curve.field
        val p = (field as? java.security.spec.ECFieldFp)?.p
            ?: throw IllegalStateException("P-256 must use a prime field")
        require(x > ZERO && y > ZERO) { "P-256 point coordinates must be non-zero" }
        require(x < p && y < p) { "P-256 point coordinate is out of range" }
        val a = spec.curve.a
        val b = spec.curve.b
        val left = y.modPow(BigInteger.valueOf(2L), p)
        val right = x.modPow(BigInteger.valueOf(3L), p)
            .add(a.multiply(x))
            .add(b)
            .mod(p)
        require(left == right) { "P-256 point is not on the curve" }
    }

    internal fun p256Spec(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun encodeCoordinate(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "EC coordinate must not be negative" }
        val raw = value.toByteArray()
        val normalized = when {
            raw.size == COORDINATE_BYTES -> raw
            raw.size == COORDINATE_BYTES + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < COORDINATE_BYTES -> ByteArray(COORDINATE_BYTES - raw.size) + raw
            else -> throw IllegalArgumentException("EC coordinate does not fit P-256")
        }
        require(normalized.size == COORDINATE_BYTES)
        return normalized
    }

    private fun isP256(actual: ECParameterSpec): Boolean {
        val expected = p256Spec()
        return actual.curve.field == expected.curve.field &&
            actual.curve.a == expected.curve.a &&
            actual.curve.b == expected.curve.b &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
    }
}
