# Première revue TDLib/JNI — issue #3

Revue du port existant au 20 septembre 2026. Aucun binaire natif intégré ; aucune validation d'exécution JNI possible dans cette passe. `TELEGRAM_NATIVE_READY=false` reste obligatoire.

## Décisions pour l'implémentation

- Utiliser le JNI Java officiel, avec sources TDLib et bindings générés au MÊME commit. Épingler SHA, NDK, CMake, OpenSSL et checksums ; produire ABI arm64-v8a et x86_64 de test, inventorier licences/SBOM et contrôler l'alignement mémoire Android 16 KiB avant distribution. Pas de binaire tiers opaque.
- Le module est facultatif et chargé seulement après vérification de disponibilité. Gérer bibliothèque absente/ABI incompatible sans casser l'ouverture des dossiers SAF. Un flag de configuration ne prouve pas qu'une bibliothèque se charge.
- Un seul propriétaire du client natif et de sa boucle d'updates ; sérialiser les transitions, corréler les requêtes par ID et génération. Ignorer callbacks tardifs après fermeture/panic. Annuler sans bloquer le thread UI et sans conserver les secrets dans les exceptions.
- `status()` doit observer un état, pas appeler `initialize()` à chaque interrogation comme le fait actuellement TelegramConnector. L'initialisation est une action explicite idempotente.
- Remplacer `NeedsUserAction(reason)` par états typés liés au schéma épinglé : paramètres, téléphone, code, mot de passe, autres actions d'authentification prises en charge, Ready, Closing, Closed, Error. Tout état inconnu ferme les exports ; jamais de Ready par défaut. Seul authorizationStateReady autorise l'export.
- `TelegramRuntimeConfig` est une data class dont toString expose apiHash : représentation expurgée obligatoire. L'API hash compilé dans un APK n'est pas un secret inexpugnable ; ne jamais l'assimiler à une clé de coffre. Numéro/code/2FA sont éphémères, jamais persistés dans état Compose sauvegardable, logs ou crash reports.
- Base et médias dans des répertoires privés dédiés ; clé de base aléatoire indépendante et non vide. Ne pas supposer que la clé de base chiffre tous les fichiers média téléchargés : vérifier sur le binaire retenu, limiter cache clair, purger fichiers et handles séparément. Aucun média dans stockage partagé par défaut.
- Le port d'export actuel ne transmet qu'un compteur/cursor : il manque un sink chiffrant, annulation, limites mémoire/taille, preuve de commit et reprise. Avancer le cursor seulement après commit durable du snapshot. Cursor secret, lié au compte/session/génération et stocké chiffré. Reconnexion/changement de compte invalide le cursor.
- Exports incrémentaux : borner lots et téléchargements, dédupliquer par identifiants stables et version, tester messages modifiés/supprimés et téléchargements partiels. Ne pas prétendre capturer un snapshot atomique du compte en mutation. Secret Chats d'un autre client non disponibles ; `use_secret_chats=false` pour cette première version.

## Panic : distinction essentielle

`destroy` ferme l'instance et détruit les données locales, mais laisse la session dans la liste des sessions serveur. `logOut` nécessite une connexion réseau. La méthode actuelle `revokeLocalSession()` doit donc être remplacée par un contrat de destruction locale clairement nommé ; ne jamais attendre logOut avant la destruction locale des clés.

Invalider les exports/callbacks avant suppression ; attendre la fermeture native ou signaler la purge incomplète, sans réinitialiser TDLib. Aucune session de lecture Telegram ne survit pour retenter une déconnexion. Depuis un autre client autorisé, l'utilisateur peut révoquer la session résiduelle ; aucune suppression des messages du compte ni action sur l'app Telegram officielle.

## Tests à livrer par GPT-5.6 avant nouvelle revue ciblée

Bibliothèque absente, ABI incorrecte, séquence auth complète et inconnue, code incorrect/expiré, annulation, compte remplacé, callbacks après Closed, export réseau interrompu, crash avant/après commit/cursor, médias/cache après destroy, base verrouillée, panic hors ligne pendant auth et export. Vérifier contenu réel des fichiers et logs, sans publier de données personnelles. Revenir à GPT-6 seulement pour écart de modèle de clés, cycle natif non maîtrisé ou bug de concurrence transversal.

Sources officielles consultées : [TDLib/JNI](https://core.telegram.org/tdlib/docs/), [paramètres et clé de base](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_tdlib_parameters.html), [destroy](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1destroy.html), [logOut](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1log_out.html).
