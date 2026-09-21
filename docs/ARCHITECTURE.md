# Architecture

Resilience Vault ne contourne pas le sandbox Android. Il ne lit que les arbres SAF explicitement accordés, les données fournies par une API officielle de connecteur et ses propres données privées.

## Connecteurs

- `signal-backup` : URI SAF vers le dossier choisi.
- `generic-tree` : URI SAF générique.
- `telegram-tdlib` : session TDLib dédiée.

Un connecteur ne reçoit jamais directement la clé maître du coffre.

## Crypto

`VaultCryptoPort` reste sans implémentation de production. Le futur format doit séparer clé de données, enveloppe locale, récupération multi-appareil et effacement cryptographique.

## Cloud

`RemoteVaultPort` ne recevra que des blobs chiffrés. Le futur `DeleteOnlyCredential` devra permettre DELETE sans GET/LIST/RESTORE.

## Panic

L'admission locale/SMS synthétique consomme l'armement et écrit LOCAL_PENDING dans le même
registre. Le coordinateur reprend la phase locale critique puis les tâches post-destruction.
Le store vérifie ses commits, refuse les transitions arrière et ne recrée pas un registre manquant.
PanicAccessGate existe, mais le futur gestionnaire de leases est encore nécessaire avant tout
accès réel aux clés. Bouton destructif, receiver SMS et effets réels restent absents/inactifs.
Désactivation du launcher reportée hors MVP.

## Décisions actuelles

- [Revue Kotlin du 21 septembre](GPT6-IMPLEMENTATION-REVIEW-2026-09-21.md).
- [Format crypto et cycle des clés](CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md).
- [Contrat serveur DELETE-only](DELETE-ONLY-SERVER-CONTRACT.md).
- [Relais GPT-5.6](RELAIS-GPT56-APRES-REVUE-2026-09-21.md).

La décision SMS du 20 septembre demeure la base ; les documents ci-dessus précisent les
correctifs et les limites restant à valider. Une interface ou un test fake ne prouve pas
la destruction de clés ni la durabilité sur Android.
