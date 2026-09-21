# Coffre V1 — format et cycle de vie des clés

Décision GPT-6 du 21 septembre 2026, issue #1. **Spécifié, pas implémenté ni activable.**
Le seul code livré ici est l'encodage canonique du contexte authentifié `VaultBinding`,
avec son vecteur de test. Il ne chiffre rien. `PRODUCTION_CRYPTO_READY` reste false.

## 1. Objectif et choix des primitives

Protéger contenu, noms de fichiers et inventaire avant envoi. Le serveur voit les identifiants
opaques, tailles, dates de transfert, volumes, adresses réseau et métadonnées de compte.
« Zéro connaissance » ne signifie pas absence de ces métadonnées. OS compromis, root,
lecture de RAM et destinataire qui conserve une copie restent hors garantie.

Choix : bibliothèque Google Tink Java/Android, à épingler et auditer dans le lot d'intégration.
Pas de nouveau cipher, KDF ou format de segments maison.

- Contenu volumineux : `StreamingAead`, paramètres `AES256_GCM_HKDF_1MB`.
- Enveloppes et manifestes bornés : `Aead`, paramètres `AES256_GCM`.
- Une nouvelle clé de flux Tink pour chaque version immuable d'objet. Chaque tentative de
  rechiffrement repart avec nouvelle clé et nouvel objectId ; la reprise réseau renvoie
  les mêmes octets chiffrés déjà finalisés. Ne jamais réinitialiser un générateur déterministe.
- Tink gère les sels, nonces et marqueurs de fin du flux. Ne pas exposer d'API nonce au
  code métier, ne pas reconstruire le format interne, ne pas sérialiser une clé en clair sur disque.
- Les enveloppes sont chiffrées par les API Tink de sérialisation de keysets avec AAD.
  Aucun fallback vers un keyset non chiffré si Android Keystore est indisponible.

Références : [Streaming AEAD](https://developers.google.com/tink/streaming-aead),
[paramètres et format AES-GCM-HKDF](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming),
[gestion des clés](https://developers.google.com/tink/key-management-overview).
Le nom exact des API et la version du paquet doivent être vérifiés lors de l'intégration.

## 2. Hiérarchie et séparation

| Élément | Création et emplacement | Pouvoir |
| --- | --- | --- |
| KEK locale | AES-256-GCM non exportable Android Keystore, alias propre installation/coffre/époque | Ouvrir l'enveloppe locale du keyset d'époque |
| Keyset d'époque E | Tink AES256_GCM ; clair uniquement en RAM ; enveloppe locale et, si opt-in, enveloppe de récupération | Ouvrir les keysets d'objets et les manifestes de cette époque |
| Keyset d'objet D | Tink streaming neuf par version ; stocké seulement chiffré sous E | Déchiffrer cet objet |
| Keyset de récupération R | Tink AES256_GCM généré ; kit exporté explicitement hors appareil puis retiré de l'app | Ouvrir les enveloppes de récupération d'époque |
| Capability DELETE | Secret serveur indépendant, chiffré sous alias Keystore distinct sans droit de lecture du coffre | Demander le tombstone d'une génération, jamais déchiffrer |
| Credential de compte/connecteur | Stockage distinct ; jamais utilisé comme substitut DELETE | Authentification réseau, à révoquer séparément |

E n'est pas un mot de passe utilisateur et R n'est pas dérivé d'un PIN. Aucun mode mot de passe
humain en V1 ; un futur mode passphrase nécessiterait une décision KDF et des limites propres.
Le kit R contient un keyset standard Tink sérialisé, version du kit, vaultId, génération,
endpoint attendu et dernière ancre de manifeste connue. Il est secret : ne pas le télécharger
sur le serveur avec ses enveloppes. Le serveur ne possède que des keysets chiffrés.

KEK locale : création après consentement, usage encrypt/decrypt GCM sans padding, IV aléatoire
fourni par le provider, export interdit. Lecture avec authentification locale explicite en V1 ;
pas de sauvegarde autonome en arrière-plan dans cette première version. Le panic peut supprimer
l'alias sans devoir déchiffrer E ni obtenir la biométrie. Tester ce comportement sur appareil.
StrongBox est optionnel, sa disponibilité ne doit pas produire de fausse promesse matérielle.
Une absence de matériel dédié ne permet jamais un fallback stockage clair.
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## 3. Identité et contexte authentifié

Tous les identifiants sont 32 octets aléatoires, représentés en hexadécimal minuscule dans
les APIs de configuration. Génération du coffre distincte de la génération d'armement SMS.
L'identité d'objet est immuable ; un changement de contenu crée un nouvel objet.

`VaultBinding.associatedData()` produit exactement **120 octets**, big-endian :

| Offset | Octets | Champ |
| --- | ---: | --- |
| 0 | 4 | ASCII RVB1 |
| 4 | 2 | version 1 |
| 6 | 1 | purpose : 1 contenu, 2 enveloppe objet, 3 manifeste, 4 enveloppe époque locale, 5 récupération époque |
| 7 | 1 | suite 1 = streaming AES256_GCM_HKDF_1MB + enveloppes AES256_GCM |
| 8 | 32 | vaultId |
| 40 | 32 | vaultGeneration |
| 72 | 32 | objectId |
| 104 | 8 | keyEpoch, entier signé positif |
| 112 | 8 | revision, entier signé non négatif |

Les enveloppes d'époque utilisent objectId zéro et revision zéro ; les objets et manifestes
ont des objectId non nuls et revision positive. Ces contraintes sémantiques doivent être
imposées par les adaptateurs de type, en plus de l'encodage. La classe actuelle ne valide que
l'encodage et les plages ; elle ne choisit pas le contexte à la place de l'appelant.

Vecteur canonique (non secret) : purpose=1, vaultId=00×32, génération=11×32, objet=22×32,
époque=1, révision=2 => `5256423100010101 || 00×32 || 11×32 || 22×32 ||
0000000000000001 || 0000000000000002`. Test byte-for-byte dans `VaultBindingTest`.
C'est un vecteur **d'encodage AAD**, pas un vecteur de validation du chiffrement Tink.

Lors d'une lecture, l'AAD attendue vient de l'inventaire authentifié et de l'identité locale
ou du kit de récupération. Ne pas accepter le coffre/génération/objet proposés par un header
attaquant comme identité attendue. Vérifier égalité avant déchiffrement. Chaque enveloppe utilise
son propre purpose ; pas d'interchangeabilité clé/contenu/manifeste.

## 4. Conteneurs et inventaire

Blob d'objet V1 : `context[120] || encryptedKeysetLength[4] || encryptedKeyset || tinkStream`.
`context` porte purpose=OBJECT_DATA ; dériver le contexte OBJECT_KEY en ne changeant que purpose
pour ouvrir le keyset. encryptedKeysetLength est un uint32 big-endian, 1..65536 ; ne jamais
allouer depuis une longueur non bornée. Minimum du flux vérifié par Tink. Refuser versions,
suites et purposes inconnus. Pas de négociation automatique vers une suite moins forte.
Limite produit initiale proposée : 8 GiB par objet, à valider avec la taille des sauvegardes
réelles ; streaming et quota avant restauration. Toutes les tailles/compteurs sont contrôlés
avec détection d'overflow. Aucun plaintext destiné à l'utilisateur ne sort avant validation complète.

Conteneur de manifeste ou enveloppe d’époque : `context[120] || tinkAeadCiphertext`,
avec purpose MANIFEST, EPOCH_LOCAL ou EPOCH_RECOVERY. Le header attendu reste vérifié
contre l’identité et l’époque connues avant déchiffrement.

Manifeste chiffré sous E avec purpose MANIFEST : schéma JSON UTF-8 strict, champs inconnus et
clés dupliquées refusés, taille max 8 MiB. Contenu : vaultId/génération, époque, séquence,
ID du manifeste précédent et hash SHA-256 de son conteneur, liste d'objets avec objectId,
révision, époque, taille claire, taille du conteneur, hash SHA-256 de ce conteneur, chemin
logique, type et provenance. Rien de personnel dans le header clair. Pas de canonicalisation
JSON requise pour AEAD : authentifier les octets effectivement stockés ; le hash porte sur
le conteneur exact, pas sur une re-sérialisation JSON.

Publication : finir et fermer le flux Tink, vérifier les longueurs/hash, uploader les blobs
immuables, puis publier le manifeste par comparaison-et-échange du head connu. Ne jamais
annoncer une sauvegarde complète avant le commit du manifeste. Un upload orphelin n'est pas
une sauvegarde valide et peut être nettoyé après une durée définie.

L'intégration doit produire fixtures Tink interopérables : fichier vide, 1 octet, frontières
1 MiB, plusieurs segments, bad tag, header/AAD modifié, mauvaise enveloppe, coupure de chaque
segment, suppression du dernier segment, concaténation de deux flux, mauvais coffre/époque,
ordre des segments modifié. Aucun succès sur EOF prématuré ou exception transformée en EOF.

## 5. Anti-rejeu, rollback et restauration

AEAD authentifie des octets, pas leur fraîcheur. Chaque appareil mémorise, hors sauvegarde
Android, le dernier head accepté (séquence + hash + génération). Refuser une séquence inférieure,
et une même séquence avec un hash différent. Écritures multi-appareils sérialisées par CAS
serveur ; V1 peut limiter à un seul écrivain par coffre pour simplifier les conflits.

Une nouvelle installation sans ancre indépendante ne peut pas prouver qu'un serveur hostile
lui montre le dernier snapshot. Le kit exporté fixe une borne de fraîcheur, pas la date actuelle.
Afficher cette limite à la restauration, jamais inventer un anti-rollback global hors ligne.
Un appareil rooté restaurant tout l'état local échappe aussi à cette garantie.

Restaurer dans un staging privé borné, authentifier tout le flux et vérifier taille/hash et
manifeste avant publication. Pour SAF, pas de garantie de renommage transactionnel universel :
export après validation locale vers une destination choisie, avec statut explicite d'export
partiel et journal de reprise. Aucun écrasement silencieux des originaux. Refuser `..`, chemins
absolus, liens symboliques et collisions de noms ambiguës. Pas d'import dans la base privée
de Signal/Telegram officiel. Les données privées de staging doivent elles-mêmes être chiffrées
au repos et appartenir au périmètre de destruction ; un staging clair persistant contredirait le panic.

Le gate actuel `check()` est une observation, pas un verrou d'accès : avant toute crypto,
ajouter un gestionnaire de leases qui sérialise fermeture du gate et délivrance des handles,
cancelle/joint les opérations en cours, interdit toute recréation d'alias et toute publication
après panic. Une vérification en début d'export ne suffit pas. Pas d'activation sans ces tests.

## 6. Création, récupération et rotation

Création : transaction explicite d'un **nouveau** coffre (nouvelle génération), journal de
provisioning, clés, enveloppes et registre de sécurité cohérents avant premier accès. Un registre
absent/corrompu pour un coffre existant ne déclenche jamais une initialisation IDLE automatique.
Les aliases orphelins d'une création interrompue sont bloqués et nettoyés par une procédure dédiée.

Récupération : consentement et kit R hors appareil ; vérifier génération/ancre avant ouverture,
créer une nouvelle KEK locale, rewrap E, valider un objet test et checkpoint avant d'autoriser
la lecture. Ne pas conserver R dans l'app pour la commodité d'un futur déverrouillage.
V1 permet récupération hors appareil, pas synchronisation silencieuse des clés entre appareils.

Rotation KEK locale : créer nouvel alias + nouvelle enveloppe, vérifier déchiffrement et commit
du pointeur, puis supprimer ancien alias. Le panic doit lister et supprimer **les deux** aliases
si une rotation est interrompue. Rotation E : nouvelle époque pour les écritures ; conserver les
anciennes époques seulement pour les objets existants, chiffrées et inventoriées. Rewrap seul
ne révoque pas une clé déjà volée : après compromission de E/D, rechiffrer sous nouvelles clés
et supprimer les anciennes copies selon les possibilités réelles du backend.

Rotation R : exige l'ancien kit ou un appareil encore autorisé, nouveau kit vérifié avant
suppression des anciennes enveloppes côté service. Les anciennes copies exportées ou snapshots
sauvés par un tiers restent déchiffrables avec l'ancien kit. Aucun effacement rétroactif magique.

## 7. Inventaire des copies et panic

| Copie / handle | Action locale critique ou limite |
| --- | --- |
| KEK locales actives et en rotation | Supprimer tous les aliases de l'inventaire de génération ; absence confirmée, erreur ≠ absence |
| Keysets E/D et objets Tink en RAM | Fermer leases, flux et caches ; limiter durée ; zéroïsation best effort, JVM non garantie |
| Enveloppes locales, keysets temporaires | Inaccessibles après destruction KEK ; purge privée postérieure, pas de clé claire temporaire |
| Staging, DB d'inventaire et index locaux | Chiffrés sous clés incluses dans l'inventaire destructif ; purge après phase critique |
| État/auth TDLib dédié | Contient potentiellement une capacité de relire Telegram ; audit séparé avant activation, pas couvert par la destruction E |
| Credential lecture/upload cloud | Retiré localement ; révocation serveur dès réseau possible ; distinct du DELETE-only |
| R dans kit externe / autre appareil | Hors portée du panic local ; prévenir l'utilisateur |
| Photos/captures/presse-papiers/export manuel | Copies hors coffre non supprimées par la destruction KEK |
| DELETE-only et alias dédié | Survivent uniquement pour retry ; ne donnent aucun accès aux données |

Panic confirmé : intention durable avec identité de génération et inventaire de clés récupérable
sans E ; gate fermé ; drain des leases ; suppression de toutes les capacités locales de lecture ;
checkpoint POST_PENDING seulement après réussite. Ne pas faire dépendre l'inventaire des aliases
de la clé à détruire. Crash à chaque frontière => reprendre sans recréer de clé. Les effets actuels
sont des interfaces sans implémentation : leur succès ne constitue aucune preuve Keystore.

Désinstallation : l'app ne peut pas promettre l'exécution d'un callback panic ; le cloud subsiste
si DELETE n'a pas été confirmé. Une réinstallation ne doit pas retrouver automatiquement le coffre
via Android Backup (règles d'extraction déjà fermées). R peut permettre une récupération explicite
si le cloud existe encore. Restauration Android indésirable ou ancien registre => blocage, jamais
recréation implicite d'une KEK sous le même alias pour « réparer » un déchiffrement échoué.

## 8. Porte d'activation

Restent indispensables : version Tink épinglée et licence/manifest inspectés ; tests JVM Tink
interopérables ; crash tests provisioning/rotation ; Android Keystore réel verrouillé/déverrouillé,
redémarrage et alias invalide ; couverture de toutes les leases ; durabilité fichier sous kill ;
revue du backend et de la récupération ; tests finaux du SHA et revue indépendante de l'intégration.
La présente décision fixe l'architecture, elle ne ferme pas #1.
