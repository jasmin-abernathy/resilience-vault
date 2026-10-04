# Resilience Vault

Coffre Android en phase de bootstrap de sécurité. Le dépôt est public, mais aucune version de production n'est encore déclarée sûre ni prête à être distribuée.

## Objectif

Resilience Vault doit permettre de sauvegarder régulièrement des données choisies explicitement par l’utilisateur, puis de les rendre rapidement inaccessibles sur l’appareil en situation d’urgence.

Sources prévues :

- **Signal** : dossier de sauvegarde locale choisi via le Storage Access Framework ;
- **Telegram** : connecteur officiel **TDLib** isolé du reste de l’application ;
- **dossiers génériques** : dossiers explicitement choisis, sans permission de stockage globale.

## État du bootstrap

Cette base contient notamment :

- projet Android Kotlin/Compose ;
- sélection persistante de dossiers SAF ;
- connecteurs Signal et dossier générique ;
- port Telegram TDLib/JNI ;
- orchestration testable du panic ;
- primitives Tink/Keystore et cycle de clés encore bloqués par les portes de production ;
- états persistants et reprise après interruption pour le panic ;
- contrat DELETE-only, provisioning et serveur local de référence SQLite ;
- une frontière de synchronisation documentée, sans activation du coffre distant de production ;
- CI Android + invariants de sécurité + tests du serveur de référence.

Le backend de production, la preuve d'effacement authentifiée, la récupération multi-appareil de bout en bout et les capacités sensibles restent volontairement verrouillés jusqu'aux audits et tests physiques prévus. L'app ne prétend donc pas encore fournir une sauvegarde de production ou un effacement d'urgence fiable.

## Architecture

```text
Signal backup folder ─┐
Generic folder ───────┼──> Connectors ──> Crypto port ──> Remote vault
Telegram / TDLib ─────┘                         │
                                               └──> Panic coordinator
```

Documentation :

- `docs/ARCHITECTURE.md`
- `docs/SECURITY-BOUNDARIES.md`
- `docs/TELEGRAM-TDLIB.md`
- `docs/GPT6-HANDOFF.md`
- `docs/ROADMAP.md`

## Telegram

Les identifiants applicatifs ne sont jamais committés :

```bash
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=...
./gradlew assembleDebug
```

Sans eux, Telegram reste désactivé et le reste de l’application fonctionne.

Le binaire TDLib/JNI n’est pas encore vendored : il sera ajouté dans un module séparé après choix d’une chaîne de build Android reproductible.

## Build

Pré-requis : JDK 17 et Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Le serveur DELETE de référence se teste séparément :

```bash
cd reference-server
python3 -m unittest discover -s tests -v
```

## Invariants

- aucune permission de stockage globale ;
- aucun Accessibility / notification listener / overlay ;
- aucun composant exporté sauf le launcher ;
- `usesCleartextTraffic=false` ;
- backup Android désactivé ;
- aucune donnée sensible dans les logs ;
- destruction locale des clés avant toute opération réseau du panic ;
- aucun secret/keystore/token dans Git ;
- `PRODUCTION_CRYPTO_READY=false` tant que l'audit et les tests physiques ne sont pas terminés ;
- `SMS_REMOTE_PANIC_READY=false` tant que le canal dédié n'est pas audité.

## Licence

Le dépôt est public mais ne contient pas encore de fichier `LICENSE`. Une licence open source doit être choisie avant toute publication stable ; tant qu'elle est absente, le code ne doit pas être présenté comme une release open source réutilisable.
