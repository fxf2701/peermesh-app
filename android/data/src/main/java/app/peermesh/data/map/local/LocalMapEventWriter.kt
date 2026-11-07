package app.peermesh.data.map.local

import app.peermesh.core.model.EventCore

/**
 * M2C4B app-facing compatibility seam for production local canonical map writes.
 *
 * Contract:
 * - receives the exact event that will later be offered to transport;
 * - does not expose Room entities or reducer internals;
 * - does not own BLE, relay, key lookup, permission checks, or UI feedback;
 * - delegates authoritative acceptance to the shared map processor;
 * - does not open a second Room transaction.
 */
fun interface LocalMapEventWriter {
    fun write(request: LocalMapWriteRequest): LocalMapWriteOutcome
}

/**
 * Exact input required by the accepted shared processor for a local map write.
 *
 * [core] must already include every stable wire field required for persistence and later transport,
 * including the attached key ID when the current local flow supplies one.
 */
data class LocalMapWriteRequest(
    val gid: String,
    val core: EventCore,
    val localActorDid: String,
    val insertedAtSeconds: Long,
)
