package org.lepotager.resiliencevault.crypto

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
import org.lepotager.resiliencevault.panic.PanicInitializationResult
import org.lepotager.resiliencevault.panic.PanicPersistentState
import org.lepotager.resiliencevault.panic.PanicStoreFailure
import org.lepotager.resiliencevault.panic.PanicStoreReadResult

internal enum class FirstInstallMarkerPhase { BEGIN, COMPLETE }

internal sealed interface FirstInstallMarkerRead {
    data object Missing : FirstInstallMarkerRead
    data class Ready(val phase: FirstInstallMarkerPhase) : FirstInstallMarkerRead
    data object Unavailable : FirstInstallMarkerRead
}

internal enum class SecurityFootprintRead {
    CLEAN,
    PRESENT,
    UNAVAILABLE,
}

internal sealed interface FirstInstallSecurityStatus {
    data object Eligible : FirstInstallSecurityStatus
    data object Interrupted : FirstInstallSecurityStatus
    data object NeedsMarkerSeal : FirstInstallSecurityStatus
    data object Ready : FirstInstallSecurityStatus
    data class Blocked(val reason: Reason) : FirstInstallSecurityStatus

    enum class Reason {
        MARKER_UNAVAILABLE,
        PANIC_UNAVAILABLE,
        SECURITY_FOOTPRINT_PRESENT,
        SECURITY_FOOTPRINT_UNAVAILABLE,
        PANIC_CHANGED_DURING_CEREMONY,
        INITIALIZATION_FAILED,
    }
}

internal interface FirstInstallMarkerStore {
    fun read(): FirstInstallMarkerRead
    fun write(phase: FirstInstallMarkerPhase)
}

internal interface FirstInstallPanicPort {
    suspend fun read(): PanicStoreReadResult
    suspend fun initializeFresh(): PanicInitializationResult
}

internal fun interface SecurityFootprintProbe {
    fun inspect(): SecurityFootprintRead
}

/**
 * Explicit product ceremony. Missing panic state alone is never proof of a new installation.
 *
 * BEGIN is committed outside security/ before the panic record is created. That makes a crash
 * resumable without allowing a later deletion of security/ to masquerade as a fresh install.
 */
internal class FirstInstallSecurityCeremony(
    private val marker: FirstInstallMarkerStore,
    private val panic: FirstInstallPanicPort,
    private val footprint: SecurityFootprintProbe,
) {
    private val mutex = Mutex()

    suspend fun status(): FirstInstallSecurityStatus = mutex.withLock {
        inspectLocked()
    }

    suspend fun initialize(): FirstInstallSecurityStatus = mutex.withLock {
        when (val markerRead = marker.read()) {
            FirstInstallMarkerRead.Unavailable ->
                FirstInstallSecurityStatus.Blocked(
                    FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
                )

            is FirstInstallMarkerRead.Ready -> when (markerRead.phase) {
                FirstInstallMarkerPhase.COMPLETE -> completedStatus()
                FirstInstallMarkerPhase.BEGIN -> resumeBegin()
            }

            FirstInstallMarkerRead.Missing -> initializeWithoutMarker()
        }
    }

    private suspend fun inspectLocked(): FirstInstallSecurityStatus =
        when (val markerRead = marker.read()) {
            FirstInstallMarkerRead.Unavailable ->
                FirstInstallSecurityStatus.Blocked(
                    FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
                )

            is FirstInstallMarkerRead.Ready -> when (markerRead.phase) {
                FirstInstallMarkerPhase.COMPLETE -> completedStatus()
                FirstInstallMarkerPhase.BEGIN -> interruptedStatus()
            }

            FirstInstallMarkerRead.Missing -> missingMarkerStatus()
        }

    private suspend fun completedStatus(): FirstInstallSecurityStatus =
        when (panic.read()) {
            is PanicStoreReadResult.Ready -> FirstInstallSecurityStatus.Ready
            is PanicStoreReadResult.Unavailable ->
                FirstInstallSecurityStatus.Blocked(
                    FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE
                )
        }

    private suspend fun interruptedStatus(): FirstInstallSecurityStatus =
        when (val read = panic.read()) {
            is PanicStoreReadResult.Ready ->
                if (read.state == PanicPersistentState.initial()) {
                    FirstInstallSecurityStatus.Interrupted
                } else {
                    FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_CHANGED_DURING_CEREMONY
                    )
                }

            is PanicStoreReadResult.Unavailable ->
                if (read.failure == PanicStoreFailure.MISSING) {
                    FirstInstallSecurityStatus.Interrupted
                } else {
                    FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE
                    )
                }
        }

    private suspend fun missingMarkerStatus(): FirstInstallSecurityStatus {
        return when (val read = panic.read()) {
            is PanicStoreReadResult.Ready ->
                FirstInstallSecurityStatus.NeedsMarkerSeal

            is PanicStoreReadResult.Unavailable -> {
                if (read.failure != PanicStoreFailure.MISSING) {
                    return FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE
                    )
                }
                when (footprint.inspect()) {
                    SecurityFootprintRead.CLEAN -> FirstInstallSecurityStatus.Eligible
                    SecurityFootprintRead.PRESENT ->
                        FirstInstallSecurityStatus.Blocked(
                            FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_PRESENT
                        )
                    SecurityFootprintRead.UNAVAILABLE ->
                        FirstInstallSecurityStatus.Blocked(
                            FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_UNAVAILABLE
                        )
                }
            }
        }
    }

    private suspend fun initializeWithoutMarker(): FirstInstallSecurityStatus {
        return when (val read = panic.read()) {
            is PanicStoreReadResult.Ready -> {
                // Migration path for a valid state created before the separate marker existed.
                if (!commitMarker(FirstInstallMarkerPhase.COMPLETE)) {
                    FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
                    )
                } else {
                    completedStatus()
                }
            }

            is PanicStoreReadResult.Unavailable -> {
                if (read.failure != PanicStoreFailure.MISSING) {
                    return FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE
                    )
                }
                when (footprint.inspect()) {
                    SecurityFootprintRead.CLEAN -> {
                        if (!commitMarker(FirstInstallMarkerPhase.BEGIN)) {
                            FirstInstallSecurityStatus.Blocked(
                                FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
                            )
                        } else {
                            resumeBegin()
                        }
                    }
                    SecurityFootprintRead.PRESENT ->
                        FirstInstallSecurityStatus.Blocked(
                            FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_PRESENT
                        )
                    SecurityFootprintRead.UNAVAILABLE ->
                        FirstInstallSecurityStatus.Blocked(
                            FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_UNAVAILABLE
                        )
                }
            }
        }
    }

    private suspend fun resumeBegin(): FirstInstallSecurityStatus {
        when (val read = panic.read()) {
            is PanicStoreReadResult.Ready -> {
                if (read.state != PanicPersistentState.initial()) {
                    return FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_CHANGED_DURING_CEREMONY
                    )
                }
            }

            is PanicStoreReadResult.Unavailable -> {
                if (read.failure != PanicStoreFailure.MISSING) {
                    return FirstInstallSecurityStatus.Blocked(
                        FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE
                    )
                }
                when (panic.initializeFresh()) {
                    PanicInitializationResult.Created -> Unit
                    PanicInitializationResult.AlreadyInitialized -> {
                        val after = panic.read()
                        if (
                            after !is PanicStoreReadResult.Ready ||
                            after.state != PanicPersistentState.initial()
                        ) {
                            return FirstInstallSecurityStatus.Blocked(
                                FirstInstallSecurityStatus.Reason.INITIALIZATION_FAILED
                            )
                        }
                    }
                    is PanicInitializationResult.Unavailable ->
                        return FirstInstallSecurityStatus.Blocked(
                            FirstInstallSecurityStatus.Reason.INITIALIZATION_FAILED
                        )
                }
            }
        }

        val verified = panic.read()
        if (
            verified !is PanicStoreReadResult.Ready ||
            verified.state != PanicPersistentState.initial()
        ) {
            return FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.INITIALIZATION_FAILED
            )
        }

        if (!commitMarker(FirstInstallMarkerPhase.COMPLETE)) {
            return FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
            )
        }
        return completedStatus()
    }

    private fun commitMarker(phase: FirstInstallMarkerPhase): Boolean =
        try {
            marker.write(phase)
            marker.read() == FirstInstallMarkerRead.Ready(phase)
        } catch (_: Exception) {
            false
        }
}

internal class AndroidFirstInstallSecurityCeremony private constructor(
    private val delegate: FirstInstallSecurityCeremony,
) {
    companion object {
        private val instances = mutableMapOf<String, AndroidFirstInstallSecurityCeremony>()

        @Synchronized
        fun create(context: Context): AndroidFirstInstallSecurityCeremony {
            val app = context.applicationContext
            val path = app.noBackupFilesDir.canonicalPath
            return instances[path] ?: build(app).also { instances[path] = it }
        }

        private fun build(context: Context): AndroidFirstInstallSecurityCeremony {
            val panicStore = AtomicFilePanicStateStore.create(context)
            val markerFile = VerifiedEpochEnvelopeFile(
                File(
                    context.noBackupFilesDir,
                    "installation/first-install-security.bin",
                ),
                FirstInstallMarkerCodec.ENCODED_BYTES,
            )
            val markerStore = object : FirstInstallMarkerStore {
                override fun read(): FirstInstallMarkerRead =
                    try {
                        markerFile.read()?.let {
                            FirstInstallMarkerRead.Ready(
                                FirstInstallMarkerCodec.decode(it)
                            )
                        } ?: FirstInstallMarkerRead.Missing
                    } catch (_: Exception) {
                        FirstInstallMarkerRead.Unavailable
                    }

                override fun write(phase: FirstInstallMarkerPhase) {
                    markerFile.write(FirstInstallMarkerCodec.encode(phase))
                    check(read() == FirstInstallMarkerRead.Ready(phase)) {
                        "First-install marker write unconfirmed"
                    }
                }
            }
            val panicPort = object : FirstInstallPanicPort {
                override suspend fun read(): PanicStoreReadResult = panicStore.read()
                override suspend fun initializeFresh(): PanicInitializationResult =
                    panicStore.initializeFresh()
            }
            val footprintProbe = SecurityFootprintProbe {
                try {
                    val aliases = AndroidVaultKek(context).aliases()
                    if (aliases.any { it.startsWith("rv.kek.v1.") }) {
                        return@SecurityFootprintProbe SecurityFootprintRead.PRESENT
                    }

                    val security = File(context.noBackupFilesDir, "security")
                    if (!security.exists()) {
                        SecurityFootprintRead.CLEAN
                    } else if (!security.isDirectory) {
                        SecurityFootprintRead.PRESENT
                    } else {
                        val children = security.listFiles()
                            ?: return@SecurityFootprintProbe SecurityFootprintRead.UNAVAILABLE
                        if (children.isEmpty()) {
                            SecurityFootprintRead.CLEAN
                        } else {
                            SecurityFootprintRead.PRESENT
                        }
                    }
                } catch (_: Exception) {
                    SecurityFootprintRead.UNAVAILABLE
                }
            }

            return AndroidFirstInstallSecurityCeremony(
                FirstInstallSecurityCeremony(
                    markerStore,
                    panicPort,
                    footprintProbe,
                )
            )
        }
    }

    suspend fun status(): FirstInstallSecurityStatus = delegate.status()
    suspend fun initialize(): FirstInstallSecurityStatus = delegate.initialize()
}

internal object FirstInstallMarkerCodec {
    const val ENCODED_BYTES = 38
    private val magic = byteArrayOf(0x52, 0x56, 0x49, 0x31)

    fun encode(phase: FirstInstallMarkerPhase): ByteArray {
        val payload = magic + byteArrayOf(1, phase.ordinal.toByte())
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): FirstInstallMarkerPhase {
        require(bytes.size == ENCODED_BYTES) { "Invalid first-install marker length" }
        val payload = bytes.copyOfRange(0, 6)
        require(MessageDigest.isEqual(bytes.copyOfRange(6, bytes.size),
            MessageDigest.getInstance("SHA-256").digest(payload))) {
            "First-install marker digest mismatch"
        }
        require(payload.copyOfRange(0, 4).contentEquals(magic)) {
            "Invalid first-install marker magic"
        }
        require(payload[4] == 1.toByte()) { "Unknown first-install marker version" }
        return FirstInstallMarkerPhase.entries.getOrNull(payload[5].toInt())
            ?: error("Unknown first-install marker phase")
    }
}
