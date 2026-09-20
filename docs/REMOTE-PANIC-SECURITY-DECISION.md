# Décision GPT-6 — panic local et SMS temporaire

Date : 2026-09-20. Base inspectée : `78f588a6133b04b52dd4e9f331a0749b448909e5`.
Issues : #2 et #8. Statut : **architecture spécifiée et modèle testé ; activation NON autorisée**.

Cette décision remplace les propositions du relais SMS pour les points tranchés ci-dessous.
Le modèle Python de `tools/security_model/` est une spécification exécutable indépendante
du runtime Android. Ce n'est ni une implémentation de stockage durable, ni une crypto de
production, ni un receiver. Aucun changement au manifeste, aux permissions ou au bouton panic.

## 1. Menaces et limites

| Adversaire / incident | Réponse et limite |
| --- | --- |
| Expéditeur inconnu ou numéro usurpé | Numéro canonique exact ET secret propre au contact ET génération active. Le numéro seul n'authentifie personne. |
| Contact autorisé malveillant, téléphone du contact compromis | Ce contact peut détruire pendant la fenêtre : c'est le pouvoir délégué. Jamais lecture, restauration, armement ou prolongation. |
| Interception SMS + usurpation du numéro | Peut permettre un déclenchement anticipé. Le SMS n'est pas un canal confidentiel ; le système ne résout pas cette menace. |
| Ancien secret, SMS retardé, doublons, deux contacts simultanés | Rotation complète, génération liée au vérificateur, échéance locale et consommation transactionnelle globale. |
| Redémarrage, changement d'heure, Doze | Horloge monotone incluant la veille ; désarmement si boot différent/inconnu ou anomalie détectée. Aucun timer nécessaire. |
| Crash entre validation et destruction | Consommation et intention durable dans la même transaction ; reprise avant tout accès au coffre. |
| Stockage plein/corrompu, Keystore indisponible | Refus d'accès et pas de succès fictif ; aucune garantie de destruction immédiate si l'OS/stockage ne l'exécute pas. |
| Hors ligne, serveur indisponible | Destruction locale indépendante ; suppression distante reste explicitement en attente. |
| Root, OS hostile, restauration arbitraire d'un ancien disque | Hors garantie : une base applicative ne fournit pas à elle seule une protection anti-rollback matérielle. |

Périmètre destructif : les clés et données propres à Resilience Vault. Aucun effacement
automatique de Signal/Telegram officiel, des messages du compte Telegram ou des originaux SAF.
Une copie récupérable sur un autre appareil ne disparaît pas par destruction d'une clé locale.
Ne promettre ni suppression physique garantie sur flash, ni réception SMS garantie, ni
effacement instantané après un crash ou arrêt forcé de l'application.

## 2. Armement et expiration

- Configuration locale après confirmation explicite, 1 à 5 numéros uniques et secrets distincts.
- Durées exactes : 1, 6, 12, 24, 48 ou 72 h. Aucune durée libre, pas de renouvellement automatique.
- Stocker génération aléatoire, identité du boot, début monotone, début UTC et durée dans
  le même état transactionnel que les vérificateurs. Échantillonner les horloges ensemble.
- Au moment de **la transaction d'acceptation**, relire horloge, permission, contact et état.
  Valide seulement si `0 <= elapsedNow - elapsedStart < duration`, boot identique connu,
  et UTC avant l'échéance initiale. L'égalité à l'échéance est déjà expirée.
- Si l'écart entre delta UTC et delta monotone dépasse 2 secondes : désarmer durablement.
  Cette tolérance absorbe l'échantillonnage et une petite correction de l'heure ; elle ne
  s'ajoute JAMAIS à l'échéance monotone. Une petite dérive ne peut donc prolonger les 72 h.
  Une modification de l'heure aller-retour entre observations peut être indétectable ;
  le plafond monotone reste applicable. Un changement de fuseau n'affecte pas l'UTC.
- Reboot : désarmement, même si l'heure UTC semble correcte. Ne pas se fier uniquement
  à un receiver BOOT_COMPLETED, ni déduire le boot de l'heure murale. L'adaptateur Android
  doit fournir une identité de boot fiable sur la matrice minSdk 26+ ; s'il ne peut pas,
  le mode distant reste indisponible. Tester notamment un reboot dont l'uptime dépasse
  l'ancien uptime d'armement. Une simple comparaison des uptimes est insuffisante.
- Mort du processus dans le même boot : la fenêtre peut continuer via l'état durable.
- Permission absente à une observation : désarmer. Après une nouvelle demande de permission,
  exiger un nouvel armement. Une révocation puis réautorisation externes entre observations
  n'est pas forcément détectable : ne pas annoncer une détection absolue sans preuve Android.
- Retrait local et réception sont sérialisés : si le retrait commit en premier, le SMS échoue ;
  si l'acceptation commit en premier, le panic n'est plus annulable. L'UI affiche ce résultat.

## 3. Secrets et identité

Décision : un secret généré de 256 bits par contact via CSPRNG système ; pas de mot de passe
choisi par l'utilisateur. Génération d'armement indépendante de 256 bits. Nouvelle génération
et tous nouveaux secrets à chaque armement, même avec les mêmes contacts. Si la génération
échoue, ne pas conserver un ancien armement comme si le nouveau avait réussi.

Commande canonique ASCII exacte : `RV1 <generation-hex64> <secret-hex64>` (133 caractères).
Pas de trim, sous-chaîne, casse insensible, recherche dans une phrase, Unicode équivalent,
commande de réarmement ou commande de lecture. Le partage doit produire exactement ce texte.

Conserver uniquement `SHA-256("RV1\\0" || generation || "\\0" || E164 || "\\0" || secret)`
avec encodage ASCII et séparateurs non admis par la grammaire ; comparer en temps constant.
Ce hash convient à un secret aléatoire de 256 bits, **pas à un mot humain**. Aucun algorithme
de chiffrement inventé ici. Le format des sauvegardes et leur crypto restent à auditer.
Stockage privé exclu de sauvegarde/extraction. Ne jamais journaliser commande, numéro,
vérificateur, credential DELETE, exception contenant l'entrée ou représentation de l'état.
Secret brut seulement le temps du partage local ; pas de SavedState, presse-papiers automatique,
analytics, notification ou crash report. Les copies chez le contact ne sont pas effaçables
par Resilience Vault. L'invalidation des vérificateurs est logique, pas un effacement flash garanti.

Normaliser les numéros au paramétrage avec une bibliothèque éprouvée et région explicitement
choisie ; afficher le résultat international avant confirmation et dédupliquer APRÈS
normalisation. À réception, appliquer la même politique : pas de suffix-match, noms
d'expéditeurs, passerelles alphanumériques, numéros courts ou région devinée depuis la SIM.
Une entrée ambiguë est refusée. Le modèle ne teste que la frontière canonique, pas libphonenumber.
Double SIM : accepter une commande valide reçue sur l'une ou l'autre SIM ; dédupliquer par
la consommation globale, jamais par SIM. Ne pas confondre SIM destinataire et contact expéditeur.

## 4. Transaction et machine à états

Deux dimensions dans **un stockage durable unique**, pas deux DataStore indépendants :
armement absent/actif ; panic IDLE/LOCAL_PENDING/POST_PENDING/COMPLETE.
Un état illisible ou inconnu interdit l'ouverture ; ce n'est pas un IDLE par défaut.

| État / événement | Transition atomique |
| --- | --- |
| IDLE + action locale d'armement | Remplacer toute la fenêtre et ses vérificateurs |
| IDLE + retrait / désactivation / expiration | Retirer le vérificateur ou toute la fenêtre ; dernier contact => désarmé |
| IDLE + SMS validé dans la transaction | Effacer TOUS les vérificateurs + enregistrer panicId et LOCAL_PENDING + fermer l'accès |
| IDLE + panic local confirmé | Même transaction et même chemin de reprise, sans précondition SMS |
| LOCAL_PENDING + destruction locale confirmée | POST_PENDING ; tâches postérieures déjà décrites dans l'intention durable |
| POST_PENDING + toutes tâches achevées | COMPLETE ; accès reste fermé |
| Panic non-IDLE + SMS / armement | Refus ; jamais retour à IDLE par ce chemin |

Le commit durable est le point de linéarisation. Aucun effet destructif avant le commit
pour une commande SMS ; aucune consommation séparée de l'intention de reprise. Échec de
commit => pas de succès retourné. Arrêt pendant commit => ancien état OU nouvelle intention
complète, jamais un état consommé sans intention. Il faut tester ce contrat dans le stockage
Android réel (transactions sérialisables et durabilité), pas considérer un Mutex comme suffisant.

Toutes les voies d'accès aux clés, restauration, export et synchronisation consultent le
gate durable. Les opérations déjà en cours doivent être arrêtées ou privées de leurs handles
avant déclaration de réussite locale ; elles ne doivent pas recréer clés ou sessions ensuite.
Ni notification, ni mise à jour Compose, ni worker réseau ne conditionne ce verrouillage.

## 5. Séparer PanicCoordinator en deux phases : OUI

Le coordinateur actuel n'est pas activable : liste d'étapes en RAM, pas d'intention durable,
pas de reprise, exception de purge/révocation pouvant empêcher DELETE, et pas de preuve de
destruction des copies de clés. Ses deux tests ne vérifient que l'ordre nominal et un retour false.

Contrats à implémenter :

```kotlin
interface PanicAdmission {
    // Validation + consommation + intention dans UNE transaction durable.
    suspend fun acceptSms(command: ValidatedSmsEnvelope): AdmissionResult
    suspend fun acceptLocal(): AdmissionResult
}
interface PanicRecovery {
    // Court, local, idempotent ; aucune requête réseau ni dépendance à Compose.
    suspend fun resumeLocalCritical(): LocalOutcome
    // Seulement après confirmation locale ; tâches indépendantes et bornées.
    suspend fun resumePostDestruction(): PostOutcome
}
```

Ces signatures sont des contrats d'architecture, pas des classes Kotlin livrées dans l'app.
`ValidatedSmsEnvelope` ne dispense pas de revalider contact, génération, horloge et état dans
la transaction. `AdmissionResult` distingue refus, erreur stockage et intention persistée ;
`LocalOutcome` distingue pending et destruction confirmée ; aucun booléen global « tout effacé ».

Phase locale : gate fermé, invalidation des opérations en cours, suppression idempotente des
aliases/enveloppes locaux nécessaires à la lecture, invalidation des clés et sessions en RAM,
puis checkpoint. Inventorier toute copie de clé avant implémentation (#1/cycle de vie).
Un alias absent peut être un succès idempotent ; une erreur Keystore n'est pas une preuve
d'absence. Crash après suppression avant checkpoint => rejouer la suppression sans recréer de clé.

Phase postérieure : purge privée, révocation de la session dédiée et DELETE indépendants,
chacun avec statut durable et retry borné. Une panne de purge ne bloque pas DELETE. L'intention
contient les références minimales au travail avant destruction ; pas besoin de déchiffrer le
coffre ensuite. Crash après DELETE serveur avant accusé local => rejouer le même DELETE.
Échec permanent reste visible comme non achevé, ne pas boucler rapidement sur 401/403.

**Désactivation du launcher reportée et hors MVP** : elle ne détruit aucune clé et peut gêner
la reprise/visibilité des erreurs. Aucun camouflage d'identité. Ne pas la rendre précondition
du panic ni annoncer la disparition de toutes les traces.

Stockage plein/corrompu : accès refusé et erreur distincte ; ne pas formater silencieusement,
recréer un coffre ou annoncer la suppression. La destruction garantie sous panne de stockage
n'est pas démontrée par ce modèle. Préallocation éventuelle et récupération réelle à auditer.

## 6. Credential DELETE-only et cloud

Le type Kotlin actuel encapsulant une String n'impose aucune restriction côté serveur.
Créer avant armement du panic cloud une capability serveur à haute entropie, liée à une
génération immuable de coffre et limitée à DELETE de ce coffre. Ne jamais conserver le
token de compte, une clé de lecture ou une session complète pour le retry.
Stocker cette capability séparément des clés détruites, exclue de sauvegarde et des logs.
Ne pas rendre sa lecture dépendante de la clé qu'on vient de détruire.

Le serveur doit refuser GET/LIST/RESTORE/UPLOAD/rotation/échange de jetons avec cette capability.
DELETE répété est idempotent, avec tombstone empêchant un upload déjà en vol de ressusciter
la même génération. Un nouveau coffre reçoit une nouvelle identité ; une vieille capability
ne doit jamais pouvoir le détruire. Prévoir rotation/révocation explicites et durée compatible
avec une longue période hors ligne. Révocation avant retry => suppression non garantie,
état bloqué explicite. Une disparition ou 404 n'est un succès que si son contrat authentifié
permet de conclure que la bonne génération est absente. Aucun backend présent à auditer :
ces exigences restent des critères d'acceptation, pas des propriétés déjà acquises.

## 7. Receiver, assemblage et distribution

Pas de receiver ni RECEIVE_SMS dans cette livraison. Aucun READ_SMS, stockage d'historique,
abortBroadcast ou effacement de SMS du téléphone. Mode normal sans SMS ; éventuel flavor
direct/FOSS séparé après examen de sa distribution, sans présumer d'une acceptation Play.

Utiliser le broadcast système et son décodage, avec protection de l'entrée à vérifier dans
le manifeste fusionné. Ne pas fabriquer un parseur PDU maison. Ne jamais accepter une
intention applicative forgée comme preuve d'un SMS. Pas de worker réseau dans l'admission.
`goAsync()` ne donne pas une durée illimitée : la phase locale doit rester bornée et mesurée.
Si Android tue l'exécution, l'intention demeure ; reprise au prochain démarrage autorisé
avant ouverture. Pas de garantie de délai d'effacement sous arrêt forcé/Doze/OEM.

Commande courte, un seul SMS normalement. Un message multipart ne peut entrer dans
l'admission qu'après reconstruction complète et cohérente : même expéditeur normalisé,
SIM, assemblage système, ordre complet et longueur exacte ; jamais concaténation entre
broadcasts ou conservation des fragments sur disque. Maximum 4 parties et 133 caractères
dans l'adaptateur proposé. Si la complétude ne peut pas être démontrée avec l'API retenue,
refuser le multipart dans le premier flavor et l'annoncer ; ne pas passer arbitrairement
`complete=true`. Le modèle ne valide pas les PDU Android.

Pas de cache persistant de corps SMS pour dédupliquer : la transition globale fait foi.
Messages invalides sans écriture lourde et sans logs d'entrée ; une limite de débit ne doit
pas devenir un verrouillage permanent que n'importe quel expéditeur pourrait provoquer.

## 8. Validation et portes restantes

Exécuter `python -m unittest discover -s tools/security_model -v` à la racine.
Les 25 tests couvrent bornes contacts/durées, retrait/rotation, correspondance contact-secret,
rejeu, concurrence, expiration exacte, boot/horloge, permission observée, assemblage abstrait,
crash avant/après effets et checkpoints, panne de clés, stockage, réseau et tâches indépendantes.
Les numéros et secrets des tests sont fictifs, jamais du matériel utilisable en production.

À obtenir avant activation : tests Kotlin du même contrat ; tests transactionnels avec kill
du processus pendant commit ; tests Android des PDU/permissions/boot ; preuve d'invalidation
de toutes les voies d'accès aux clés ; backend DELETE-only et uploads concurrents ; tests
réels hors ligne, verrouillage, double SIM et redémarrage ; manifests fusionnés et dernier SHA
tests/lint/build verts. Les tests Python ne prouvent aucune de ces intégrations.
Issues #2 et #8 restent ouvertes. La partie conception est livrée ; le produit n'est pas sûr
ni activable sur cette seule base.

## Références officielles consultées

- [SystemClock](https://developer.android.com/reference/android/os/SystemClock) : base monotone incluant la veille.
- [BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver) : limites de vie du receiver et goAsync.
- [Telephony.Sms.Intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents) : réception et décodage système.

Les choix de durée, de désarmement et de transaction sont les décisions de ce projet,
pas des garanties supplémentaires fournies par ces API.
