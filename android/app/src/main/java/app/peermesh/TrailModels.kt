package app.peermesh

import kotlinx.serialization.Serializable

@Serializable
data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val t: Long,
    val acc: Float? = null,
    val spd: Float? = null,
    val mm: Int = 0,
)

@Serializable
data class TrailRecord(
    val id: String,
    val name: String,
    val mode: String,
    val start_t: Long,
    val end_t: Long,
    val points: List<TrailPoint>,
    val point_count: Int,
    val dist_m: Float = 0f,
    val unstable: Int = 0,
    val unstable_note: String? = null,
)
