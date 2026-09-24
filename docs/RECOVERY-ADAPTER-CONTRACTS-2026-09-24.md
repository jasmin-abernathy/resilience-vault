# Resilience Vault — contrats d’adaptateurs de récupération

Statut : préparation GPT-5.6. **Aucun adaptateur de production, aucune archive réelle et aucun
kit R réel ne sont branchés par ce lot.**

## But

Les interfaces de `RecoveryEvidencePorts.kt` décrivent la frontière entre le futur code
cryptographique E/D/R et `RecoveryPreflightPolicy`. Elles évitent qu’un booléen UI, un nom de
fichier ou une valeur non authentifiée soit présenté comme une preuve de récupérabilité.

## Entrées minimales authentifiées

Chaque preuve doit être liée à un `RecoveryArtifactBinding` contenant exactement :

- `vaultId` authentifié ;
- génération authentifiée ;
- head de manifeste authentifié.

Le kit externe doit en plus être entièrement lu et authentifié. L’archive doit être complète,
contenir les ciphertexts attendus, être entièrement lue et authentifiée, et sa relation au scope
DELETE doit être connue. L’essai de restauration doit avoir restauré intégralement les objets
attendus pour ce même binding. Le head actif doit provenir d’une source authentifiée du coffre.

## Règle de fermeture des flux

Les ports sont volontairement **post-vérification** : ils ne retournent jamais `InputStream`.
Une implémentation future doit : ouvrir → lire jusqu’à EOF → authentifier → fermer le flux →
seulement ensuite retourner l’objet `Verified…`. Une exception, un EOF prématuré ou une fermeture
incertaine ne produit aucune preuve.

## Ce que ce contrat ne prouve pas

- il ne choisit pas les API Tink pour E/D/R ;
- il ne définit pas le format du kit R ;
- il ne prouve pas la durabilité du stockage Android ;
- il ne branche aucun backend ;
- il ne prouve pas qu’une restauration complète fonctionne sur un autre appareil.

Ces décisions restent réservées à GPT-6 et aux tests appareil.
