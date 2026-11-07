package app.peermesh.security.crypto

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.AppSig

object AppBatchCrypto {
    fun sign(batch: AppBatchEnvelope): AppBatchEnvelope {
        val mac = Base64Url.encode(AppMac.computeMac(batch))
        return batch.copy(app_sig = AppSig(mac = mac))
    }

    fun verify(batch: AppBatchEnvelope): Boolean {
        val sig = batch.app_sig ?: return false
        val expected = Base64Url.decode(sig.mac)
        return AppMac.verifyMac(batch, expected)
    }
}
