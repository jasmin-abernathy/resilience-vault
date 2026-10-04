# Roadmap

## A — bootstrap
- [x] dépôt GitHub initialisé
- [x] règles repo-factory / safe install
- [x] Kotlin/Compose
- [x] SAF Signal
- [x] SAF générique
- [x] port TDLib
- [x] orchestration panic testable
- [ ] planifier la synchro périodique quand le pipeline chiffré sera réellement activé
- [x] CI GitHub Actions
- [x] tests du serveur de référence inclus dans la CI
- [ ] SHA de consolidation final entièrement vert

## B — pipeline local
- [ ] inventaire incrémental
- [ ] staging privé de production
- [ ] crypto auditée pour activation de production
- [ ] restauration locale de production
- [x] modèles/tests JVM d'interruption, reprise et invariants
- [x] journaux atomiques et reprise après états intermédiaires modélisés
- [ ] validation sur appareils Android réels : Keystore, biométrie, reboot, stockage plein et crash

## C — cloud zéro connaissance
- [ ] stockage objet de production
- [ ] upload idempotent de production
- [x] contrat de provisioning distant versionné
- [x] serveur DELETE de référence loopback/SQLite
- [x] tombstone + purge + réconciliation après restauration modélisés et testés
- [x] primitive delete-only et coordination côté Android, non activables en production
- [ ] authentification/provisioning de production
- [ ] verifier DELETE de production
- [ ] attestation/preuve d'effacement authentifiée
- [ ] garanties anti-rollback distribuées documentées et validées
- [ ] restauration nouvel appareil de bout en bout
- [x] rotation de clés implémentée côté modèle/runtime Android
- [ ] validation de production de la rotation et de la récupération multi-appareil

## D — Telegram
- [ ] build TDLib reproductible
- [ ] JNI de production
- [ ] auth state machine
- [ ] archive incrémentale
- [ ] médias
- [ ] révocation session

## E — contact de confiance / remote panic
- [x] modèle de déclenchement SMS documenté et audité au niveau conception
- [x] mode armé désactivé par défaut
- [x] UI et modèle 1 à 5 contacts
- [x] fenêtre 1 h / 6 h / 12 h / 24 h / 48 h / 72 h
- [ ] secret one-shot distinct par contact, rotation à chaque nouvel armement, validé sur Android réel
- [x] fenêtre commune + expiration/reboot/horloge fail-closed au niveau modèle
- [ ] tout déclenchement valide invalide immédiatement les secrets des 1 à 5 contacts dans l'implémentation finale
- [ ] tests Android réels replay / multipart / doublon / concurrence / reboot / horloge
- [ ] flavor/canal SMS séparé du build par défaut et revue politique Android/Play
- [ ] nouvelle revue GPT-6 avant toute activation de `SMS_REMOTE_PANIC_READY`

## F — publication
- [ ] audit sécurité final
- [ ] nom final
- [ ] licence
- [ ] privacy policy
- [ ] build reproductible
- [ ] canal de distribution

## Consolidation du 4 octobre 2026

La tête de travail consolidée dérive du lot post-PR31 et reste volontairement non activable en
production. Les anciens lots empilés décrivent l'historique de conception ; la consolidation vers
`main` doit être jugée sur un **SHA final unique**.

État acquis dans la pile de travail :

- [x] Tink 1.23.0 épinglé et intégration Android du cycle de clés codée ;
- [x] Keystore/biométrie, rotation, journaux de provisioning et ActiveVaultAuthority codés ;
- [x] machine à états panic V2 et frontières fail-closed renforcées ;
- [x] modèle delete-only, provisioning distant et serveur de référence transactionnel ;
- [x] tests de fault injection et de cohérence DELETE côté serveur de référence ;
- [x] borne du corps de preuve à 8 KiB et erreurs SQLite converties en 503 générique ;
- [x] vecteur de digest provisioning reproduit indépendamment en Kotlin ;
- [ ] CI complète sur le SHA final de consolidation ;
- [ ] tests physiques Android et revue GPT-6 des portes d'activation ;
- [ ] backend, identité, hébergement, stockage et attestation de production choisis.

Les cases de conception et les tests JVM/Python ne remplacent pas les portes d'activation.
`PRODUCTION_CRYPTO_READY=false` et `SMS_REMOTE_PANIC_READY=false` doivent rester inchangés tant
que leurs validations dédiées ne sont pas terminées.
