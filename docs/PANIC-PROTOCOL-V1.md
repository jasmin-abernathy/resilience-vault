# Panic v1 — machine à états et capability de suppression

Issue #2, revue de conception du 20 septembre 2026. Contrat à implémenter avant toute activation ; le coordinateur Kotlin du bootstrap n'est PAS conforme à ce contrat.

## Résultats de l'audit du bootstrap

| Constat dans PanicCoordinator / ports | Conséquence | Correction requise |
| --- | --- | --- |
| lockInterface suspend avant destruction | une erreur UI peut empêcher la destruction | verrou d'accès interne synchrone ; UI après destruction |
| liste en mémoire seulement | crash = perte de progression | journal durable préarmé + reprise au démarrage |
| pas de verrou partagé avec sauvegarde/restauration | recréation de clés ou publication après panic | verrou de cycle de vie et génération invalidée |
| suppression distante booléenne | refus définitif confondu avec panne réseau | résultats typés et traitement explicite |
| queue créée après réseau | crash peut perdre le retry | capsule durable créée AVANT armement |
| disableLauncher inconditionnel | perte du point de reprise utilisateur | retirer cette étape du parcours ; aucune dissimulation |
| révocation session non qualifiée | attente réseau possible dans phase locale | séparer destruction locale et révocation serveur |
| DeleteOnlyCredential est une value class String | toString peut exposer le secret | conteneur opaque, représentation expurgée, pas de sérialisation générique |
| VaultCryptoPort sans contexte | objets interchangeables à tort | contexte RVLT obligatoire, aucune surcharge sans AAD |

## Préarmement

Un coffre n'est armable qu'après vérification du kit externe, des alias, du verrou de cycle de vie et du journal. Le panic LOCAL est le défaut de conception. L'option DISTANT exige un consentement préalable distinct et une capability valide ; ne pas choisir ce mode au moment du retry.

Pour le mode distant, préparer durablement une capsule indépendante des clés détruites : schéma, origine HTTPS fixe, vault ID, génération du coffre, operation ID aléatoire (128 bits), secret DELETE, état, compteur/backoff. Pas de nom de fichier, URI SAF, clé racine, token de lecture, cookie, refresh token ou session Telegram. Protéger la capsule au repos par un alias distinct ne permettant que de lire cette capsule. Exclure backups système/transferts ; ne pas transmettre le secret à WorkManager dans ses inputData.

Journal et capsule sont persistés et relus AVANT d'autoriser l'armement. Les mises à jour sont atomiques et durables ; une écriture en échec doit être traitée comme résultat inconnu, jamais comme succès. Le modèle Python suppose des écritures atomiques pour tester l'ordre ; il ne prouve ni fsync ni les garanties Android/Keystore.

## États durables et ordre

| État | Opération autorisée | Reprise après crash |
| --- | --- | --- |
| ARMED | sauvegarde normale si alias et journal cohérents | vérifier cohérence avant tout accès |
| INTENT | aucune nouvelle lecture/écriture du coffre | détruire les clés, même si déjà absentes |
| KEYS_GONE | purge locale uniquement | répéter la purge idempotente |
| LOCAL_CLEAN | mode local : COMPLETE ; distant : DELETE_PENDING | ne pas recréer de session ou clé |
| DELETE_PENDING | DELETE avec capsule uniquement | même opération/génération, backoff |
| REMOTE_BLOCKED | pas de retry automatique | conserver résultat inconnu/refus et accès à l'écran de suivi |
| COMPLETE | aucun travail normal | nettoyage idempotent du secret de capsule |
| QUARANTINED | aucune opération normale ni réseau | clés détruites si possible ; intervention explicite |

Déclenchement : fermer immédiatement la barrière d'accès interne et invalider les générations des tâches, prendre le verrou partagé, persister INTENT, détruire les alias de lecture et tous les détenteurs de clés en mémoire, vérifier l'absence, persister KEYS_GONE. Ne pas attendre une animation/UI ou un appel réseau. Annuler les tâches ne suffit pas : chaque publication et création de clé vérifie la génération sous verrou. Un appel réseau déjà envoyé ne peut être rappelé ; le tombstone serveur bloque ses effets tardifs.

Ensuite purger staging/caches/session locale et relâcher les grants SAF si approprié (sans supprimer par défaut les fichiers sources). Persister LOCAL_CLEAN. La purge ne touche pas la capsule. Aucune garantie d'effacement physique flash ou des copies antérieures ; si une purge échoue, rester à KEYS_GONE, fermé, sans rapport « terminé ».

En distant, persister DELETE_PENDING avant le premier réseau. Une réponse d'acceptation n'est pas une suppression physique achevée. Après confirmation terminale persistée, effacer la capsule ; si un crash survient entre ces deux actions, COMPLETE répète cet effacement. Ne jamais supprimer la capsule avant la confirmation durable. Le coordinateur doit préserver l'annulation de coroutine, sans la transformer en succès ; la reprise reste possible via l'état durable.

## Stockage plein, état corrompu et erreurs

Si INTENT ne peut être écrit, tenter quand même la destruction locale immédiate, mais interdire réseau et succès. Au redémarrage, un coffre provisionné avec alias absent est fermé ; il ne redevient jamais un coffre neuf. Si écriture ET destruction échouent, la confidentialité locale n'est pas garantie : maintenir la fermeture en mémoire, retourner une erreur critique et ne rien annoncer comme effacé. Un crash dans cette fenêtre reste une limite irréductible sans support durable fiable.

Journal absent ou corrompu pour une installation provisionnée : QUARANTINED, pas de récupération automatique, pas de réseau avec un token découvert au hasard. Alias absent + ancien ARMED = état incohérent, jamais régénération automatique. Capsule corrompue/perdue : destruction locale toujours possible, suppression distante non garantie. Une erreur de stockage ne doit jamais entraîner un fallback de credential vers un token de compte.

Reboot/Doze : reprise différée quand Android permet l'exécution. Force-stop/désinstallation peuvent empêcher tout retry ; aucun délai garanti. Garder le lanceur accessible pour reprendre et voir le statut. WorkManager sert de réveil ; le journal est la source de vérité. Une file vide ne signifie pas « supprimé ».

## DELETE-only : contrat du backend #5

Secret aléatoire 256 bits, opaque, indépendant de toute clé et de l'authentification du compte. Stocker côté serveur son digest avec vault ID, génération immuable, operation ID, état actif/révoqué. Comparaison en temps constant ; aucune présence du token dans URL/logs/analytics/erreurs. Présenter via Authorization sur HTTPS, sans redirection ni cookies partagés. Backend configuré explicitement, jamais fourni par les snapshots.

Seule route autorisée : `DELETE /v1/vaults/{vaultId}/generations/{generation}` avec operation ID fixé à l'armement. Refuser GET, HEAD, LIST, POST, PUT, PATCH, RESTORE, export, création de session et échange de token. Le filtrage est SERVEUR ; le type Kotlin n'est pas une garantie. Un token d'un autre coffre/génération/opération ne fonctionne pas. Un nouveau coffre ne recycle jamais la génération supprimée.

Transaction serveur : valider capability, poser un tombstone irréversible de génération, interdire immédiatement lectures/restaurations/uploads/commits et renouvellements de credentials pour cette génération, puis supprimer objets/versions et planifier la purge des sauvegardes selon une politique documentée. Le tombstone est conservé après effacement du secret et rejette les uploads retardés et tous les anciens tokens de lecture. Les autres appareils peuvent garder leurs copies locales : le serveur ne les efface pas.

| Résultat serveur / transport | État client |
| --- | --- |
| 202 accepté avec tombstone, purge en cours | DELETE_PENDING ; répéter DELETE, jamais GET avec la capability |
| 204 terminal conforme au contrat | COMPLETE puis supprimer capsule |
| timeout, perte réseau, 408, 429, 5xx | DELETE_PENDING, backoff exponentiel borné et jitter ; respecter Retry-After borné |
| 401/403, token expiré/révoqué, génération incorrecte | REMOTE_BLOCKED, aucun refresh de lecture |
| 404/410 générique, 3xx, réponse invalide | REMOTE_BLOCKED ; ne prouve pas l'effacement |

Un rejeu identique retourne le même résultat terminal, sans recréer quoi que ce soit. Le serveur conserve un reçu minimal lié au digest de la capability pour répondre après suppression. Pas de endpoint d'inspection dédié à cette capability. Le kit/compte peut révoquer une capability avant usage ; une révocation ne ressuscite jamais un coffre déjà tombstoné. Rotation : enregistrer nouvelle capability, persister et vérifier nouvelle capsule, révoquer ancienne ensuite. Une capsule périmée reste une limitation distante explicite, pas une raison de conserver une clé de lecture.

## Validation avant activation

Les tests Python couvrent chaque interruption entre effets et écritures, répétition, réseau intermittent, stockage en échec, corruption, refus/rejeu de capability, génération isolée et écritures tardives. C'est un modèle de contrat, PAS un backend ni un coordinateur Android.

GPT-5.6 devra porter les mêmes scénarios dans les tests Kotlin, injecter exceptions et annulations aux suspensions, et tester deux coordinateurs concurrents partageant le verrou. Sur appareil : tuer le processus avant/après chaque suppression Keystore/écriture journal, reboot, stockage plein, cache TDLib ouvert, SAF révoqué, force-stop et reprise. Vérifier qu'aucun écran/service ne recrée de clés après panic. Les issues #1/#2 restent ouvertes jusqu'à preuve sur l'implémentation.

Références : [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [travail persistant Android](https://developer.android.com/develop/background-work/background-tasks/persistent), [TDLib destroy](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1destroy.html), [TDLib logOut](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1log_out.html). Les états, routes et règles de capability ci-dessus constituent notre contrat applicatif.
