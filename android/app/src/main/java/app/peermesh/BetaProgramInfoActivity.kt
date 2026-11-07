package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.beta.PeerMeshBetaProgram

class BetaProgramInfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beta_program_info)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val seasonText = findViewById<TextView>(R.id.beta_program_info_season)
        val endsAtText = findViewById<TextView>(R.id.beta_program_info_ends_at)

        headerTitle.text = getString(R.string.beta_program_info_title)
        headerMenu.setImageResource(R.drawable.ic_close)
        headerMenu.contentDescription = getString(R.string.close)
        headerMenu.setOnClickListener { finish() }

        val config = PeerMeshBetaProgram.currentConfig()
        val season = config?.season?.takeIf { it.isNotBlank() }
        val endsAt = config?.endsAt?.takeIf { it.isNotBlank() }

        seasonText.visibility = if (season != null) View.VISIBLE else View.GONE
        if (season != null) {
            seasonText.text = getString(R.string.beta_program_info_season, season)
        }

        endsAtText.visibility = if (endsAt != null) View.VISIBLE else View.GONE
        if (endsAt != null) {
            endsAtText.text = getString(R.string.beta_program_info_ends_at, endsAt)
        }
    }
}
