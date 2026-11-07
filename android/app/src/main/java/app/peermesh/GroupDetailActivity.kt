package app.peermesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.security.crypto.SecureKeyStore
import com.journeyapps.barcodescanner.BarcodeEncoder
import app.peermesh.data.membership.GroupMembershipPolicy

class GroupDetailActivity : AppCompatActivity() {
    private var showPassphrase = false
    private var showGid = false
    private val localUserProfileStore by lazy { LocalUserProfileStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_detail)

        val gid = intent.getStringExtra(EXTRA_GID) ?: ""

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        headerTitle.text = getString(R.string.group_detail_title)
        headerMenu.setOnClickListener { finish() }

        val gidValue = findViewById<TextView>(R.id.group_gid_value)
        val removedNotice = findViewById<TextView>(R.id.group_removed_notice)
        val inviteRotationNotice = findViewById<TextView>(R.id.group_invite_rotation_notice)
        val gidShow = findViewById<ImageButton>(R.id.group_gid_show)
        val gidCopy = findViewById<ImageButton>(R.id.group_gid_copy)
        val passValue = findViewById<TextView>(R.id.group_passphrase_value)
        val passShow = findViewById<ImageButton>(R.id.group_passphrase_show)
        val passCopy = findViewById<ImageButton>(R.id.group_passphrase_copy)
        val nameValue = findViewById<TextView>(R.id.group_name_value)
        val nameEdit = findViewById<ImageButton>(R.id.group_name_edit)
        val nameEditRow = findViewById<View>(R.id.group_name_edit_row)
        val nameEditInput = findViewById<EditText>(R.id.group_name_edit_input)
        val nameSave = findViewById<ImageButton>(R.id.group_name_save)
        val nameCancel = findViewById<ImageButton>(R.id.group_name_cancel)
        val displayNameValue = findViewById<TextView>(R.id.user_display_name_value)
        val displayNameEdit = findViewById<ImageButton>(R.id.user_display_name_edit)
        val displayNameEditRow = findViewById<View>(R.id.user_display_name_edit_row)
        val displayNameEditInput = findViewById<EditText>(R.id.user_display_name_edit_input)
        val displayNameSave = findViewById<ImageButton>(R.id.user_display_name_save)
        val displayNameCancel = findViewById<ImageButton>(R.id.user_display_name_cancel)
        val avatarCat = findViewById<TextView>(R.id.avatar_cat)
        val avatarDog = findViewById<TextView>(R.id.avatar_dog)
        val avatarFox = findViewById<TextView>(R.id.avatar_fox)
        val avatarBear = findViewById<TextView>(R.id.avatar_bear)
        val avatarRow = findViewById<LinearLayout>(R.id.user_avatar_row)
        val openSettings = findViewById<Button>(R.id.group_open_settings)
        val sendInvite = findViewById<Button>(R.id.group_send_invite)
        val showQr = findViewById<Button>(R.id.group_show_qr)
        val close = findViewById<Button>(R.id.group_close)

        nameEditInput.filters = arrayOf(InputFilter.LengthFilter(GROUP_NAME_MAX_LEN))
        displayNameEditInput.filters = arrayOf(InputFilter.LengthFilter(DISPLAY_NAME_MAX_LEN))

        val db = AppDatabaseProvider.get(this)
        val secureStore = SecureKeyStore(this)
        val actorDid = DeviceIdProvider.get(this)
        Thread {
            val group = db.groupDao().findGroup(gid)
            val salt = secureStore.loadSalt(gid)
            val passphrase = secureStore.loadPassphrase(gid)
            val currentGroupKey = secureStore.loadGroupKey(gid)
            val profile = db.userProfileDao().find(gid, actorDid)
            val isRemoved = GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
            val requiresInviteRotation = GroupInvitePolicy.requiresRotation(db, gid)
            runOnUiThread {
                removedNotice.visibility = if (isRemoved) View.VISIBLE else View.GONE
                inviteRotationNotice.visibility = if (requiresInviteRotation) View.VISIBLE else View.GONE
                gidValue.text = maskValue(gid)
                nameValue.text = group?.name ?: "-"
                nameEditInput.setText(group?.name ?: "")
                passValue.text = if (requiresInviteRotation) "-" else maskValue(passphrase ?: "")
                displayNameValue.text = profile?.display_name ?: "-"
                displayNameEditInput.setText(profile?.display_name ?: "")
                var currentAvatarId = profile?.avatar_id
                updateAvatarSelection(currentAvatarId, avatarCat, avatarDog, avatarFox, avatarBear)
                val hasInvite = GroupInvitePolicy.inviteMaterialAvailable(
                    isRemoved = isRemoved,
                    hasSalt = currentGroupKey != null,
                    hasPassphrase = !group?.kid.isNullOrBlank(),
                    requiresRotation = false,
                )
                sendInvite.isEnabled = hasInvite
                showQr.isEnabled = hasInvite
                sendInvite.alpha = if (hasInvite) 1.0f else 0.4f
                showQr.alpha = if (hasInvite) 1.0f else 0.4f
                val isOwner = group?.owner_did == actorDid
                val canEditGroup = isOwner && !isRemoved
                nameEdit.isEnabled = canEditGroup
                nameEdit.alpha = if (canEditGroup) 1.0f else 0.4f
                displayNameEdit.alpha = if (isRemoved) 0.4f else 1.0f
                avatarRow.alpha = if (isRemoved) 0.4f else 1.0f
                val passphraseAvailable = !requiresInviteRotation && !passphrase.isNullOrBlank()
                passShow.isEnabled = passphraseAvailable
                passShow.alpha = if (passphraseAvailable) 1.0f else 0.4f
                passCopy.isEnabled = passphraseAvailable
                passCopy.alpha = if (passphraseAvailable) 1.0f else 0.4f

                gidShow.setOnClickListener {
                    showGid = !showGid
                    gidShow.setImageResource(if (showGid) R.drawable.ic_eye_off else R.drawable.ic_eye)
                    gidValue.text = if (showGid) gid else maskValue(gid)
                }
                gidCopy.setOnClickListener { copyToClipboard("gid", gid) }
                passCopy.setOnClickListener {
                    if (!passphraseAvailable) {
                        toast(getString(R.string.group_invite_rotation_required))
                        return@setOnClickListener
                    }
                    copyToClipboard("passphrase", passphrase ?: "")
                }
                passShow.setOnClickListener {
                    if (!passphraseAvailable) {
                        toast(getString(R.string.group_invite_rotation_required))
                        return@setOnClickListener
                    }
                    showPassphrase = !showPassphrase
                    passShow.setImageResource(if (showPassphrase) R.drawable.ic_eye_off else R.drawable.ic_eye)
                    passValue.text = if (showPassphrase) passphrase ?: "-" else maskValue(passphrase ?: "")
                }
                nameEdit.setOnClickListener {
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    if (!isOwner) {
                        toast(getString(R.string.group_name_owner_only))
                        return@setOnClickListener
                    }
                    nameEditRow.visibility = View.VISIBLE
                }
                nameCancel.setOnClickListener {
                    nameEditRow.visibility = View.GONE
                    nameEditInput.setText(group?.name ?: "")
                }
                nameSave.setOnClickListener {
                    val nextName = nameEditInput.text.toString()
                    if (nextName.length > GROUP_NAME_MAX_LEN) {
                        toast(getString(R.string.group_name_too_long))
                        return@setOnClickListener
                    }
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    if (!isOwner) {
                        toast(getString(R.string.group_name_owner_only))
                        return@setOnClickListener
                    }
                    group ?: run {
                        toast("Group not found")
                        return@setOnClickListener
                    }
                    Thread {
                        val ok = SysEventWriter.updateGroup(applicationContext, gid, nextName)
                        runOnUiThread {
                            if (ok) {
                                nameValue.text = nextName
                                nameEditRow.visibility = View.GONE
                            } else {
                                toast(
                                    if (isRemoved) getString(R.string.group_removed_detail_read_only)
                                    else "Update group name failed"
                                )
                            }
                        }
                    }.start()
                }
                displayNameEdit.setOnClickListener {
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    displayNameEditRow.visibility = View.VISIBLE
                }
                displayNameCancel.setOnClickListener {
                    displayNameEditRow.visibility = View.GONE
                    displayNameEditInput.setText(profile?.display_name ?: "")
                }
                displayNameSave.setOnClickListener {
                    val rawNickname = displayNameEditInput.text.toString()
                    val nextName = resolveGroupNickname(rawNickname)
                    if (nextName == null) {
                        toast(getString(R.string.group_display_name_required))
                        return@setOnClickListener
                    }
                    if (rawNickname.length > DISPLAY_NAME_MAX_LEN) {
                        toast(getString(R.string.group_display_name_too_long))
                        return@setOnClickListener
                    }
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    Thread {
                        val ok = SysEventWriter.updateUserProfile(
                            applicationContext,
                            gid,
                            nextName,
                            currentAvatarId
                        )
                        runOnUiThread {
                            if (ok) {
                                displayNameValue.text = nextName
                                displayNameEditInput.setText(nextName)
                                displayNameEditRow.visibility = View.GONE
                                toast(getString(R.string.group_display_name_updated))
                            } else {
                                toast(
                                    if (isRemoved) getString(R.string.group_removed_detail_read_only)
                                    else "Update display name failed"
                                )
                            }
                        }
                    }.start()
                }

                val onAvatarSelected: (String) -> Unit = onAvatarSelected@{ avatarId ->
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@onAvatarSelected
                    }
                    currentAvatarId = avatarId
                    updateAvatarSelection(currentAvatarId, avatarCat, avatarDog, avatarFox, avatarBear)
                    val name = resolveGroupNickname(displayNameEditInput.text.toString())
                        ?: displayNameValue.text.toString().takeUnless { it.isBlank() || it == "-" }
                    if (name.isNullOrBlank()) {
                        toast(getString(R.string.group_display_name_required))
                        return@onAvatarSelected
                    }
                    Thread {
                        SysEventWriter.updateUserProfile(
                            applicationContext,
                            gid,
                            name,
                            currentAvatarId
                        )
                    }.start()
                }

                val openAvatarPicker = {
                    showAvatarPicker(currentAvatarId) { selectedAvatarId ->
                        onAvatarSelected(selectedAvatarId)
                    }
                }
                avatarRow.setOnClickListener { openAvatarPicker() }
                avatarCat.setOnClickListener { openAvatarPicker() }
                avatarDog.setOnClickListener { openAvatarPicker() }
                avatarFox.setOnClickListener { openAvatarPicker() }
                avatarBear.setOnClickListener { openAvatarPicker() }
                openSettings.setOnClickListener {
                    startActivity(Intent(this, GroupSettingsActivity::class.java).apply {
                        putExtra(GroupSettingsActivity.EXTRA_GID, gid)
                        putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, group?.name)
                    })
                }
                sendInvite.setOnClickListener {
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    val inviteGroupKey = currentGroupKey ?: run {
                        toast(getString(R.string.group_invite_unavailable))
                        return@setOnClickListener
                    }
                    val inviteKid = group?.kid?.takeIf { it.isNotBlank() } ?: run {
                        toast(getString(R.string.group_invite_unavailable))
                        return@setOnClickListener
                    }
                    val link = buildInviteLink(
                        gid = gid,
                        kid = inviteKid,
                        groupKey = inviteGroupKey,
                        ownerDid = group?.owner_did,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.group_send_invite)))
                }
                showQr.setOnClickListener {
                    if (isRemoved) {
                        toast(getString(R.string.group_removed_detail_read_only))
                        return@setOnClickListener
                    }
                    val inviteGroupKey = currentGroupKey ?: run {
                        toast(getString(R.string.group_invite_unavailable))
                        return@setOnClickListener
                    }
                    val inviteKid = group?.kid?.takeIf { it.isNotBlank() } ?: run {
                        toast(getString(R.string.group_invite_unavailable))
                        return@setOnClickListener
                    }
                    val link = buildInviteLink(
                        gid = gid,
                        kid = inviteKid,
                        groupKey = inviteGroupKey,
                        ownerDid = group?.owner_did,
                    )
                    val view = layoutInflater.inflate(R.layout.dialog_qr_code, null)
                    val image = view.findViewById<android.widget.ImageView>(R.id.qr_code_image)
                    val size = resources.getDimensionPixelSize(R.dimen.qr_code_size)
                    val bitmap = BarcodeEncoder().encodeBitmap(
                        link,
                        com.google.zxing.BarcodeFormat.QR_CODE,
                        size,
                        size,
                    )
                    image.setImageBitmap(bitmap)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.group_show_qr))
                        .setView(view)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                close.setOnClickListener {
                    finish()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    private fun maskValue(value: String): String {
        if (value.isBlank()) return "-"
        if (value.length <= 6) return "••••"
        return value.take(4) + "…" + value.takeLast(2)
    }

    private fun copyToClipboard(label: String, value: String) {
        if (value.isBlank()) {
            toast("Nothing to copy")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied")
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildInviteLink(
        gid: String,
        kid: String,
        groupKey: ByteArray,
        ownerDid: String?,
    ): String {
        val groupKeyEncoded = Base64Url.encode(groupKey)
        val ownerParam = ownerDid
            ?.takeIf { it.isNotBlank() }
            ?.let { "&owner_did=${Uri.encode(it)}" }
            .orEmpty()
        return "peermesh://join?gid=$gid&kid=${Uri.encode(kid)}&group_key=${Uri.encode(groupKeyEncoded)}$ownerParam"
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun resolveGroupNickname(rawValue: String): String? {
        val sanitized = rawValue.trim()
        if (sanitized.isNotBlank()) return sanitized
        return localUserProfileStore.load()?.displayName?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
        private const val GROUP_NAME_MAX_LEN = 40
        private const val DISPLAY_NAME_MAX_LEN = 40
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
