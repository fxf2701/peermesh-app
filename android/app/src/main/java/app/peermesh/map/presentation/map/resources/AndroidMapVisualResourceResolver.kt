package app.peermesh.map.presentation.map.resources

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import app.peermesh.R
import app.peermesh.MapKinds
import app.peermesh.map.application.rendering.MapAccessibilityCategory
import app.peermesh.map.application.rendering.MapAccessibilityStateToken
import app.peermesh.map.application.rendering.MapCategoryVisualStyle
import app.peermesh.map.application.rendering.MapCoordinationParticipationVisualStyle
import app.peermesh.map.application.rendering.MapItemRenderSpec
import app.peermesh.map.application.rendering.MapItemProminence
import app.peermesh.map.application.rendering.MapLabelMode
import app.peermesh.map.application.rendering.MapLifecycleVisualStyle
import app.peermesh.map.application.rendering.MapRadiusMode
import app.peermesh.map.application.rendering.MapSituationEvidenceVisualStyle
import app.peermesh.map.application.rendering.MapTemporalVisualStyle
import app.peermesh.map.application.scene.MapSceneGeometry

/**
 * Android-only M3D2 mapper from platform-neutral visual tokens to concrete resources.
 * It must not derive category, lifecycle, evidence, participation, or temporal semantics.
 */
class AndroidMapVisualResourceResolver(
    private val context: Context,
) : MapVisualResourceResolver {
    override fun resolve(spec: MapItemRenderSpec): AndroidMapRenderModel {
        val palette = palette(spec)
        val label = if (spec.visual.labelMode == MapLabelMode.SHORT) {
            spec.content.label?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val preview = boundedMapLabelPreview(it)
                // A marker's label is a separate osmdroid overlay. Place it
                // below the semantic icon, matching Area labels, so it cannot
                // obscure the status badge at the icon's upper-right corner.
                val leadingInsetPx = if (spec.geometry is MapSceneGeometry.Marker) {
                    0
                } else {
                    0
                }
                val topInsetPx = if (
                    spec.geometry is MapSceneGeometry.Marker ||
                    spec.geometry is MapSceneGeometry.Area
                ) {
                    // Marker labels sit half a badge-height closer to their
                    // icon than Area labels: close enough to associate, but
                    // still below the icon rather than alongside it.
                    val labelTopInsetDp = when (spec.geometry) {
                        is MapSceneGeometry.Marker -> if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 17f else 14f
                        is MapSceneGeometry.Area -> if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 29f else 26f
                    }
                    dp(labelTopInsetDp).toInt()
                } else {
                    0
                }
                ResolvedMapLabel(
                    preview,
                    TextBadgeDrawable(context, preview, palette.outline, leadingInsetPx, topInsetPx),
                    leadingInsetPx,
                    topInsetPx,
                )
            }
        } else {
            null
        }
        val description = contentDescription(spec)
        val zIndex = when (spec.visual.prominence) {
            MapItemProminence.MUTED -> 0f
            MapItemProminence.STANDARD -> 1f
            MapItemProminence.EMPHASIZED -> 2f
        }
        return when (val geometry = spec.geometry) {
            is MapSceneGeometry.Marker -> AndroidMapRenderModel.Marker(
                identity = spec.identity,
                latitude = geometry.latitude,
                longitude = geometry.longitude,
                icon = markerDrawable(spec, palette),
                alpha = palette.alpha,
                zIndex = zIndex,
                label = label,
                radius = geometry.radiusMeters?.takeIf {
                    spec.visual.radiusMode == MapRadiusMode.VISIBLE && it.isFinite() && it > 0.0
                }?.let {
                    ResolvedMapRadius(it, palette.fill, palette.outline, dp(2f))
                },
                contentDescription = description,
            )
            is MapSceneGeometry.Area -> AndroidMapRenderModel.Area(
                identity = spec.identity,
                geometry = geometry.geometry,
                kindIcon = areaKindDrawable(spec, palette),
                fillColor = palette.fill,
                outlineColor = palette.outline,
                outlineWidthPx = if (spec.visual.prominence == MapItemProminence.EMPHASIZED) dp(4f) else dp(2f),
                outlinePattern = ResolvedMapOutlinePattern(
                    when {
                        spec.visual.temporal == MapTemporalVisualStyle.EXPIRED ->
                            android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
                        spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED ->
                            android.graphics.DashPathEffect(floatArrayOf(dp(12f), dp(4f)), 0f)
                        spec.visual.temporal == MapTemporalVisualStyle.INTERACTION_CLOSED ->
                            android.graphics.DashPathEffect(floatArrayOf(dp(2f), dp(2f)), 0f)
                        spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.HISTORICAL_OR_STALE_ONLY ->
                            android.graphics.DashPathEffect(floatArrayOf(dp(10f), dp(2f), dp(2f), dp(2f)), 0f)
                        spec.visual.category == MapCategoryVisualStyle.UNRESOLVED ||
                            spec.visual.category == MapCategoryVisualStyle.CANONICAL_ONLY ->
                            android.graphics.DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
                        else -> null
                    },
                ),
                zIndex = zIndex,
                label = label,
                contentDescription = description,
            )
        }
    }

    private data class Palette(val fill: Int, val outline: Int, val alpha: Int)

    private fun palette(spec: MapItemRenderSpec): Palette {
        val base = when (spec.visual.category) {
            MapCategoryVisualStyle.SITUATION -> Color.rgb(211, 72, 54)
            MapCategoryVisualStyle.COORDINATION -> Color.rgb(35, 112, 184)
            MapCategoryVisualStyle.UNRESOLVED -> Color.rgb(105, 105, 105)
            MapCategoryVisualStyle.CANONICAL_ONLY -> Color.rgb(121, 85, 72)
        }
        val evidenceOutline = when {
            // Resolution is more authoritative than historical or current
            // confirmation. Its dashed border and muted outline must remain
            // visible even if an old confirmation exists in the source state.
            spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> darken(base)
            spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT ->
                Color.rgb(32, 111, 58)
            spec.visual.coordinationParticipation == MapCoordinationParticipationVisualStyle.ACKNOWLEDGED ->
                Color.rgb(30, 80, 155)
            else -> darken(base)
        }
        val semanticAlpha = when {
            spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> 145
            spec.visual.temporal == MapTemporalVisualStyle.EXPIRED -> 120
            spec.visual.prominence == MapItemProminence.MUTED -> 140
            else -> 235
        }
        return Palette(
            fill = Color.argb((semanticAlpha * 0.28f).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
            outline = evidenceOutline,
            alpha = if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 255 else semanticAlpha,
        )
    }

    private fun markerDrawable(spec: MapItemRenderSpec, palette: Palette): Drawable =
        SemanticMarkerDrawable(context, spec, palette)

    private fun areaKindDrawable(spec: MapItemRenderSpec, palette: Palette): Drawable =
        AreaKindDrawable(context, spec, palette)

    private fun contentDescription(spec: MapItemRenderSpec): String {
        val parts = mutableListOf(
            when (spec.accessibility.category) {
                MapAccessibilityCategory.SITUATION -> context.getString(R.string.map_render_category_situation)
                MapAccessibilityCategory.COORDINATION -> context.getString(R.string.map_render_category_coordination)
                MapAccessibilityCategory.UNRESOLVED_ITEM -> context.getString(R.string.map_render_category_limited)
            },
            context.getString(
                when (spec.geometry) {
                    is MapSceneGeometry.Marker -> R.string.map_render_shape_marker
                    is MapSceneGeometry.Area -> R.string.map_render_shape_area
                },
            ),
        )
        kindDescription(spec.content.normalizedKind)?.let { parts += it }
        spec.content.label?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += boundedMapLabelPreview(it)
        }
        if (
            spec.visual.emphasis == app.peermesh.map.application.rendering.MapItemEmphasis.SELECTED ||
            spec.visual.emphasis ==
            app.peermesh.map.application.rendering.MapItemEmphasis.SELECTED_AND_FOCUSED
        ) {
            parts += context.getString(R.string.map_render_state_selected)
        }
        spec.accessibility.stateTokens.distinct().forEach { token ->
            parts += context.getString(
                when (token) {
                    MapAccessibilityStateToken.ACTIVE -> R.string.map_render_state_active
                    MapAccessibilityStateToken.RESOLVED -> R.string.map_render_state_resolved
                    MapAccessibilityStateToken.EXPIRED -> R.string.map_render_state_expired
                    MapAccessibilityStateToken.INTERACTION_CLOSED -> R.string.map_render_state_interaction_closed
                    MapAccessibilityStateToken.CURRENT_EVIDENCE_PRESENT ->
                        R.string.map_render_state_current_evidence
                    MapAccessibilityStateToken.NO_CURRENT_EVIDENCE ->
                        R.string.map_render_state_no_current_evidence
                    MapAccessibilityStateToken.ACKNOWLEDGEMENT_PRESENT ->
                        R.string.map_render_state_acknowledged
                    MapAccessibilityStateToken.NO_ACKNOWLEDGEMENT ->
                        R.string.map_render_state_not_acknowledged
                    MapAccessibilityStateToken.AREA_BOUNDARY_NOT_VERIFIED ->
                        R.string.map_render_state_area_boundary_limit
                    MapAccessibilityStateToken.LIMITED_INFORMATION ->
                        R.string.map_render_state_limited_information
                },
            )
        }
        return parts.joinToString(context.getString(R.string.map_render_description_separator))
    }

    private fun kindDescription(kind: String?): String? = MapKinds.labelForKey(kind)
        ?.substringAfter(' ', missingDelimiterValue = "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: kind?.trim()?.takeIf { it.isNotEmpty() }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.65f).toInt(),
        (Color.green(color) * 0.65f).toInt(),
        (Color.blue(color) * 0.65f).toInt(),
    )

    private fun withOpaqueAlpha(color: Int): Int = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Runtime-only semantic marker artwork. Category is encoded by outline shape;
     * kind is the central emoji; evidence/resolution have an additional glyph or
     * dash pattern so their meaning never depends on color alone.
     */
    private class SemanticMarkerDrawable(
        context: Context,
        private val spec: MapItemRenderSpec,
        private val palette: Palette,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val size = (
            if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 36f else 30f
        ) * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withOpaqueAlpha(palette.fill)
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.outline
            style = Paint.Style.STROKE
            strokeWidth = when {
                spec.visual.prominence == MapItemProminence.EMPHASIZED -> 4f * density
                spec.visual.temporal == MapTemporalVisualStyle.INTERACTION_CLOSED -> 4f * density
                spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.HISTORICAL_OR_STALE_ONLY -> 3f * density
                else -> 2f * density
            }
            pathEffect = when {
                spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED ->
                    DashPathEffect(floatArrayOf(7f * density, 3f * density), 0f)
                spec.visual.temporal == MapTemporalVisualStyle.EXPIRED ->
                    DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)
                spec.visual.temporal == MapTemporalVisualStyle.INTERACTION_CLOSED ->
                    DashPathEffect(floatArrayOf(2f * density, 2f * density), 0f)
                spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.HISTORICAL_OR_STALE_ONLY ->
                    DashPathEffect(floatArrayOf(8f * density, 2f * density), 0f)
                else -> null
            }
        }
        private val kindPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            // Kind is the primary non-color map cue. Keep it large enough to
            // remain recognizable on small, dense devices such as Galaxy A10s.
            textSize = if (spec.visual.prominence == MapItemProminence.EMPHASIZED) {
                22f * density
            } else {
                19f * density
            }
        }
        private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 11f * density
        }
        private val kindToken = MapKinds.labelForKey(spec.content.normalizedKind)
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }
            ?: when (spec.visual.category) {
                MapCategoryVisualStyle.SITUATION -> "!"
                MapCategoryVisualStyle.COORDINATION -> "↗"
                else -> "?"
            }

        init {
            setBounds(0, 0, size.toInt().coerceAtLeast(1), size.toInt().coerceAtLeast(1))
        }

        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds).apply { inset(outlinePaint.strokeWidth / 2f, outlinePaint.strokeWidth / 2f) }
            val isSituation = spec.visual.category == MapCategoryVisualStyle.SITUATION
            if (isSituation) {
                canvas.drawOval(rect, fillPaint)
                canvas.drawOval(rect, outlinePaint)
            } else {
                val radius = if (spec.visual.category == MapCategoryVisualStyle.COORDINATION) 4f * density else 10f * density
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                canvas.drawRoundRect(rect, radius, radius, outlinePaint)
            }
            val centerY = rect.centerY() - (kindPaint.ascent() + kindPaint.descent()) / 2f
            canvas.drawText(kindToken, rect.centerX(), centerY, kindPaint)

            val cue = when {
                spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> "×"
                spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT -> "✓"
                spec.visual.coordinationParticipation == MapCoordinationParticipationVisualStyle.ACKNOWLEDGED -> "•"
                else -> null
            }
            cue?.let {
                val cueRadius = 7f * density
                // Keep marker status at the icon's top-right corner. The
                // label is rendered below this drawable, so it cannot cover
                // confirmed (✓), resolved (×), or acknowledged (•) state.
                val x = rect.right - cueRadius * 1f
                val y = rect.top + cueRadius * 1f
                val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.outline }
                canvas.drawCircle(x, y, cueRadius, badge)
                val textY = y - (cuePaint.ascent() + cuePaint.descent()) / 2f
                canvas.drawText(it, x, textY, cuePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            outlinePaint.alpha = alpha
            kindPaint.alpha = alpha
            cuePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            outlinePaint.colorFilter = colorFilter
            kindPaint.colorFilter = colorFilter
            cuePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun withOpaqueAlpha(color: Int): Int = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Area artwork deliberately uses a dashed circle rather than the marker's
     * category frame. The emoji stays at the polygon centroid, so area kind is
     * visible without being mistaken for a point marker.
     */
    private class AreaKindDrawable(
        context: Context,
        private val spec: MapItemRenderSpec,
        private val palette: Palette,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val size = (
            if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 50f else 44f
        ) * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Do not reuse category color here: the large dark-gray dashed
            // circle is the explicit area-vs-marker shape cue on every fill.
            color = Color.rgb(70, 70, 70)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        }
        private val kindPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = if (spec.visual.prominence == MapItemProminence.EMPHASIZED) {
                25f * density
            } else {
                22f * density
            }
        }
        private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 11f * density
        }
        private val kindToken = MapKinds.labelForKey(spec.content.normalizedKind)
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }
            ?: "?"

        init {
            setBounds(0, 0, size.toInt().coerceAtLeast(1), size.toInt().coerceAtLeast(1))
        }

        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds).apply { inset(borderPaint.strokeWidth / 2f, borderPaint.strokeWidth / 2f) }
            canvas.drawOval(rect, fillPaint)
            canvas.drawOval(rect, borderPaint)
            val centerY = rect.centerY() - (kindPaint.ascent() + kindPaint.descent()) / 2f
            canvas.drawText(kindToken, rect.centerX(), centerY, kindPaint)

            // Match marker evidence/lifecycle affordances so an area does not
            // lose its active/confirmed/resolved status at the centroid.
            val cue = when {
                spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> "×"
                spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT -> "✓"
                spec.visual.coordinationParticipation == MapCoordinationParticipationVisualStyle.ACKNOWLEDGED -> "•"
                else -> null
            }
            cue?.let {
                val cueRadius = 7f * density
                val x = rect.right - cueRadius * .55f
                val y = rect.top + cueRadius * .7f
                val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.outline }
                canvas.drawCircle(x, y, cueRadius, badge)
                val textY = y - (cuePaint.ascent() + cuePaint.descent()) / 2f
                canvas.drawText(it, x, textY, cuePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            borderPaint.alpha = alpha
            kindPaint.alpha = alpha
            cuePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            kindPaint.colorFilter = colorFilter
            cuePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = size.toInt().coerceAtLeast(1)
        override fun getIntrinsicHeight(): Int = size.toInt().coerceAtLeast(1)
    }

    private class TextBadgeDrawable(
        context: Context,
        private val text: String,
        color: Int,
        private val leadingInsetPx: Int,
        private val topInsetPx: Int,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val badgeWidthPx = (textPaint.measureText(text) + 16f * density).toInt().coerceAtLeast(1)
        private val widthPx = (leadingInsetPx + badgeWidthPx).coerceAtLeast(1)
        private val badgeHeightPx = (24f * density).toInt().coerceAtLeast(1)
        private val heightPx = (topInsetPx + badgeHeightPx).coerceAtLeast(1)

        init {
            setBounds(0, 0, widthPx, heightPx)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(
                (bounds.left + leadingInsetPx).toFloat(),
                (bounds.top + topInsetPx).toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                6f * density,
                6f * density,
                backgroundPaint,
            )
            val badgeCenterY = bounds.top + topInsetPx + badgeHeightPx / 2f
            val y = badgeCenterY - (textPaint.ascent() + textPaint.descent()) / 2f
            val badgeCenterX = bounds.left + leadingInsetPx + badgeWidthPx / 2f
            canvas.drawText(text, badgeCenterX, y, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            textPaint.alpha = alpha
            backgroundPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            textPaint.colorFilter = colorFilter
            backgroundPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = widthPx
        override fun getIntrinsicHeight(): Int = heightPx
    }

}
