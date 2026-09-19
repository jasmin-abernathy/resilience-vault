# AGENTS.md — Resilience Vault

Appliquer également :

- `jasmin-abernathy/repo-factory/COMPATIBILITY-AND-DEPRECATION-PLAYBOOK.md`
- `jasmin-abernathy/android-safe-install-playbook`

La règle la plus prudente prévaut.

1. Pas de permission Android « au cas où ».
2. Pas d’accès large au stockage : SAF uniquement.
3. Aucun AccessibilityService, NotificationListenerService, overlay ou device admin pour contourner Android.
4. Aucun secret, token, contenu de message ou identifiant sensible dans les logs.
5. Aucun protocole cryptographique maison. Le format de clé/récupération/panic doit être audité avant activation.
6. Panic idempotent et fail-closed : destruction des clés locales avant réseau et UI.
7. Le connecteur Telegram doit rester optionnel et ne jamais casser le build lorsqu’il n’est pas configuré.
8. Ne jamais committer TELEGRAM_API_HASH, clé de chiffrement, token cloud ou keystore.
9. Composants Android non exportés par défaut ; launcher seule exception actuelle.
10. `allowBackup=false` et `usesCleartextTraffic=false` sont des invariants.
11. Toute suppression réelle reçoit des tests d’interruption, répétition et état partiel.
12. CI : travailler en lots puis valider le SHA final.
13. Ne pas annoncer sauvegarde/restauration/panic comme sûrs avant les audits listés dans `docs/GPT6-HANDOFF.md`.
