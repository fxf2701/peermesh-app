package app.peermesh

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

object PeerMeshEntitlements {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_STATUS = "pro_entitlement_status"
    private const val KEY_SOURCE = "pro_entitlement_source"
    private const val KEY_EXPIRES_AT_MS = "pro_entitlement_expires_at_ms"
    private const val KEY_UPDATED_AT_MS = "pro_entitlement_updated_at_ms"

    private const val SOURCE_DEFAULT = "default"
    private const val SOURCE_BILLING = "billing"
    private const val SOURCE_BILLING_UNAVAILABLE = "billing_unavailable"
    private const val SOURCE_DEV_OVERRIDE = "dev_override"

    @Volatile
    private var cachedState = defaultState()

    private val listeners = CopyOnWriteArrayList<(EntitlementState) -> Unit>()

    fun initialize(context: Context) {
        cachedState = load(context.applicationContext)
        if (!isDevOverrideAllowed() && cachedState.source == SOURCE_DEV_OVERRIDE) {
            clearDevOverride(context.applicationContext)
        }
    }

    fun current(): EntitlementState = cachedState

    fun isProActive(): Boolean = cachedState.unlocksPro

    fun addListener(listener: (EntitlementState) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(cachedState)
    }

    fun removeListener(listener: (EntitlementState) -> Unit) {
        listeners.remove(listener)
    }

    fun updateFromBilling(
        context: Context,
        active: Boolean,
        trial: Boolean = false,
        expiresAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (isDevOverrideAllowed() && cachedState.source == SOURCE_DEV_OVERRIDE) return
        val status = when {
            active && trial -> EntitlementStatus.TRIAL
            active -> EntitlementStatus.PRO
            else -> EntitlementStatus.EXPIRED
        }
        save(
            context = context,
            status = status,
            source = SOURCE_BILLING,
            expiresAtMs = expiresAtMs,
            nowMs = nowMs,
        )
    }

    fun markBillingUnknown(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (isDevOverrideAllowed() && cachedState.source == SOURCE_DEV_OVERRIDE) return
        save(
            context = context,
            status = EntitlementStatus.UNKNOWN,
            source = SOURCE_BILLING_UNAVAILABLE,
            expiresAtMs = null,
            nowMs = nowMs,
        )
    }

    fun setDevOverride(
        context: Context,
        status: EntitlementStatus,
        expiresAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!isDevOverrideAllowed()) {
            clearDevOverride(context)
            return
        }
        save(
            context = context,
            status = status,
            source = SOURCE_DEV_OVERRIDE,
            expiresAtMs = expiresAtMs,
            nowMs = nowMs,
        )
    }

    fun clearDevOverride(context: Context) {
        val prefs = prefs(context)
        if (prefs.getString(KEY_SOURCE, SOURCE_DEFAULT) != SOURCE_DEV_OVERRIDE) return
        save(
            context = context,
            status = EntitlementStatus.FREE,
            source = SOURCE_DEFAULT,
            expiresAtMs = null,
            nowMs = System.currentTimeMillis(),
        )
    }

    fun isDevOverrideActive(): Boolean {
        return isDevOverrideAllowed() && cachedState.source == SOURCE_DEV_OVERRIDE
    }

    private fun save(
        context: Context,
        status: EntitlementStatus,
        source: String,
        expiresAtMs: Long?,
        nowMs: Long,
    ) {
        prefs(context).edit()
            .putString(KEY_STATUS, status.id)
            .putString(KEY_SOURCE, source)
            .putLong(KEY_EXPIRES_AT_MS, expiresAtMs ?: 0L)
            .putLong(KEY_UPDATED_AT_MS, nowMs)
            .apply()
        publish(
            EntitlementState(
                status = status,
                source = source,
                expiresAtMs = expiresAtMs,
                updatedAtMs = nowMs,
            )
        )
    }

    private fun publish(state: EntitlementState) {
        val previous = cachedState
        cachedState = state
        if (previous == state) return
        listeners.forEach { listener ->
            listener(state)
        }
    }

    private fun state(
        status: EntitlementStatus,
        source: String,
        expiresAtMs: Long?,
        updatedAtMs: Long,
    ): EntitlementState {
        return EntitlementState(
            status = status,
            source = source,
            expiresAtMs = expiresAtMs,
            updatedAtMs = updatedAtMs,
        )
    }

    private fun load(context: Context): EntitlementState {
        val prefs = prefs(context)
        val status = EntitlementStatus.fromId(
            prefs.getString(KEY_STATUS, EntitlementStatus.FREE.id)
        )
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L).takeIf { it > 0L }
        return state(
            status = status,
            source = prefs.getString(KEY_SOURCE, SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
            expiresAtMs = expiresAt,
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT_MS, 0L),
        )
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun isDevOverrideAllowed(): Boolean {
        return BuildConfig.ALLOW_PRO_DEV_OVERRIDE
    }

    private fun defaultState(): EntitlementState {
        return state(
            status = EntitlementStatus.FREE,
            source = SOURCE_DEFAULT,
            expiresAtMs = null,
            updatedAtMs = 0L,
        )
    }
}

data class EntitlementState(
    val status: EntitlementStatus,
    val source: String,
    val expiresAtMs: Long?,
    val updatedAtMs: Long,
) {
    val unlocksPro: Boolean
        get() = status == EntitlementStatus.PRO || status == EntitlementStatus.TRIAL
}

enum class EntitlementStatus(val id: String) {
    FREE("free"),
    TRIAL("trial"),
    PRO("pro"),
    EXPIRED("expired"),
    UNKNOWN("unknown");

    companion object {
        fun fromId(id: String?): EntitlementStatus {
            return values().firstOrNull { it.id == id } ?: FREE
        }
    }
}
