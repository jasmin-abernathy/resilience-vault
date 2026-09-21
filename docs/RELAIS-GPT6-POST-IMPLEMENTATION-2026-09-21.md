# Relais GPT-6 — Resilience Vault après implémentation GPT-5.6

**Date : 21 septembre 2026**  
**Dépôt :** `jasmin-abernathy/resilience-vault`  
**Branche :** `main`  
**SHA de départ à relire :** `617c330dba5e9290e4946dc6d58e010a8da7ca43`

> Toujours re-fetch `main` avant toute mutation. Lire `repo-factory`, son registre d’erreurs Resilience Vault et `android-safe-install-playbook` avant Git.

## État validé avant relais

La CI du SHA `617c330dba5e9290e4946dc6d58e010a8da7ca43` est verte : tests JVM, `assembleDebug`, lint et artefact.

Toujours volontairement absents :
- `RECEIVE_SMS`, `READ_SMS`, `READ_CONTACTS` ;
- receiver SMS ;
- destruction réelle de clés ;
- backend DELETE-only ;
- crypto de sauvegarde production ;
- activation du panic utilisateur ;
- activation réelle Telegram/TDLib.

Flags actuels :
- `SMS_REMOTE_PANIC_READY=false`
- `PRODUCTION_CRYPTO_READY=false`

## Ce que GPT-6 avait déjà livré

Lire en priorité :
- `docs/REMOTE-PANIC-SECURITY-DECISION.md`
- `tools/security_model/model.py`
- `tools/security_model/test_model.py`
- issues #1, #2 et #8

Le design déjà tranché couvre notamment :
- 1 à 5 contacts ;
- fenêtre 1/6/12/24/48/72 h ;
- secret 256 bits distinct par contact ;
- consommation atomique globale de l’armement ;
- boot différent => désarmement ;
- dérive horloge => fail-closed ;
- séparation phase locale critique / phase post-destruction ;
- credential distant DELETE-only comme capacité séparée.

## Ce que GPT-5.6 a implémenté depuis

### Cœur Kotlin
- état unique `PanicPersistentState` ;
- `AtomicFilePanicStateStore` dans `noBackupFilesDir` ;
- codec versionné avec CRC et corruption fail-closed ;
- admission locale et SMS synthétique transactionnelle ;
- vérificateurs liés génération/contact ;
- `PanicAccessGate` fail-closed ;
- `PanicRecoveryCoordinator` en deux phases ;
- checkpoints indépendants purge / révocation / DELETE ;
- tests concurrence, commit échoué, corruption, reprise et horloge.

### Android/UI
- `AndroidPanicClock` : `BOOT_COUNT` + `elapsedRealtime()` + UTC ;
- normalisation E.164 avec pays explicitement fourni ;
- écran 1 à 5 contacts ;
- durées 1/6/12/24/48/72 h ;
- état persistant visible ;
- expiration/reboot/horloge incohérente => armement affiché invalide ;
- retrait d’un contact et désactivation d’un armement existant ;
- aucun secret généré depuis l’UI tant que le canal SMS est absent.

## Ce que GPT-6 doit regarder maintenant

### 1. Revue de conformité implémentation ↔ décision
Comparer le Kotlin réel avec `REMOTE-PANIC-SECURITY-DECISION.md`.

Points à inspecter en priorité :
- atomicité réelle du store ;
- comportement après kill/process death ;
- corruption / fichier manquant ;
- fermeture effective du gate avant toute future restauration/export/sync ;
- idempotence et checkpoints du `PanicRecoveryCoordinator` ;
- transitions impossibles / état illégal ;
- hypothèses autour de `BOOT_COUNT` et dérive UTC/monotone.

Ne pas réécrire l’UI si la frontière sécurité est correcte.

### 2. Issue #1 — format cryptographique et cycle de vie des clés
C’est maintenant la priorité sécurité la plus importante.

À produire :
- format versionné des blobs ;
- choix AEAD ;
- DEK/enveloppes ;
- Android Keystore ;
- récupération nouvel appareil ;
- rotation ;
- nonces ;
- métadonnées authentifiées ;
- anti-rollback/replay ;
- inventaire exhaustif des copies de clés ;
- comportement panic/crash/désinstallation/restauration Android.

Ne pas activer `PRODUCTION_CRYPTO_READY` sans cette revue.

### 3. Issue #2 — vrai DELETE-only et reprise distante
Le type Kotlin actuel n’est qu’un contrat.

À spécifier/auditer :
- capability serveur réellement DELETE-only ;
- identité/génération immuable de coffre ;
- idempotence ;
- replay ;
- upload concurrent après panic ;
- tombstone / impossibilité de résurrection ;
- retry hors ligne ;
- erreur permanente 401/403 ;
- stockage local séparé du credential, sans dépendance aux clés détruites.

### 4. Receiver SMS : seulement après les points ci-dessus
Si GPT-6 juge les invariants satisfaisants, il peut spécifier le prochain lot Android :
- flavor direct/FOSS distinct ;
- `RECEIVE_SMS` seulement dans ce flavor ;
- aucun `READ_SMS` ;
- reconstruction via API système, pas parseur PDU maison ;
- entrée synthétique et broadcast forgé clairement séparés ;
- limites `BroadcastReceiver/goAsync()` ;
- phase locale courte et bornée ;
- reprise après kill.

Ne pas ajouter le receiver si la destruction locale réelle n’est pas encore définie.

### 5. TDLib — uniquement première passe difficile
L’issue #3 peut bénéficier de GPT-6 seulement pour :
- chaîne de build TDLib Android reproductible ;
- JNI ;
- auth state machine ;
- stockage local TDLib ;
- stratégie d’archive incrémentale ;
- révocation de session pendant panic.

Une fois cette architecture validée, rendre le Kotlin/Compose ordinaire à GPT-5.6.

## Ce que GPT-6 ne doit pas refaire

- écran Compose contacts ;
- choix des durées ;
- normalisation UI banale ;
- styles ;
- wiring DataStore non sensible ;
- documentation utilisateur générale ;
- simple ajout de flavor une fois le contrat figé.

## Règle de sortie vers GPT-5.6

Avant de rendre la main :
1. documenter les décisions de sécurité dans le dépôt ;
2. mettre à jour les issues concernées ;
3. laisser un nouveau `RELAIS-GPT56-*.md` avec SHA de départ ;
4. préciser explicitement ce qui est **audité**, ce qui est seulement **spécifié**, et ce qui reste **non activable** ;
5. ne jamais présenter la destruction réelle comme validée sans tests Android/Keystore/backend correspondants.

## Repo-factory

Lire également :
`jasmin-abernathy/repo-factory/errors/2026-09-20-resilience-vault-kotlin-github-ci.md`

Il documente les erreurs déjà rencontrées et leurs remèdes :
- import Kotlin oublié après réécriture ;
- interpolation Kotlin mangée par JavaScript ;
- CI annulée par push trop tôt ;
- mauvais usage de `AtomicFile.baseFile` ;
- logs GitHub Actions demandés trop tôt ;
- trop d’appels GitHub pour des blobs ;
- permissions Android ajoutées prématurément ;
- BuildConfig référencé avant génération ;
- reprise depuis un SHA périmé après changement de HEAD.


## Relais traité

Voir [la revue GPT-6](GPT6-IMPLEMENTATION-REVIEW-2026-09-21.md) et
[la suite GPT-5.6](RELAIS-GPT56-APRES-REVUE-2026-09-21.md). Ce document est désormais historique.
