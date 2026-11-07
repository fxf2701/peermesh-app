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
import com.google.android.material.navigation.NavigationView

class AdditionalNoticesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_additional_notices)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val relaySourceButton = findViewById<Button>(R.id.relay_sync_source_button)
        val relayCreatorSiteButton = findViewById<Button>(R.id.relay_sync_creator_site_button)
        val trailSourceButton = findViewById<Button>(R.id.trail_source_button)
        val trailUpstreamButton = findViewById<Button>(R.id.trail_upstream_button)
        val mapDataLinkButton = findViewById<Button>(R.id.map_data_link_button)

        headerTitle.text = getString(R.string.page_title_additional_notices)
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
                R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }

        relaySourceButton.setOnClickListener {
            openExternalUrl(getString(R.string.additional_notices_relay_sync_source_url))
        }
        relayCreatorSiteButton.setOnClickListener {
            openExternalUrl(getString(R.string.additional_notices_relay_sync_creator_site_url))
        }
        trailSourceButton.setOnClickListener {
            openExternalUrl(getString(R.string.additional_notices_trail_source_url))
        }
        trailUpstreamButton.setOnClickListener {
            openExternalUrl(getString(R.string.additional_notices_trail_upstream_url))
        }
        mapDataLinkButton.setOnClickListener {
            openExternalUrl(getString(R.string.additional_notices_map_data_url))
        }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
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
}
