# Relais GPT-5.6 — Resilience Vault

Lire repo-factory et android-safe-install-playbook, puis AGENTS.md du dépôt.
Repartir du main actuel ; ne pas réutiliser le SHA de l'ancien relais comme tête.

## Livré par GPT-6

- `docs/REMOTE-PANIC-SECURITY-DECISION.md` : menaces, expiration, secrets, transactions,
  deux phases du panic, limites Android, contrat serveur DELETE-only.
- `tools/security_model/model.py` et `test_model.py` : spécification exécutable indépendante,
  25 tests adversariaux passés localement. Ce n'est pas du code Android livré à l'utilisateur.
- Aucun receiver, permission, crypto réelle ou suppression activé ; aucun APK demandé.

## Lot 1 repris par GPT-5.6

Portage du contrat vers le cœur Kotlin, sans activation utilisateur :

- état persistant unique armement + intention panic ;
- codec versionné avec détection de corruption ;
- adaptateur Android `AtomicFile` stocké dans `noBackupFilesDir` ;
- admission locale/SMS synthétique transactionnelle ;
- secrets générés 256 bits et vérificateurs liés génération/contact ;
- gate d'accès fail-closed ;
- coordinateur scindé phase locale / phase post-destruction ;
- tâches post-destruction indépendantes et checkpointées ;
- tests JVM du contrat, corruption, commit échoué, concurrence et reprise.

Le store `AtomicFile` n'est volontairement pas encore initialisé ni branché à l'UI :
une absence de fichier reste fail-closed. L'initialisation doit être reliée explicitement
au cycle de vie du coffre, pas transformée en défaut IDLE silencieux.

## Prochains lots

1. Construire les écrans : 1 à 5 contacts, durées prédéfinies, retrait, désactivation,
   affichage expiration et avertissement clair du pouvoir donné aux contacts.
2. Adapter horloge/numéros : BOOT_COUNT + elapsedRealtime, normalisation explicite,
   partage des commandes sans persistance du secret brut. Ne pas montrer « actif »
   si receiver ou prérequis sécurité sont absents.
3. Ajouter le gate aux futures voies de restauration/export/synchronisation réelles.
4. Réserver le flavor/receiver à un lot ultérieur après les portes de la décision.
   En attendant, entrées synthétiques uniquement. Ne pas demander READ_SMS/READ_CONTACTS.

Un seul commit cohérent par lot, vérification finale sur ce SHA. Pour les modifications
Kotlin : tests/lint/build applicables et manifest fusionné ; conserver toutes les protections.

## Ne pas déclarer terminé à tort

La conception et le cœur Kotlin ne valident pas la destruction réelle. #2 et #8 restent ouvertes.
Le format cryptographique, copies de clés, Keystore, backend DELETE-only, PDU multipart et
durabilité Android réelle restent à valider. Une découverte qui change ces frontières nécessite
une nouvelle revue de conception ; ne pas improviser une crypto ni contourner Android.

La désactivation du launcher reste hors MVP.

## Lot 2 repris par GPT-5.6

Préparation UI et adaptateurs Android, toujours sans activation SMS :

- adaptateur d'horloge : `Settings.Global.BOOT_COUNT` + `SystemClock.elapsedRealtime()` + UTC ;
- le mode devient indisponible si l'identité de boot n'est pas lisible ;
- normalisation E.164 via `PhoneNumberUtils.formatNumberToE164` avec ISO pays explicitement fourni ;
- écran de préparation 1 à 5 contacts, sans `READ_CONTACTS` ;
- durées strictes 1 / 6 / 12 / 24 / 48 / 72 h ;
- détection visuelle des numéros invalides et doublons ;
- avertissement explicite du pouvoir destructif délégué ;
- `SMS_REMOTE_PANIC_READY=false` dans ce build : aucun armement actif, aucune persistance des numéros saisis ;
- aucun receiver, aucune permission SMS ajoutée.

Le bouton d'armement reste désactivé tant que le canal SMS n'existe pas. Les commandes/secrets
ne sont donc pas générés depuis cet écran et rien n'est présenté comme « actif ».
