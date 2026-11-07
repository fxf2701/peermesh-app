package app.peermesh.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.BlockedPeerDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.DtnAdvertisedDao
import app.peermesh.data.db.dao.DtnCarrierManifestDao
import app.peermesh.data.db.dao.DtnPeerManifestKeyDao
import app.peermesh.data.db.dao.DtnPeerStateDao
import app.peermesh.data.db.dao.DtnPendingPullDao
import app.peermesh.data.db.dao.DtnSyncCursorDao
import app.peermesh.data.db.dao.EventDedupeDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.FeedbackDao
import app.peermesh.data.db.dao.GroupDao
import app.peermesh.data.db.dao.GroupPeerRouteDao
import app.peermesh.data.db.dao.KeyDao
import app.peermesh.data.db.dao.LiveTrailDao
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.db.dao.OutboundBatchDao
import app.peermesh.data.db.dao.PendingCanonicalMapEventDao
import app.peermesh.data.db.dao.MapItemVoteDao
import app.peermesh.data.db.dao.MapMarkerFieldClockDao
import app.peermesh.data.db.dao.ModerationActionDao
import app.peermesh.data.db.dao.ModerationReportDao
import app.peermesh.data.db.dao.NearbyRoomDao
import app.peermesh.data.db.dao.NearbyRoomInviteQuotaDao
import app.peermesh.data.db.dao.NearbyRoomMessageDao
import app.peermesh.data.db.dao.NearbyRoomMuteStateDao
import app.peermesh.data.db.dao.NearbyRoomParticipantDao
import app.peermesh.data.db.dao.NearbyRoomRelayOfferDao
import app.peermesh.data.db.dao.NearbyRoomRelayWantDao
import app.peermesh.data.db.dao.OpaqueDtnCapsuleDao
import app.peermesh.data.db.dao.RelayCursorDao
import app.peermesh.data.db.dao.RelayPushStateDao
import app.peermesh.data.db.dao.RemovedMemberDao
import app.peermesh.data.db.dao.UserProfileDao
import app.peermesh.data.db.dao.TrailDao
import app.peermesh.data.db.entity.ChatMessageEntity
import app.peermesh.data.db.entity.BlockedPeerEntity
import app.peermesh.data.db.entity.DeletedEventEntity
import app.peermesh.data.db.entity.DtnAdvertisedEntity
import app.peermesh.data.db.entity.DtnCarrierManifestBatchEntity
import app.peermesh.data.db.entity.DtnCarrierManifestEntity
import app.peermesh.data.db.entity.DtnPeerManifestKeyEntity
import app.peermesh.data.db.entity.DtnPeerStateEntity
import app.peermesh.data.db.entity.DtnPendingPullEntity
import app.peermesh.data.db.entity.DtnSyncCursorEntity
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.EventDedupeEntity
import app.peermesh.data.db.entity.PendingFeedbackEntity
import app.peermesh.data.db.entity.GroupEntity
import app.peermesh.data.db.entity.GroupPeerRouteEntity
import app.peermesh.data.db.entity.KeyEntity
import app.peermesh.data.db.entity.LiveTrailEntity
import app.peermesh.data.db.entity.LiveTrailPointEntity
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.db.entity.MapLayerEntity
import app.peermesh.data.db.entity.MapItemVoteEntity
import app.peermesh.data.db.entity.MapMarkerFieldClockEntity
import app.peermesh.data.db.entity.ModerationActionEntity
import app.peermesh.data.db.entity.PendingModerationReportEntity
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomInviteQuotaEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity
import app.peermesh.data.db.entity.NearbyRoomParticipantEntity
import app.peermesh.data.db.entity.NearbyRoomRelayOfferEntity
import app.peermesh.data.db.entity.NearbyRoomRelayWantEntity
import app.peermesh.data.db.entity.OpaqueDtnCapsuleDeliveryEntity
import app.peermesh.data.db.entity.OpaqueDtnCapsuleEntity
import app.peermesh.data.db.entity.OutboundBatchEntity
import app.peermesh.data.db.entity.OutboundBatchEventEntity
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryEntity
import app.peermesh.data.db.entity.PendingCanonicalMapEventEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.db.entity.RelayCursorEntity
import app.peermesh.data.db.entity.RelayPushStateEntity
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.TrailEntity
import app.peermesh.data.db.entity.TrailPointEntity
import app.peermesh.data.db.entity.UserProfileEntity

@Database(
    entities = [
        EventEntity::class,
        EventDedupeEntity::class,
        GroupEntity::class,
        GroupPeerRouteEntity::class,
        KeyEntity::class,
        ChatMessageEntity::class,
        DeletedEventEntity::class,
        MapLayerEntity::class,
        MapMarkerEntity::class,
        MapMarkerFieldClockEntity::class,
        MapItemVoteEntity::class,
        BlockedPeerEntity::class,
        PendingFeedbackEntity::class,
        PendingModerationReportEntity::class,
        ModerationActionEntity::class,
        RemovedMemberEntity::class,
        MapAreaEntity::class,
        MapItemDomainStateEntity::class,
        MapItemActionEntity::class,
        LiveTrailEntity::class,
        LiveTrailPointEntity::class,
        RelayCursorEntity::class,
        RelayPushStateEntity::class,
        TrailEntity::class,
        TrailPointEntity::class,
        UserProfileEntity::class,
        OutboundBatchEntity::class,
        OutboundBatchEventEntity::class,
        OutboundBatchPeerDeliveryEntity::class,
        DtnPeerStateEntity::class,
        DtnAdvertisedEntity::class,
        DtnSyncCursorEntity::class,
        DtnPendingPullEntity::class,
        OpaqueDtnCapsuleEntity::class,
        OpaqueDtnCapsuleDeliveryEntity::class,
        DtnCarrierManifestEntity::class,
        DtnCarrierManifestBatchEntity::class,
        DtnPeerManifestKeyEntity::class,
        NearbyRoomEntity::class,
        NearbyRoomParticipantEntity::class,
        NearbyRoomMessageEntity::class,
        NearbyRoomMuteStateEntity::class,
        NearbyRoomInviteQuotaEntity::class,
        NearbyRoomRelayOfferEntity::class,
        NearbyRoomRelayWantEntity::class,
        PendingCanonicalMapEventEntity::class,
    ],
    version = 55,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventsDao(): EventsDao
    abstract fun eventDedupeDao(): EventDedupeDao
    abstract fun chatDao(): ChatDao
    abstract fun blockedPeerDao(): BlockedPeerDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun deletedEventDao(): DeletedEventDao
    abstract fun groupDao(): GroupDao
    abstract fun groupPeerRouteDao(): GroupPeerRouteDao
    abstract fun mapDao(): MapDao
    abstract fun mapItemDomainStateDao(): MapItemDomainStateDao
    abstract fun mapItemActionDao(): MapItemActionDao
    abstract fun mapItemVoteDao(): MapItemVoteDao
    abstract fun moderationActionDao(): ModerationActionDao
    abstract fun moderationReportDao(): ModerationReportDao
    abstract fun keyDao(): KeyDao
    abstract fun mapMarkerFieldClockDao(): MapMarkerFieldClockDao
    abstract fun relayCursorDao(): RelayCursorDao
    abstract fun relayPushStateDao(): RelayPushStateDao
    abstract fun removedMemberDao(): RemovedMemberDao
    abstract fun trailDao(): TrailDao
    abstract fun liveTrailDao(): LiveTrailDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun outboundBatchDao(): OutboundBatchDao
    abstract fun dtnPeerStateDao(): DtnPeerStateDao
    abstract fun dtnAdvertisedDao(): DtnAdvertisedDao
    abstract fun dtnSyncCursorDao(): DtnSyncCursorDao
    abstract fun dtnPendingPullDao(): DtnPendingPullDao
    abstract fun opaqueDtnCapsuleDao(): OpaqueDtnCapsuleDao
    abstract fun dtnCarrierManifestDao(): DtnCarrierManifestDao
    abstract fun dtnPeerManifestKeyDao(): DtnPeerManifestKeyDao
    abstract fun nearbyRoomDao(): NearbyRoomDao
    abstract fun nearbyRoomParticipantDao(): NearbyRoomParticipantDao
    abstract fun nearbyRoomMessageDao(): NearbyRoomMessageDao
    abstract fun nearbyRoomMuteStateDao(): NearbyRoomMuteStateDao
    abstract fun nearbyRoomInviteQuotaDao(): NearbyRoomInviteQuotaDao
    abstract fun nearbyRoomRelayOfferDao(): NearbyRoomRelayOfferDao
    abstract fun nearbyRoomRelayWantDao(): NearbyRoomRelayWantDao
    abstract fun pendingCanonicalMapEventDao(): PendingCanonicalMapEventDao
}
