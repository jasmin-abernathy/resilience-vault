# Revue de consolidation — 5 octobre 2026

Base examinée : `7f729dbd5a9be7bb5113abf4d42b9273226e00b8`, PR #33.
Cette revue de code et ses tests ne constituent pas une validation de production.

## Corrections de ce lot

1. **Perte du ledger** : les connexions SQLite ordinaires pouvaient recréer un fichier disparu,
   puis le démarrage recréait ses tables vides. Désormais les connexions et ATTACH utilisent
   `mode=rw`, sans création. Un seul fichier présent ou un schéma existant incomplet bloque le
   démarrage. Les deux fichiers d'un nouveau modèle local sont réservés exclusivement avant
   leur initialisation ; une initialisation interrompue ne se répare pas automatiquement.
2. **Contradiction entre primaire et ledger** : le démarrage et la réconciliation refusent un
   tombstone primaire ou une outbox sans autorité correspondante. Une ancienne base primaire
   reste restaurable si le ledger durable est intact ; le sens inverse n'est pas réparé.
3. **Lecture et purge** : la lecture vérifie aussi l'existence et l'état de la génération
   primaire ; une purge exige un tombstone avec le même identifiant d'opération avant tout effet.
4. **Chemins de stockage** : un même fichier, un alias symbolique ou un lien dur ne peut servir
   simultanément de primaire et de ledger. Les métacaractères des chemins restent littéraux.
5. **Schéma de confirmation** : `schemaVersion=true` et `1.0` ne sont plus assimilés à l'entier
   `1`. Un état JSON structuré est refusé par `ProofValidationError`.
6. **CI sans paquet** : tests JVM, compilation des tests instrumentés et lint conservés ;
   `assembleDebug` retiré pour respecter l'interdiction de produire un APK sans demande.

La primitive SQLite employée est documentée dans https://www.sqlite.org/uri.html : `mode=rw`
ouvre un fichier existant, contrairement à `rwc`. Aucun algorithme cryptographique ajouté.

## Frontières Android relues

- `AuthenticatedLocalKekEnvelope` : IV produit par Keystore, AAD vérifiée, Cipher authentifié
  identique, callback à usage unique et conteneur Tink strict. Ce constat ne remplace pas les
  tests biométriques API 26–29 et 30+ ni la validation des propriétés matérielles.
- `VaultProvisioningTransaction` : journal BEGIN avant création, enveloppe relue/déchiffrée,
  registre puis COMMITTED ; pas de reprise implicite d'un état intermédiaire.
- `VaultKekRotation` : inventaire des deux aliases avant nouvelle clé ; enveloppe durable et
  preuve du même E avant destruction de l'ancien alias ; états incomplets bloquants.
- `AndroidVaultLifecycle` / `VaultCryptoRuntime` : opérations sérialisées sous lease, fermeture
  des sessions et drainage avant destruction des aliases et credentials de lecture.
- `AndroidInstallationSecurityMutationCoordinator` : transition d'autorité bloquante pendant
  rotation ; relecture identité/registre/panic avant publication ; adoption legacy UNKNOWN.
- `PanicRecoveryCoordinator` : phase locale avant effets réseau ; les erreurs restent en attente,
  et LEGACY_UNPROVEN n'autorise pas COMPLETE. Les timeouts sont coopératifs : l'adaptateur réel
  devra aussi borner ses appels bloquants.
- Capsule DELETE : espace de clés distinct, tuple tenant/service/coffre/génération lié à l'AAD,
  hash de capsule dans l'intention persistante, capacité de lecture détruite séparément.
- Provisioning distant : modèle de transitions et preuves de binding seulement ; aucune
  publication Android CONFIGURED ni origine de production disponible.
- Récupération : vérification kit/archive/head attendu, nouvelle identité et rechiffrement
  vers une nouvelle session. Le parcours persistant sur un second appareil reste à valider.

La revue ne prétend pas démontrer l'absence de toute faille Android. Les adaptateurs physiques,
les effets système et l'intégration réseau ne sont pas exécutés dans cet environnement.

## Décision sur la preuve d'effacement

Un HTTP 200 et le parseur candidat ne prouvent ni l'émetteur ni l'effacement des sauvegardes.
Une signature authentifierait une déclaration du serveur, pas l'effacement physique à elle seule.

Avant une annonce COMPLETE de production, exiger :

- tuple service/tenant/coffre/génération/opération/digest exact et version de protocole admise ;
- autorité tombstone durable, isolée des restaurations ordinaires et ancrée contre le rollback ;
- inventaire réel des objets actifs, staging, versions et réplicas couvert par la purge ;
- politique de backups nommée, avec état exact des copies encore conservées ; ne jamais annoncer
  « toutes les copies supprimées » tant que des sauvegardes récupérables subsistent ;
- preuve liée à une requête fraîche et à une révision minimale persistante ; un horodatage fourni
  par le serveur seul ne résout ni le rejeu ni une horloge client incorrecte ;
- consultation authentifiée de l'autorité pour un état en ligne, ou attestation signée standard
  revue pour un justificatif durable hors ligne. Aucun format/signataire maison ajouté ici ;
- pour une attestation signée : identité et key ID approuvés, stockage de clé, rotation et
  révocation explicites, conservation des clés nécessaires à l'historique, refus après perte ou
  compromission tant qu'une procédure indépendante n'a pas rétabli la confiance.

Le choix d'hébergement, l'IdP, les domaines, l'identité du signataire et la politique de backups
restent des décisions opérationnelles. Aucune valeur fictive n'est inscrite dans l'application.

## Limites restantes et suite

- Le modèle local peut encore créer un environnement quand **les deux chemins sont absents**.
  Ce comportement de laboratoire ne distingue pas une première utilisation de la perte totale
  de l'installation. La production doit avoir un enrollment/bootstrap explicite séparé du
  démarrage ordinaire et une autorité indépendante ; ne pas exposer ce serveur de référence.
- Un rollback coordonné du primaire et du ledger, sans preuve externe plus récente, n'est pas
  détectable par ces deux fichiers. Les corrections ne constituent pas un anti-rollback distribué.
- Arrêt brutal de processus, coupure électrique, disque plein, restauration administrative et
  sauvegardes de l'hébergement réel restent à éprouver. Les injections d'exceptions SQLite ne
  démontrent pas à elles seules la durabilité matérielle.
- Le serveur reste loopback, authentification par défaut DenyAll ; aucun serveur public livré.
- Les tests physiques de `ANDROID-HARDWARE-TEST-MATRIX-2026-09-24.md` restent obligatoires.
- Avant le prochain lot d'intégration : fixer l'identité/authentification et la séparation réelle
  stockage actif / archive de récupération / autorité tombstone. Puis implémenter et auditer les
  adaptateurs ; les tests téléphone ne sont donc pas encore la seule limite.

Les deux gates restent `false`. Pas de merge #33, pas de release, APK ou AAB.

## Vérification

- Base : 64 tests serveur passent avant modification.
- Lot : 77 tests serveur (13 nouveaux), 32 tests des modèles et invariants statiques.
- Validation Android attendue sur le SHA final via la CI de #33 : tests JVM, compilation des
  tests instrumentés et lint. Consulter le run de ce SHA ; un ancien run vert ne valide pas ce lot.
