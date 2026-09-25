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
import org.lepotager.resiliencevault.crypto.AndroidFirstInstallSecurityCeremony
import org.lepotager.resiliencevault.crypto.FirstInstallSecurityStatus
import org.lepotager.resiliencevault.panic.AndroidPanicClock
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
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
    var securityRefreshToken by remember { mutableStateOf(0) }

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

            FirstInstallSecurityCard {
                securityRefreshToken += 1
            }

            RemotePanicPreparationCard(securityRefreshToken)

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
private fun FirstInstallSecurityCard(onSecurityChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ceremony = remember(context) {
        AndroidFirstInstallSecurityCeremony.create(context.applicationContext)
    }
    var status by remember { mutableStateOf<FirstInstallSecurityStatus?>(null) }

    fun refresh() {
        scope.launch { status = ceremony.status() }
    }

    fun initialize() {
        scope.launch {
            val next = ceremony.initialize()
            status = next
            if (next == FirstInstallSecurityStatus.Ready) {
                onSecurityChanged()
            }
        }
    }

    LaunchedEffect(ceremony) {
        status = ceremony.status()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sécurité locale", style = MaterialTheme.typography.titleMedium)
            when (val current = status) {
                null -> Text("Vérification de l’installation…")
                FirstInstallSecurityStatus.Ready ->
                    Text("Registre de sécurité initialisé et protégé contre une réinitialisation silencieuse.")
                FirstInstallSecurityStatus.Eligible -> {
                    Text(
                        "Installation locale vierge détectée. L’initialisation reste explicite et ne sera jamais déduite du seul fait qu’un fichier manque."
                    )
                    Button(
                        onClick = ::initialize
                    ) { Text("Initialiser la sécurité locale") }
                }
                FirstInstallSecurityStatus.Interrupted -> {
                    Text("Une initialisation explicite a été interrompue. Elle peut être reprise sans recréer un état existant.")
                    Button(
                        onClick = ::initialize
                    ) { Text("Reprendre l’initialisation") }
                }
                FirstInstallSecurityStatus.NeedsMarkerSeal -> {
                    Text("Un registre valide antérieur existe. Il peut être marqué comme installation existante sans être réinitialisé.")
                    Button(
                        onClick = ::initialize
                    ) { Text("Finaliser la protection") }
                }
                is FirstInstallSecurityStatus.Blocked -> {
                    Text(firstInstallBlockedMessage(current.reason))
                    OutlinedButton(onClick = ::refresh) { Text("Revérifier") }
                }
            }
        }
    }
}

private fun firstInstallBlockedMessage(
    reason: FirstInstallSecurityStatus.Reason
): String = when (reason) {
    FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE ->
        "Marqueur d’installation indisponible ou corrompu : initialisation bloquée."
    FirstInstallSecurityStatus.Reason.PANIC_UNAVAILABLE ->
        "Registre de sécurité indisponible ou incohérent : initialisation bloquée."
    FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_PRESENT ->
        "Des traces de sécurité d’une installation antérieure existent : aucune réinitialisation automatique n’est autorisée."
    FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_UNAVAILABLE ->
        "Impossible de vérifier l’absence d’un état antérieur : initialisation bloquée."
    FirstInstallSecurityStatus.Reason.PANIC_CHANGED_DURING_CEREMONY ->
        "Le registre a changé pendant l’initialisation : reprise refusée."
    FirstInstallSecurityStatus.Reason.INITIALIZATION_FAILED ->
        "L’initialisation n’a pas pu être vérifiée durablement : accès bloqué."
}

@Composable
private fun RemotePanicPreparationCard(securityRefreshToken: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val normalizer = remember { PlatformPhoneNumberNormalizer() }
    val panicClock = remember(context) { AndroidPanicClock(context.contentResolver) }
    val store = remember(context) { AtomicFilePanicStateStore.create(context.applicationContext) }
    val admission = remember(store) { PanicAdmissionService(store) }

    var persistentState by remember { mutableStateOf<PanicStoreReadResult?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var countryIso by remember { mutableStateOf("") }
    var contactInputs by remember { mutableStateOf(listOf("")) }
    var selectedDurationMs by remember { mutableStateOf(24L * 60L * 60L * 1_000L) }

    fun refreshState() {
        scope.launch { persistentState = admission.refreshRemoteState() }
    }

    LaunchedEffect(store, securityRefreshToken) {
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
    onDisarm: () -> Unit
) {
    when (readResult) {
        null -> Text("Vérification du registre de sécurité…")
        is PanicStoreReadResult.Unavailable -> Text(
            if (readResult.failure.name == "MISSING")
                "Registre de sécurité non initialisé : mode distant bloqué par défaut."
            else
                "Registre de sécurité indisponible (${readResult.failure}) : mode distant bloqué."
        )
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
