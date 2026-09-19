# Resilience Vault

Coffre Android privé en phase de bootstrap, destiné à devenir open source après stabilisation du modèle de sécurité.

## Objectif

Resilience Vault doit permettre de sauvegarder régulièrement des données choisies explicitement par l’utilisateur, puis de les rendre rapidement inaccessibles sur l’appareil en situation d’urgence.

Sources prévues :

- **Signal** : dossier de sauvegarde locale choisi via le Storage Access Framework ;
- **Telegram** : connecteur officiel **TDLib** isolé du reste de l’application ;
- **dossiers génériques** : dossiers explicitement choisis, sans permission de stockage globale.

## État du bootstrap

Cette base contient :

- projet Android Kotlin/Compose ;
- sélection persistante de dossiers SAF ;
- connecteurs Signal et dossier générique ;
- port Telegram TDLib/JNI ;
- orchestration testable du panic ;
- une frontière de synchronisation documentée, sans tâche réseau active tant que le coffre n’est pas prêt ;
- CI via `app-build-factory`.

Le format cryptographique distant, la récupération multi-appareil et le bouton d’urgence réel sont volontairement verrouillés jusqu’à audit. L’app ne prétend donc pas encore fournir une sauvegarde de production ou un effacement d’urgence fiable.

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

## Invariants

- aucune permission de stockage globale ;
- aucun Accessibility / notification listener / overlay ;
- aucun composant exporté sauf le launcher ;
- `usesCleartextTraffic=false` ;
- backup Android désactivé ;
- aucune donnée sensible dans les logs ;
- destruction locale des clés avant toute opération réseau du panic ;
- aucun secret/keystore/token dans Git.

## Licence

Le dépôt reste privé pendant le bootstrap. La licence de publication doit être choisie avant ouverture du dépôt.
