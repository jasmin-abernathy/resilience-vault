package org.lepotager.resiliencevault.connectors.generic

import android.content.Context
import kotlinx.coroutines.flow.first
import org.lepotager.resiliencevault.connectors.BackupConnector
import org.lepotager.resiliencevault.connectors.ConnectorHealth
import org.lepotager.resiliencevault.connectors.ConnectorStatus
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.storage.PersistedTreeAccess

class GenericTreeConnector(
    private val context: Context,
    private val settings: VaultSettings
) : BackupConnector {
    override suspend fun status(): ConnectorStatus {
        val uri = settings.snapshot.first().genericTreeUri
            ?: return ConnectorStatus("generic-tree", "Dossier", ConnectorHealth.NEEDS_CONFIGURATION,
                "Choisir un dossier à protéger.")
        val access = PersistedTreeAccess.hasReadAccess(context.contentResolver, uri)
        return ConnectorStatus(
            "generic-tree",
            "Dossier",
            if (access) ConnectorHealth.READY else ConnectorHealth.BLOCKED,
            if (access) "Dossier accessible en lecture via Android SAF."
            else "L’autorisation persistante du dossier n’est plus disponible."
        )
    }
}
