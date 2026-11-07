package app.peermesh

import android.os.Bundle
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity

class QrScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<ImageButton>(R.id.qr_scan_close)?.setOnClickListener {
            finish()
        }
    }
}
