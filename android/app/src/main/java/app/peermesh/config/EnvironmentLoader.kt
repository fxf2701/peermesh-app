package app.peermesh.config

import app.peermesh.BuildConfig

object EnvironmentLoader {
    fun load(): AppSettings {
        return AppSettings(
            environment = parseEnvironment(BuildConfig.ENV_MODE),
            distribution = parseDistribution(BuildConfig.DIST_NAME),
            relayBaseUrl = BuildConfig.RELAY_BASE_URL.trim(),
            registryBaseUrl = BuildConfig.REGISTRY_BASE_URL.trim(),
            relayRequestSignatureSecret = BuildConfig.RELAY_REQUEST_SIGNATURE_SECRET.trim(),
            registryRequestSignatureSecret = BuildConfig.REGISTRY_REQUEST_SIGNATURE_SECRET.trim(),
        )
    }

    private fun parseEnvironment(value: String): Environment {
        return when (value.lowercase()) {
            "dev" -> Environment.DEV
            "prod" -> Environment.PROD
            else -> Environment.PROD
        }
    }

    private fun parseDistribution(value: String): Distribution {
        return when (value.lowercase()) {
            "emergency" -> Distribution.EMERGENCY
            "official" -> Distribution.OFFICIAL
            else -> Distribution.OFFICIAL
        }
    }
}
