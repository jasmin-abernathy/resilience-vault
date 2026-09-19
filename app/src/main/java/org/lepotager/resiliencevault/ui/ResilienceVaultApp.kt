package org.lepotager.resiliencevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lepotager.resiliencevault.BuildConfig
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.storage.PersistedTreeAccess

@Composable
fun ResilienceVaultApp(settings: VaultSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by settings.snapshot.collectAsState(initial = null)

    val signalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            PersistedTreeAccess.persistReadAccess(context.contentResolver, uri)
            scope.launch { settings.setSignalTree(uri) }
        }
    }
    val genericPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            PersistedTreeAccess.persistReadAccess(context.contentResolver, uri)
            scope.launch { settings.setGenericTree(uri) }
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Resilience Vault", style = MaterialTheme.typography.headlineMedium)
            Text("Coffre local-first. Chiffrement distant et panic verrouillés jusqu’à audit.")

            SourceCard(
                "Signal",
                if (snapshot?.signalTreeUri != null) "Dossier de sauvegarde sélectionné."
                else "Sélectionne le dossier de sauvegarde locale Signal."
            ) { signalPicker.launch(snapshot?.signalTreeUri) }

            SourceCard(
                "Dossier générique",
                if (snapshot?.genericTreeUri != null) "Dossier sélectionné."
                else "Aucun dossier sélectionné."
            ) { genericPicker.launch(snapshot?.genericTreeUri) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Telegram", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (!BuildConfig.TELEGRAM_CONFIGURED)
                            "Port TDLib prêt ; api_id/api_hash non configurés."
                        else "Identifiants présents ; TDLib/JNI reste à intégrer."
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Activer Telegram")
                        Switch(
                            checked = snapshot?.telegramEnabled ?: false,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setTelegramEnabled(enabled) }
                            },
                            enabled = BuildConfig.TELEGRAM_CONFIGURED && BuildConfig.TELEGRAM_NATIVE_READY
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bouton d’urgence", style = MaterialTheme.typography.titleMedium)
                    Text("Désactivé tant que clés, suppression distante et interruptions ne sont pas auditées.")
                    Button(onClick = {}, enabled = false) { Text("Audit requis") }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, detail: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
            OutlinedButton(onClick = action) { Text("Choisir le dossier") }
        }
    }
}
