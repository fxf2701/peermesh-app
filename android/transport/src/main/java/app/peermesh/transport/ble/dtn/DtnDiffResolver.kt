package app.peermesh.transport.ble.dtn

class DtnDiffResolver(
    private val store: DtnInventoryStore,
    private val repairSuppressionStore: DtnRepairSuppressionStore? = null,
) {
    data class WantResolution(
        val missing: List<String>,
        val suppressed: List<String>,
        val suppressedLocallySatisfied: List<String>,
        val suppressedLocallyPendingRelay: List<String>,
        val suppressedLocallyForwardedSuccess: List<String>,
    )

    fun missingFromLocal(gid: String, remoteIds: List<String>, limit: Int): List<String> {
        return resolveWantIds(gid, remoteIds, nowSeconds = 0L, limit = limit).missing
    }

    fun resolveWantIds(
        gid: String,
        remoteIds: List<String>,
        nowSeconds: Long,
        limit: Int,
    ): WantResolution {
        if (remoteIds.isEmpty() || limit <= 0) {
            return WantResolution(
                missing = emptyList(),
                suppressed = emptyList(),
                suppressedLocallySatisfied = emptyList(),
                suppressedLocallyPendingRelay = emptyList(),
                suppressedLocallyForwardedSuccess = emptyList(),
            )
        }
        val distinctRemoteIds = remoteIds.distinct()
        val existing = store.existingEventIds(gid, distinctRemoteIds)
        val candidateIds = distinctRemoteIds.filterNot { it in existing }
        val localSatisfied = repairSuppressionStore?.filterLocallySatisfied(
            eventIds = candidateIds,
            nowSeconds = nowSeconds,
        ) ?: emptySet()
        val locallyPendingRelay = repairSuppressionStore?.filterLocallyPendingRelay(
            eventIds = candidateIds.filterNot { it in localSatisfied },
            nowSeconds = nowSeconds,
        ) ?: emptySet()
        val locallyForwarded = repairSuppressionStore?.filterLocallyForwardedSuccess(
            eventIds = candidateIds.filterNot { it in localSatisfied || it in locallyPendingRelay },
            nowSeconds = nowSeconds,
        ) ?: emptySet()
        val suppressed = LinkedHashSet<String>(localSatisfied.size + locallyPendingRelay.size + locallyForwarded.size).apply {
            addAll(localSatisfied)
            addAll(locallyPendingRelay)
            addAll(locallyForwarded)
        }
        val missing = distinctRemoteIds
            .asSequence()
            .filterNot { it in existing || it in suppressed }
            .take(limit)
            .toList()
        return WantResolution(
            missing = missing,
            suppressed = distinctRemoteIds.filter { it in suppressed },
            suppressedLocallySatisfied = distinctRemoteIds.filter { it in localSatisfied },
            suppressedLocallyPendingRelay = distinctRemoteIds.filter { it in locallyPendingRelay },
            suppressedLocallyForwardedSuccess = distinctRemoteIds.filter { it in locallyForwarded },
        )
    }

    fun buildWantPacket(
        localPeerId: String,
        havePacket: DtnControlPacket,
        nowSeconds: Long,
        limit: Int = DtnForwardPolicy.DEFAULT_TRANSFER_LIMIT,
    ): DtnControlPacket? {
        val gid = havePacket.gid ?: return null
        if (havePacket.type != DtnControlTypes.INV_HAVE) return null
        val missing = resolveWantIds(gid, havePacket.ids, nowSeconds, limit).missing
        if (missing.isEmpty()) return null
        return DtnControlPacket(
            type = DtnControlTypes.INV_WANT,
            peerId = localPeerId,
            gid = gid,
            sessionId = havePacket.sessionId,
            mode = DtnInventoryModes.ID_LIST,
            inventoryTier = havePacket.inventoryTier,
            ids = missing,
            ts = nowSeconds,
        )
    }
}
