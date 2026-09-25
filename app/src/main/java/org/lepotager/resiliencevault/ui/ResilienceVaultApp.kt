package org.lepotager.resiliencevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.lepotager.resiliencevault.BuildConfig
import org.lepotager.resiliencevault.panic.AndroidFirstInstallPanicCeremony
import org.lepotager.resiliencevault.panic.AndroidPanicClock
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
import org.lepotager.resiliencevault.panic.FirstInstallPanicCeremonyResult
import org.lepotager.resiliencevault.panic.FirstInstallSecurityEvidence
import org.lepotager.resiliencevault.panic.PanicAdmissionService
import org.lepotager.resiliencevault.panic.PanicPhase
import org.lepotager.resiliencevault.panic.PanicStoreReadResult
import org.lepotager.resiliencevault.panic.PanicTransactionResult
import org.lepotager.resiliencevault.panic.PhoneNumberInvalidReason
import org.lepotager.resiliencevault.panic.PhoneNumberNormalization
import org.lepotager.resiliencevault.panic.PlatformPhoneNumberNormalizer
import org.lepotager.resiliencevault.panic.RemotePanicPolicy
import org.lepotager.resiliencevault.panic.RemotePanicWindow
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
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Resilience Vault", style = MaterialTheme.typography.headlineMedium)
            Text("Coffre local-first. Chiffrement distant et panic verrouillés jusqu’à validation.")

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

            RemotePanicPreparationCard()

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bouton d’urgence", style = MaterialTheme.typography.titleMedium)
                    Text("Désactivé tant que clés, suppression distante et interruptions ne sont pas validées.")
                    Button(onClick = {}, enabled = false) { Text("Validation requise") }
                }
            }
        }
    }
}

@Composable
private fun RemotePanicPreparationCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val normalizer = remember { PlatformPhoneNumberNormalizer() }
    val panicClock = remember(context) { AndroidPanicClock(context.contentResolver) }
    val store = remember(context) { AtomicFilePanicStateStore.create(context.applicationContext) }
    val admission = remember(store) { PanicAdmissionService(store) }
    val firstInstall = remember(context) {
        AndroidFirstInstallPanicCeremony.create(context.applicationContext)
    }

    var persistentState by remember { mutableStateOf<PanicStoreReadResult?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var countryIso by remember { mutableStateOf("") }
    var contactInputs by remember { mutableStateOf(listOf("")) }
    var selectedDurationMs by remember { mutableStateOf(24L * 60L * 60L * 1_000L) }

    fun refreshState() {
        scope.launch { persistentState = admission.refreshRemoteState() }
    }

    LaunchedEffect(store) {
        persistentState = admission.refreshRemoteState()
    }

    val clockSnapshot = panicClock.snapshot()
    val bootIdentityAvailable = clockSnapshot.bootId != null

    val normalized = contactInputs.map { raw -> normalizer.normalize(raw, countryIso) }
    val successfulNumbers = normalized.mapNotNull {
        (it as? PhoneNumberNormalization.Success)?.e164
    }
    val duplicateNumbers = successfulNumbers
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    val configurationValid =
        contactInputs.size in 1..RemotePanicPolicy.MAX_CONTACTS &&
            normalized.all { it is PhoneNumberNormalization.Success } &&
            duplicateNumbers.isEmpty() &&
            bootIdentityAvailable

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Contacts de confiance", style = MaterialTheme.typography.titleMedium)
            Text(
                "De 1 à 5 contacts pourront, lorsque cette fonction sera activée, " +
                    "déclencher la destruction d’urgence pendant la fenêtre choisie."
            )

            PersistentRemotePanicStatus(
                readResult = persistentState,
                panicClock = panicClock,
                onRemoveContact = { number ->
                    scope.launch {
                        val result = admission.removeTrustedContact(number)
                        actionMessage = when (result) {
                            is PanicTransactionResult.Success ->
                                if (result.value) "Contact retiré de l’armement." else "Aucun changement."
                            is PanicTransactionResult.Unavailable ->
                                "État de sécurité indisponible : ${result.failure}."
                        }
                        persistentState = admission.refreshRemoteState()
                    }
                },
                onDisarm = {
                    scope.launch {
                        val result = admission.disarmRemote()
                        actionMessage = when (result) {
                            is PanicTransactionResult.Success ->
                                if (result.value) "Armement distant désactivé." else "Aucun armement actif."
                            is PanicTransactionResult.Unavailable ->
                                "État de sécurité indisponible : ${result.failure}."
                        }
                        persistentState = admission.refreshRemoteState()
                    }
                },
                onInitializeFirstInstall = {
                    scope.launch {
                        actionMessage = firstInstallMessage(firstInstall.initialize())
                        persistentState = admission.refreshRemoteState()
                    }
                }
            )

            Text(
                if (BuildConfig.SMS_REMOTE_PANIC_READY)
                    "Canal SMS disponible."
                else
                    "Canal SMS indisponible dans ce build : aucun contact saisi ici n’est enregistré ni armé."
            )

            OutlinedTextField(
                value = countryIso,
                onValueChange = { countryIso = it.take(2) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pays ISO pour les numéros (ex. FR)") },
                singleLine = true
            )

            contactInputs.forEachIndexed { index, raw ->
                val result = normalized[index]
                val canonical = (result as? PhoneNumberNormalization.Success)?.e164
                val isDuplicate = canonical != null && canonical in duplicateNumbers

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { value ->
                            contactInputs = contactInputs.toMutableList().also { it[index] = value }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Contact ${index + 1} — numéro") },
                        singleLine = true
                    )

                    when {
                        isDuplicate -> Text(
                            "Ce numéro est déjà présent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        result is PhoneNumberNormalization.Success -> Text(
                            "Numéro reconnu : ${result.e164}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        raw.isNotBlank() || countryIso.isNotBlank() -> Text(
                            normalizationMessage(result),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (contactInputs.size > 1) {
                        OutlinedButton(
                            onClick = {
                                contactInputs = contactInputs.filterIndexed { i, _ -> i != index }
                            }
                        ) { Text("Retirer ce contact") }
                    }
                }
            }

            OutlinedButton(
                onClick = { contactInputs = contactInputs + "" },
                enabled = contactInputs.size < RemotePanicPolicy.MAX_CONTACTS
            ) {
                Text(
                    if (contactInputs.size < RemotePanicPolicy.MAX_CONTACTS)
                        "Ajouter un contact"
                    else
                        "Maximum 5 contacts"
                )
            }

            Text("Durée d’armement", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemotePanicPolicy.allowedDurationsMs.forEach { duration ->
                    val hours = duration / 3_600_000L
                    if (duration == selectedDurationMs) {
                        Button(onClick = { selectedDurationMs = duration }) { Text("${hours} h") }
                    } else {
                        OutlinedButton(onClick = { selectedDurationMs = duration }) { Text("${hours} h") }
                    }
                }
            }

            Text(
                if (bootIdentityAvailable)
                    "Identité de démarrage Android disponible."
                else
                    "Identité de démarrage indisponible : le mode distant restera désactivé."
            )

            Button(
                onClick = {},
                enabled = BuildConfig.SMS_REMOTE_PANIC_READY && configurationValid
            ) {
                Text(
                    if (BuildConfig.SMS_REMOTE_PANIC_READY)
                        "Armer le déclenchement distant"
                    else
                        "Armement SMS indisponible"
                )
            }

            actionMessage?.let { Text(it) }

            OutlinedButton(onClick = ::refreshState) {
                Text("Actualiser l’état")
            }
        }
    }
}

@Composable
private fun PersistentRemotePanicStatus(
    readResult: PanicStoreReadResult?,
    panicClock: AndroidPanicClock,
    onRemoveContact: (String) -> Unit,
    onDisarm: () -> Unit,
    onInitializeFirstInstall: () -> Unit
) {
    when (readResult) {
        null -> Text("Vérification du registre de sécurité…")
        is PanicStoreReadResult.Unavailable -> {
            if (readResult.failure.name == "MISSING") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Registre de sécurité non initialisé : accès au coffre bloqué par défaut.")
                    Text(
                        "Uniquement pour une installation réellement neuve : la vérification " +
                            "refusera l’initialisation si une donnée locale de sécurité ou une " +
                            "clé du coffre existe déjà.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = onInitializeFirstInstall) {
                        Text("Initialiser cette nouvelle installation")
                    }
                }
            } else {
                Text(
                    "Registre de sécurité indisponible (${readResult.failure}) : accès au coffre bloqué."
                )
            }
        }
        is PanicStoreReadResult.Ready -> {
            val state = readResult.state
            if (state.phase != PanicPhase.IDLE) {
                Text("Panic déjà engagé (${state.phase}) : accès au coffre bloqué.")
                return
            }

            val arm = state.arm
            if (arm == null) {
                Text("Aucun armement distant actif.")
                return
            }

            val currentClock = panicClock.snapshot()
            val valid = RemotePanicWindow.isValid(arm, currentClock)
            val expiry = RemotePanicWindow.expiresUtcMs(arm)
            if (!valid || expiry == null) {
                Text("Armement expiré ou invalidé : il ne doit pas être considéré comme actif.")
            } else {
                val formatted = DateFormat.getDateTimeInstance().format(Date(expiry))
                Text(
                    if (BuildConfig.SMS_REMOTE_PANIC_READY)
                        "Armement enregistré jusqu’au ${formatted}."
                    else
                        "Armement enregistré mais inexécutable dans ce build ; expiration ${formatted}."
                )
            }

            arm.contacts.forEach { contact ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(contact.e164)
                    OutlinedButton(onClick = { onRemoveContact(contact.e164) }) {
                        Text("Retirer")
                    }
                }
            }

            OutlinedButton(onClick = onDisarm) {
                Text("Désactiver l’armement")
            }
        }
    }
}

private fun firstInstallMessage(result: FirstInstallPanicCeremonyResult): String =
    when (result) {
        FirstInstallPanicCeremonyResult.Created ->
            "Registre de sécurité initialisé pour cette nouvelle installation."
        FirstInstallPanicCeremonyResult.AlreadyInitialized ->
            "Le registre de sécurité est déjà initialisé."
        is FirstInstallPanicCeremonyResult.StoreUnavailable ->
            "Initialisation refusée : registre indisponible (${result.failure})."
        is FirstInstallPanicCeremonyResult.Refused ->
            when (result.evidence) {
                FirstInstallSecurityEvidence.FILES_PRESENT ->
                    "Initialisation refusée : des données locales existent déjà."
                FirstInstallSecurityEvidence.LOCAL_KEK_PRESENT ->
                    "Initialisation refusée : une clé locale du coffre existe déjà."
                FirstInstallSecurityEvidence.UNAVAILABLE ->
                    "Impossible de prouver que l’installation est neuve : état laissé bloqué."
                FirstInstallSecurityEvidence.PRISTINE ->
                    "Initialisation refusée par précaution."
            }
    }

private fun normalizationMessage(result: PhoneNumberNormalization): String =
    when ((result as? PhoneNumberNormalization.Invalid)?.reason) {
        PhoneNumberInvalidReason.COUNTRY_REQUIRED -> "Choisis d’abord le pays ISO."
        PhoneNumberInvalidReason.INVALID_COUNTRY_ISO -> "Le pays doit être un code ISO à deux lettres."
        PhoneNumberInvalidReason.NUMBER_REQUIRED -> "Saisis un numéro."
        PhoneNumberInvalidReason.INVALID_NUMBER -> "Numéro non reconnu pour ce pays."
        null -> ""
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
