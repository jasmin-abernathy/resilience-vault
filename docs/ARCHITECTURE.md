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

Ordre invariant :

1. verrouillage UI ;
2. destruction clés locales ;
3. purge staging ;
4. révocation sessions connecteurs ;
5. demande de suppression distante ;
6. retry delete-only si hors ligne ;
7. désactivation launcher.

Le bouton destructif n’est pas encore relié à cette orchestration.
