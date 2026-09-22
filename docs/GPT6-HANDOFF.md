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

## 6. Déclenchement distant temporaire par SMS

Voir `docs/RELAIS-GPT6-REMOTE-PANIC-SMS-2026-09-20.md`, issue #2 et issue #8.

GPT-6 doit auditer avant toute activation :
- le modèle de confiance de **1 à 5 contacts** et de l'identité de chaque expéditeur SMS ;
- un secret one-shot distinct par contact et sa vérification locale sans stockage en clair ;
- l'expiration stricte avec hard cap proposé de 72 h ;
- le comportement en cas de reboot, changement d'heure ou horloge non fiable ;
- les SMS multipart, dupliqués, rejoués ou simultanés ;
- la consommation atomique de **tout l'armement** dès qu'un des 1 à 5 contacts déclenche valablement le panic ;
- la séparation entre destruction locale immédiate et nettoyage distant/asynchrone ;
- la présence de `RECEIVE_SMS` uniquement dans un flavor/canal explicitement prévu.

GPT-6 ne doit pas consacrer du temps au Compose, à l'écran de réglage ou au wiring Android ordinaire tant que ces invariants ne sont pas tranchés.

## Décision et relais du 20 septembre 2026

La conception du panic SMS est maintenant documentée dans [REMOTE-PANIC-SECURITY-DECISION.md](REMOTE-PANIC-SECURITY-DECISION.md), avec un modèle exécutable et 25 tests adversariaux. Lire le [relais GPT-5.6](RELAIS-GPT56-REMOTE-PANIC-2026-09-20.md) pour l'implémentation suivante. Ce travail ne valide pas la crypto réelle, le stockage Android, les PDU ou le backend : #2 et #8 restent ouvertes et le receiver reste absent.

## Relais actuel — après revue du 21 septembre 2026

Le relais post-implémentation GPT-6 a été traité. Lire désormais
[RELAIS-GPT56-APRES-REVUE-2026-09-21.md](RELAIS-GPT56-APRES-REVUE-2026-09-21.md),
[la revue de code](GPT6-IMPLEMENTATION-REVIEW-2026-09-21.md), le format crypto et le contrat
DELETE-only liés depuis ARCHITECTURE.md. La prochaine revue GPT-6 devra porter sur les leases,
le provisioning et le cycle de clés réellement implémentés. Les intégrations restent non activables.
