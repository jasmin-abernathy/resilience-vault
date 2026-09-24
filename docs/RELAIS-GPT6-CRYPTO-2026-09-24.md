# Relais GPT-6 — Resilience Vault / crypto production

Date : 24 septembre 2026  
Branche préparée : `work/crypto-hardening-prep-2026-09-23`  
Base : `210dba9333408b551b64900699cf961e3e1f19f1`

## But

Passer de la spécification crypto existante à une implémentation Android réellement zéro
connaissance, sans affaiblir les propriétés panic/delete-only déjà conçues.

## Ce qui est déjà prêt avant GPT-6

- `VaultAccessLeaseManager` ferme puis draine les accès en vol avant destruction de la capacité
  de lecture.
- `PRODUCTION_CRYPTO_READY=false` et `SMS_REMOTE_PANIC_READY=false`.
- Android Backup et device-transfer restent totalement exclus.
- HTTP clair reste interdit par le manifeste.
- `RemoteVaultPort` n'expose qu'`uploadCiphertext(...)` ; aucun backend réseau n'est actif.
- Tink Android est épinglé en 1.23.0, version stable upstream publiée le 9 juillet 2026.
- Test JVM du choix `AES256_GCM_HKDF_1MB` : round-trip multi-segment, AAD modifiée,
  ciphertext altéré, troncature et non-déterminisme.
- CI renforcée : invariants bootstrap + modèles Python avant build Android.
- Aucun keyset Tink de production n'est sérialisé ni stocké par ce lot.

## À auditer / implémenter par GPT-6

### 1. Provisioning explicite et fail-closed

Implémenter la création transactionnelle d'un nouveau coffre : identité/génération, inventaire
de sécurité, alias Keystore, enveloppe d'époque et journal de provisioning. Un état absent ou
corrompu pour un coffre déjà connu ne doit jamais recréer silencieusement une clé.

Tester les crashs entre chaque étape et la reprise après reboot.

### 2. KEK Android Keystore

Créer une KEK AES-256-GCM non exportable par installation/coffre/époque. Aucun fallback vers un
fichier, SharedPreferences, DataStore ou keyset clair si Keystore est indisponible.

Décider et tester précisément :
- exigences d'authentification locale ;
- comportement appareil verrouillé/déverrouillé ;
- invalidation biométrique si utilisée ;
- reboot ;
- présence/absence StrongBox sans fausse promesse matérielle ;
- suppression d'alias pendant panic et rotation interrompue.

### 3. Keysets Tink d'époque et d'objet

Implémenter la hiérarchie décrite dans `CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md` :
- E : keyset d'époque protégé sous la KEK locale ;
- D : keyset streaming neuf par version d'objet, protégé sous E ;
- manifests/enveloppes via AEAD ;
- objets via StreamingAead `AES256_GCM_HKDF_1MB`.

Aucun keyset clair ne doit être écrit sur disque. Aucun `InsecureSecretKeyAccess` ne doit être
introduit dans le chemin applicatif pour contourner la protection des keysets.

### 4. Pipeline plaintext -> chiffrement -> cloud

Le plaintext doit être consommé depuis SAF/connecteur et injecté dans le chiffreur sans fichier
temporaire persistant en clair. Le composant réseau ne doit recevoir que le conteneur finalisé.

Ajouter un test d'intégration avec une sentinelle telle que
`SUPER_SECRET_TEST_84729` et vérifier son absence dans cache, fichiers privés, DataStore/DB,
logs, requêtes réseau capturées et backend de test.

### 5. Staging et restauration

Tout staging privé persistant doit être chiffré au repos sous une capacité incluse dans le panic.
Authentifier le flux complet avant exposition/export utilisateur. Tester EOF prématuré, segment
manquant, AAD/header modifié, objet d'un autre coffre et rollback de manifeste.

### 6. Récupération R et rotation

Implémenter le kit R explicitement exporté hors appareil, sans copie silencieuse serveur.
Tester récupération sur nouvel appareil, rotation KEK/E/R, ancienne copie de kit, interruption
de rotation et inventaire de tous les aliases à détruire.

### 7. Panic + vraie crypto

Relier `VaultAccessLeaseManager` aux handles cryptographiques réels. Le panic doit :
1. fermer l'admission ;
2. drainer/canceller les accès ;
3. détruire toutes les capacités locales de lecture ;
4. persister le checkpoint ;
5. seulement ensuite tenter les opérations réseau.

Tester sur appareil physique les interruptions/crashs/reboots aux frontières.

### 8. Backend après preuve du pipeline

Ne brancher la Lune o2switch qu'après validation du pipeline local. Le serveur doit stocker
ciphertext + identifiants opaques + métadonnées techniques minimales. La capability DELETE
reste séparée des credentials de lecture/upload et ne doit autoriser aucune autre méthode.

## Portes à ne pas lever pendant cette revue

- garder `PRODUCTION_CRYPTO_READY=false` jusqu'à la fin de la revue et des tests appareil ;
- ne pas activer un backend cloud avec upload réel avant preuve qu'aucun plaintext n'atteint
  le port réseau ;
- ne pas ajouter `RECEIVE_SMS` au build principal ;
- ne pas annoncer publiquement « zero-knowledge », sauvegarde sûre ou panic fiable avant
  validation du SHA final.

## Critères de sortie GPT-6

La revue peut proposer de lever `PRODUCTION_CRYPTO_READY` uniquement si :
- key lifecycle et provisioning sont fail-closed ;
- tests Tink + Keystore réels passent ;
- aucun plaintext/keyset clair n'est retrouvé dans les surfaces persistantes ;
- leases/panic sont testés avec les vrais handles ;
- restauration et récupération sont testées ;
- CI est verte sur le SHA final ;
- une relecture indépendante du diff crypto ne trouve pas de contournement évident.
