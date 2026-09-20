# Travaux réservés à GPT-6 / audit renforcé

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

## 6. Déclenchement distant temporaire par SMS

Voir `docs/RELAIS-GPT6-REMOTE-PANIC-SMS-2026-09-20.md`, issue #2 et issue #8.

GPT-6 doit auditer avant toute activation :
- le modèle de confiance du contact et de l'identité de l'expéditeur SMS ;
- le secret one-shot et sa vérification locale sans stockage en clair ;
- l'expiration stricte avec hard cap proposé de 72 h ;
- le comportement en cas de reboot, changement d'heure ou horloge non fiable ;
- les SMS multipart, dupliqués, rejoués ou simultanés ;
- la consommation atomique du droit distant avant de lancer le panic ;
- la séparation entre destruction locale immédiate et nettoyage distant/asynchrone ;
- la présence de `RECEIVE_SMS` uniquement dans un flavor/canal explicitement prévu.

GPT-6 ne doit pas consacrer du temps au Compose, à l'écran de réglage ou au wiring Android ordinaire tant que ces invariants ne sont pas tranchés.
