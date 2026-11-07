package app.peermesh.data.map.local

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.processing.RoomMapEventProcessorFactory

/**
 * Public app composition seam for M2C4B.
 *
 * The concrete Room processor and its reducer/DAO graph remain internal to the data layer. Local
 * and remote production paths must continue to be constructed from the same accepted processor
 * factory rather than duplicating the graph.
 */
object LocalMapEventWriterFactory {
    fun lazy(db: AppDatabase): LocalMapEventWriter {
        val writer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            RoomLocalMapEventWriter(
                processor = RoomMapEventProcessorFactory.lazy(db),
            )
        }
        return LocalMapEventWriter { request -> writer.write(request) }
    }
}
