package app.peermesh.data.map.processing

/** Shared local/remote map acceptance seam. Production integration is deferred to M2C4. */
fun interface MapEventProcessor {
    fun process(request: MapEventProcessingRequest): MapEventProcessingResult
}
