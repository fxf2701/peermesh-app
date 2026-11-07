package app.peermesh.map.application.interaction

fun interface MapAvailabilityClock {
    fun nowSeconds(): Long
}

object SystemMapAvailabilityClock : MapAvailabilityClock {
    override fun nowSeconds(): Long = System.currentTimeMillis() / 1_000L
}
