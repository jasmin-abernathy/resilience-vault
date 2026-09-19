package org.lepotager.resiliencevault.connectors

enum class ConnectorHealth { READY, NEEDS_CONFIGURATION, UNAVAILABLE, BLOCKED }

data class ConnectorStatus(
    val id: String,
    val label: String,
    val health: ConnectorHealth,
    val detail: String
)

interface BackupConnector {
    suspend fun status(): ConnectorStatus
}
