# Intégration cryptographique — état vérifiable du 24 septembre 2026

Base : PR #13, b6d84a73fa29ae020b9de5525ea295042c09aca7. Aucune activation de production, fusion automatique ou publication d'APK.

## Code réel ajouté

- TinkVaultSession utilise Tink Android 1.23.0 : E en AES256_GCM, D neuf par objet en AES256_GCM_HKDF_1MB, keysets D/E uniquement sérialisés chiffrés avec AAD. Import limité à un keyset d'un seul type attendu. R seul possède une sortie en clair, réservée au kit externe explicite.
- Le contexte RVB1 existant est vérifié octet pour octet contre le contexte attendu. Objets : header 120 octets, longueur d'enveloppe D sur 4 octets big-endian, enveloppe Tink, flux Tink. La KEK locale utilise un sous-format version 1, IV 12 octets et tag GCM 16 octets. Aucun nonce applicatif fourni aux primitives Tink.
- AndroidAuthenticatedKekAead exige une SecretKey non exportable, conserve l'authentification par usage et propage les erreurs. Le callback d'autorisation doit terminer un vrai BiometricPrompt/CryptoObject ; il n'est PAS encore relié à l'UI. Tink peut vérifier une sérialisation chiffrée par déchiffrement : plusieurs autorisations peuvent donc être nécessaires, à tester sur appareil. Aucun contournement de cette vérification n'est admis.
- VaultProvisioningTransaction orchestre les vrais ports Android : BEGIN durable avant création de clé ; KEY_CREATED ; wrapping E ; écriture AtomicFile, sync et vérification ; déchiffrement de contrôle ; ENVELOPE_WRITTEN ; registre relu ; COMMITTED. AndroidVaultProvisioningEffects relie les stores existants, AndroidVaultKek et le nouveau fichier d'enveloppe dans noBackupFilesDir. Le journal incomplet bloque l'ouverture ; aucun chemin de réparation automatique.
- VaultCryptoRuntime invalide les handles, annule/draine les propriétaires de leases et appelle séparément suppression des aliases puis credentials lecture. La libération d'une lease annulée est protégée par NonCancellable. Les callbacks de destruction doivent confirmer tous les aliases, y compris ceux d'un provisioning interrompu ; le câblage Android complet de cet inventaire reste à faire.
- TinkRecoveryArchive vérifie kit, enveloppe E, manifeste et TOUS les objets jusqu'à EOF avant preuve. Les sources sont fermées avant retour, y compris sur erreur. Le trial relit/déchiffre les objets. Le transfert vers B crée des D neufs sous l'E neuf de B et impose vaultId et génération différents ; aucune réinstallation de l'E de A.

## Format portable strict

Toutes les longueurs/counts sont int32 big-endian positifs et bornés. Identifiants : 32 octets ; époque/révision : int64 positifs. Aucun champ optionnel ou trailing byte accepté.

Identité archive : vaultId[32], génération[32], head[32], époque[8], manifestId[32], révision[8]. Le head est SHA-256 du conteneur manifeste chiffré exact. Le vérificateur EXIGE un RecoveryArtifactBinding attendu indépendant ; il ne le déduit pas des octets reçus.

- RVK1 : magic[4], identité, longueur R[4], keyset R standard Tink, longueur tag[4], chiffrement AEAD de message vide avec tout le préfixe comme AAD. Le kit contient un secret ; il n'est jamais à déposer avec l'archive au serveur. Sa vérification comprend aussi l'ouverture de l'archive, pas seulement un checksum autoréférentiel.
- RVA1 : magic[4], identité, champ enveloppe E, champ manifeste chiffré, nombre d'objets, champs conteneurs objet dans l'ordre du manifeste.
- Enveloppe E externe : contexte EPOCH_RECOVERY[120] + head[32] comme AAD, puis keyset E chiffré par R.
- RVM2 : manifeste binaire chiffré : magic[4], count[4], puis pour chaque objet ID[32], revision[8], taille claire[4], taille conteneur[4], digest conteneur[32]. IDs uniques et non nuls. Le nom RVM2 distingue explicitement ce premier codec exécuté de la proposition JSON antérieure ; aucun fallback JSON n'est implémenté.

Cette première archive conserve les contenus et identifiants, PAS encore les chemins/noms/provenances du futur inventaire produit. Ne pas la présenter comme sauvegarde complète d'une arborescence utilisateur.

## Limites volontaires de ce lot

16 MiB clairs par objet, 1 MiB par manifeste, 64 MiB par archive, 128 objets. Ces limites remplacent pour cette implémentation la proposition 8 GiB du document initial. Tout dépassement est refusé. La restitution est bornée en RAM, jamais en staging clair sur disque ; aucun octet partiel n'est rendu avant le dernier tag. Les grands backups Signal nécessitent encore une chaîne de staging chiffré par flux. La RAM JVM/Tink et les copies détenues par l'appelant ne sont pas effaçables avec garantie ; les buffers temporaires contrôlés sont remis à zéro quand possible.

Les sessions et exports sont des APIs internes. Ne pas retourner de primitive, de session ou de plaintext hors de la portée d'une lease dans le câblage produit. Toute publication durable doit aussi être protégée contre le panic au moment du commit ; le runtime actuel ne constitue pas à lui seul une transaction d'export SAF/backend.

La récupération conserve une ancre attendue exacte ; elle refuse un head différent. Elle ne prouve pas que l'ancre est la plus récente dans le monde. Aucun backend, anti-rollback matériel ou registre distant n'est ajouté.

## Frontières encore ouvertes avant la fin du chantier

1. Rotation à deux aliases : PAS implémentée ici. Ne pas simuler une rotation via createExplicit. Il faut journal durable de rotation, publication du pointeur d'enveloppe et inventaire destructif couvrant ancien, nouveau et aliases orphelins. Ancien alias supprimé uniquement après vérification et commit de la nouvelle enveloppe. C'est le prochain lot de sécurité, pas une tâche déjà validée.
2. Assemblage Android singleton : mutualiser la transaction de provisioning/rotation et le panic, construire l'inventaire d'aliases depuis registre ET journaux incomplets, fermer les opérations de création pendant le panic. Les adaptateurs existent mais ne sont pas raccordés à un bouton.
3. Autorisation biométrique réelle, persistance du nouveau coffre B et publication transactionnelle de ses nouveaux conteneurs/manifeste. Le transfert cryptographique A→B en RAM est implémenté ; le parcours utilisateur complet ne l'est pas.
4. Adaptateur ActiveVaultHeadEvidencePort branché au store du produit : la vérification cryptographique est disponible dans verifyActiveHead, mais aucun store actif n'est inventé. La politique d'emplacement hors DELETE doit venir du stockage configuré, jamais d'un champ de l'archive.
5. Tests instrumentés sur appareils, reboot/kill/stockage plein pendant les vraies écritures, rotation, purge physique contrôlée et inventaire des credentials. Les tests de modèle/JVM ne prouvent pas ces propriétés.

## Tests ajoutés et documentation API

Tests JVM : enveloppes/AAD/contexte, altération/version/longueur, frontières de segment, troncature et suffixe, invalidation réelle du handle, récupération après fermeture A, nouveaux IDs sur B, kit/archive manquants, mauvais kit/génération/head, fermeture des sources et erreur de fermeture, interruption à chaque appel du provisioning, alias absent/corruption et panic hors ligne annulant une opération active.

Sources API vérifiées sur Tink v1.23.0 :
- https://github.com/tink-crypto/tink-java/blob/v1.23.0/src/main/java/com/google/crypto/tink/TinkProtoKeysetFormat.java
- https://github.com/tink-crypto/tink-java/blob/v1.23.0/src/main/java/com/google/crypto/tink/aead/PredefinedAeadParameters.java
- https://developer.android.com/privacy-and-security/keystore

PRODUCTION_CRYPTO_READY et SMS_REMOTE_PANIC_READY restent false. Aucun backend n'est considéré production-ready. Le présent document décrit une implémentation partielle testable, pas une garantie de suppression/récupération après destruction totale.

## Follow-up: durable KEK rotation and lifecycle assembly

`VaultKekRotation` and `AndroidVaultRotationEffects` now implement KEK-only rotation.
The E/D epoch stays unchanged. The rotation alias suffix `.r<registry revision>`
is independently bound to the wrapping AEAD associated data. A durable BEGIN intent
and registry containing both aliases precede key generation. The new encrypted E is
synced, read back, opened and tested against an encrypted proof made with the original E
before the old KEK can be deleted. Registry finalization and COMMITTED follow deletion.
Repeated rotations retain one alias. Missing/corrupt/incomplete rotation state blocks
opening; there is no automatic repair, fallback or key regeneration. Interrupted rotation
may require recovery from the external kit. The local digest is corruption detection,
not an authenticated anti-rollback counter.

`AndroidVaultLifecycle` is an internal singleton per installation directory. Provisioning,
opening and rotation share the same runtime operation mutex and panic leases. It refuses
fresh provisioning while orphan KEKs exist. It requires an already initialized IDLE panic
record; first-install state initialization is deliberately not inferred from missing files.
It is NOT yet invoked by the production UI. The credential removal callback is mandatory
and must be provided once by the app's read-credential owner; no cloud credential backend
is invented by this assembly.

Panic closes admission and invalidates sessions immediately, then cancels and drains all
leases, including queued mutations and key-opening operations. Destruction now requires
a successfully completed drain, not merely an empty set of published sessions. A timeout
or cancelled drain cannot authorize destruction. Actual Android alias inventory covers
ALL `rv.kek.v1.*` keys in this app's Keystore namespace, including incomplete provisioning
and rotation orphans even if journal/registry files are missing or corrupt. All deletion
attempts are made after a partial failure, but any error or residual alias prevents success.
Other Keystore namespaces are preserved. No network is needed.

Added JVM tests exercise repeated real-Tink rotation, cuts after every rotation boundary,
corrupted readback, identity/key failures, orphan/partial/repeated deletion, serialization
against panic and cancelled-drain refusal. These are not physical Android durability tests.

Remaining integration work: BiometricPrompt UI and cancellation; trusted read-credential
removal; first-install panic-state ceremony; durable device-B object/manifest publication;
real active-head/storage-scope adapters; file inventory and larger encrypted staging;
physical crash/reboot/full-disk/Keystore tests and audit. Production and SMS gates remain
false. Earlier statements above that rotation and singleton assembly were missing describe
the initial PR commit and are superseded only by this section; UI/backend/device validation
are still open.
