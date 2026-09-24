# Relais GPT-6 — après préparation registre/récupération GPT-5.6

Date : 24 septembre 2026
Branche : `work/crypto-hardening-prep-2026-09-23`
Point de départ de ce lot : `3111f7c8fe1744c41df93d23c8ec42d530cc1500` (CI verte).

## Ce que GPT-5.6 a préparé

- `AtomicVaultProvisioningJournalStore` conserve la même API Android mais délègue la logique
  fail-closed à `VerifiedVaultProvisioningJournalStore`, testable sans prétendre simuler la
  durabilité Android.
- Tests ajoutés : write silencieux, erreur après publication, corruption, concurrence de création,
  transition sautée/étrangère.
- Registre candidat `VaultSecurityRegistryRecord` + codec borné + store AtomicFile V1 dans
  `noBackupFilesDir`. Il inventorie identité/génération/révision et plusieurs aliases de rotation.
- Le registre n’est relié à aucune création/suppression de clé ; ses écritures sont `internal`.
- `RecoveryPreflightPolicy` exige kit relu, archive complète, archive hors scope DELETE, essai de
  restauration et correspondance avec le head actif exact.
- Tableau de couverture ajouté dans `DESTRUCTION-INVENTORY-COVERAGE-2026-09-24.md`.

## Ce qui doit rester bloqué pour GPT-6

1. Revoir le format/migration du registre et décider si le modèle V1 installation-level convient.
2. Définir la transaction exacte journal ↔ registre ↔ création KEK ↔ enveloppe E, y compris crash.
3. Définir la rotation à deux aliases : ajout durable avant nouvelle enveloppe, puis retrait
   seulement après preuve de suppression de l’ancien alias.
4. Relier le registre aux leases/panic et décider quoi faire si Keystore retourne erreur plutôt
   qu’absence.
5. Valider la récupération : kit R, archive indépendante, tombstone, nouveau vault/génération.
6. Auditer les API Tink exactes E/D/R et l’absence de keyset clair/fallback.
7. Exiger tests instrumentés/physiques AtomicFile + Keystore avant activation.

## Gates inchangées

- `PRODUCTION_CRYPTO_READY=false`.
- aucun backend cloud réel ;
- aucune permission SMS ajoutée ;
- aucune promesse produit « récupérable » ou « zero-knowledge » à valider depuis ces seuls tests ;
- ne pas fusionner avant CI verte sur le SHA final et revue indépendante.

## Validation attendue

Relever le HEAD réel de cette branche, vérifier ses workflows sur ce SHA exact puis poursuivre.
Les tests JVM de store démontrent le contrat de décision, pas la résistance à une coupure de
courant Android. Les tests matériels demandés dans le relais précédent restent obligatoires.
