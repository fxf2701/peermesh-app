package app.peermesh.nearby

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.GroupInvitationMaterialAuthority
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.nearby.presentation.NearbyPublicInvitationDraft

data class NearbyPublicInvitationGroupOption(
    val groupId: String,
    val displayName: String,
)

/** P7 boundary for public composer group selection and delegation to the locked GI2E3 pipeline. */
class NearbyPublicInvitationComposerAuthority internal constructor(
    private val localDeviceIdProvider: () -> String,
    private val loadEligibleGroupOptions: (String) -> List<NearbyPublicInvitationGroupOption>,
    private val resolveInvitationCredential: (String, String) -> InvitationCredential?,
    private val sendPublicInvitation: (
        NearbyPublicGroupInvitationSendRequest,
    ) -> NearbyPublicGroupInvitationSendResult,
) {
    constructor(
        context: Context,
        pipeline: NearbyPublicGroupInvitationSendPipeline,
    ) : this(
        localDeviceIdProvider = { DeviceIdProvider.get(context.applicationContext) },
        loadEligibleGroupOptions = { actorDid ->
            GroupInvitationMaterialAuthority(context.applicationContext)
                .loadEligibleGroups(actorDid)
                .map { group ->
                    NearbyPublicInvitationGroupOption(
                        groupId = group.groupId,
                        displayName = group.displayName,
                    )
                }
        },
        resolveInvitationCredential = { actorDid, groupId ->
            GroupInvitationMaterialAuthority(context.applicationContext)
                .resolveCredential(actorDid, groupId)
        },
        sendPublicInvitation = pipeline::send,
    )

    fun localDeviceId(): String = localDeviceIdProvider()

    fun loadEligibleGroups(): List<NearbyPublicInvitationGroupOption> =
        loadEligibleGroupOptions(localDeviceId())

    fun send(
        roomId: String,
        draft: NearbyPublicInvitationDraft,
    ): NearbyPublicGroupInvitationSendResult {
        val actorDid = localDeviceId()
        val credential = resolveInvitationCredential(actorDid, draft.groupId)
            ?: return NearbyPublicGroupInvitationSendResult.PreparationUnavailable
        return sendPublicInvitation(
            NearbyPublicGroupInvitationSendRequest(
                roomId = roomId,
                title = draft.title,
                shortDescription = draft.description,
                invitationCredential = credential,
            )
        )
    }
}
