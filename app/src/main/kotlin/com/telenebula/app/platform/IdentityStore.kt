package com.telenebula.app.platform

import android.content.Context
import com.telenebula.core.model.Profile
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface ProfileLoad {
    data object None : ProfileLoad
    class Corrupt(val cause: String) : ProfileLoad
    class Loaded(val profile: Profile) : ProfileLoad
}

sealed interface HostKey {
    data object Missing : HostKey
    class Unreadable(val cause: String) : HostKey
    class Available(val pem: String) : HostKey
}

/**
 * The device identity: `files/profile.json` (same path and shape as the RN build) and the nebula
 * host private key sealed by [KeystoreBox] in `files/host.key.enc`. Nothing else reads either file.
 */
class IdentityStore(
    context: Context,
    private val json: Json,
    private val box: KeystoreBox,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val profileFile = File(context.filesDir, PROFILE_FILE)
    private val keyFile = File(context.filesDir, KEY_FILE)
    private val stagedKeyFile = File(context.filesDir, STAGED_KEY_FILE)

    /**
     * A profile written by an older build decodes as it stands: unknown keys (the RN build's
     * `displayName`) are ignored and every nebula option missing from the file falls back to its
     * own default, which is what filling a partial config means now that it is typed.
     */
    suspend fun loadProfile(): ProfileLoad = withContext(io) {
        when (val read = FileIo.read(profileFile)) {
            FileRead.Missing -> ProfileLoad.None
            is FileRead.Failed -> ProfileLoad.Corrupt(read.cause.userMessage())
            is FileRead.Text -> runCatching { ProfileLoad.Loaded(json.decodeFromString(Profile.serializer(), read.value)) }
                .getOrElse { ProfileLoad.Corrupt(it.userMessage()) }
        }
    }

    suspend fun saveProfile(profile: Profile) = withContext(io) {
        FileIo.writeAtomic(profileFile, json.encodeToString(Profile.serializer(), profile))
    }

    suspend fun saveHostKey(pem: String) = withContext(io) {
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(box.seal(pem.toByteArray(Charsets.UTF_8)))
    }

    /** A key picked during setup or renewal, sealed at once; it replaces the live key only on [commitStagedHostKey]. */
    suspend fun stageHostKey(pem: String) = withContext(io) {
        stagedKeyFile.parentFile?.mkdirs()
        stagedKeyFile.writeBytes(box.seal(pem.toByteArray(Charsets.UTF_8)))
    }

    /** The staged key, for the one check that needs its text; null when nothing is staged or it cannot be read. */
    suspend fun stagedHostKey(): String? = withContext(io) {
        if (!stagedKeyFile.isFile) return@withContext null
        runCatching { String(box.open(stagedKeyFile.readBytes()), Charsets.UTF_8) }.getOrNull()
    }

    suspend fun commitStagedHostKey() = withContext(io) {
        if (!stagedKeyFile.isFile) throw IllegalStateException("No private key was picked")
        if (!stagedKeyFile.renameTo(keyFile)) {
            keyFile.delete()
            check(stagedKeyFile.renameTo(keyFile)) { "Could not store the private key" }
        }
    }

    suspend fun discardStagedHostKey() = withContext(io) {
        stagedKeyFile.delete()
        Unit
    }

    suspend fun loadHostKey(): HostKey = withContext(io) {
        if (!keyFile.isFile) return@withContext HostKey.Missing
        runCatching { HostKey.Available(String(box.open(keyFile.readBytes()), Charsets.UTF_8)) }
            .getOrElse { HostKey.Unreadable(it.userMessage()) }
    }

    suspend fun resetIdentity() = withContext(io) {
        keyFile.delete()
        stagedKeyFile.delete()
        profileFile.delete()
        box.deleteKey()
    }

    private companion object {
        const val PROFILE_FILE = "profile.json"
        const val KEY_FILE = "host.key.enc"
        const val STAGED_KEY_FILE = "host.key.staged.enc"
    }
}
