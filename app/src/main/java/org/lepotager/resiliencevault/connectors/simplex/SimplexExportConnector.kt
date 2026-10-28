package org.lepotager.resiliencevault.connectors.simplex

import android.content.Context
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lepotager.resiliencevault.connectors.BackupConnector
import org.lepotager.resiliencevault.connectors.ConnectorHealth
import org.lepotager.resiliencevault.connectors.ConnectorStatus
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.storage.PersistedDocumentAccess

/**
 * Manual SimpleX export handoff. This does NOT inspect the database or upload it.
 * No cross-app private storage access, no SimpleX control or erase permission.
 */
class SimplexExportConnector(
    private val context: Context,
    private val settings: VaultSettings
) : BackupConnector {
    override suspend fun status(): ConnectorStatus = withContext(Dispatchers.IO) {
        val uri = settings.snapshot.first().simplexExportUri
            ?: return@withContext result(
                ConnectorHealth.NEEDS_CONFIGURATION,
                "Exporter la base dans SimpleX puis sélectionner le fichier ZIP."
            )

        val resolver = context.contentResolver
        if (!PersistedDocumentAccess.hasReadAccess(resolver, uri)) {
            return@withContext result(
                ConnectorHealth.BLOCKED,
                "Autorisation de lecture expirée : sélectionner de nouveau le ZIP."
            )
        }

        try {
            val (name, length) = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val declaredSize = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else null
                    displayName to declaredSize
                }
            } ?: (null to null)

            val header = resolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArray(4)
                var count = 0
                while (count < bytes.size) {
                    val n = input.read(bytes, count, bytes.size - count)
                    if (n <= 0) break
                    count += n
                }
                bytes.copyOf(count)
            } ?: byteArrayOf()

            when (SimplexArchiveValidator.check(name, length, header)) {
                null -> result(
                    ConnectorHealth.READY,
                    "ZIP accessible en lecture ; origine, restauration et sauvegarde distante non vérifiées."
                )
                SimplexArchiveIssue.NAME_NOT_ZIP -> result(
                    ConnectorHealth.BLOCKED, "Le fichier choisi doit être une archive .zip."
                )
                SimplexArchiveIssue.EMPTY_OR_TRUNCATED,
                SimplexArchiveIssue.INVALID_ZIP_HEADER -> result(
                    ConnectorHealth.BLOCKED, "Archive ZIP invalide ou incomplète : refaire l’export."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            result(ConnectorHealth.BLOCKED, "Impossible de lire le fichier exporté.")
        } catch (_: SecurityException) {
            result(ConnectorHealth.BLOCKED, "Accès au fichier exporté refusé.")
        } catch (_: RuntimeException) {
            result(ConnectorHealth.BLOCKED, "Le fournisseur de fichiers est indisponible.")
        }
    }

    private fun result(health: ConnectorHealth, detail: String) =
        ConnectorStatus("simplex-export", "SimpleX Chat", health, detail)
}
