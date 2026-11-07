package app.peermesh.map.presentation.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.R
import app.peermesh.ThemeConfig
import app.peermesh.DeviceIdProvider
import app.peermesh.MapKinds
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.MapAreaGeometrySummary
import app.peermesh.map.application.detail.MapItemCanonicalPresentation
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.application.interaction.MapInteractionAvailabilityReason
import app.peermesh.map.application.interaction.MapInteractionCapabilityWarning
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.submission.MapInteractionRejection
import app.peermesh.map.application.submission.MapInteractionSubmissionFailure
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityType
import app.peermesh.map.composition.MapItemCommunityPresentationFactory
import app.peermesh.map.composition.MapItemDetailShellFactory
import app.peermesh.map.composition.MapItemTimelineControllerFactory
import app.peermesh.map.presentation.community.MapItemCommunityProjectionComposer
import app.peermesh.map.presentation.community.MapItemCommunityProjectionInput
import app.peermesh.map.presentation.community.MapItemCommunityProjectionSnapshot
import app.peermesh.map.presentation.community.MapItemCommunityConfirmCtaAction
import app.peermesh.map.presentation.community.MapItemCommunityDetailViewState
import app.peermesh.map.presentation.community.MapItemCommunityDetailViewStateMapper
import app.peermesh.map.presentation.community.MapItemCommunitySection
import app.peermesh.map.presentation.community.MapItemCommunityStringResource
import app.peermesh.map.presentation.community.MapItemCommunityText
import app.peermesh.map.presentation.community.MapItemCommunityTimelineInput
import app.peermesh.map.presentation.list.MapOwnerDisplayName
import app.peermesh.map.presentation.list.MapOwnerDisplayNameResolver
import app.peermesh.map.presentation.list.MapOwnerNameProjection
import app.peermesh.map.presentation.timeline.MapItemTimelineAdapter
import app.peermesh.map.presentation.timeline.MapItemTimelineController
import app.peermesh.map.presentation.timeline.MapItemTimelineState
import app.peermesh.map.presentation.timeline.MapItemTimelineStateObserver
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue

/** Minimal M3A4 shell. Canonical owner and legacy Confidence/Contribute controls remain on the map. */
open class MapItemDetailActivity : AppCompatActivity() {
    private var controller: MapItemDetailShellController? = null
    private var timelineController: MapItemTimelineController? = null
    private var timelineAdapter: MapItemTimelineAdapter? = null
    private var latestTimelinePresentation: MapItemTimelinePresentation? = null
    private var latestDetailState: MapItemDetailShellState? = null
    private var communityProjectionComposer: MapItemCommunityProjectionComposer? = null
    private var latestCommunityProjection: MapItemCommunityProjectionSnapshot? = null
    private var latestCommunityViewState = MapItemCommunityDetailViewState.Hidden
    private val timelineCoordinator = MapItemDetailTimelineCoordinator()
    private var timelineIdentity: MapItemIdentity? = null
    private var timelineActorNames: Map<String, MapOwnerNameProjection> = emptyMap()
    private var timelineActorNameLoadGeneration = 0L
    private var viewLifecycleEnded = false
    private var skipFirstResumeRefresh = true
    /** UI-only idempotency cue; persisted participation remains domain-owned. */
    private var currentUserAcknowledgedCoordination = false
    private var acknowledgementAwaitingOutcome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_item_detail)
        communityProjectionComposer = MapItemCommunityPresentationFactory.create()
        findViewById<Button>(R.id.map_detail_refresh).setOnClickListener {
            timelineCoordinator.dispatchManualRefresh(
                refreshDetail = { controller?.refresh() },
                loadTimelineInitial = { timelineController?.loadInitial() },
                refreshTimelineFirstPage = { timelineController?.refreshFirstPage() },
            )
        }
        findViewById<Button>(R.id.map_detail_timeline_retry).setOnClickListener {
            when (latestTimelinePresentation?.retryOperation) {
                MapItemTimelineRetryOperation.START_TIMELINE -> timelineIdentity?.let(::startTimelineAfterDetail)
                MapItemTimelineRetryOperation.LOAD_INITIAL -> timelineController?.loadInitial()
                MapItemTimelineRetryOperation.REFRESH_FIRST_PAGE -> timelineController?.refreshFirstPage()
                null -> Unit
            }
        }
        findViewById<Button>(R.id.map_detail_timeline_load_older).setOnClickListener {
            if (latestTimelinePresentation?.loadOlderEnabled == true) {
                timelineController?.loadOlder()
            }
        }
        timelineAdapter = MapItemTimelineAdapter(::timelineEntryText)
        findViewById<RecyclerView>(R.id.map_detail_timeline_list).apply {
            layoutManager = LinearLayoutManager(this@MapItemDetailActivity)
            adapter = timelineAdapter
        }

        val identity = DefaultMapItemDetailNavigationArguments.decode(intent)
        if (identity == null) {
            renderTerminal(getString(R.string.map_detail_invalid_item))
            return
        }
        timelineIdentity = identity
        loadTimelineActorNames(identity)
        val dependencies = createDetailDependencies()
        MapItemDetailTimelineStartup.start(
            startDetail = {
                controller = DefaultMapItemDetailShellController(
                    identity = identity,
                    loadMapItemDetail = dependencies.loadMapItemDetail,
                    evaluateMapItemInteractions = dependencies.evaluateMapItemInteractions,
                    submitMapInteraction = dependencies.submitMapInteraction,
                    execution = createDetailExecution(),
                    observer = MapItemDetailShellStateObserver(::render),
                ).also { it.load() }
            },
            startTimeline = {
                timelineController = createAndStartTimeline(identity)
            },
            onTimelineFailure = {
                renderTimelinePresentation(timelineCoordinator.onTimelineStartupFailure())
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (skipFirstResumeRefresh) {
            skipFirstResumeRefresh = false
        } else {
            controller?.refresh()
            timelineIdentity?.let(::loadTimelineActorNames)
        }
    }

    override fun onDestroy() {
        if (!viewLifecycleEnded) {
            viewLifecycleEnded = true
            timelineController?.close()
            timelineController = null
            controller?.close()
            controller = null
            timelineAdapter = null
            latestTimelinePresentation = null
            latestDetailState = null
            latestCommunityProjection = null
            latestCommunityViewState = MapItemCommunityDetailViewState.Hidden
            communityProjectionComposer = null
            timelineIdentity = null
            findViewById<RecyclerView>(R.id.map_detail_timeline_list).adapter = null
        }
        super.onDestroy()
    }

    private fun render(state: MapItemDetailShellState) {
        if (viewLifecycleEnded) return
        latestDetailState = state
        val timelinePresentation = timelineCoordinator.onDetailContent(state.content)
        when (val content = state.content) {
            MapItemDetailShellContent.InitialLoading -> renderTerminal(getString(R.string.map_detail_loading), loading = true)
            is MapItemDetailShellContent.Loaded -> renderLoaded(content.detail, content.enrichment)
            is MapItemDetailShellContent.InvalidDomainEnrichment -> {
                renderCanonical(content.canonical)
                setText(R.id.map_detail_domain, getString(R.string.map_detail_invalid_domain))
            }
            MapItemDetailShellContent.TargetUnavailable -> renderTerminal(getString(R.string.map_detail_target_unavailable))
            MapItemDetailShellContent.InvalidItemData -> renderTerminal(getString(R.string.map_detail_invalid_item))
            MapItemDetailShellContent.ReadFailure -> renderTerminal(getString(R.string.map_detail_read_failure))
        }
        renderTimelinePresentation(timelinePresentation)
        updateAcknowledgementCtaState(state.feedback)
        renderInteractions(state)
        state.feedback?.let { feedback ->
            if (timelineCoordinator.shouldRefreshForAcceptedFeedback(feedback)) {
                timelineController?.refreshFirstPage()
            }
            Toast.makeText(this, feedbackText(feedback), Toast.LENGTH_LONG).show()
            controller?.consumeFeedback()
        }
    }

    private fun renderLoaded(detail: MapItemDetail, enrichment: DomainEnrichmentState) {
        renderCanonical(detail.canonical)
        renderTimelineHeading(detail.domain?.category)
        val domainText = when (enrichment) {
            DomainEnrichmentState.MISSING -> getString(R.string.map_detail_domain_missing)
            DomainEnrichmentState.UNRESOLVED_LEGACY -> getString(R.string.map_detail_domain_unresolved)
            DomainEnrichmentState.AVAILABLE -> detail.domain?.let { domain ->
                when (domain.category) {
                    MapItemCategory.SITUATION -> if (domain.lifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE) {
                        getString(R.string.map_detail_situation_resolved, domain.confirmationActorCount)
                    } else {
                        getString(R.string.map_detail_situation_active, domain.confirmationActorCount)
                    }
                    MapItemCategory.COORDINATION -> getString(
                        R.string.map_detail_coordination,
                        domain.acknowledgementActorCount,
                    )
                    null -> getString(R.string.map_detail_domain_unresolved)
                }
            } ?: getString(R.string.map_detail_domain_missing)
        }
        setText(R.id.map_detail_domain, domainText)
    }

    private fun renderTimelineHeading(category: MapItemCategory?) {
        setText(
            R.id.map_detail_timeline_heading,
            getString(
                if (category == MapItemCategory.COORDINATION) {
                    R.string.map_detail_timeline_heading_coordination
                } else {
                    R.string.map_detail_timeline_heading
                },
            ),
        )
    }

    private fun renderCanonical(canonical: MapItemCanonicalPresentation) {
        setText(R.id.map_detail_title, canonical.label?.takeIf { it.isNotBlank() } ?: getString(R.string.map_detail_title_fallback))
        setText(
            R.id.map_detail_kind,
            canonical.normalizedKind?.takeIf { it.isNotBlank() }?.let { kind ->
                getString(R.string.map_detail_kind, MapKinds.labelForKey(kind) ?: kind)
            }
                ?: getString(R.string.map_detail_kind_unknown),
        )
        val location = when (canonical) {
            is MarkerCanonicalPresentation -> getString(
                R.string.map_detail_marker_location,
                canonical.coordinates.latitude,
                canonical.coordinates.longitude,
            )
            is AreaCanonicalPresentation -> when (val summary = canonical.geometrySummary) {
                is MapAreaGeometrySummary.Polygon -> getString(R.string.map_detail_area_polygon, summary.vertexCount)
                is MapAreaGeometrySummary.MultiPolygon -> getString(
                    R.string.map_detail_area_multipolygon,
                    summary.polygonCount,
                    summary.vertexCount,
                )
            }
        }
        setText(R.id.map_detail_location, location)
        val note = canonical.note?.takeIf { it.isNotBlank() }
        setText(R.id.map_detail_note, note?.let { getString(R.string.map_detail_owner_note, it) }.orEmpty())
        setText(
            R.id.map_detail_availability_summary,
            listOfNotNull(
                if (canonical.availability == app.peermesh.map.application.detail.MapItemCanonicalAvailability.DELETED) {
                    getString(R.string.map_detail_removed)
                } else null,
                canonical.expiresAt?.let {
                getString(R.string.map_detail_expiry, DateFormat.getDateTimeInstance().format(Date(it * 1000L)))
                } ?: getString(R.string.map_detail_expiry_none),
            ).joinToString(" "),
        )
    }

    private fun renderTerminal(message: String, loading: Boolean = false) {
        setText(R.id.map_detail_title, message)
        listOf(
            R.id.map_detail_kind,
            R.id.map_detail_location,
            R.id.map_detail_note,
            R.id.map_detail_domain,
            R.id.map_detail_availability_summary,
        ).forEach { setText(it, "") }
        findViewById<Button>(R.id.map_detail_refresh).isEnabled = !loading
    }

    private fun renderInteractions(state: MapItemDetailShellState) {
        val availability = state.availability
        val bindings = listOf(
            Triple(MapInteractionType.Situation.Confirm, R.id.map_detail_confirm, R.id.map_detail_confirm_reason),
            Triple(MapInteractionType.Situation.AddUpdate, R.id.map_detail_update, R.id.map_detail_update_reason),
            Triple(MapInteractionType.Situation.MarkNoLongerActive, R.id.map_detail_resolve, R.id.map_detail_resolve_reason),
            Triple(MapInteractionType.Coordination.Acknowledge, R.id.map_detail_acknowledge, R.id.map_detail_acknowledge_reason),
            Triple(MapInteractionType.Coordination.AddNote, R.id.map_detail_add_note, R.id.map_detail_add_note_reason),
        )
        bindings.forEach { (type, buttonId, reasonId) ->
            val item = availability?.interactions?.singleOrNull { it.interaction == type }
            val button = findViewById<Button>(buttonId)
            val reason = findViewById<TextView>(reasonId)
            val cta = latestCommunityViewState.confirmCta
            val visible = item?.isVisible == true && (
                type != MapInteractionType.Situation.Confirm || cta.visible
            ) && !(type == MapInteractionType.Coordination.Acknowledge && currentUserAcknowledgedCoordination)
            button.visibility = if (visible) View.VISIBLE else View.GONE
            button.isEnabled = if (type == MapInteractionType.Situation.Confirm) {
                cta.enabled
            } else {
                item?.isEnabled == true && !state.isSubmissionInFlight
            }
            reason.visibility = if (visible && item?.isEnabled == false) View.VISIBLE else View.GONE
            reason.text = item?.primaryReason?.let(::availabilityReasonText).orEmpty()
            button.setOnClickListener {
                if (button.visibility != View.VISIBLE || !button.isEnabled) return@setOnClickListener
                if (type == MapInteractionType.Situation.Confirm) {
                    MapItemCommunityConfirmCtaAction.open(::openInteraction)
                } else {
                    openInteraction(type)
                }
            }
        }
        val warning = availability?.interactions?.firstNotNullOfOrNull { it.capabilityWarning }
        findViewById<TextView>(R.id.map_detail_capability_warning).apply {
            visibility = if (warning != null) View.VISIBLE else View.GONE
            text = if (warning != null) capabilityWarningText(warning) else ""
        }
        findViewById<Button>(R.id.map_detail_refresh).isEnabled = !state.isSubmissionInFlight
    }

    private fun openInteraction(type: MapInteractionType) {
        val owner = controller ?: return
        owner.openInteraction(type)
        when (type) {
            MapInteractionType.Situation.Confirm -> showConfirmDialog()
            MapInteractionType.Situation.AddUpdate -> showUpdateDialog()
            MapInteractionType.Situation.MarkNoLongerActive -> showResolveDialog()
            MapInteractionType.Coordination.Acknowledge -> {
                acknowledgementAwaitingOutcome = true
                owner.submitActiveForm()
            }
            MapInteractionType.Coordination.AddNote -> showCoordinationNoteDialog()
        }
    }

    private fun showConfirmDialog() {
        val (view, source, note) = sourceAndNoteForm()
        val isArea = controller?.currentState?.identity?.itemType == MapItemType.AREA
        AlertDialog.Builder(this)
            .setTitle(R.string.map_detail_confirm)
            .setMessage(if (isArea) getString(R.string.map_detail_area_confirmation_explanation) else null)
            .setView(view)
            .setPositiveButton(R.string.map_detail_submit) { _, _ ->
                controller?.updateForm(MapItemInteractionForm.ConfirmSituation(source.selectedSource(), note.text.toString()))
                controller?.submitActiveForm()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> controller?.dismissForm() }
            .setOnCancelListener { controller?.dismissForm() }
            .show()
    }

    private fun showUpdateDialog() {
        val container = formContainer()
        val content = EditText(this).apply { hint = getString(R.string.map_detail_update_content); minLines = 3 }
        val updateType = labeledSpinner(
            container,
            R.string.map_detail_update_type,
            listOf(getString(R.string.map_detail_update_still_active), getString(R.string.map_detail_update_changed)),
        )
        val source = labeledSpinner(container, R.string.map_detail_information_source, sourceLabels())
        container.addView(content, 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.map_detail_add_update)
            .setView(container)
            .setPositiveButton(R.string.map_detail_submit, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> controller?.dismissForm() }
            .setOnCancelListener { controller?.dismissForm() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (content.text.isNullOrBlank()) {
                    content.error = getString(R.string.map_detail_required_content)
                    return@setOnClickListener
                }
                val disposition = if (updateType.selectedItemPosition == 0) {
                    MapSituationUpdateType.STILL_ACTIVE
                } else MapSituationUpdateType.SITUATION_CHANGED
                controller?.updateForm(MapItemInteractionForm.AddSituationUpdate(
                    content.text.toString(), disposition, source.selectedSource(),
                ))
                controller?.submitActiveForm()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showResolveDialog() {
        val (view, source, note) = sourceAndNoteForm()
        AlertDialog.Builder(this)
            .setTitle(R.string.map_detail_resolution_title)
            .setMessage(R.string.map_detail_resolution_explanation)
            .setView(view)
            .setPositiveButton(R.string.map_detail_resolve) { _, _ ->
                controller?.updateForm(MapItemInteractionForm.ResolveSituation(note.text.toString(), source.selectedSource()))
                controller?.submitActiveForm()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> controller?.dismissForm() }
            .setOnCancelListener { controller?.dismissForm() }
            .show()
    }

    private fun showCoordinationNoteDialog() {
        val content = EditText(this).apply { hint = getString(R.string.map_detail_coordination_note_content); minLines = 3 }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.map_detail_add_note)
            .setView(content)
            .setPositiveButton(R.string.map_detail_submit, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> controller?.dismissForm() }
            .setOnCancelListener { controller?.dismissForm() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (content.text.isNullOrBlank()) {
                    content.error = getString(R.string.map_detail_required_content)
                    return@setOnClickListener
                }
                controller?.updateForm(MapItemInteractionForm.AddCoordinationNote(content.text.toString()))
                controller?.submitActiveForm()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun sourceAndNoteForm(): Triple<LinearLayout, Spinner, EditText> {
        val container = formContainer()
        val source = labeledSpinner(container, R.string.map_detail_information_source, sourceLabels())
        val note = EditText(this).apply { hint = getString(R.string.map_detail_optional_note); minLines = 2 }
        container.addView(note)
        return Triple(container, source, note)
    }

    private fun formContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
    }

    private fun labeledSpinner(container: LinearLayout, labelRes: Int, labels: List<String>): Spinner {
        container.addView(TextView(this).apply { setText(labelRes) })
        return Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            container.addView(spinner)
        }
    }

    private fun sourceLabels() = listOf(
        getString(R.string.map_detail_source_unspecified),
        getString(R.string.map_detail_source_firsthand),
        getString(R.string.map_detail_source_reported),
        getString(R.string.map_detail_source_uncertain),
    )

    private fun Spinner.selectedSource(): MapInformationSource? = when (selectedItemPosition) {
        1 -> MapInformationSource.FIRSTHAND
        2 -> MapInformationSource.REPORTED_BY_OTHERS
        3 -> MapInformationSource.UNCERTAIN
        else -> null
    }

    private fun feedbackText(feedback: MapItemDetailShellFeedback): String = when (feedback) {
        is MapItemDetailShellFeedback.Accepted -> if (feedback.deliveryWarning == null) {
            getString(R.string.map_detail_saved)
        } else getString(R.string.map_detail_saved_delivery_warning)
        MapItemDetailShellFeedback.AlreadyRecorded -> getString(R.string.map_detail_already_recorded)
        is MapItemDetailShellFeedback.InvalidInput -> getString(R.string.map_detail_input_invalid)
        is MapItemDetailShellFeedback.Failed -> if (feedback.reason == MapInteractionSubmissionFailure.PERSISTENCE_FAILURE) {
            getString(R.string.map_detail_save_failed)
        } else getString(R.string.map_detail_app_failure)
        MapItemDetailShellFeedback.InteractionUnavailable -> getString(R.string.map_detail_action_unavailable)
        is MapItemDetailShellFeedback.Rejected -> when (feedback.reason) {
            MapInteractionRejection.NOT_CURRENT_MEMBER -> getString(R.string.map_detail_rejected_member)
            MapInteractionRejection.TARGET_MISSING,
            MapInteractionRejection.TARGET_TYPE_CONFLICT,
            -> getString(R.string.map_detail_rejected_missing)
            MapInteractionRejection.TARGET_DELETED -> getString(R.string.map_detail_rejected_deleted)
            MapInteractionRejection.CATEGORY_UNRESOLVED,
            MapInteractionRejection.ACTION_INCOMPATIBLE,
            -> getString(R.string.map_detail_rejected_category)
            MapInteractionRejection.TARGET_EXPIRED -> getString(R.string.map_detail_rejected_expired)
            MapInteractionRejection.INTERACTION_WINDOW_CLOSED -> getString(R.string.map_detail_rejected_closed)
            MapInteractionRejection.TARGET_RESOLVED,
            MapInteractionRejection.LIFECYCLE_REACTIVATION_FORBIDDEN,
            -> getString(R.string.map_detail_rejected_resolved)
            MapInteractionRejection.INVALID_ACTION,
            MapInteractionRejection.OTHER,
            -> getString(R.string.map_detail_rejected_generic)
        }
    }

    private fun availabilityReasonText(reason: MapInteractionAvailabilityReason): String = when (reason) {
        MapInteractionAvailabilityReason.DOMAIN_STATE_MISSING -> getString(R.string.map_detail_disabled_missing_domain)
        MapInteractionAvailabilityReason.CATEGORY_UNRESOLVED,
        MapInteractionAvailabilityReason.SITUATION_LIFECYCLE_UNRESOLVED,
        MapInteractionAvailabilityReason.TEMPORAL_STATE_UNRESOLVED,
        -> getString(R.string.map_detail_disabled_unresolved)
        MapInteractionAvailabilityReason.TARGET_DELETED -> getString(R.string.map_detail_disabled_deleted)
        MapInteractionAvailabilityReason.SITUATION_RESOLVED,
        MapInteractionAvailabilityReason.SITUATION_ALREADY_RESOLVED,
        -> getString(R.string.map_detail_disabled_resolved)
        MapInteractionAvailabilityReason.TARGET_EXPIRED -> getString(R.string.map_detail_disabled_expired)
        MapInteractionAvailabilityReason.INTERACTION_WINDOW_CLOSED -> getString(R.string.map_detail_disabled_closed)
        MapInteractionAvailabilityReason.LOCAL_ACTOR_NOT_MEMBER,
        MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNKNOWN,
        MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNAVAILABLE,
        -> getString(R.string.map_detail_disabled_member)
        MapInteractionAvailabilityReason.LOCAL_FEATURE_UNSUPPORTED -> getString(R.string.map_detail_disabled_unsupported)
        MapInteractionAvailabilityReason.INVALID_TARGET_IDENTITY,
        MapInteractionAvailabilityReason.CANONICAL_TARGET_MISSING,
        MapInteractionAvailabilityReason.TARGET_TYPE_CONFLICT,
        MapInteractionAvailabilityReason.INVALID_CANONICAL_STATE,
        MapInteractionAvailabilityReason.INVALID_DOMAIN_STATE,
        MapInteractionAvailabilityReason.INTERNAL_READ_FAILURE,
        MapInteractionAvailabilityReason.UNEXPECTED_COORDINATION_LIFECYCLE,
        -> getString(R.string.map_detail_disabled_invalid)
        MapInteractionAvailabilityReason.ACTION_INCOMPATIBLE_WITH_CATEGORY -> getString(R.string.map_detail_disabled_generic)
    }

    private fun capabilityWarningText(@Suppress("UNUSED_PARAMETER") warning: MapInteractionCapabilityWarning) =
        getString(R.string.map_detail_capability_warning)



    /**
     * M3C4 skeleton integration point. Both source snapshots remain owned by their
     * accepted controllers; this Activity stores only the latest immutable view inputs.
     */
    private fun reprojectCommunityPresentation() {
        if (viewLifecycleEnded) return
        val detailState = latestDetailState ?: return
        latestCommunityProjection = communityProjectionComposer?.project(
            MapItemCommunityProjectionInput(
                identity = detailState.identity,
                detailContent = detailState.content,
                timeline = communityTimelineInput(latestTimelinePresentation),
            ),
        )
        renderCommunityPresentation(latestCommunityProjection)
    }

    private fun renderCommunityPresentation(projection: MapItemCommunityProjectionSnapshot?) {
        latestCommunityViewState = MapItemCommunityDetailViewStateMapper.map(
            projection,
            latestDetailState?.availability,
            latestDetailState?.isSubmissionInFlight == true,
        )
        val container = findViewById<View>(R.id.map_detail_community_section)
        container.visibility = if (latestCommunityViewState.visible) View.VISIBLE else View.GONE
        renderCommunitySection(
            R.id.map_detail_verification_heading,
            R.id.map_detail_verification_body,
            latestCommunityViewState.verification,
        )
        renderCommunitySection(
            R.id.map_detail_freshness_heading,
            R.id.map_detail_freshness_body,
            latestCommunityViewState.freshness,
        )
        renderCommunitySection(
            R.id.map_detail_participation_heading,
            R.id.map_detail_participation_body,
            latestCommunityViewState.participation,
        )
        renderCommunitySection(
            R.id.map_detail_note_preview_heading,
            R.id.map_detail_note_preview_body,
            latestCommunityViewState.notePreview,
        )
    }

    private fun renderCommunitySection(headingId: Int, bodyId: Int, section: MapItemCommunitySection) {
        val viewVisibility = if (section.visible) View.VISIBLE else View.GONE
        findViewById<TextView>(headingId).visibility = viewVisibility
        findViewById<TextView>(bodyId).apply {
            visibility = viewVisibility
            text = section.lines.joinToString("\n", transform = ::communityText)
        }
    }

    private fun communityText(text: MapItemCommunityText): String = when (text) {
        is MapItemCommunityText.Resource -> getString(text.id, *text.arguments.map(::communityArgument).toTypedArray())
        is MapItemCommunityText.Timestamp -> getString(
            text.id,
            *(text.beforeTimestamp.map(::communityArgument) +
                formatTimestamp(text.seconds) +
                text.afterTimestamp.map(::communityArgument)).toTypedArray(),
        )
    }

    private fun communityArgument(argument: Any): Any = when (argument) {
        is MapItemCommunityStringResource -> getString(argument.id)
        else -> argument
    }

    private fun communityTimelineInput(
        timeline: MapItemTimelinePresentation?,
    ): MapItemCommunityTimelineInput = when {
        timeline == null || !timeline.visible || timeline.status == MapItemTimelineStatus.LOADING ->
            MapItemCommunityTimelineInput.NotReady
        timeline.status == MapItemTimelineStatus.INITIAL_FAILURE ->
            MapItemCommunityTimelineInput.Unavailable
        timeline.retainedHistory ||
            timeline.status == MapItemTimelineStatus.REFRESH_FAILURE ||
            timeline.status == MapItemTimelineStatus.APPEND_FAILURE ->
            MapItemCommunityTimelineInput.RetainedAfterFailure(timeline.entries)
        else -> MapItemCommunityTimelineInput.Available(timeline.entries)
    }

    private fun formatTimestamp(seconds: Long): String =
        if (seconds <= Long.MAX_VALUE / 1_000L) {
            DateFormat.getDateTimeInstance().format(Date(seconds * 1_000L))
        } else {
            getString(R.string.map_detail_timeline_timestamp_unavailable)
        }

    private fun startTimelineAfterDetail(identity: MapItemIdentity) {
        if (viewLifecycleEnded || timelineController != null) return
        try {
            timelineController = createAndStartTimeline(identity)
        } catch (_: Exception) {
            renderTimelinePresentation(timelineCoordinator.onTimelineStartupFailure())
        }
    }

    private fun createAndStartTimeline(identity: MapItemIdentity): MapItemTimelineController {
        val candidate = createTimelineController(identity, MapItemTimelineStateObserver(::renderTimeline))
        try {
            candidate.loadInitial()
            return candidate
        } catch (exception: Exception) {
            candidate.close()
            throw exception
        } catch (error: Error) {
            candidate.close()
            throw error
        }
    }

    /** Loads display-only map member names without leaking a DAO into timeline rows. */
    private fun loadTimelineActorNames(identity: MapItemIdentity) {
        val generation = ++timelineActorNameLoadGeneration
        Thread {
            val projections = AppDatabaseProvider.get(applicationContext)
                .userProfileDao()
                .listByGid(identity.groupId)
                .associate { profile ->
                    profile.actor_did to MapOwnerNameProjection(groupNickname = profile.display_name)
                }
            runOnUiThread {
                if (viewLifecycleEnded || generation != timelineActorNameLoadGeneration || timelineIdentity != identity) {
                    return@runOnUiThread
                }
                timelineActorNames = projections
                timelineAdapter?.refreshEntryTexts()
            }
        }.start()
    }

    /** Composition seams retain production defaults while allowing Android tests to fake accepted boundaries. */
    protected open fun createDetailDependencies() = MapItemDetailShellFactory.create(applicationContext)

    protected open fun createDetailExecution(): MapItemDetailShellExecution = AndroidMapItemDetailShellExecution()

    protected open fun createTimelineController(
        identity: MapItemIdentity,
        observer: MapItemTimelineStateObserver,
    ): MapItemTimelineController = MapItemTimelineControllerFactory.create(
        database = AppDatabaseProvider.get(applicationContext),
        identity = identity,
        observer = observer,
    )

    /** M3B3 Android-only rendering; controller remains the timeline state owner. */
    private fun renderTimeline(state: MapItemTimelineState) {
        if (viewLifecycleEnded) return
        renderTimelinePresentation(timelineCoordinator.onTimelineState(state))
    }

    private fun renderTimelinePresentation(presentation: MapItemTimelinePresentation) {
        if (viewLifecycleEnded) return
        latestTimelinePresentation = presentation
        reprojectCommunityPresentation()
        val section = findViewById<View>(R.id.map_detail_timeline_section)
        if (!presentation.visible) {
            section.visibility = View.GONE
            return
        }
        val retainedNotice = findViewById<TextView>(R.id.map_detail_timeline_retained_notice)
        val status = findViewById<TextView>(R.id.map_detail_timeline_status)
        val retry = findViewById<Button>(R.id.map_detail_timeline_retry)
        val loadOlder = findViewById<Button>(R.id.map_detail_timeline_load_older)
        val list = findViewById<RecyclerView>(R.id.map_detail_timeline_list)

        section.visibility = View.VISIBLE
        retainedNotice.visibility = if (presentation.retainedHistory) View.VISIBLE else View.GONE
        retainedNotice.text = if (presentation.retainedHistory) {
            getString(R.string.map_detail_timeline_retained_history)
        } else {
            ""
        }
        timelineAdapter?.submitEntries(presentation.entries)
        val acknowledgedByCurrentUser = presentation.entries.any { entry ->
            entry.type == MapItemActivityType.COORDINATION_ACKNOWLEDGED &&
                entry.actor.stableId == DeviceIdProvider.get(this)
        }
        if (acknowledgedByCurrentUser && !currentUserAcknowledgedCoordination) {
            currentUserAcknowledgedCoordination = true
            latestDetailState?.let(::renderInteractions)
        }
        list.visibility = if (presentation.entries.isEmpty()) View.GONE else View.VISIBLE
        status.text = when (presentation.status) {
            MapItemTimelineStatus.NONE -> ""
            MapItemTimelineStatus.LOADING -> getString(R.string.map_detail_timeline_loading)
            MapItemTimelineStatus.EMPTY -> getString(R.string.map_detail_timeline_empty)
            MapItemTimelineStatus.REFRESHING -> getString(R.string.map_detail_timeline_refreshing)
            MapItemTimelineStatus.INITIAL_FAILURE -> getString(R.string.map_detail_timeline_load_failed)
            MapItemTimelineStatus.REFRESH_FAILURE -> getString(R.string.map_detail_timeline_refresh_failed)
            MapItemTimelineStatus.APPEND_FAILURE -> getString(R.string.map_detail_timeline_append_failed)
            MapItemTimelineStatus.END_REACHED -> getString(R.string.map_detail_timeline_end_reached)
        }
        status.visibility = if (presentation.status == MapItemTimelineStatus.NONE) View.GONE else View.VISIBLE
        retry.visibility = if (presentation.retryOperation == null) View.GONE else View.VISIBLE
        loadOlder.visibility = if (presentation.olderAction == MapItemTimelineOlderAction.HIDDEN) View.GONE else View.VISIBLE
        loadOlder.isEnabled = presentation.loadOlderEnabled
        loadOlder.text = when (presentation.olderAction) {
            MapItemTimelineOlderAction.HIDDEN,
            MapItemTimelineOlderAction.LOAD,
            -> getString(R.string.map_detail_timeline_load_older)
            MapItemTimelineOlderAction.LOADING -> getString(R.string.map_detail_timeline_loading_older)
            MapItemTimelineOlderAction.RETRY -> getString(R.string.map_detail_timeline_retry_older)
        }
    }

    private fun updateAcknowledgementCtaState(feedback: MapItemDetailShellFeedback?) {
        if (!acknowledgementAwaitingOutcome) return
        when (feedback) {
            is MapItemDetailShellFeedback.Accepted,
            MapItemDetailShellFeedback.AlreadyRecorded,
            -> {
                currentUserAcknowledgedCoordination = true
                acknowledgementAwaitingOutcome = false
            }
            is MapItemDetailShellFeedback.Rejected,
            is MapItemDetailShellFeedback.InvalidInput,
            is MapItemDetailShellFeedback.Failed,
            MapItemDetailShellFeedback.InteractionUnavailable,
            -> acknowledgementAwaitingOutcome = false
            null -> Unit
        }
    }

    /** Localized Android rendering of the immutable M3B1 activity vocabulary. */
    private fun timelineEntryText(entry: MapItemActivityEntry): CharSequence {
        val action = when (entry.type) {
            MapItemActivityType.SITUATION_CONFIRMED ->
                getString(R.string.map_detail_timeline_situation_confirmed)
            MapItemActivityType.SITUATION_UPDATED ->
                getString(R.string.map_detail_timeline_situation_updated)
            MapItemActivityType.SITUATION_MARKED_NO_LONGER_ACTIVE ->
                getString(R.string.map_detail_timeline_situation_resolved)
            MapItemActivityType.COORDINATION_ACKNOWLEDGED ->
                getString(R.string.map_detail_timeline_coordination_acknowledged)
            MapItemActivityType.COORDINATION_NOTE_ADDED ->
                getString(R.string.map_detail_timeline_coordination_note_added)
        }
        val actor = timelineActorName(entry.actor.stableId)
        return buildList {
            add(
                getString(
                    R.string.map_detail_timeline_entry_summary,
                    action,
                    actor,
                    formatTimelineRelativeTime(entry.actionTimestampSeconds),
                ),
            )
            entry.informationSource?.let { source ->
                val value = when (source) {
                    MapInformationSource.FIRSTHAND -> getString(R.string.map_detail_source_firsthand)
                    MapInformationSource.REPORTED_BY_OTHERS -> getString(R.string.map_detail_source_reported)
                    MapInformationSource.UNCERTAIN -> getString(R.string.map_detail_source_uncertain)
                }
                add(getString(R.string.map_detail_timeline_source, value))
            }
            entry.updateDisposition?.let { disposition ->
                val value = when (disposition) {
                    MapSituationUpdateType.STILL_ACTIVE -> getString(R.string.map_detail_update_still_active)
                    MapSituationUpdateType.SITUATION_CHANGED -> getString(R.string.map_detail_update_changed)
                }
                add(getString(R.string.map_detail_timeline_disposition, value))
            }
            entry.targetLifecycle?.let { lifecycle ->
                val value = when (lifecycle) {
                    MapSituationLifecycleStatus.ACTIVE -> getString(R.string.map_detail_timeline_lifecycle_active)
                    MapSituationLifecycleStatus.NO_LONGER_ACTIVE ->
                        getString(R.string.map_detail_timeline_lifecycle_no_longer_active)
                }
                add(getString(R.string.map_detail_timeline_target_lifecycle, value))
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                add(getString(R.string.map_detail_timeline_note, boundedTimelineText(note)))
            }
        }.joinToString("\n")
    }

    private fun timelineActorName(actorDid: String?): String = when (
        val displayName = MapOwnerDisplayNameResolver.resolve(
            ownerActorDid = actorDid,
            activeActorDid = DeviceIdProvider.get(this),
            projections = timelineActorNames,
        )
    ) {
        MapOwnerDisplayName.You -> getString(R.string.map_detail_timeline_actor_you)
        is MapOwnerDisplayName.Named -> displayName.value
        MapOwnerDisplayName.GroupMember -> getString(R.string.map_detail_timeline_actor_group_member)
    }

    private fun formatTimelineRelativeTime(timestampSeconds: Long): String {
        if (timestampSeconds <= 0L || timestampSeconds > Long.MAX_VALUE / 1_000L) {
            return getString(R.string.map_detail_timeline_timestamp_unavailable)
        }
        val deltaSeconds = System.currentTimeMillis() / 1_000L - timestampSeconds
        val duration = when (val absoluteSeconds = deltaSeconds.absoluteValue) {
            in 0..59 -> "${absoluteSeconds}s"
            in 60..3_599 -> "${absoluteSeconds / 60}m"
            in 3_600..86_399 -> "${absoluteSeconds / 3_600}h"
            else -> "${absoluteSeconds / 86_400}d"
        }
        return getString(
            if (deltaSeconds >= 0L) R.string.map_age_ago_format else R.string.map_age_in_format,
            duration,
        )
    }

    private fun boundedTimelineText(value: String): String =
        if (value.length <= MAX_TIMELINE_FIELD_CHARS) value else value.take(MAX_TIMELINE_FIELD_CHARS) + "…"

    private fun setText(id: Int, value: CharSequence) {
        findViewById<TextView>(id).apply {
            text = value
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private const val MAX_TIMELINE_FIELD_CHARS = 4_000

        fun intent(context: Context, identity: MapItemIdentity): Intent =
            DefaultMapItemDetailNavigationArguments.put(
                Intent(context, MapItemDetailActivity::class.java),
                identity,
            )
    }
}
