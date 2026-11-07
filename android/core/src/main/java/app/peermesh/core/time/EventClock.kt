package app.peermesh.core.time

data class EventClock(
    val t: Long,
    val lt: Long,
    val actorDid: String,
)

object EventClockComparator {
    fun compare(a: EventClock, b: EventClock): Int {
        if (a.t != b.t) return a.t.compareTo(b.t)
        if (a.lt != b.lt) return a.lt.compareTo(b.lt)
        return a.actorDid.compareTo(b.actorDid)
    }

    fun isNewer(candidate: EventClock, current: EventClock?): Boolean {
        if (current == null) return true
        return compare(candidate, current) > 0
    }
}
