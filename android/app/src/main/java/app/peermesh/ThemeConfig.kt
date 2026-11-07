package app.peermesh

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View

object ThemeConfig {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_THEME = "app_theme"

    enum class AppTheme(
        val id: String,
        val themeRes: Int,
        val isLight: Boolean,
    ) {
        PEER_MESH_AMBER("peer_mesh_amber", R.style.Theme_PeerMesh, false),
        PEER_MESH_LIGHT("peer_mesh_light", R.style.Theme_PeerMesh_Light, true);
    }

    fun apply(activity: Activity) {
        val theme = load(activity)
        activity.setTheme(theme.themeRes)
        applySystemBars(activity, theme.isLight)
    }

    fun applySystemBars(activity: Activity) {
        applySystemBars(activity, load(activity).isLight)
    }

    private fun applySystemBars(activity: Activity, isLight: Boolean) {
        val window = activity.window

        val backgroundColor =
            resolveColor(activity, android.R.attr.colorBackground)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        var flags = window.decorView.systemUiVisibility

        flags = if (isLight) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (isLight) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }

        window.decorView.systemUiVisibility = flags
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    fun load(context: Context): AppTheme {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppTheme.PEER_MESH_AMBER.id)

        return AppTheme.values().firstOrNull { it.id == value }
            ?: AppTheme.PEER_MESH_AMBER
    }

    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }
}