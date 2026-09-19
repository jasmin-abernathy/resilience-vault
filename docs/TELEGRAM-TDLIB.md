# Connecteur Telegram / TDLib

Décision : utiliser TDLib, l’interface officielle Telegram prévue pour construire des clients. Pas de scraping, Accessibility, notification listener ni lecture de la base privée du client Telegram Android.

## Configuration

TDLib nécessite notamment `api_id`, `api_hash` et un répertoire local inscriptible.

```bash
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=...
./gradlew assembleDebug
```

Sans ces valeurs, Telegram reste désactivé.

## Module futur

```text
telegram-tdlib/
├── native/
├── TdlibBridge.kt
├── AuthorizationStateMachine.kt
├── ArchiveReader.kt
└── SessionEraser.kt
```

## Règles

- ne jamais loguer numéro, code d’authentification, 2FA ou contenu des messages ;
- base TDLib dans le stockage privé ;
- session distincte du client Telegram officiel ;
- révocation locale idempotente ;
- export incrémental et borné ;
- aucune suppression des messages du compte dans le panic par défaut.

Avant de vendoriser TDLib : vérifier chaîne de build Android reproductible, ABI, provenance, taille, mises à jour, licence/notices, bindings et CI.
