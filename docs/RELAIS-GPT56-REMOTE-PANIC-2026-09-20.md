# Relais GPT-5.6 — Resilience Vault

Lire repo-factory et android-safe-install-playbook, puis AGENTS.md du dépôt.
Repartir du main actuel ; ne pas réutiliser le SHA de l'ancien relais comme tête.

## Livré par GPT-6

- `docs/REMOTE-PANIC-SECURITY-DECISION.md` : menaces, expiration, secrets, transactions,
  deux phases du panic, limites Android, contrat serveur DELETE-only.
- `tools/security_model/model.py` et `test_model.py` : spécification exécutable indépendante,
  25 tests adversariaux passés localement. Ce n'est pas du code Android livré à l'utilisateur.
- Aucun receiver, permission, crypto réelle ou suppression activé ; aucun APK demandé.

## Prochain lot concret

1. Porter la machine à états et les tests en Kotlin pur, en conservant le bootstrap inactif.
   Ne pas copier le stockage en RAM du modèle : ce verrou représente une transaction durable.
2. Implémenter un stockage transactionnel unique : armement + intention panic. Tester
   crash/commit, corruption et stockage plein ; aucun défaut IDLE à la lecture d'une erreur.
3. Scinder le coordinateur actuel selon les contrats, avec faux adaptateurs testables.
   Ajouter le gate aux voies d'accès avant toute implémentation destructive.
4. Construire les écrans : 1 à 5 contacts, durées prédéfinies, retrait, désactivation,
   affichage expiration et avertissement clair du pouvoir donné aux contacts.
5. Adapter horloge/numéros : boot fiable ou mode indisponible ; normalisation explicite,
   génération CSPRNG et partage sans persistance du secret brut. Ne pas montrer « actif »
   si receiver ou prérequis sécurité sont absents.
6. Réserver le flavor/receiver à un lot ultérieur après les portes de la décision. En attendant,
   tester avec des entrées synthétiques uniquement. Ne pas demander READ_SMS/READ_CONTACTS
   par commodité ; saisie manuelle suffit au premier écran.

Un seul commit cohérent par lot, vérification finale sur ce SHA. Ne pas déclencher des builds
Android pour de simples docs. Pour les modifications Kotlin : tests/lint/build applicables,
manifest fusionné ; conserver toutes les protections existantes.

## Ne pas déclarer terminé à tort

La conception ne vaut pas validation de la destruction réelle. #2 et #8 restent ouvertes.
Le format cryptographique, copies de clés, Keystore, backend DELETE-only, PDU multipart et
durabilité Android restent à valider. Une découverte qui change ces frontières nécessite
une nouvelle revue de conception ; ne pas improviser une crypto ni contourner Android.

La désactivation du launcher est reportée hors MVP. L'ancien coordinateur et l'ancien ordre
dans ARCHITECTURE.md décrivaient le bootstrap ; la décision présente fait autorité pour le refactor.
