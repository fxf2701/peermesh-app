package app.peermesh.map.application.detail

data class MapItemDetail(
    val identity: MapItemIdentity,
    val canonical: MapItemCanonicalPresentation,
    val domain: MapItemDomainPresentation?,
)
