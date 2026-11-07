package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.beta.BetaProgramConfig
import app.peermesh.beta.PeerMeshBetaProgram

class BetaProgramWhatsNewActivity : AppCompatActivity() {
    private lateinit var versionText: TextView
    private lateinit var changelogText: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var retryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beta_whats_new)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        versionText = findViewById(R.id.beta_whats_new_version)
        changelogText = findViewById(R.id.beta_whats_new_changelog)
        emptyStateText = findViewById(R.id.beta_whats_new_empty)
        retryButton = findViewById(R.id.beta_whats_new_retry)

        headerTitle.text = getString(R.string.beta_whats_new_title)
        headerMenu.setImageResource(R.drawable.ic_close)
        headerMenu.contentDescription = getString(R.string.close)
        headerMenu.setOnClickListener { finish() }

        retryButton.setOnClickListener { refresh() }
        render(PeerMeshBetaProgram.currentConfig())
        refresh()
    }

    private fun refresh() {
        retryButton.isEnabled = false
        Thread {
            val result = PeerMeshBetaProgram.refresh(applicationContext)
            runOnUiThread {
                retryButton.isEnabled = true
                render(result.config)
            }
        }.start()
    }

    private fun render(config: BetaProgramConfig?) {
        val latestVersion = config?.latestVersion?.takeIf { it.isNotBlank() }
        versionText.text = if (latestVersion != null) {
            getString(R.string.beta_whats_new_version, latestVersion)
        } else {
            getString(R.string.beta_whats_new_version_unknown)
        }

        if (config?.changelog.isNullOrEmpty()) {
            changelogText.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = getString(R.string.beta_whats_new_empty)
            return
        }

        changelogText.visibility = View.VISIBLE
        emptyStateText.visibility = View.GONE
        changelogText.text = config?.changelog.orEmpty().joinToString(separator = "\n") { "\u2022 $it" }
    }
}
