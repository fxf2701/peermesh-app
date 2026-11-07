package app.peermesh.map.presentation.map.resources

internal const val MAX_MAP_LABEL_PREVIEW_CHARS = 48

internal fun boundedMapLabelPreview(value: String): String =
    if (value.length <= MAX_MAP_LABEL_PREVIEW_CHARS) {
        value
    } else {
        value.take(MAX_MAP_LABEL_PREVIEW_CHARS - 1).trimEnd() + "…"
    }
