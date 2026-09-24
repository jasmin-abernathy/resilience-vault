# Architecture

Resilience Vault ne contourne pas le sandbox Android. Il ne lit que les arbres SAF explicitement accordés, les données fournies par une API officielle de connecteur et ses propres données privées.

## Connecteurs

- `signal-backup` : URI SAF vers le dossier choisi.
- `generic-tree` : URI SAF générique.
- `telegram-tdlib` : session TDLib dédiée.

Un connecteur ne reçoit jamais directement la clé maître du coffre.

## Crypto

`VaultCryptoPort` reste sans implémentation de production et `PRODUCTION_CRYPTO_READY=false`.
Tink Android 1.23.0 est épinglé afin de compiler et tester le choix
`AES256_GCM_HKDF_1MB` avant activation. Les tests actuels utilisent uniquement des keysets
éphémères en mémoire : ils vérifient la primitive, l'AAD, l'altération et la troncature, mais ne
valident ni Android Keystore, ni provisioning, ni rotation, ni récupération.

Le format cible sépare clé de données, enveloppe locale, récupération multi-appareil et
effacement cryptographique. Voir `CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md`.

## Cloud

`RemoteVaultPort` ne reçoit que des blobs explicitement nommés ciphertext. Aucun backend réel
n'est encore activé. Le futur `DeleteOnlyCredential` doit permettre DELETE sans
GET/LIST/RESTORE.

## Panic

L'admission locale/SMS synthétique consomme l'armement et écrit LOCAL_PENDING dans le même
registre. Le coordinateur reprend la phase locale critique puis les tâches post-destruction.
Le store vérifie ses commits, refuse les transitions arrière et ne recrée pas un registre manquant.

`VaultAccessLeaseManager` est maintenant implémenté : les opérations sensibles doivent acquérir
une lease, et le panic ferme puis draine les handles déjà acquis avant destruction de la capacité
de lecture. Ce mécanisme reste à valider avec une vraie implémentation Keystore/crypto et des tests
sur appareil physique. Bouton destructif, receiver SMS et effets réels restent absents/inactifs.
Désactivation du launcher reportée hors MVP.

## Décisions actuelles

- [Revue Kotlin du 21 septembre](GPT6-IMPLEMENTATION-REVIEW-2026-09-21.md).
- [Format crypto et cycle des clés](CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md).
- [Contrat serveur DELETE-only](DELETE-ONLY-SERVER-CONTRACT.md).
- [Relais GPT-5.6](RELAIS-GPT56-APRES-REVUE-2026-09-21.md).

La décision SMS du 20 septembre demeure la base ; les documents ci-dessus précisent les
correctifs et les limites restant à valider. Une interface, un test JVM ou un modèle Python ne
prouve pas la destruction de clés ni la durabilité réelle sur Android.
