# Resilience Vault

Coffre Android en phase de bootstrap de sécurité. Le code source est actuellement visible
publiquement, mais **aucune version n'est encore déclarée prête pour un usage de production** et la
licence de redistribution reste à choisir.

## Objectif

Resilience Vault doit permettre de sauvegarder régulièrement des données choisies explicitement par
l’utilisateur, puis de les rendre rapidement inaccessibles sur l’appareil en situation d’urgence.

Sources prévues :

- **Signal** : dossier de sauvegarde locale choisi via le Storage Access Framework ;
- **Telegram** : connecteur officiel **TDLib** isolé du reste de l’application ;
- **dossiers génériques** : dossiers explicitement choisis, sans permission de stockage globale.

## État du bootstrap

La pile de travail contient notamment :

- projet Android Kotlin/Compose ;
- sélection persistante de dossiers SAF ;
- connecteurs Signal et dossier générique ;
- port Telegram préparé, avec TDLib/JNI de production encore à finaliser ;
- orchestration testable du panic et machine à états fail-closed ;
- intégration Tink/Keystore/biométrie et cycle de clés, toujours derrière la porte
  `PRODUCTION_CRYPTO_READY=false` ;
- primitive delete-only, provisioning distant et registre d'autorité du coffre ;
- serveur DELETE de référence Python/SQLite, uniquement local, pour valider les transactions,
  tombstones, purges et scénarios de restauration ;
- CI GitHub Actions couvrant les invariants de sécurité, les modèles exécutables, le serveur de
  référence et les tâches Gradle de validation.

Le backend de production, l'attestation d'effacement, la récupération multi-appareil de bout en
bout et l'activation réelle du panic distant restent volontairement verrouillés jusqu'aux audits et
tests matériels prévus. L'app ne prétend donc pas encore fournir une sauvegarde de production ou un
effacement d'urgence fiable.

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

Le binaire TDLib/JNI n’est pas encore vendored : il sera ajouté dans un module séparé après choix
et validation d’une chaîne de build Android reproductible.

## Build

Pré-requis : JDK 17 et Android SDK 36.

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug lintDebug
```

Le serveur DELETE de référence utilise uniquement la bibliothèque standard Python :

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
- `PRODUCTION_CRYPTO_READY=false` tant que les audits et tests matériels ne sont pas terminés ;
- `SMS_REMOTE_PANIC_READY=false` tant que son canal dédié n'est pas validé.

## Licence

Le dépôt est publiquement visible, mais aucune licence de redistribution n'est encore définie.
Le choix de licence fait partie des décisions à prendre avant une publication stable.
