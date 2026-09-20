# Travaux réservés à GPT-6 / audit renforcé

## Passe du 20 septembre 2026

Revue de conception réalisée sur le bootstrap `6c679bbc6000822d76b590955a9c3f287fcd3081` :

- [Protocole crypto et récupération v1](CRYPTO-PROTOCOL-V1.md).
- [Machine à états panic et contrat DELETE-only](PANIC-PROTOCOL-V1.md).
- [Première revue du port TDLib](TDLIB-SECURITY-REVIEW.md).
- [Relais d'implémentation GPT-5.6](GPT56-HANDOFF-2026-09-20.md).
- Modèles et tests exécutables : `security_review/`.

Les sujets ci-dessous ont maintenant une décision de conception ; la validation des adaptateurs réels reste nécessaire. Ne pas confondre les tests de modèle avec une activation de production. Les issues #1/#2 restent ouvertes comme portes de validation.

GPT-6 doit être utilisé seulement là où une erreur de conception aurait un impact de sécurité réel.

## 1. Format cryptographique

Auditer format versionné, AEAD, DEK, enveloppes, récupération multi-appareil, nonces, métadonnées authentifiées, rollback/replay et restauration transactionnelle.

## 2. Cycle de vie des clés

Création, rotation, récupération, changement d’appareil, panic, crash pendant panic, désinstallation et restauration Android indésirable.

## 3. Delete-only credential

Capability DELETE uniquement, non transformable en lecture, rotation, replay sûr, idempotence et comportement hors ligne.

## 4. Machine à états du panic

Tester adversarialement arrêt du processus entre chaque étape, stockage plein, base corrompue, réseau intermittent, double déclenchement et reboot.

## 5. TDLib natif

Première analyse du build officiel Android/JNI, state machine d’authentification, base locale et stratégie d’export. Une fois le port validé, le Kotlin/Compose ordinaire revient à GPT-5.6.
