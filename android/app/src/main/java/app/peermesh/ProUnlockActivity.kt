package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.beta.PeerMeshBetaProgram

class ProUnlockActivity : AppCompatActivity() {
    private var entitlementStatus: TextView? = null
    private var cta: Button? = null
    private var restore: Button? = null

    private val entitlementListener: (EntitlementState) -> Unit = { state ->
        runOnUiThread {
            renderEntitlement(state)
            if (state.unlocksPro) {
                Toast.makeText(this, R.string.pro_unlock_restored, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_unlock)

        val betaEnabled = PeerMeshBetaProgram.isEnabled()
        val feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val title = findViewById<TextView>(R.id.pro_unlock_title)
        val offer = findViewById<TextView>(R.id.pro_unlock_offer)
        val body = findViewById<TextView>(R.id.pro_unlock_body)
        val features = findViewById<TextView>(R.id.pro_unlock_features)
        val ctaButton = findViewById<Button>(R.id.pro_unlock_cta)
        val restoreButton = findViewById<Button>(R.id.pro_unlock_restore)
        val close = findViewById<Button>(R.id.pro_unlock_close)
        entitlementStatus = findViewById(R.id.pro_unlock_status)
        cta = ctaButton
        restore = restoreButton

        headerTitle.text = getString(R.string.pro_unlock_header)
        headerMenu.setImageResource(R.drawable.ic_close)
        headerMenu.contentDescription = getString(R.string.close)
        headerMenu.setOnClickListener { finish() }

        title.text = getString(titleFor(feature))
        body.text = getString(bodyFor(feature))
        if (betaEnabled && !PeerMeshEntitlements.isProActive()) {
            headerTitle.text = getString(R.string.beta_program_title)
            title.text = getString(R.string.beta_program_paywall_title)
            offer.text = getString(R.string.beta_program_paywall_offer)
            body.text = getString(R.string.beta_program_paywall_body)
            features.text = getString(R.string.beta_program_paywall_features)
            ctaButton.visibility = View.GONE
            restoreButton.visibility = View.GONE
        }
        renderEntitlement(PeerMeshEntitlements.current())
        ctaButton.setOnClickListener {
            PeerMeshBillingManager.launchProPurchase(this)
        }
        restoreButton.setOnClickListener {
            setBusy(true)
            PeerMeshBillingManager.refreshEntitlements(this) { state ->
                runOnUiThread {
                    setBusy(false)
                    renderEntitlement(state)
                    if (state.unlocksPro) {
                        Toast.makeText(this, R.string.pro_unlock_restored, Toast.LENGTH_SHORT).show()
                        finish()
                    } else if (state.status == EntitlementStatus.UNKNOWN) {
                        Toast.makeText(this, R.string.pro_unlock_billing_unavailable, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.pro_unlock_no_active_subscription, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        close.setOnClickListener { finish() }
        PeerMeshEntitlements.addListener(entitlementListener)
    }

    override fun onResume() {
        super.onResume()
        if (!PeerMeshBetaProgram.isEnabled() || PeerMeshEntitlements.isProActive()) {
            PeerMeshBillingManager.refreshEntitlements(this)
        }
    }

    override fun onDestroy() {
        PeerMeshEntitlements.removeListener(entitlementListener)
        super.onDestroy()
    }

    private fun setBusy(busy: Boolean) {
        cta?.isEnabled = !busy
        restore?.isEnabled = !busy
    }

    private fun renderEntitlement(state: EntitlementState) {
        if (PeerMeshBetaProgram.isEnabled() && !state.unlocksPro) {
            entitlementStatus?.text = getString(R.string.beta_program_paywall_status)
            return
        }
        entitlementStatus?.text = when {
            state.unlocksPro -> getString(R.string.pro_unlock_status_active)
            state.status == EntitlementStatus.EXPIRED -> getString(R.string.pro_unlock_status_expired)
            state.status == EntitlementStatus.UNKNOWN -> getString(R.string.pro_unlock_status_unknown)
            else -> getString(R.string.pro_unlock_status_free)
        }
    }

    private fun titleFor(feature: String): Int {
        return when (feature) {
            PeerMeshProGate.FEATURE_MAP -> R.string.pro_unlock_map_title
            PeerMeshProGate.FEATURE_TRAIL -> R.string.pro_unlock_trail_title
            PeerMeshProGate.FEATURE_SHARED_LIVE_TRAIL -> R.string.pro_unlock_shared_live_trail_title
            else -> R.string.pro_unlock_title
        }
    }

    private fun bodyFor(feature: String): Int {
        return when (feature) {
            PeerMeshProGate.FEATURE_MAP -> R.string.pro_unlock_map_body
            PeerMeshProGate.FEATURE_TRAIL -> R.string.pro_unlock_trail_body
            PeerMeshProGate.FEATURE_SHARED_LIVE_TRAIL -> R.string.pro_unlock_shared_live_trail_body
            else -> R.string.pro_unlock_body
        }
    }

    companion object {
        const val EXTRA_FEATURE = "extra_feature"
    }
}
