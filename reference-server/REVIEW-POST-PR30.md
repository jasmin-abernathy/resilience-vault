# Audit de la PR #30 — serveur DELETE de référence

Audit appliqué au head exact `58e0efc13f55a862a88592dbfbd30ebcac56e286`.

## Constats conformes

- serveur loopback uniquement ;
- authenticators production par défaut `DenyAll` ;
- séparation provisioning/status/revoke/DELETE ;
- tenant issu du principal, pas du corps seul ;
- JSON borné et strict ;
- provisioning idempotent et conflit même operationId/digest différent ;
- tombstone et outbox dans la même transaction SQLite ;
- READ, staging, finalisation et restauration administrative relisent l'autorité tombstone ;
- restore de base primaire ancienne réconcilie le ledger avant de rouvrir l'état ;
- `opaque-v1` reste une fixture, pas un protocole crypto.

## Défaut réel trouvé

`run_purge_once()` ne comptait que `active_objects` dans la décision COMPLETE. Les lignes
`staged_uploads` préexistantes au tombstone n'étaient ni supprimées par le worker ni comptées dans
`_effective_delete_state()` / la réconciliation après restore.

Conséquence : un DELETE pouvait être déclaré `COMPLETE` alors qu'un objet restait encore dans le
staging de référence.

Correction du lot suivant :

- supprimer le staging de la génération dans le worker de purge ;
- compter `active_objects + staged_uploads` pour décider PENDING/COMPLETE ;
- forcer PENDING après restore si l'un des deux périmètres réapparaît ;
- ajouter des tests crash/restore spécifiques au staging.

## Limites toujours assumées

- le ledger SQLite séparé n'est pas une preuve d'isolation backup production ;
- aucun verifier DELETE réel ;
- aucune signature/attestation serveur ;
- aucun anti-rollback distribué ;
- aucun IdP, serviceId, host, DB ou object store production ;
- aucune CI GitHub sur ce lot tant que le quota Actions est indisponible.


---

# Matrice de fault injection — serveur de référence

Tous les points sont déterministes via un callback `fault_injector(point)` optionnel. Il n'est pas
utilisé en production : il sert uniquement à prouver les frontières transactionnelles du modèle.

| Point | Effet attendu après restart |
| --- | --- |
| `provisioning.before_commit` | aucune ligne durable ; retry crée l'opération |
| `provisioning.after_commit` | opération durable ; retry exact idempotent |
| `delete.after_tombstone` | rollback tombstone + primary + outbox |
| `delete.after_primary_mark` | rollback tombstone + primary + outbox |
| `delete.before_commit` | rollback complet |
| `delete.after_commit` | tombstone/outbox durables ; même `deleteOperationId` au replay |
| `purge.before_delete` | PENDING inchangé |
| `purge.after_delete_before_complete` | suppressions rollback ; PENDING |
| `purge.after_state_before_commit` | COMPLETE rollback ; PENDING |
| `purge.after_commit` | COMPLETE durable même si réponse perdue |
| `reconcile.before_commit` | aucune réconciliation partielle durable |

Scénarios complémentaires :

- ledger corrompu : démarrage bloqué ;
- double reprise : idempotente ;
- backup primaire ancien réintroduisant active/staging : PENDING puis re-purge ;
- DELETE concurrent à revoke : soit DELETE est bloqué, soit le tombstone gagne ; jamais ACTIVE après tombstone ;
- DELETE concurrent à finalisation d'upload : couvert par les tests #30 existants et réaudité.


---

# Threat model — backend DELETE de référence

Ce document décrit le **modèle de référence**, pas une certification production.

## Actifs

- capability DELETE client-side ;
- verifier serveur ;
- principal de provisioning ;
- binding tenant/service/vault/generation ;
- journal d'idempotence `operationId + requestDigest` ;
- tombstone et sa révision monotone future ;
- outbox de purge ;
- objets actifs, staging, versions/réplicas futurs ;
- éventuelle clé d'attestation future.

## Autorités

- authority tombstone : décide qu'une génération ne peut plus redevenir ACTIVE ;
- principal provisioning : peut créer/status/revoke le provisioning, pas DELETE ;
- capability DELETE : peut demander DELETE du tuple exact, rien d'autre ;
- stockage actif : subordonné au tombstone ;
- future ancre serveur anti-rollback : non implémentée.

## Menaces et réponses

| Menace | Invariant | Couverture actuelle | Responsabilité manquante |
| --- | --- | --- | --- |
| fuite capability DELETE | scope exact, aucun droit compte/provision | matrice auth #30 | verifier réel + rotation/révocation |
| vol principal provisioning | aucun DELETE via ce principal | matrice auth #30 | IdP, MFA/enrollment, recovery |
| replay provisioning exact | idempotent | tests #30 | rétention réelle operationId |
| replay avec digest différent | conflit permanent | tests #30 | politique après expiration |
| downgrade verifierVersion | version doit être explicitement admise | note crypto | registre versions production |
| rollback base active | tombstone externe domine restore | tests restore | isolation réelle ledger/backups |
| rollback ledger | service doit bloquer, jamais reconstruire ACTIVE | test corruption ; anti-rollback absent | ancre externe/serveur |
| admin restaure objet tombstoné | toute restauration consulte tombstone | test #30 | IAM admin + procédures |
| staging oublié à la purge | COMPLETE interdit tant que staging subsiste | **corrigé dans ce lot** | mapping object store réel |
| réplication retardée | COMPLETE exige scope purgé | schéma preuve seulement | inventaire replicas réel |
| réponse COMPLETE stale | révision/time minimum | tests preuve candidate | source de révision monotone |
| faux JSON COMPLETE | structure != authenticité | tests schéma | signature ou reconsultation authentifiée |
| perte clé d'attestation future | aucune fausse preuve locale | pas de clé actuelle | rotation/recovery/HSM |
| appareil compromis, serveur sain | serveur ancre génération/tombstone | conception seulement | protocole anti-rollback |
| appareil + serveur rollbackés | aucune garantie absolue locale | explicitement non résolu | autorité externe supplémentaire/ops |

## Périmètres de backup

Le modèle possède deux fichiers SQLite (primaire + ledger) uniquement pour tester la priorité du
tombstone. En production il faut nommer quels systèmes sont dans le même blast radius de restore,
quels backups peuvent réintroduire active/staging/versions, et quelle autorité monotone n'est pas
restaurée avec eux.

## Règle de sûreté

Si authority tombstone, ledger, attestation ou ancre monotone sont indisponibles ou contradictoires :
**bloquer le service**. Ne jamais reconstruire ACTIVE à partir d'un vieux backup.


---

# Preuve DELETE COMPLETE — schéma candidat non authentifié

Ce schéma est un **contrat de structure et de binding uniquement**. Il ne prouve pas que le serveur a
émis les octets. Une preuve durable hors ligne exigera plus tard soit une signature serveur revue et
rotatable, soit une nouvelle consultation authentifiée d'une autorité durable.

## Champs V1

```text
schemaVersion
serviceId
tenantId
vaultId
generation
deleteOperationId
requestDigest
state
tombstoneRevision
responseId
verifiedAtEpochSeconds
purgeScope
backupPolicyId
```

`state` vaut `PENDING` ou `COMPLETE`.

`purgeScope` est une liste triée, sans doublon, parmi :

```text
active
staging
versions
replicas
```

Le client consommateur doit fournir une expectation contenant le tuple exact, le
`deleteOperationId`, le `requestDigest`, une révision tombstone minimale, un instant de vérification
minimal et le périmètre de purge obligatoire.

## Rejets obligatoires

- HTTP 204 nu ;
- HTTP 200 sans corps ;
- état PENDING présenté comme COMPLETE ;
- autre tenant/service/vault/generation ;
- autre deleteOperationId/requestDigest ;
- révision tombstone inférieure à l'ancre attendue ;
- réponse trop ancienne ;
- purgeScope incomplet ;
- champ manquant, inconnu ou dupliqué.

## Non-objectifs

- aucune signature fictive ;
- aucune clé publique fictive ;
- aucun wiring Android ;
- aucune promesse de permanence si l'autorité tombstone peut elle-même être rollbackée.


---

# Vérificateur DELETE — candidat pour revue crypto uniquement

Le serveur de référence stocke actuellement un `verifierVersion` et un `verifierHex` opaques. Cette
note vérifie qu'une construction candidate peut **tenir dans ce contrat existant** ; elle ne
l'approuve pas.

## Construction candidate à auditer

Secret : exactement 32 octets uniformes générés côté client.

Domaine exact :

```text
ASCII("RV-DELETE-CAPABILITY-VERIFIER-CANDIDATE-V1\\0")
```

Préimage candidate :

```text
domain || secret32
```

Vérificateur :

```text
lowerhex(SHA-256(preimage))
```

Version candidate descriptive :

```text
sha256-domain-secret-candidate-v1
```

Cette version n'est **pas** autorisée en production. `opaque-v1` reste également interdit en
production.

## Vecteurs de test

| secret32 hex | verifier hex |
| --- | --- |
| `0000000000000000000000000000000000000000000000000000000000000000` | `ac6f12e35b580bb9ea60fe826f1bde4d83d1b486690a8ad064e59529dec8ee86` |
| `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` | `6369cd897518c9a2c6b917ebea4c7f8453c67dd3fd86ffacd59a10198d150d1f` |
| `ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff` | `c78ae2eefd5448a53b1fa87f79ba3edcacf6149f78692ac9ba99279d8b2a37c6` |

## Questions de revue obligatoires

- comparaison constant-time du verifier ;
- binding immuable du verifier au tuple `(tenant, service, vault, generation, version)` ;
- impossibilité de réutiliser une capability entre générations ;
- stratégie de version/downgrade ;
- révocation après provisioning ;
- destruction du secret client après encapsulation selon le parcours prévu ;
- comportement en cas de fuite de capability ;
- reproduction indépendante des vecteurs en Kotlin/JVM avant adoption ;
- revue crypto indépendante avant tout authenticator réel.


---

# Durcissement post-PR31 — borne du proof et erreurs stockage

Le parseur public `DeleteCompletionProofCandidate.parse_json(raw)` applique désormais la même
borne locale que l'API HTTP candidate :

```text
MAX_PROOF_BODY_BYTES = 8 KiB
```

Avant tout décodage :

- le type doit être exactement `bytes` ;
- le corps doit être non vide ;
- la taille doit être <= 8 KiB.

Un JSON valide suivi uniquement de whitespace reste donc refusé si sa taille dépasse la borne. Cette
borne protège le parseur ; elle ne transforme pas le proof candidate en attestation authentifiée.

## Frontière HTTP / attestation

Le corps renvoyé par `http_api.py` reste une réponse de session locale candidate. Même si ses champs
sont compatibles avec une future preuve, **il ne doit jamais être présenté comme
`DeleteCompletionProofCandidate` attesté simplement parce qu'il arrive sur HTTP 200**.

La validation de `proof.py` établit seulement :

- format ;
- binding ;
- révision/fraîcheur minimale ;
- scope annoncé.

Elle n'établit pas l'identité de l'émetteur. Une production réelle exigera une reconsultation
authentifiée d'une autorité durable ou une attestation cryptographique revue avec gestion de clés.

## Erreurs SQLite

Une `sqlite3.DatabaseError` levée pendant une requête HTTP est maintenant convertie en refus
générique :

```text
HTTP 503
{"version":1,"error":"STORAGE_UNAVAILABLE"}
```

Aucun détail SQLite n'est renvoyé au client. Une corruption au démarrage reste bloquante : le
serveur de référence ne reconstruit jamais ACTIVE à partir d'un état incertain.
