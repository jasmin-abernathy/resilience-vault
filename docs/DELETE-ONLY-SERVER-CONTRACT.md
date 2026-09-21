# Contrat serveur DELETE-only — V1

Décision GPT-6 du 21 septembre 2026, issue #2. **Spécification uniquement : aucun backend
connecté ni déployé.** Les tests de modèle ne prouvent pas l'autorisation d'un service réel.

## Autorité et routes

Une capability opaque de 32 octets CSPRNG, liée à `(tenant, vaultId, génération)` immuables.
En base : hash du secret + scope DELETE_GENERATION + version/révocation, jamais secret brut.
TLS, en-tête Authorization seulement ; pas de token dans URL, logs proxy ou erreurs. Comparaison
constante, quotas anti-abus qui ne suppriment pas la validité d'un token légitime durablement.

| Opération avec capability DELETE | Résultat |
| --- | --- |
| DELETE génération exacte | Tombstone durable, statut pending ou complete |
| DELETE répété même génération | Même résultat idempotent, jamais nouvelle génération |
| GET/LIST/RESTORE/UPLOAD/HEAD de données | Refus, même si un middleware d'authentification reconnaît le token |
| Modification de compte, création coffre, rotation/échange de token | Refus |
| DELETE d'un autre tenant/coffre/génération | Refus sans révélation d'existence |
| Paramètre permettant de choisir une autre cible | Refus ; la cible vient aussi du scope enregistré |

Pour observer la fin sans élargir les droits, répéter DELETE : pas de route GET générale.
Les claims du client ne fixent jamais les scopes. La vérification d'une capability DELETE
n'est pas une authentification de compte. Middleware séparé et tests de matrice sur chaque route.

## Transaction et impossibilité de résurrection

Sous verrou transactionnel de la génération : vérifier autorité, écrire tombstone irréversible
ACTIVE -> TOMBSTONED et outbox de purge dans **la même transaction**, puis commit. Les autres
routes contrôlent ce tombstone avant toute lecture ou publication. TOMBSTONED -> ACTIVE interdit.
L'outbox permet de reprendre après crash sans dépendre d'un worker déjà en mémoire.

Une réservation d'upload obtenue AVANT le panic n'est pas un droit définitif : finalisation,
publication du manifeste et émission d'URLs de lecture revérifient la génération sous ce même
verrou. Ordres possibles : upload commit puis tombstone (la purge le couvre), ou tombstone puis
upload (publication refusée). Un nouveau coffre reçoit une nouvelle génération jamais réutilisée.
Tous les writers, y compris admin, réparation et restauration de sauvegarde serveur, respectent
le tombstone. Le journal des tombstones est conservé au moins tant qu'une restauration de backup
pourrait ramener une génération supprimée, et réappliqué avant remise en ligne d'un backup.

Stockage objet : upload dans staging non public ; pas d'URL de lecture longue durée contournant
le gate. Une URL pré-signée déjà émise peut continuer à fonctionner jusqu'à son expiration :
préférer accès via gateway contrôlée, ou documenter précisément cette fenêtre. Le worker purge
objets, versions, multipart uploads, réplicas gérés et staging, avec retries idempotents. Les
politiques de backups/rétention du fournisseur limitent l'effacement physique : les annoncer.

## Sémantique des réponses

- 202 TOMBSTONED_PENDING : accès applicatif révoqué mais purge incomplète ; **ne pas mettre
  remoteDeleteComplete=true**. Rejouer DELETE avec temporisation.
- 200/204 COMPLETE : génération ciblée purgée du stockage actif défini par le contrat,
  réponse authentifiée ; aucune promesse d'effacement physique des backups non encore expirés.
- 404 générique, redirection, corps inconnu ou réponse tronquée : pas une preuve de suppression.
- Réseau/429/5xx : retry exponentiel avec jitter et plafond, indépendant des clés détruites.
- 401/403 ou révocation : état BLOCKED_AUTH durable, arrêt des retries automatiques rapides,
  message de suppression distante non confirmée ; ne pas transformer en succès.

Le type actuel `RemoteDeleteResult.Deleted` est trop pauvre pour distinguer tombstone et purge.
Le prochain adaptateur doit produire Pending/Complete/Retryable/BlockedAuth. Les flags booléens
du registre panic ne stockent pas encore ces erreurs permanentes : migration de schéma requise
avant scheduler/activation. Un champ ajouté doit avoir des règles de migration fail-closed.

## Durée et stockage local

Token sans expiration temporelle automatique en V1, jusqu'à consommation logique/révocation
explicite : cela permet une longue panne réseau, au prix d'un pouvoir destructif durable en cas
vol du token. Présenter ce compromis ; ne pas le confondre avec les secrets SMS limités à 72 h.
Rotation/révocation possible par le propriétaire via un canal de compte séparé, jamais via DELETE.
Garder une preuve de tombstone authentifiable pour que les répétitions légitimes aboutissent.

Sur Android, chiffrer le token et sa cible sous une KEK DELETE distincte des clés de lecture,
dans noBackupFilesDir. Pas d'authentification utilisateur requise pour utiliser ce seul token
lors d'un retry post-panic, à tester verrouillage/reboot selon les API supportées. Le registre
panic doit garder une référence immuable à cet outbox avant destruction des autres clés.
Une erreur permanente ou un token perdu ne peut être réparé en conservant le token de compte.

## Tests obligatoires du futur serveur

1. Matrice DELETE vs chaque autre méthode, route et génération, y compris encodages ambigus.
2. Deux DELETE simultanés ; répétition après crash entre tombstone et worker.
3. Upload commencé avant DELETE, finalisé après ; publication manifeste concurrente.
4. Lecture et URL pré-signée déjà en vol ; object versioning et staging orphelin.
5. Restauration d'un backup serveur antérieur au tombstone.
6. Ancienne capability face à nouveau coffre de même utilisateur.
7. 401/403/404/429/500, délais et reprise hors ligne ; aucune dépendance aux clés détruites.
8. Échec de purge d'une version => pending, jamais COMPLETE prématuré.

Le modèle exécutable `tools/security_model/test_cloud_contract.py` couvre la séparation d'autorité,
le tombstone, la finalisation concurrente et les répétitions abstraites. Il ne remplace pas
ces tests sur transactions/base/stockage HTTP réels.
