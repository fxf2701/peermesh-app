package app.peermesh.security.groupinvitation

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class P256Sec1PublicKeyCodecTest {
    @Test
    fun encodeDecode_roundTripsValidP256PointInCanonicalFormat() {
        val encoded = P256Sec1PublicKeyCodec.encode(p256PublicKey())

        assertEquals(65, encoded.size)
        assertEquals(0x04, encoded[0].toInt())
        assertEquals(32, encoded.copyOfRange(1, 33).size)
        assertEquals(32, encoded.copyOfRange(33, 65).size)
        assertArrayEquals(encoded, P256Sec1PublicKeyCodec.encode(P256Sec1PublicKeyCodec.decode(encoded)))
    }

    @Test
    fun encode_leftPadsShortCoordinateAndStripsBigIntegerSignByte() {
        var leadingZero: ByteArray? = null
        var highBit: ByteArray? = null
        for (attempt in 0 until 10_000) {
            val encoded = P256Sec1PublicKeyCodec.encode(p256PublicKey())
            if (encoded[1] == 0.toByte() || encoded[33] == 0.toByte()) leadingZero = encoded
            if (encoded[1].toInt() and 0x80 != 0 || encoded[33].toInt() and 0x80 != 0) highBit = encoded
            if (leadingZero != null && highBit != null) break
        }

        assertTrue("Expected a coordinate requiring zero padding", leadingZero != null)
        assertTrue("Expected a coordinate whose BigInteger has a sign byte", highBit != null)
        val zeroPaddedPoint = requireNotNull(leadingZero)
        val signBytePoint = requireNotNull(highBit)
        assertEquals(65, zeroPaddedPoint.size)
        assertEquals(65, signBytePoint.size)
        P256Sec1PublicKeyCodec.validate(zeroPaddedPoint)
        P256Sec1PublicKeyCodec.validate(signBytePoint)
    }

    @Test
    fun validate_rejectsWrongLengthsAndNonCanonicalPrefixes() {
        listOf(64, 66).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) {
                P256Sec1PublicKeyCodec.validate(ByteArray(length))
            }
        }
        listOf(0x00, 0x02, 0x03, 0x06, 0x07).forEach { prefix ->
            val candidate = validPoint().apply { this[0] = prefix.toByte() }
            assertThrows(IllegalArgumentException::class.java) {
                P256Sec1PublicKeyCodec.validate(candidate)
            }
        }
    }

    @Test
    fun validate_rejectsZeroCoordinatesAndPointAtInfinityRepresentation() {
        val zeroPoint = ByteArray(65).apply { this[0] = 0x04 }
        assertThrows(IllegalArgumentException::class.java) {
            P256Sec1PublicKeyCodec.validate(zeroPoint)
        }
        assertThrows(IllegalArgumentException::class.java) {
            P256Sec1PublicKeyCodec.validate(ByteArray(1))
        }
    }

    @Test
    fun validate_rejectsCoordinateAtFieldPrimeAndOffCurvePoint() {
        val p = (P256Sec1PublicKeyCodec.p256Spec().curve.field as java.security.spec.ECFieldFp).p
        val outOfRange = validPoint()
        copyUnsigned32(p, outOfRange, 1)
        assertThrows(IllegalArgumentException::class.java) {
            P256Sec1PublicKeyCodec.validate(outOfRange)
        }

        val offCurve = ByteArray(65).apply {
            this[0] = 0x04
            this[32] = 0x01
            this[64] = 0x01
        }
        assertThrows(IllegalArgumentException::class.java) {
            P256Sec1PublicKeyCodec.validate(offCurve)
        }
    }

    private fun validPoint(): ByteArray = P256Sec1PublicKeyCodec.encode(p256PublicKey())

    private fun p256PublicKey(): ECPublicKey = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair().public as ECPublicKey
    }

    private fun copyUnsigned32(value: BigInteger, destination: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val unsigned = if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33) else bytes
        assertEquals(32, unsigned.size)
        unsigned.copyInto(destination, offset)
    }
}
