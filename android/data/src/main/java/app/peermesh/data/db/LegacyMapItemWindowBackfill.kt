package app.peermesh.data.db

import app.peermesh.core.map.MapItemWindows

/** Pure, deterministic legacy TTL conversion shared by migration tests. */
object LegacyMapItemWindowBackfill {
    const val MIN_OWNER_EDIT_SECONDS: Long = 10L * 60L
    const val MAX_OWNER_EDIT_SECONDS: Long = 6L * 60L * 60L

    data class Result(
        val windows: MapItemWindows,
        val temporalOrigin: String,
    )

    fun derive(createdAtSeconds: Long, expiresAtSeconds: Long?): Result {
        if (expiresAtSeconds == null) {
            return Result(
                windows = MapItemWindows(null, null, null),
                temporalOrigin = MapItemPersistenceValues.TemporalOrigin.LEGACY_NO_EXPIRY,
            )
        }
        if (createdAtSeconds <= 0L || expiresAtSeconds <= 0L || expiresAtSeconds <= createdAtSeconds) {
            return Result(
                windows = MapItemWindows(null, null, null),
                temporalOrigin = MapItemPersistenceValues.TemporalOrigin.LEGACY_UNRESOLVED,
            )
        }

        val ttlSeconds = expiresAtSeconds - createdAtSeconds
        val halfTtlSeconds = ttlSeconds / 2L
        val ownerEditDuration = halfTtlSeconds.coerceIn(
            MIN_OWNER_EDIT_SECONDS,
            MAX_OWNER_EDIT_SECONDS,
        )
        val ownerEditUntil = try {
            Math.addExact(createdAtSeconds, ownerEditDuration)
        } catch (_: ArithmeticException) {
            return unresolved()
        }
        return Result(
            windows = MapItemWindows(
                expiresAtSeconds = expiresAtSeconds,
                ownerEditUntilSeconds = ownerEditUntil,
                interactionUntilSeconds = expiresAtSeconds,
            ),
            temporalOrigin = MapItemPersistenceValues.TemporalOrigin.LEGACY_TTL_DERIVED,
        )
    }

    private fun unresolved() = Result(
        windows = MapItemWindows(null, null, null),
        temporalOrigin = MapItemPersistenceValues.TemporalOrigin.LEGACY_UNRESOLVED,
    )
}
