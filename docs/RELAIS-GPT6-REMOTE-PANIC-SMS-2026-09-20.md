# Relais GPT-6 — Resilience Vault / remote panic SMS

**Date : 20 septembre 2026**  
**Dépôt :** `jasmin-abernathy/resilience-vault`  
**Branche cible :** `main`  
**Point de départ connu :** `6c679bbc6000822d76b590955a9c3f287fcd3081`  
**Issues principales :** #2 et #8

## À lire impérativement avant Git

1. `jasmin-abernathy/repo-factory/README.md`
2. `jasmin-abernathy/repo-factory/AGENTS.md`
3. `jasmin-abernathy/repo-factory/COMPATIBILITY-AND-DEPRECATION-PLAYBOOK.md`
4. `jasmin-abernathy/android-safe-install-playbook/AGENTS.md`
5. `jasmin-abernathy/android-safe-install-playbook/PLAYBOOK.md`
6. `resilience-vault/AGENTS.md`
7. `resilience-vault/docs/GPT6-HANDOFF.md`
8. issues #2 et #8

Ne pas considérer un commit comme une validation : relire le SHA final, les fichiers touchés et les checks disponibles.

---

## Contexte produit

Resilience Vault est une application Android de coffre/résilience destinée à :

- sauvegarder des dossiers explicitement choisis ;
- récupérer les sauvegardes locales Signal via SAF ;
- intégrer Telegram via TDLib sans lire la base privée du client Telegram officiel ;
- chiffrer les sauvegardes avant futur upload zéro connaissance ;
- fournir un panic local capable, à terme, de détruire d'abord les clés locales puis de demander la suppression distante.

Le bootstrap actuel est volontairement prudent :

- aucune permission réseau ;
- aucune permission de stockage large ;
- `allowBackup=false` ;
- `usesCleartextTraffic=false` ;
- aucun AccessibilityService, notification listener, overlay ou device admin ;
- crypto de production non activée ;
- bouton panic destructif non activé ;
- TDLib/JNI non encore intégré.

Le code actuel contient notamment :

- `PanicCoordinator`
- `VaultCryptoPort`
- `RemoteVaultPort`
- connecteurs Signal / dossier générique
- port `TelegramBridge`

---

# Nouvelle fonction à auditer : contact de confiance / panic par SMS

## Besoin

L'utilisateur peut **armer temporairement** le déclenchement distant du panic.

Pendant cette fenêtre seulement, **un des 1 à 5 contacts de confiance** choisis par l'utilisateur peut envoyer un SMS contenant son secret/mot-clé pour déclencher le **même panic** que le bouton local.

Le contact ne doit jamais pouvoir :
- armer la fonction ;
- prolonger la durée ;
- réarmer après expiration ;
- accéder aux sauvegardes ;
- restaurer des données ;
- modifier la configuration.

## UX envisagée

Mode désactivé par défaut.

L'utilisateur choisit :
- **de 1 à 5 contacts/numéros autorisés** ;
- une durée commune : 1 h, 6 h, 12 h, 24 h, 48 h ou 72 h ;
- un secret distinct généré à forte entropie pour chaque contact, éventuellement remplaçable sous règles de robustesse.

Hard cap proposé : **72 heures**.

L'écran montre explicitement :
- la liste des 1 à 5 contacts autorisés ;
- la date/heure de fin commune ;
- un bouton permettant de retirer immédiatement un contact ;
- un bouton « Désactiver maintenant ».

Chaque nouvel armement invalide tous les anciens secrets. Retirer un contact invalide immédiatement son secret. Dès qu'un contact déclenche valablement le panic, **tout l'armement est consommé** et les secrets des autres contacts deviennent invalides.

---

# Invariants de sécurité à trancher par GPT-6

## 1. Expiration réellement bornée

La sécurité ne doit pas dépendre d'un timer Android qui aurait pu être tué.

À la réception du SMS, l'état d'armement doit être revalidé.

Problème à résoudre : `System.currentTimeMillis()` peut être modifié, tandis que `elapsedRealtime()` ne survit pas au reboot.

Comparer au moins ces stratégies :
- désarmement forcé au reboot : très fail-closed, moins pratique ;
- persistance au reboot avec détection d'anomalie d'horloge ;
- combinaison wall-clock + monotonic clock + marqueur de boot.

Un retour arrière de l'horloge ne doit **jamais prolonger** silencieusement la fenêtre.

Documenter clairement la limite si une garantie stricte de 72 h hors ligne et à travers reboot n'est pas démontrable.

## 2. Secrets SMS par contact

Le secret circule dans un SMS : ne pas le traiter comme un canal confidentiel.

Exigences :
- secret aléatoire et difficile à deviner **distinct pour chacun des 1 à 5 contacts** ;
- one-shot ;
- consommation atomique de l'armement avant le panic ;
- un déclenchement valide invalide tous les secrets de la fenêtre ;
- rotation de tous les secrets à chaque nouvel armement ;
- ne pas stocker le secret brut dans l'état persistant ;
- aucun secret dans les logs, crash reports ou notifications.

Évaluer un vérificateur local adapté plutôt qu'une comparaison avec une valeur en clair.

## 3. Identité des 1 à 5 contacts

Le numéro de l'expéditeur n'est pas à lui seul une authentification forte.

Auditer :
- normalisation des numéros ;
- formats internationaux ;
- spoofing possible ;
- SMS provenant de passerelles ;
- double SIM si cela change le comportement ;
- combinaison obligatoire **expéditeur autorisé + secret propre à ce contact** ;
- suppression/révocation d'un contact atomique et immédiatement effective.

## 4. SMS multipart / replay / duplication

Le receiver ne doit jamais paniquer sur un fragment.

Prévoir :
- reconstruction complète du message avant validation ;
- traitement atomique ;
- déduplication ;
- protection replay ;
- concurrence de deux SMS identiques ;
- concurrence de SMS valides provenant de deux contacts différents ;
- message reçu pendant qu'un panic est déjà en cours.

Après acceptation du premier message valide, **la fenêtre entière** doit être définitivement consommée : aucun des autres contacts ne doit pouvoir déclencher à nouveau.

## 5. BroadcastReceiver et latence du panic

Point particulièrement important.

Un receiver SMS n'est pas un bon endroit pour exécuter une longue séquence réseau.

Évaluer une séparation explicite :

```text
SMS valide
   ↓
consommation atomique de l'autorisation
   ↓
PHASE LOCALE CRITIQUE
- verrouillage
- destruction clés locales
- invalidation accès local
   ↓
PHASE POST-DESTRUCTION
- purge
- révocation sessions
- DELETE cloud
- retry delete-only
- launcher disable
```

L'objectif est que la donnée devienne inutilisable localement **avant** qu'une opération réseau, un worker différé ou une limitation du BroadcastReceiver puisse intervenir.

Décider si `PanicCoordinator` doit être scindé en deux phases.

## 6. Android / flavors

Ne pas ajouter `RECEIVE_SMS` au build actuel avant décision.

Architecture souhaitée :
- build normal sans permission SMS ;
- flavor/canal FOSS/direct avec remote panic SMS si la distribution le permet ;
- aucun `READ_SMS` pour parcourir l'historique ;
- aucun stockage des SMS reçus dans Resilience Vault.

Vérifier la politique Android/Google Play actuelle avant toute intégration store.

---

# Ce que GPT-6 doit produire

1. Un **threat model court mais concret** du remote panic SMS avec 1 à 5 contacts.
2. Une décision documentée sur l'expiration, notamment reboot + modification d'heure.
3. Une décision sur le secret one-shot et son stockage/vérificateur local.
4. Une proposition de machine à états atomique.
5. Une décision sur la séparation `PanicCoordinator` phase locale / phase post-destruction.
6. Les invariants et tests adversariaux nécessaires.
7. Mettre à jour les docs/issues du dépôt avec ces décisions.
8. Seulement si le design est suffisamment tranché : ajouter les **interfaces/états/tests** nécessaires.

## Ne pas gaspiller GPT-6 sur

- écrans Compose ordinaires ;
- picker de contact ;
- styles ;
- wiring DataStore banal ;
- création du flavor Gradle une fois l'architecture décidée ;
- chaînes de traduction ;
- documentation utilisateur ordinaire.

Ces tâches peuvent revenir à GPT-5.6 après l'audit.

---

# Tests adversariaux minimum

- mode non armé ;
- zéro contact configuré ;
- 1 contact configuré ;
- 5 contacts configurés ;
- tentative d'un 6e contact refusée ;
- numéro incorrect ;
- secret incorrect ;
- bon numéro + son propre secret ;
- bon numéro + secret d'un autre contact ;
- secret rejoué ;
- SMS multipart ;
- fragments reçus dans un ordre inhabituel ;
- SMS dupliqué ;
- deux SMS simultanés du même contact ;
- deux SMS valides simultanés de deux contacts différents ;
- expiration une milliseconde avant validation ;
- changement d'heure vers le futur ;
- changement d'heure vers le passé ;
- reboot pendant la fenêtre ;
- permission SMS révoquée ;
- panic déjà lancé ;
- crash après consommation du secret mais avant destruction locale ;
- crash après destruction locale mais avant DELETE distant ;
- téléphone hors ligne ;
- serveur distant indisponible.

---

# Règle de clôture

Ne pas activer le receiver destructif ni déclarer la fonction sûre tant que :
- #2 n'est pas auditée ;
- les états d'interruption sont couverts ;
- l'expiration est fail-closed ;
- le merged manifest du flavor concerné est vérifié ;
- le SHA final a ses tests/lint/build applicables verts.

## Relais traité — 20 septembre 2026

Les propositions ci-dessus sont historiques. Consulter [la décision GPT-6](REMOTE-PANIC-SECURITY-DECISION.md) et [la suite GPT-5.6](RELAIS-GPT56-REMOTE-PANIC-2026-09-20.md). Secret obligatoirement généré, plafond 72 h, désarmement au reboot, transaction consommation + intention, deux phases de panic ; launcher reporté. Modèle de spécification testé ; aucune activation Android.
