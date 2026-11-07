package app.peermesh.core.encoding

import android.util.Base64
import java.util.Base64 as JvmBase64

object Base64Url {
    private const val ENCODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val DECODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP

    fun encode(data: ByteArray): String {
        return try {
            Base64.encodeToString(data, ENCODE_FLAGS)
        } catch (_: RuntimeException) {
            JvmBase64.getUrlEncoder().withoutPadding().encodeToString(data)
        }
    }

    fun decode(encoded: String): ByteArray {
        return try {
            Base64.decode(encoded, DECODE_FLAGS)
        } catch (_: RuntimeException) {
            JvmBase64.getUrlDecoder().decode(encoded)
        }
    }
}
