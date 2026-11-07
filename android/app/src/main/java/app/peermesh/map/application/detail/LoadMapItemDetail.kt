package app.peermesh.map.application.detail

fun interface LoadMapItemDetail {
    fun load(identity: MapItemIdentity): MapItemDetailLoadResult
}
