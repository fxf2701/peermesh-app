package app.peermesh.transport.ble.dtn

data class DtnDiagnosticEvent(
    val label: String,
    val type: String? = null,
    val direction: String? = null,
    val peerId: String? = null,
    val gid: String? = null,
    val sessionId: String? = null,
    val idCount: Int = 0,
    val dataCount: Int = 0,
    val responseCount: Int = 0,
    val sentCount: Int = 0,
    val idSample: List<String> = emptyList(),
    val dataIdSample: List<String> = emptyList(),
    val reason: String? = null,
)

fun interface DtnDiagnosticsSink {
    fun log(event: DtnDiagnosticEvent)
}
