package app.peermesh.security.crypto

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.AppBatchCanonicalizer
import app.peermesh.core.model.AppBatchEnvelope
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AppMac {
    // TODO: rotate and keep this in a secure delivery channel.
    private const val APP_HMAC_KEY_B64 = "u3pG0s0B8qjJ0wS8fOq8YxF1K7qB1w0E2h2o9x2vH30"
    private const val HMAC_ALGO = "HmacSHA256"

    fun computeMac(batch: AppBatchEnvelope): ByteArray {
        val canonical = AppBatchCanonicalizer.canonicalizeForMac(batch)
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(Base64Url.decode(APP_HMAC_KEY_B64), HMAC_ALGO))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
    }

    fun verifyMac(batch: AppBatchEnvelope, expectedMac: ByteArray): Boolean {
        val actual = computeMac(batch)
        return actual.contentEquals(expectedMac)
    }
}
