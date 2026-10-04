# Roadmap

## A — bootstrap
- [x] dépôt créé
- [x] règles repo-factory / safe install
- [x] Kotlin/Compose
- [x] SAF Signal
- [x] SAF générique
- [x] port TDLib
- [x] orchestration panic testable
- [ ] planifier la synchro périodique quand le pipeline chiffré sera réellement activé
- [x] CI
- [ ] SHA final vert

## B — pipeline local
- [ ] inventaire incrémental
- [ ] staging privé
- [ ] crypto auditée
- [ ] restauration locale
- [ ] tests interruption/reprise sur appareil réel

## C — cloud zéro connaissance
- [ ] stockage objet de production
- [ ] upload idempotent de production
- [ ] delete-only credential réel
- [ ] restauration nouvel appareil de bout en bout
- [ ] rotation clés validée en production

## D — Telegram
- [ ] build TDLib reproductible
- [ ] JNI
- [ ] auth state machine
- [ ] archive incrémentale
- [ ] médias
- [ ] révocation session

## E — contact de confiance / remote panic
- [ ] audit GPT-6 du modèle de déclenchement SMS (#2)
- [x] mode armé désactivé par défaut
- [x] UI et modèle 1 à 5 contacts
- [x] fenêtre 1 h / 6 h / 12 h / 24 h / 48 h / 72 h
- [ ] secret one-shot distinct par contact, rotation à chaque nouvel armement
- [x] fenêtre commune + expiration/reboot/horloge fail-closed
- [ ] tout déclenchement valide invalide immédiatement les secrets des 1 à 5 contacts
- [ ] tests replay / multipart / doublon / concurrence entre contacts / reboot / horloge sur Android réel
- [ ] flavor/canal SMS séparé du build sans permission sensible (#8)

## F — publication
- [ ] audit
- [ ] nom final
- [ ] licence
- [ ] privacy policy
- [ ] build reproductible
- [ ] canal de distribution

## Revue du 21 septembre — distinction conception / activation
- [x] revue de conformité Kotlin et correctifs de frontières de sécurité
- [x] spécification crypto V1 et cycle de clés (#1), sans activation production
- [x] contrat serveur DELETE-only et modèle de concurrence (#2), sans backend actif
- [x] modèles et tests Python de spécification
- [x] leases d'accès + drain avant destruction de clé
- [x] provisioning explicite du coffre et journal de crash implémentés dans la pile de sécurité
- [x] Tink 1.23.0 épinglé + tests JVM de la primitive streaming (non production)
- [ ] crash tests Android/Keystore sur appareil réel et validation de production Tink
- [x] backend DELETE de référence avec tests d'autorisation, tombstone, purge et restauration
- [ ] backend de production + preuve d'effacement authentifiée

## Consolidation du 4 octobre 2026

La tête de consolidation part de `a45fd3c8af37d360758b6131753d076067e46d21`, soit la pile de sécurité issue des PR #9 à #32.

- [x] conserver `PRODUCTION_CRYPTO_READY=false`
- [x] conserver `SMS_REMOTE_PANIC_READY=false`
- [x] ajouter `reference-server/**` aux chemins surveillés par la CI
- [x] exécuter les tests du serveur DELETE de référence dans le job `security-contracts`
- [ ] obtenir une CI complète verte sur le SHA de consolidation
- [ ] revue GPT-6 du cycle de clés, du provisioning et du DELETE réellement implémentés avant activation
- [ ] tests physiques Android avant toute affirmation de sûreté de production

Les cases de conception, les tests JVM et le serveur de référence ne remplacent pas les portes
d'activation des sections B/C/E. `PRODUCTION_CRYPTO_READY` reste faux jusqu'à la revue de sécurité
et aux validations sur appareil réel.
