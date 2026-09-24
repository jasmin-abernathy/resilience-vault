# Roadmap

## A — bootstrap
- [x] dépôt privé
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
- [ ] tests interruption/reprise

## C — cloud zéro connaissance
- [ ] stockage objet
- [ ] upload idempotent
- [ ] delete-only credential réel
- [ ] restauration nouvel appareil
- [ ] rotation clés

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
- [ ] tests replay / multipart / doublon / concurrence entre contacts / reboot / horloge
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
- [x] spécification crypto V1 et cycle de clés (#1), sans implémentation AEAD active
- [x] contrat serveur DELETE-only et modèle de concurrence (#2), sans backend actif
- [x] 32 tests Python de spécification
- [x] leases d'accès + drain avant destruction de clé
- [ ] provisioning explicite du coffre et journal de crash
- [x] Tink 1.23.0 épinglé + tests JVM de la primitive streaming (non production)
- [ ] crash tests Android/Keystore réels et intégration de production Tink
- [ ] tests d'autorisation du backend et purge réelle

Les cases de conception et les tests JVM ne remplacent pas les portes d'activation des sections
B/C/E. `PRODUCTION_CRYPTO_READY` reste faux jusqu'à la revue GPT-6 du cycle de clés réel.
