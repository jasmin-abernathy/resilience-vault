# Relais GPT-5.6 après revue de sécurité du 21 septembre 2026

Dépôt `jasmin-abernathy/resilience-vault`, branche `main`.
Base auditée : `e130b96efe4db7444a83c7c14892db663bd40d12`.
**SHA de reprise : le main incluant le correctif SDK décrit ci-dessous**, consigné dans
les commentaires des issues #1/#2/#8 avec le run CI final. Re-fetch main et les checks avant travail.
Ne pas repartir du SHA de base : il ne contient pas les correctifs de cette revue.

Lire repo-factory/AGENTS, playbook compatibilité, registre d'erreurs Resilience Vault et
android-safe-install-playbook. Conserver un lot cohérent par push. Aucun APK sans demande.

## Audité et corrigé dans le code

Lire [la revue détaillée](GPT6-IMPLEMENTATION-REVIEW-2026-09-21.md).
Admission : observation horloge/capacité sous transaction ; ancien snapshot de SMS supprimé.
Stockage : instance unique par chemin, sync explicite + relecture, latch d'erreur, pas de reset
si fichier manquant, transitions irréversibles, lecture bornée. Reprise : exceptions/timeout
isolés, cancellation conservée. État invalide observé = invalidation persistée ; toString sensibles masqués.

Tests Python : 32 réussis localement. Tests Kotlin étendus, à vérifier sur le run du SHA final
indiqué dans les issues ; ce document n'affirme pas un résultat CI avant son exécution.

## Spécifié, encore à implémenter

1. [Crypto V1](CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md) : Tink streaming pour objets, AEAD enveloppes,
   KEK Android Keystore, keysets par époque/objet, kit externe de récupération, rotation, staging
   chiffré et restauration. VaultBinding + tests ne sont que le contexte AAD, pas du chiffrement.
2. [DELETE-only](DELETE-ONLY-SERVER-CONTRACT.md) : capability séparée, tombstone + outbox atomiques,
   contrôle à finalisation upload, aucune résurrection, pending distinct de complete, blocked-auth.

## Prochain lot recommandé, sans receiver

- Construire d'abord le **gestionnaire de leases d'accès** et le provisioning explicite nouveau
  coffre ; tests panic vs lecture/export et crash de création. Pas de réparation par recréation IDLE.
- Ajouter les tests Android du store réel (factory multiple, kill entre étapes, stockage plein,
  restauration AtomicFile) et AndroidPanicClock (provider inconnu, reboot). Aucun succès revendiqué
  sur tests fake uniquement. VerifiedPanicStateStoreTest est un contrat, pas un test du filesystem.
- Intégrer une version Tink épinglée en adaptateur **inactif**, fixtures aller-retour et altérations,
  tests de fin de flux et tailles, protection des keysets. Garder PRODUCTION_CRYPTO_READY=false.
- Implémenter le cycle Keystore/provisioning/rotation avec journal. Aucun catch qui régénère une clé
  après erreur de déchiffrement. Export de R explicitement confirmé et R absent du stockage app.
- Migrer les statuts postérieurs persistants avant worker réseau ; aucun polling infini sur 401/403.
- Préparer ensuite le backend selon le contrat et ses tests de routes/concurrence/rétention.

Les écrans de contacts sont préservés. L'unique changement UI appelle refreshRemoteState au
chargement/rafraîchissement et après actions. Le service sans adaptateur réel reste fermé et
invalide un éventuel armement devenu inexécutable. Ne pas changer SMS_REMOTE_PANIC_READY pour
rendre un bouton actif : cela ne crée ni receiver ni sécurité de transport.

## Non activable

Crypto de production, panic destructif, SMS receiver/flavor, TDLib et DELETE réseau restent
inactifs. #1/#2/#8 ne sont pas fermées : la conception a avancé, mais les intégrations et tests
appareil/serveur restent des portes obligatoires. TDLib/JNI (#3) n'a pas été modifié dans ce lot.

Revenir à GPT-6 pour revue du cycle de clés et des leases effectivement implémentés, ou si
l'intégration impose un changement de format/garantie. Ne pas réinventer de chiffrement.

## Correctif SDK pendant validation

Le premier run du lot `9934f5bd6c34f4b0e2772239174e779ff008bbb8` a échoué : O_DIRECTORY
n'est pas une constante du SDK Android public. Le correctif utilise O_RDONLY puis
S_ISDIR(fstat(fd).st_mode) avant fsync. Ne pas utiliser le run initial comme validation.

## Lot leases d'accès — GPT-5.6

Implémenté après la revue GPT-6, sans crypto ni receiver :
- `VaultAccessLeaseManager` process-local unique par instance de `PanicStateStore` ;
- `withLease` pour les futures voies READ/EXPORT/SYNC/RESTORE ;
- fermeture irréversible des nouvelles acquisitions pendant le panic ;
- drain des leases en cours avant destruction de la capacité de lecture ;
- cancellation/exception d'une opération libère toujours son lease ;
- le coordinateur ne tente plus la destruction des clés si le drain d'accès échoue ou expire ;
- tests de concurrence, fermeture, store manquant/panic et cancellation.

Le provisioning réel n'est PAS implémenté ici : il doit être lié au futur inventaire de clés/aliases
et à la génération du coffre définis par `CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md`.
Revue GPT-6 requise avant de considérer les leases comme une garantie de production.
