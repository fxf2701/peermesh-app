package app.peermesh.map.application.rendering

data class MapRenderSnapshot(
    val revision: Long,
    val groupId: String,
    val items: List<MapItemRenderSpec>,
) {
    init {
        require(revision >= 0L) { "revision must be non-negative" }
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(items.all { it.identity.groupId == groupId }) {
            "all render items must belong to the snapshot group"
        }
        require(items.map { it.identity }.distinct().size == items.size) {
            "render snapshot identities must be unique"
        }
    }
}
