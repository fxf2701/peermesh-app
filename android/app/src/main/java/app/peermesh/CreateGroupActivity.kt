package app.peermesh

import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.id.GidFactory
import app.peermesh.core.id.GroupKeyIdFactory
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.GroupEntity
import app.peermesh.security.crypto.GroupKeyDeriver
import app.peermesh.security.crypto.SecureKeyStore
import java.security.SecureRandom

class CreateGroupActivity : AppCompatActivity() {
    private val localUserProfileStore by lazy { LocalUserProfileStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_group)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        headerTitle.text = getString(R.string.group_create_title)
        headerMenu.setOnClickListener { finish() }

        val nameInput = findViewById<EditText>(R.id.group_name_input)
        val displayNameInput = findViewById<EditText>(R.id.group_display_name_input)
        val environmentGroup = findViewById<RadioGroup>(R.id.group_environment_group)
        val environmentUrban = findViewById<RadioButton>(R.id.group_environment_urban)
        val environmentOutdoor = findViewById<RadioButton>(R.id.group_environment_outdoor)
        val avatarCat = findViewById<TextView>(R.id.avatar_cat)
        val avatarDog = findViewById<TextView>(R.id.avatar_dog)
        val avatarFox = findViewById<TextView>(R.id.avatar_fox)
        val avatarBear = findViewById<TextView>(R.id.avatar_bear)
        val avatarRow = findViewById<LinearLayout>(R.id.user_avatar_row)
        val createButton = findViewById<Button>(R.id.group_create_button)
        nameInput.filters = arrayOf(InputFilter.LengthFilter(GROUP_NAME_MAX_LEN))
        displayNameInput.filters = arrayOf(InputFilter.LengthFilter(DISPLAY_NAME_MAX_LEN))

        val globalProfile = localUserProfileStore.load()
        displayNameInput.setText(globalProfile?.displayName.orEmpty())
        var currentAvatarId: String? = globalProfile?.avatarId
        updateAvatarSelection(currentAvatarId, avatarCat, avatarDog, avatarFox, avatarBear)
        environmentGroup.check(environmentUrban.id)

        createButton.setOnClickListener {
            val name = nameInput.text.toString()
            val rawNickname = displayNameInput.text.toString()
            val displayName = resolveGroupNickname(rawNickname)
            if (displayName == null) {
                toast(getString(R.string.group_display_name_required))
                return@setOnClickListener
            }
            if (rawNickname.length > DISPLAY_NAME_MAX_LEN) {
                toast(getString(R.string.group_display_name_too_long))
                return@setOnClickListener
            }
            if (name.length > GROUP_NAME_MAX_LEN) {
                toast(getString(R.string.group_name_too_long))
                return@setOnClickListener
            }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val passphrase = generateJoinPassphrase()
            Thread {
                val environment = when (environmentGroup.checkedRadioButtonId) {
                    environmentOutdoor.id -> MapKinds.PROFILE_OUTDOOR
                    else -> MapKinds.PROFILE_URBAN
                }
                val ok = createGroup(passphrase, salt, name, displayName, currentAvatarId, environment)
                runOnUiThread {
                    if (ok) {
                        val gid = GidFactory.derive(passphrase.toCharArray(), salt)
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_LAST_JOINED_GID, gid)
                            .apply()
                        val detailIntent = Intent(this, GroupDetailActivity::class.java).apply {
                            putExtra(GroupDetailActivity.EXTRA_GID, gid)
                        }
                        startActivity(detailIntent)
                        finish()
                    } else {
                        toast("Create group failed")
                    }
                }
            }.start()
        }

        val openAvatarPicker = {
            showAvatarPicker(currentAvatarId) { selectedAvatarId ->
                currentAvatarId = selectedAvatarId
                updateAvatarSelection(currentAvatarId, avatarCat, avatarDog, avatarFox, avatarBear)
            }
        }
        avatarRow.setOnClickListener { openAvatarPicker() }
        avatarCat.setOnClickListener { openAvatarPicker() }
        avatarDog.setOnClickListener { openAvatarPicker() }
        avatarFox.setOnClickListener { openAvatarPicker() }
        avatarBear.setOnClickListener { openAvatarPicker() }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    private fun createGroup(
        passphrase: String,
        salt: ByteArray,
        name: String,
        displayName: String,
        avatarId: String?,
        environmentProfile: String,
    ): Boolean {
        return try {
            val db = AppDatabaseProvider.get(applicationContext)
            val groupKey = GroupKeyDeriver.deriveGroupKey(passphrase.toCharArray(), salt)
            val gid = GidFactory.derive(passphrase.toCharArray(), salt)
            val now = System.currentTimeMillis() / 1000L
            val kid = GroupKeyIdFactory.fromKey(groupKey, salt)
            val groupName = name.takeIf { it.isNotBlank() } ?: "Group ${gid.take(6)}"
            val ownerDid = DeviceIdProvider.get(applicationContext)
            val group = GroupEntity(
                gid = gid,
                kid = kid,
                name = groupName,
                owner_did = ownerDid,
                context_profile = environmentProfile,
                created_at = now,
                updated_at = now,
            )
            db.runInTransaction {
                db.keyDao().upsert(
                    app.peermesh.data.db.entity.KeyEntity(
                        gid = gid,
                        kid = kid,
                        created_at = now,
                        updated_at = now,
                    )
                )
                db.groupDao().upsert(group)
            }
            val secureStore = SecureKeyStore(applicationContext)
            secureStore.storeGroupKey(gid, groupKey)
            secureStore.storeSalt(gid, salt)
            secureStore.storePassphrase(gid, passphrase)
            secureStore.storeGroupKeyVersion(gid, kid, groupKey)
            SysEventWriter.updateGroup(applicationContext, gid, groupName)
            SysEventWriter.updateUserProfile(applicationContext, gid, displayName, avatarId)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun resolveGroupNickname(rawValue: String): String? {
        val sanitized = rawValue.trim()
        if (sanitized.isNotBlank()) return sanitized
        return localUserProfileStore.load()?.displayName?.takeIf { it.isNotBlank() }
    }

    private fun generateJoinPassphrase(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64Url.encode(bytes)
    }

    companion object {
        private const val GROUP_NAME_MAX_LEN = 40
        private const val DISPLAY_NAME_MAX_LEN = 40
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_LAST_JOINED_GID = "last_joined_gid"
    }

    private fun updateAvatarSelection(
        selected: String?,
        cat: TextView,
        dog: TextView,
        fox: TextView,
        bear: TextView,
    ) {
        val safeSelected = selected ?: AvatarUtils.AVATAR_CAT
        cat.text = AvatarUtils.avatarEmojiForId(this, safeSelected)
        dog.text = "▾"
        fox.text = ""
        bear.text = ""
        cat.contentDescription = getString(R.string.avatar_selected)
        dog.contentDescription = getString(R.string.avatar_choose)
        fox.visibility = View.GONE
        bear.visibility = View.GONE
        setAvatarSelected(cat, true)
        setAvatarSelected(dog, false)
    }

    private fun showAvatarPicker(
        selected: String?,
        onSelected: (String) -> Unit,
    ) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        AvatarUtils.loadAvatarGroups(this).forEach { group ->
            val title = TextView(this).apply {
                text = group.title
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
            }
            container.addView(title)

            val grid = GridLayout(this).apply {
                columnCount = 6
                useDefaultMargins = false
            }

            group.avatars.forEach { avatar ->
                val item = TextView(this).apply {
                    text = avatar.emoji
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                    contentDescription = avatar.label
                    setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setAvatarSelected(this, avatar.id == selected)
                    setOnClickListener {
                        onSelected(avatar.id)
                        avatarPickerDialog?.dismiss()
                    }
                }
                val size = (48 * density).toInt()
                val margin = (4 * density).toInt()
                val params = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                grid.addView(item, params)
            }
            container.addView(grid)
        }

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        avatarPickerDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.avatar_picker_title))
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var avatarPickerDialog: AlertDialog? = null

    private fun setAvatarSelected(button: TextView, selected: Boolean) {
        val density = button.resources.displayMetrics.density
        val surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        val border = resolveThemeColor(R.attr.peerMeshMenuDivider)
        val accent = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        button.alpha = if (selected) 1.0f else 0.72f
        button.background = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(surface)
            setStroke((1f * density).toInt().coerceAtLeast(1), if (selected) accent else border)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedArray = obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        return color
    }
}
