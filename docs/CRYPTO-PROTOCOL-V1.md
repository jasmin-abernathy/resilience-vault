# Coffre v1 — décision de conception, non activée

Revue du 20 septembre 2026, base `6c679bbc6000822d76b590955a9c3f287fcd3081`, issue #1.
Cette spécification autorise une implémentation derrière le verrou existant, pas une mise en production.
`PRODUCTION_CRYPTO_READY=false` reste obligatoire. Aucun chiffrement maison : utiliser les primitives Tink ; ne pas réimplémenter leur segmentation, dérivation ou génération de nonce.

## Modèle de menace et limites

Le serveur peut lire, supprimer, substituer ou rejouer tous les octets stockés. Il ne reçoit aucune clé de déchiffrement. TLS et une authentification de compte indépendante restent nécessaires pour les droits de service. Les tailles, horaires, nombre d'objets et adresse réseau ne sont pas cachés. Un appareil déjà compromis, un dump mémoire antérieur, les copies exportées et un autre appareil autorisé sortent de la garantie d'effacement local.

La récupération est volontairement conservée après un panic LOCAL. Supprimer les clés de cet appareil ne détruit donc pas la possibilité de restauration avec un kit externe. Le panic DISTANT, option distincte, demande la suppression du coffre ; aucun serveur malveillant ne peut être forcé cryptographiquement à supprimer ses copies. Ne pas annoncer un effacement universel.

## Primitives et clés

| Élément | Choix v1 | Conservation |
| --- | --- | --- |
| Objet de données | Tink Streaming AEAD `AES128_GCM_HKDF_1MB`, keyset neuf par objet immuable | Keyset uniquement dans le manifeste chiffré |
| Manifeste | Tink AEAD `AES256_GCM`, keyset neuf par snapshot | Keyset enveloppé par la clé racine d'époque |
| Racine d'époque | Tink AEAD `AES256_GCM`, keyset aléatoire | Enveloppe locale et enveloppe de récupération |
| Protection locale | AES-GCM Android Keystore, clé non exportable par installation | Alias dédié ; aucune copie système |
| Récupération | Clé aléatoire de 32 octets, utilisée par AEAD Tink AES256-GCM RAW | Kit externe ; jamais persistée sur cet appareil |
| Session TDLib | Clé indépendante, protégée par la même frontière d'effacement locale | Jamais dans les snapshots récupérables |
| DELETE distant | Secret serveur indépendant de 32 octets | Capsule séparée, sans clé de lecture |

Choix applicatif : un objet contient au plus 1 GiB de clair, un manifeste au plus 8 MiB ; découper les fichiers plus grands en objets ordonnés dans le manifeste. Maximum 10 000 objets par snapshot. Refuser ces bornes avant allocation et les faire respecter en lecture de flux. Les métadonnées ne contiennent jamais du clair sensible dans les en-têtes.

Chaque tentative d'encodage utilise de nouveaux ID et keysets, y compris après crash ou conflit multi-appareil. Reprendre un upload n'est permis qu'en renvoyant les octets EXACTS d'un ciphertext déjà finalisé. Ne jamais reprendre un chiffrement au milieu d'un flux. Les nonces/salts restent gérés par la bibliothèque. Rotation de racine après 100 000 enveloppes ou avant ; le compteur est une borne conservatrice du service, pas une preuve de fraîcheur contre un serveur hostile.

## Encodage et données associées

L'en-tête RVLT fait exactement 64 octets, sans JSON, padding ni champs optionnels :

| Offset | Taille | Champ |
| --- | --- | --- |
| 0 | 4 | ASCII `RVLT` |
| 4 | 1 | Version = 1 |
| 5 | 1 | Type : 1 objet, 2 manifeste, 3 enveloppe manifeste, 4 enveloppe récupération |
| 6 | 2 | Suite, uint16 big endian : 1 Streaming AEAD, 2 AEAD AES256-GCM |
| 8 | 16 | Vault ID aléatoire |
| 24 | 16 | Record ID aléatoire et immuable |
| 40 | 8 | Époque uint64 big endian, de 1 à 2^63−1 |
| 48 | 8 | Révision uint64 big endian, de 1 à 2^63−1 |
| 56 | 8 | Taille du plaintext uint64 big endian |

Le fichier est `header || ciphertext Tink intégral`. L'AAD est exactement le header. Type 1 impose suite 1 ; les autres imposent suite 2. La bibliothèque conserve son propre framing et ses préfixes. Rejeter version, type, suite, bornes et taille d'en-tête inconnus avant déchiffrement ; aucune négociation ou rétrogradation automatique. Une enveloppe (types 3/4) est bornée à 64 KiB.

La taille est seulement une indication non fiable avant authentification. Comparer aussi tous les champs avec le contexte ATTENDU issu du manifeste authentifié ou du kit ; ne pas accepter un header fourni par le serveur comme sa propre preuve. Consommer le flux jusqu'à EOF, contrôler la longueur exacte et rejeter tout suffixe ou troncature avant publication. Les tests de portage devront vérifier ce comportement de la version Tink retenue.

Le manifeste chiffré utilise UTF-8 JSON : rejet des clés dupliquées, des nombres flottants, champs inconnus, profondeur > 8 et chaînes > 4096 octets. Champs obligatoires : `schema=1`, `vaultId`, `snapshotId`, `epoch`, `revision`, `parentDigest` (SHA-256 du précédent enregistrement manifeste complet, zéro pour le premier), `files`. Chaque fichier a un ID aléatoire, un nom relatif validé, une longueur et la liste ORDONNÉE des objets : ID, header attendu, longueur, SHA-256 du ciphertext complet, keyset Tink sérialisé en base64. Ces keysets sont secrets. Aucun chemin absolu, `..`, lien symbolique, doublon de destination ou sortie hors arbre choisi. Les chemins servent à la restauration, jamais de clés de stockage serveur.

L'enveloppe de manifeste contient le keyset du manifeste et le SHA-256 de son enregistrement complet ; son record ID est le snapshot ID. L'enveloppe de récupération contient le keyset racine de l'époque et le contexte du coffre. ID, époque et révision doivent également correspondre au header. Toutes les clés importées sont générées par un client de confiance, jamais proposées par le serveur. Les algorithmes des keysets importés sont limités à la liste ci-dessus ; aucune résolution d'URL KMS depuis un keyset externe.

## Commit, concurrence et rollback

Uploader d'abord les objets immuables puis le manifeste et son enveloppe ; publier enfin le head par compare-and-swap sur le digest parent. Un conflit laisse des objets orphelins à collecter et exige une nouvelle révision avec de nouveaux ID/keysets. Ne pas fusionner silencieusement deux listes de fichiers. Les droits d'upload ne permettent pas de réécrire un objet existant.

Le client mémorise `(vaultId, epoch, revision, headDigest)` après publication ou restauration vérifiée. Refuser une révision plus basse et une même révision avec un digest différent. Une révision supérieure nécessite une chaîne de parents valide jusqu'au checkpoint connu. Un saut d'époque exige une transition authentifiée par la précédente racine et un renouvellement de récupération.

L'AEAD ne prévient PAS le rejeu d'un snapshot complet valide. Le CAS d'un serveur hostile ne prévient PAS les forks. Après perte de tous les checkpoints indépendants, la fraîcheur ne peut pas être prouvée : afficher « authenticité vérifiée, fraîcheur non vérifiable », jamais « dernière sauvegarde ». Un kit externe porte un checkpoint ; les snapshots plus récents doivent fournir une chaîne vérifiable. Cette v1 n'introduit pas de service tiers de transparence.

## Récupération et cycle de vie

Le kit externe contient version, origine HTTPS choisie, vault ID, secret de récupération en base64url, ID/digest de l'enveloppe racine et checkpoint. L'app ne suit aucun hôte arbitraire reçu dans un fichier sans validation et confirmation. Un test de récupération avant activation vérifie le secret, le contexte, le manifeste et un objet témoin. Ne pas sauvegarder le kit dans le coffre qu'il déverrouille, le presse-papiers, les logs ou une capture automatique.

Un second appareil s'authentifie au service séparément, importe le kit explicitement, restaure la racine en mémoire puis la protège par son propre alias Keystore. Le secret de récupération ne sert jamais de mot de passe API. Pas de PIN humain comme clé ; un mode passphrase exigerait une autre revue/KDF et n'est pas inclus.

Rotation normale : créer une nouvelle racine, publier la transition et un nouveau kit vérifié avant abandon de l'ancienne. Conserver les anciennes racines nécessaires aux snapshots historiques dans une archive chiffrée par la nouvelle racine. Révocation d'un appareil compromis : révoquer ses droits serveur et créer de nouvelles clés/époque pour les données futures. Réenvelopper ne révoque pas les anciennes copies de clés ; les données déjà connues restent connues. Une rotation après compromission exige un rechiffrement complet si l'objectif inclut les archives conservées.

Panic local : supprimer alias et enveloppes locales, clés de session, staging et tokens de lecture. Ne jamais recréer automatiquement un alias manquant pour un coffre déjà provisionné. Désinstallation : aucun retry garanti ; le kit externe peut permettre une nouvelle installation. Restauration Android partielle, état absent/corrompu ou alias manquant : coffre fermé, récupération explicite uniquement. Android Keystore limite l'extraction mais ne garantit pas l'effacement de copies mémoire antérieures.

## Restauration transactionnelle

Vérifier enveloppe, contexte, checkpoint et manifeste avant de lire les objets. Restaurer dans un staging privé, isolé et chiffré au repos par une clé locale éphémère. Vérifier tous les tags jusqu'à EOF, tailles et digests avant publication. Un échec ne remplace jamais un fichier utilisateur existant. Un journal par fichier distingue vérifié, export en cours et export terminé.

SAF n'offre pas de transaction multi-fichier universelle : exporter vers de nouvelles destinations, une par une, après validation complète ; conserver un rapport des succès/échecs et exiger une reprise explicite. Une restauration partielle choisie peut publier uniquement les fichiers complets validés. Ne jamais présenter un fichier partiellement écrit comme restauré. Après crash, revalider le staging et ne pas reprendre des octets plaintext non authentifiés. Toute restauration prend le même verrou de cycle de vie que le panic et ne peut publier après son déclenchement.

## Vecteurs et critères d'acceptation

`security_review/tests/test_contracts.py` fige l'encodage et les refus de contexte/rollback. `test_aead_vectors.py` vérifie un vecteur AES-GCM connu et l'altération des champs AAD avec la bibliothèque cryptography ; ce n'est PAS un vecteur du format Tink.

Avant activation, ajouter avec la version Tink épinglée : vecteurs interop Java, fichiers 0/1 octet, frontières de segment ±1, clé incorrecte, permutation/suppression/duplication de segments, troncature à chaque frontière, suffixe, mauvaise époque/coffre/type, stream interrompu, limite 1 GiB, restauration SAF interrompue, concurrence panic/restauration. Vérifier dépendances/CVE, parser fuzzé et appareil API 26 + appareil récent. Aucun résultat de cette revue ne remplace ces tests.

## Références consultées

- [Tink Streaming AEAD](https://developers.google.com/tink/streaming-aead) et [format AES-GCM-HKDF](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming).
- [Tink AEAD](https://developers.google.com/tink/aead) et [limite de compteur streaming](https://developers.google.com/tink/issues/streaming-aead-overflow).
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

Le framing RVLT, les plafonds, le modèle de récupération et le protocole de commit sont des décisions de conception de ce projet, pas des garanties annoncées par ces sources.
