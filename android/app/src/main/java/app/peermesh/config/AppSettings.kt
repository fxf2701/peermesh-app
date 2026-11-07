package app.peermesh.config

data class AppSettings(
    val environment: Environment,
    val distribution: Distribution,
    val relayBaseUrl: String,
    val registryBaseUrl: String,
    val relayRequestSignatureSecret: String,
    val registryRequestSignatureSecret: String,
)

enum class Environment {
    DEV,
    PROD,
}

enum class Distribution {
    OFFICIAL,
    EMERGENCY,
}
