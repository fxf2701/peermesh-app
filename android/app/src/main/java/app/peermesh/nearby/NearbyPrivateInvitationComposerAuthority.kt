package app.peermesh.nearby

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.GroupInvitationMaterialAuthority
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.nearby.presentation.NearbyPrivateInvitationDraft

/** Presentation-safe local group option exposed to the composer UI. */
data class NearbyPrivateInvitationGroupOption(
    val groupId: String,
    val displayName: String,
)

/**
 * P6 application boundary for group selection and private invitation creation.
 *
 * The Activity supplies only identity args and an immutable semantic draft. Local group material is
 * resolved by the existing group authority and passed as an opaque credential directly to GI2E4.
 */
class NearbyPrivateInvitationComposerAuthority internal constructor(
    private val localDeviceIdProvider: () -> String,
    private val loadEligibleGroupOptions: (String) -> List<NearbyPrivateInvitationGroupOption>,
    private val resolveInvitationCredential: (String, String) -> InvitationCredential?,
    private val sendPrivateInvitation: (
        NearbyPrivateGroupInvitationSendRequest,
    ) -> NearbyPrivateGroupInvitationSendResult,
) {
    constructor(
        context: Context,
        pipeline: NearbyPrivateGroupInvitationSendPipeline,
    ) : this(
        localDeviceIdProvider = {
            DeviceIdProvider.get(context.applicationContext)
        },
        loadEligibleGroupOptions = { actorDid ->
            GroupInvitationMaterialAuthority(context.applicationContext)
                .loadEligibleGroups(actorDid)
                .map { group ->
                    NearbyPrivateInvitationGroupOption(
                        groupId = group.groupId,
                        displayName = group.displayName,
                    )
                }
        },
        resolveInvitationCredential = { actorDid, groupId ->
            GroupInvitationMaterialAuthority(context.applicationContext)
                .resolveCredential(actorDid, groupId)
        },
        sendPrivateInvitation = pipeline::send,
    )

    fun localDeviceId(): String = localDeviceIdProvider()

    fun loadEligibleGroups(): List<NearbyPrivateInvitationGroupOption> =
        loadEligibleGroupOptions(localDeviceId())

    fun send(
        roomId: String,
        targetParticipantId: String,
        draft: NearbyPrivateInvitationDraft,
    ): NearbyPrivateGroupInvitationSendResult {
        val actorDid = localDeviceId()
        val credential = resolveInvitationCredential(actorDid, draft.groupId)
            ?: return NearbyPrivateGroupInvitationSendResult.PreparationUnavailable

        return sendPrivateInvitation(
            NearbyPrivateGroupInvitationSendRequest(
                roomId = roomId,
                targetParticipantId = targetParticipantId,
                title = draft.title,
                shortDescription = draft.description,
                invitationCredential = credential,
            )
        )
    }
}
