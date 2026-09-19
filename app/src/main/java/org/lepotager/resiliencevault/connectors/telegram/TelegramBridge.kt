package org.lepotager.resiliencevault.connectors.telegram

data class TelegramRuntimeConfig(val apiId: Int, val apiHash: String) {
    val isConfigured: Boolean get() = apiId > 0 && apiHash.isNotBlank()
}
data class TelegramArchiveCursor(val opaqueValue: String?)
data class TelegramArchiveBatch(
    val itemCount: Int,
    val nextCursor: TelegramArchiveCursor?,
    val completed: Boolean
)

interface TelegramBridge {
    val isNativeRuntimeAvailable: Boolean
    suspend fun initialize(config: TelegramRuntimeConfig): TelegramBridgeState
    suspend fun exportNextBatch(cursor: TelegramArchiveCursor?): TelegramArchiveBatch
    suspend fun revokeLocalSession()
}

sealed interface TelegramBridgeState {
    data object Ready : TelegramBridgeState
    data class NeedsUserAction(val reason: String) : TelegramBridgeState
    data class Unavailable(val reason: String) : TelegramBridgeState
}

class UnavailableTelegramBridge : TelegramBridge {
    override val isNativeRuntimeAvailable = false
    override suspend fun initialize(config: TelegramRuntimeConfig): TelegramBridgeState =
        TelegramBridgeState.Unavailable("TDLib/JNI n’est pas encore intégré au build.")
    override suspend fun exportNextBatch(cursor: TelegramArchiveCursor?): TelegramArchiveBatch =
        error("TDLib/JNI is not available")
    override suspend fun revokeLocalSession() = Unit
}
