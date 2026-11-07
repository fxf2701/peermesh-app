package app.peermesh

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import app.peermesh.beta.BetaProgramConfig
import app.peermesh.beta.PeerMeshBetaProgram
import com.google.android.material.navigation.NavigationView

class AboutActivity : AppCompatActivity() {
    private val betaProgramListener: (BetaProgramConfig?) -> Unit = { config ->
        runOnUiThread { renderBetaProgram(config) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val versionText = findViewById<TextView>(R.id.about_version)
        val betaVersionText = findViewById<TextView>(R.id.about_beta_version)
        val supportEmail = findViewById<TextView>(R.id.about_support_email)
        val betaInfoButton = findViewById<Button>(R.id.about_beta_info_button)
        val betaWhatsNewButton = findViewById<Button>(R.id.about_beta_whats_new_button)
        val sharedAwarenessButton = findViewById<Button>(R.id.about_shared_awareness_button)
        val emailButton = findViewById<Button>(R.id.about_support_email_button)
        val siteButton = findViewById<Button>(R.id.about_support_site_button)
        val privacyReadButton = findViewById<Button>(R.id.about_privacy_read_button)
        val privacyButton = findViewById<Button>(R.id.about_privacy_button)
        val termsButton = findViewById<Button>(R.id.about_terms_button)
        val ossButton = findViewById<Button>(R.id.about_oss_button)
        val noticesButton = findViewById<Button>(R.id.about_notices_button)

        headerTitle.text = getString(R.string.page_title_about)
        headerMenu.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        val headerView = navView.getHeaderView(0)
        ViewCompat.setOnApplyWindowInsetsListener(headerView) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(
                view.paddingLeft,
                topInset + resources.getDimensionPixelSize(R.dimen.drawer_statusbar_padding),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        PeerMeshFeatureUi.applyDrawerCapabilities(navView)
        navView.setCheckedItem(R.id.menu_about)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> startActivity(Intent(this, MainActivity::class.java))
                R.id.menu_nearby_rooms -> startActivity(Intent(this, NearbyRoomsActivity::class.java))
                R.id.menu_trails -> PeerMeshFeatureUi.openTrailOrPaywall(this)
                R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menu_statistics -> startActivity(Intent(this, StatisticsActivity::class.java))
                R.id.menu_test -> startActivity(Intent(this, TestActivity::class.java))
                R.id.menu_about -> {}
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }

        val versionLabel = getString(
            R.string.about_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        versionText.text = versionLabel
        betaVersionText.text = getString(R.string.settings_beta_version, BuildConfig.VERSION_NAME)

        betaInfoButton.setOnClickListener {
            startActivity(Intent(this, BetaProgramInfoActivity::class.java))
        }
        betaWhatsNewButton.setOnClickListener {
            startActivity(Intent(this, BetaProgramWhatsNewActivity::class.java))
        }
        sharedAwarenessButton.setOnClickListener {
            openExternalUrl(getString(R.string.about_shared_awareness_url))
        }

        emailButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${supportEmail.text}")
            }
            startActivity(intent)
        }
        siteButton.setOnClickListener {
            openExternalUrl(getString(R.string.about_support_site_url))
        }

        privacyReadButton.setOnClickListener {
            openExternalUrl(getString(R.string.about_privacy_url))
        }

        privacyButton.setOnClickListener {
            openExternalUrl(getString(R.string.about_privacy_url))
        }
        termsButton.setOnClickListener {
            openExternalUrl(getString(R.string.about_terms_url))
        }
        ossButton.setOnClickListener {
            openOssLicenses()
        }
        noticesButton.setOnClickListener {
            startActivity(Intent(this, AdditionalNoticesActivity::class.java))
        }

        renderBetaProgram(PeerMeshBetaProgram.currentConfig())
        PeerMeshBetaProgram.addListener(betaProgramListener)
        PeerMeshBetaProgram.refreshAsyncIfStale(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    override fun onDestroy() {
        PeerMeshBetaProgram.removeListener(betaProgramListener)
        super.onDestroy()
    }

    private fun renderBetaProgram(config: BetaProgramConfig?) {
        val seasonText = findViewById<TextView>(R.id.about_beta_season)
        val season = config?.season?.takeIf { it.isNotBlank() }
        seasonText.visibility = if (season != null) android.view.View.VISIBLE else android.view.View.GONE
        if (season != null) {
            seasonText.text = getString(R.string.settings_beta_season, season)
        }
    }

    private fun openExternalUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.about_link_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
        }
        startActivity(intent)
    }

    private fun openOssLicenses() {
        val className = "com.google.android.gms.oss.licenses.OssLicensesMenuActivity"
        val title = getString(R.string.page_title_open_source)

        runCatching {
            val clazz = Class.forName(className)
            clazz
                .getMethod("setActivityTitle", String::class.java)
                .invoke(null, title)

            startActivity(
                Intent(this, clazz).apply {
                    setPackage(packageName)
                }
            )
        }.onFailure {
            Toast.makeText(
                this,
                R.string.about_link_missing,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
