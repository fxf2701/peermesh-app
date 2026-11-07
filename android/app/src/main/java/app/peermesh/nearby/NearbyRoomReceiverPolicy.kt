package app.peermesh.nearby

object NearbyRoomReceiverPolicy {
    fun shouldProcess(kind: String, matchingRuntimeRoom: Boolean): Boolean =
        matchingRuntimeRoom || isJoinTransitionKind(kind)

    fun isJoinTransitionKind(kind: String): Boolean =
        kind == NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1 ||
            kind == NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1 ||
            kind == NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1 ||
            kind == NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1
}
