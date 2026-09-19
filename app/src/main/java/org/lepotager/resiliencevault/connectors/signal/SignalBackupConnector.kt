package org.lepotager.resiliencevault.connectors.signal

import android.content.Context
import kotlinx.coroutines.flow.first
import org.lepotager.resiliencevault.connectors.BackupConnector
import org.lepotager.resiliencevault.connectors.ConnectorHealth
import org.lepotager.resiliencevault.connectors.ConnectorStatus
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.storage.PersistedTreeAccess

class SignalBackupConnector(
    private val context: Context,
    private val settings: VaultSettings
) : BackupConnector {
    override suspend fun status(): ConnectorStatus {
        val uri = settings.snapshot.first().signalTreeUri
            ?: return ConnectorStatus("signal-backup", "Signal", ConnectorHealth.NEEDS_CONFIGURATION,
                "Choisir le dossier de sauvegarde locale Signal.")
        val access = PersistedTreeAccess.hasReadAccess(context.contentResolver, uri)
        return ConnectorStatus(
            "signal-backup",
            "Signal",
            if (access) ConnectorHealth.READY else ConnectorHealth.BLOCKED,
            if (access) "Dossier accessible en lecture via Android SAF."
            else "L’autorisation persistante du dossier n’est plus disponible."
        )
    }
}
