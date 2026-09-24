# Relais GPT-6 — Resilience Vault après lot GPT-5.6 post-PR #10

Date : 24 septembre 2026

## Empilement

- PR #9 : préparation crypto / registre / journal, base `main`.
- PR #10 : bindings de récupération, base PR #9.
- Branche GPT-5.6 : `work/recovery-adapters-ci-20260924`, base HEAD PR #10
  `303e0d4e9bf16751cc050dc061a5f354d33a139e`.

## Cause CI #10 diagnostiquée

`resilience-vault` est devenu public alors que `app-build-factory` reste privé. Le workflow de
Vault appelait le reusable workflow privé. GitHub n’autorise pas un caller public à utiliser un
reusable workflow privé, ce qui explique le run #10 instantanément en échec avec `jobs=[]`.

Le lot GPT-5.6 rend donc la CI Vault autonome : mêmes versions JDK/SDK et mêmes tâches Gradle,
mais aucun APK n’est uploadé/distribué.

## Préparations ajoutées

- contrats post-vérification `RecoveryEvidencePorts.kt` pour kit externe, archive chiffrée
  complète, essai de restauration et head actif ; aucun `InputStream` n’est exposé au policy ;
- assembleur typé vers `RecoveryPreflightEvidence`, sans booléen UI libre pour les preuves ;
- tests adversariaux : ancien kit après rotation, archive incomplète/sous scope DELETE, head
  avancé, registre tronqué, écriture de rotation incertaine, transition journal interrompue ;
- matrice séparant JVM / instrumenté / émulateur / téléphone physique ;
- inventaire panic complété, notamment originaux SAF/Signal/Telegram explicitement hors pouvoir
  d’effacement de Vault.

## Toujours réservé à GPT-6

1. API Tink exactes et format E/D/R.
2. Transaction journal ↔ registre ↔ KEK ↔ enveloppe E, migration et rotation 2 aliases.
3. Implémentations réelles des ports de récupération et authentification cryptographique.
4. Raccordement des vrais handles aux leases puis destruction Keystore réelle.
5. Backend DELETE-only, tombstone/purge et credentials réels.
6. Restauration complète sur second appareil vers nouveau vaultId/génération.
7. Décision finale d’activation de `PRODUCTION_CRYPTO_READY` seulement après tests matériels.

## Gates

- ne pas fusionner automatiquement les PR ;
- `PRODUCTION_CRYPTO_READY=false` ;
- aucun backend réel ;
- aucune permission SMS ajoutée ;
- aucun APK publié ;
- aucune promesse “récupérable après tout effacer” avant exercice complet sur appareil.

## Validation

Relever le HEAD réel de cette branche et la CI du SHA exact avant toute suite. Un test JVM de
policy/store ne prouve ni AtomicFile après coupure électrique, ni Keystore, ni StrongBox.
