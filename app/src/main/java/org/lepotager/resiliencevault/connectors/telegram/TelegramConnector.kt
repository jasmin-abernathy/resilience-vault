package org.lepotager.resiliencevault.connectors.telegram

import org.lepotager.resiliencevault.BuildConfig
import org.lepotager.resiliencevault.connectors.BackupConnector
import org.lepotager.resiliencevault.connectors.ConnectorHealth
import org.lepotager.resiliencevault.connectors.ConnectorStatus

class TelegramConnector(
    private val bridge: TelegramBridge = UnavailableTelegramBridge()
) : BackupConnector {
    private val config = TelegramRuntimeConfig(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH)

    override suspend fun status(): ConnectorStatus {
        if (!config.isConfigured) {
            return ConnectorStatus("telegram-tdlib", "Telegram", ConnectorHealth.NEEDS_CONFIGURATION,
                "TELEGRAM_API_ID et TELEGRAM_API_HASH ne sont pas configurés.")
        }
        if (!bridge.isNativeRuntimeAvailable) {
            return ConnectorStatus("telegram-tdlib", "Telegram", ConnectorHealth.UNAVAILABLE,
                "Configuration présente ; le module TDLib/JNI reste à intégrer.")
        }
        return when (val state = bridge.initialize(config)) {
            TelegramBridgeState.Ready ->
                ConnectorStatus("telegram-tdlib", "Telegram", ConnectorHealth.READY, "TDLib est prêt.")
            is TelegramBridgeState.NeedsUserAction ->
                ConnectorStatus("telegram-tdlib", "Telegram", ConnectorHealth.NEEDS_CONFIGURATION, state.reason)
            is TelegramBridgeState.Unavailable ->
                ConnectorStatus("telegram-tdlib", "Telegram", ConnectorHealth.UNAVAILABLE, state.reason)
        }
    }
}
