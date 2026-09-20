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
- [ ] delete-only credential
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
- [ ] mode armé désactivé par défaut
- [ ] de 1 à 5 contacts autorisés
- [ ] fenêtre 1 h / 6 h / 12 h / 24 h / 48 h / 72 h
- [ ] secret one-shot distinct par contact, rotation à chaque nouvel armement
- [ ] fenêtre d'armement commune à tous les contacts, expiration fail-closed
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
