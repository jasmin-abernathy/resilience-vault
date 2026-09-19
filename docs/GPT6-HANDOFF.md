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
