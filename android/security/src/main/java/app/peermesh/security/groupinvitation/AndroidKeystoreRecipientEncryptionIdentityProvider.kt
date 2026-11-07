package app.peermesh.security.groupinvitation

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

private typealias Reason = RecipientEncryptionIdentityStatus.Reason

internal sealed interface RecipientResetRecoveryState {
    data object Absent : RecipientResetRecoveryState
    data object InProgress : RecipientResetRecoveryState
    data object Indeterminate : RecipientResetRecoveryState
    data object Invalid : RecipientResetRecoveryState
    data object ReadFailure : RecipientResetRecoveryState
}

internal fun recoveryStatus(
    resetState: RecipientResetRecoveryState,
    aliasExists: Boolean,
): RecipientEncryptionIdentityStatus? = when (resetState) {
    RecipientResetRecoveryState.ReadFailure ->
        RecipientEncryptionIdentityStatus.TemporarilyUnavailable(Reason.METADATA_UNAVAILABLE)
    RecipientResetRecoveryState.Invalid ->
        RecipientEncryptionIdentityStatus.Invalidated(Reason.RESET_RECOVERY_REQUIRED)
    RecipientResetRecoveryState.InProgress,
    RecipientResetRecoveryState.Indeterminate ->
        if (aliasExists) null else RecipientEncryptionIdentityStatus.Invalidated(Reason.RESET_RECOVERY_REQUIRED)
    RecipientResetRecoveryState.Absent -> null
}

class AndroidKeystoreRecipientEncryptionIdentityProvider internal constructor(
    context: Context,
    private val keyStoreLoader: () -> KeyStore,
    private val metadataClearer: (() -> Boolean)? = null,
    private val aliasDeleter: ((KeyStore) -> Unit)? = null,
    private val aliasExistsChecker: ((KeyStore) -> Boolean)? = null,
) : RecipientEncryptionIdentityProvider {
    constructor(context: Context) : this(
        context = context,
        keyStoreLoader = {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        },
    )

    private val metadata = context.applicationContext.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)

    override fun getOrCreateIdentity(): RecipientEncryptionIdentityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RecipientEncryptionIdentityStatus.UnsupportedPlatform
        }
        return synchronized(PROCESS_LOCK) {
            val keyStore = try {
                loadKeyStore()
            } catch (_: GeneralSecurityException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            }
            val metadataState = readMetadata()
            val resetState = readResetState()
            val aliasExists = try {
                keyStore.containsAlias(KEY_ALIAS)
            } catch (_: GeneralSecurityException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            }
            recoveryStatus(resetState, aliasExists)?.let { return@synchronized it }
            if (!aliasExists) {
                probedKeyId = null
                if (metadataState !is MetadataState.Missing) {
                    return@synchronized invalidated(
                        if (metadataState is MetadataState.Invalid) Reason.METADATA_INCONSISTENT else Reason.ALIAS_MISSING,
                    )
                }
                try {
                    generateKeyPair()
                } catch (_: GeneralSecurityException) {
                    return@synchronized temporary(Reason.KEY_GENERATION_FAILED)
                } catch (_: RuntimeException) {
                    return@synchronized temporary(Reason.KEY_GENERATION_FAILED)
                }
            }
            val reloadedKeyStore = try {
                loadKeyStore()
            } catch (_: GeneralSecurityException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                return@synchronized temporary(Reason.KEYSTORE_UNAVAILABLE)
            }
            when (val loaded = loadValidatedIdentity(reloadedKeyStore, metadataState)) {
                is LoadedIdentity.Available -> {
                    if (resetState != RecipientResetRecoveryState.Absent && !clearResetState()) {
                        temporary(Reason.METADATA_WRITE_FAILED)
                    } else {
                        RecipientEncryptionIdentityStatus.Available(loaded.identity)
                    }
                }
                is LoadedIdentity.TemporarilyUnavailable -> temporary(loaded.reason)
                is LoadedIdentity.Invalidated -> invalidated(loaded.reason)
            }
        }
    }

    override fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RecipientPrivateKeyResult.UnsupportedPlatform
        }
        return synchronized(PROCESS_LOCK) {
            val keyStore = try {
                loadKeyStore()
            } catch (_: GeneralSecurityException) {
                return@synchronized RecipientPrivateKeyResult.TemporarilyUnavailable(Reason.KEYSTORE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                return@synchronized RecipientPrivateKeyResult.TemporarilyUnavailable(Reason.KEYSTORE_UNAVAILABLE)
            }
            val metadataState = readMetadata()
            val resetState = readResetState()
            val aliasExists = try {
                keyStore.containsAlias(KEY_ALIAS)
            } catch (_: GeneralSecurityException) {
                return@synchronized RecipientPrivateKeyResult.TemporarilyUnavailable(Reason.KEYSTORE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                return@synchronized RecipientPrivateKeyResult.TemporarilyUnavailable(Reason.KEYSTORE_UNAVAILABLE)
            }
            recoveryStatus(resetState, aliasExists)?.let { status ->
                return@synchronized when (status) {
                    is RecipientEncryptionIdentityStatus.TemporarilyUnavailable ->
                        RecipientPrivateKeyResult.TemporarilyUnavailable(status.reason)
                    is RecipientEncryptionIdentityStatus.Invalidated -> RecipientPrivateKeyResult.Invalidated(status.reason)
                    else -> RecipientPrivateKeyResult.Invalidated(Reason.RESET_RECOVERY_REQUIRED)
                }
            }
            if (!aliasExists) {
                probedKeyId = null
                return@synchronized if (metadataState is MetadataState.Missing) {
                    RecipientPrivateKeyResult.NotFound
                } else {
                    RecipientPrivateKeyResult.Invalidated(
                        if (metadataState is MetadataState.Invalid) Reason.METADATA_INCONSISTENT else Reason.ALIAS_MISSING,
                    )
                }
            }
            when (val loaded = loadValidatedIdentity(keyStore, metadataState)) {
                is LoadedIdentity.Available -> {
                    if (resetState != RecipientResetRecoveryState.Absent && !clearResetState()) {
                        RecipientPrivateKeyResult.TemporarilyUnavailable(Reason.METADATA_WRITE_FAILED)
                    } else if (!constantTimeEquals(loaded.identity.keyId, expectedKeyId)) {
                        RecipientPrivateKeyResult.KeyIdMismatch
                    } else {
                        RecipientPrivateKeyResult.Available(loaded.privateKey)
                    }
                }
                is LoadedIdentity.TemporarilyUnavailable ->
                    RecipientPrivateKeyResult.TemporarilyUnavailable(loaded.reason)
                is LoadedIdentity.Invalidated -> RecipientPrivateKeyResult.Invalidated(loaded.reason)
            }
        }
    }

    override fun resetIdentity(activeNearbyRoomCount: Int): RecipientIdentityResetResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RecipientIdentityResetResult.UnsupportedPlatform
        }
        RecipientIdentityResetOrchestrator.activeRoomPrecondition(activeNearbyRoomCount)?.let { return it }
        return synchronized(PROCESS_LOCK) {
            RecipientIdentityResetOrchestrator.reset(
                loadTarget = ::loadKeyStore,
                persistResetIntent = { writeResetState(RecipientResetRecoveryState.InProgress) },
                clearIdentityMetadata = { metadataClearer?.invoke() ?: clearIdentityMetadata() },
                clearResetState = ::clearResetState,
                deleteIdentity = { keyStore -> aliasDeleter?.invoke(keyStore) ?: keyStore.deleteEntry(KEY_ALIAS) },
                identityExists = { keyStore -> aliasExistsChecker?.invoke(keyStore) ?: keyStore.containsAlias(KEY_ALIAS) },
                persistIndeterminateState = { writeResetState(RecipientResetRecoveryState.Indeterminate) },
                clearProbeCache = { probedKeyId = null },
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun loadValidatedIdentity(
        keyStore: KeyStore,
        metadataState: MetadataState,
    ): LoadedIdentity {
        if (metadataState is MetadataState.Invalid) {
            probedKeyId = null
            return LoadedIdentity.Invalidated(Reason.METADATA_INCONSISTENT)
        }
        return try {
            val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
                ?: return LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: return LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
            if (privateKey.encoded != null || privateKey.format != null || privateKey.algorithm != KeyProperties.KEY_ALGORITHM_EC) {
                return LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
            }
            val keyInfo = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                .getKeySpec(privateKey, KeyInfo::class.java)
            if (keyInfo.purposes != KeyProperties.PURPOSE_AGREE_KEY) {
                return LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
            }
            val publicKeySec1 = P256Sec1PublicKeyCodec.encode(publicKey)
            val keyId = RecipientEncryptionKeyId.derive(ALGORITHM, publicKeySec1)
            if (metadataState is MetadataState.Present && !constantTimeEquals(metadataState.keyId, keyId)) {
                probedKeyId = null
                return LoadedIdentity.Invalidated(Reason.METADATA_INCONSISTENT)
            }
            if (probedKeyId != keyId) {
                when (probeAgreement(privateKey, publicKey)) {
                    ProbeResult.Success -> probedKeyId = keyId
                    ProbeResult.Invalidated -> {
                        probedKeyId = null
                        return LoadedIdentity.Invalidated(Reason.KEY_INVALIDATED)
                    }
                    ProbeResult.KeyMismatch -> {
                        probedKeyId = null
                        return LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
                    }
                    ProbeResult.Failed -> {
                        probedKeyId = null
                        return LoadedIdentity.TemporarilyUnavailable(Reason.KEY_AGREEMENT_PROBE_FAILED)
                    }
                }
            }
            if (metadataState is MetadataState.Missing && !writeMetadata(keyId)) {
                return LoadedIdentity.TemporarilyUnavailable(Reason.METADATA_WRITE_FAILED)
            }
            LoadedIdentity.Available(
                identity = RecipientEncryptionIdentity(ALGORITHM, keyId, publicKeySec1),
                privateKey = privateKey,
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            probedKeyId = null
            LoadedIdentity.Invalidated(Reason.KEY_INVALIDATED)
        } catch (_: UnrecoverableKeyException) {
            probedKeyId = null
            LoadedIdentity.Invalidated(Reason.KEY_INVALIDATED)
        } catch (_: IllegalArgumentException) {
            probedKeyId = null
            LoadedIdentity.Invalidated(Reason.INVALID_KEY_MATERIAL)
        } catch (_: GeneralSecurityException) {
            probedKeyId = null
            LoadedIdentity.TemporarilyUnavailable(Reason.KEY_LOAD_FAILED)
        } catch (_: RuntimeException) {
            probedKeyId = null
            LoadedIdentity.TemporarilyUnavailable(Reason.KEYSTORE_UNAVAILABLE)
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun loadKeyStore(): KeyStore = keyStoreLoader()

    @TargetApi(Build.VERSION_CODES.S)
    private fun probeAgreement(privateKey: PrivateKey, publicKey: PublicKey): ProbeResult {
        var recipientSecret: ByteArray? = null
        var peerSecret: ByteArray? = null
        return try {
            val peerGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            peerGenerator.initialize(ECGenParameterSpec(P256_CURVE))
            val peer = peerGenerator.generateKeyPair()
            val recipientAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
            recipientAgreement.init(privateKey)
            recipientAgreement.doPhase(peer.public, true)
            recipientSecret = recipientAgreement.generateSecret()
            val peerAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
            peerAgreement.init(peer.private)
            peerAgreement.doPhase(publicKey, true)
            peerSecret = peerAgreement.generateSecret()
            when {
                recipientSecret.size != P256_SHARED_SECRET_BYTES || peerSecret.size != P256_SHARED_SECRET_BYTES ->
                    ProbeResult.Failed
                !java.security.MessageDigest.isEqual(recipientSecret, peerSecret) -> ProbeResult.KeyMismatch
                else -> ProbeResult.Success
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            ProbeResult.Invalidated
        } catch (_: GeneralSecurityException) {
            ProbeResult.Failed
        } catch (_: RuntimeException) {
            ProbeResult.Failed
        } finally {
            recipientSecret?.fill(0)
            peerSecret?.fill(0)
        }
    }

    private fun readMetadata(): MetadataState = try {
        val hasAnyMetadata = metadata.contains(METADATA_CREATED) ||
            metadata.contains(METADATA_VERSION) ||
            metadata.contains(METADATA_ALGORITHM) ||
            metadata.contains(METADATA_KEY_ID)
        if (!hasAnyMetadata) {
            MetadataState.Missing
        } else {
            val created = metadata.getBoolean(METADATA_CREATED, false)
            val version = metadata.getInt(METADATA_VERSION, -1)
            val algorithm = metadata.getString(METADATA_ALGORITHM, null)
            val keyId = metadata.getString(METADATA_KEY_ID, null)
            if (
                created &&
                version == IDENTITY_VERSION &&
                algorithm == ALGORITHM.wireValue &&
                keyId != null &&
                keyId.length == KEY_ID_ENCODED_LENGTH &&
                keyId.all(::isBase64UrlCharacter)
            ) {
                MetadataState.Present(keyId)
            } else {
                MetadataState.Invalid
            }
        }
    } catch (_: RuntimeException) {
        MetadataState.Invalid
    }

    private fun writeMetadata(keyId: String): Boolean = metadata.edit()
        .putBoolean(METADATA_CREATED, true)
        .putInt(METADATA_VERSION, IDENTITY_VERSION)
        .putString(METADATA_ALGORITHM, ALGORITHM.wireValue)
        .putString(METADATA_KEY_ID, keyId)
        .commit()

    private fun clearIdentityMetadata(): Boolean = metadata.edit()
        .remove(METADATA_CREATED)
        .remove(METADATA_VERSION)
        .remove(METADATA_ALGORITHM)
        .remove(METADATA_KEY_ID)
        .commit()

    private fun readResetState(): RecipientResetRecoveryState = try {
        val hasVersion = metadata.contains(RESET_STATE_VERSION_KEY)
        val hasState = metadata.contains(RESET_STATE_KEY)
        if (!hasVersion && !hasState) {
            RecipientResetRecoveryState.Absent
        } else if (!hasVersion || !hasState || metadata.getInt(RESET_STATE_VERSION_KEY, -1) != RESET_STATE_VERSION) {
            RecipientResetRecoveryState.Invalid
        } else {
            when (metadata.getString(RESET_STATE_KEY, null)) {
                RESET_IN_PROGRESS -> RecipientResetRecoveryState.InProgress
                RESET_OUTCOME_INDETERMINATE -> RecipientResetRecoveryState.Indeterminate
                else -> RecipientResetRecoveryState.Invalid
            }
        }
    } catch (_: RuntimeException) {
        RecipientResetRecoveryState.ReadFailure
    }

    private fun writeResetState(state: RecipientResetRecoveryState): Boolean {
        val encoded = when (state) {
            RecipientResetRecoveryState.InProgress -> RESET_IN_PROGRESS
            RecipientResetRecoveryState.Indeterminate -> RESET_OUTCOME_INDETERMINATE
            else -> return false
        }
        return metadata.edit()
            .putInt(RESET_STATE_VERSION_KEY, RESET_STATE_VERSION)
            .putString(RESET_STATE_KEY, encoded)
            .commit()
    }

    private fun clearResetState(): Boolean = metadata.edit()
        .remove(RESET_STATE_VERSION_KEY)
        .remove(RESET_STATE_KEY)
        .commit()

    private fun constantTimeEquals(left: String, right: String): Boolean =
        java.security.MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8),
        )

    private fun isBase64UrlCharacter(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'

    private fun temporary(reason: Reason) = RecipientEncryptionIdentityStatus.TemporarilyUnavailable(reason)

    private fun invalidated(reason: Reason) = RecipientEncryptionIdentityStatus.Invalidated(reason)

    private sealed interface MetadataState {
        data object Missing : MetadataState
        data class Present(val keyId: String) : MetadataState
        data object Invalid : MetadataState
    }

    private sealed interface LoadedIdentity {
        data class Available(
            val identity: RecipientEncryptionIdentity,
            val privateKey: PrivateKey,
        ) : LoadedIdentity
        data class TemporarilyUnavailable(val reason: Reason) : LoadedIdentity
        data class Invalidated(val reason: Reason) : LoadedIdentity
    }

    private enum class ProbeResult {
        Success,
        Failed,
        Invalidated,
        KeyMismatch,
    }

    companion object {
        const val KEY_ALIAS = "peermesh.group_invitation.recipient.p256.v1"
        internal const val METADATA_PREFS = "group_invitation_recipient_identity"
        internal const val METADATA_CREATED = "created_v1"
        internal const val METADATA_VERSION = "identity_version"
        internal const val METADATA_ALGORITHM = "algorithm"
        internal const val METADATA_KEY_ID = "active_key_id_v1"
        internal const val RESET_STATE_VERSION_KEY = "reset_state_version"
        internal const val RESET_STATE_KEY = "reset_state"
        internal const val RESET_IN_PROGRESS = "RESET_IN_PROGRESS"
        internal const val RESET_OUTCOME_INDETERMINATE = "RESET_OUTCOME_INDETERMINATE"
        internal const val RESET_STATE_VERSION = 1
        private const val IDENTITY_VERSION = 1
        private const val KEY_ID_ENCODED_LENGTH = 43
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val P256_CURVE = "secp256r1"
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
        private const val P256_SHARED_SECRET_BYTES = 32
        private val ALGORITHM = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        private val PROCESS_LOCK = Any()
        @Volatile private var probedKeyId: String? = null
    }
}
