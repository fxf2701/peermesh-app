package app.peermesh.beta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BetaProgramConfig(
    val enabled: Boolean = false,
    val season: String? = null,
    val endsAt: String? = null,
    val latestVersion: String? = null,
    val minimumSupportedVersion: String? = null,
    val changelog: List<String> = emptyList(),
)

@Serializable
internal data class RegistryBetaProgramEnvelope(
    val ok: Boolean? = null,
    @SerialName("beta_program") val betaProgramSnake: BetaProgramConfig? = null,
    val betaProgram: BetaProgramConfig? = null,
) {
    fun resolvedConfig(): BetaProgramConfig? = betaProgramSnake ?: betaProgram
}
