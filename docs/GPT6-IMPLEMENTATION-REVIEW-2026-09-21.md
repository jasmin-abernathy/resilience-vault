# Revue GPT-6 de l'implémentation — 21 septembre 2026

Base examinée : `e130b96efe4db7444a83c7c14892db663bd40d12`, main. Dernier lot Kotlin antérieur :
`617c330dba5e9290e4946dc6d58e010a8da7ca43`, CI tests/build/lint réussie vérifiée au démarrage.
Cette revue porte sur le code et les tests, **pas sur un téléphone ni un backend de production**.

## Constats et corrections du présent lot

| Priorité | Constat sur la base | Correction et preuve ajoutée |
| --- | --- | --- |
| Haute | SMS admis avec heure/permission capturées dans l'enveloppe avant attente du mutex | Observateur de confiance injecté, échantillonné sous transaction puis recontrôlé avant mutation ; test expiration pendant attente et permission perdue pendant validation |
| Haute | Deux appels à create() donnent deux mutex pour le même AtomicFile | Instance unique par chemin canonique, même verrou et même latch ; concurrence réelle de plusieurs services sur VerifiedPanicStateStore testée ; factory Android à tester sur appareil |
| Haute | finishWrite() sans exception interprété comme preuve de commit | Sync explicite qui propage les erreurs, fsync du répertoire, relecture byte-for-byte sous verrou ; échec => latch fermé pour tout le processus ; tests écriture ignorée et erreur après publication |
| Haute | État valide mais transition arrière possible par transaction générique | validateTransitionFrom : pas de retour IDLE, saut de phase, changement de panicId ni régression de checkpoint ; tests dédiés |
| Haute | initializeEmptyIfMissing pourrait rouvrir un coffre après perte de registre si réutilisé | Suppression de cette API inutilisée ; MISSING reste fermé. Provisioning futur séparé et explicite, pas de reset de sécurité |
| Moyenne | readBytes() alloue avant limite du codec | Lecture fichier bornée à 32 KiB + 20 octets ; codec vérifie aussi la taille totale |
| Haute | Exception/attente indéfinie d'un effet bloque les suivants | Exceptions ordinaires converties en échec retryable ; délais coopératifs par effet ; cancellation parent propagée ; tests purge exception, révocation bloquée, clés en erreur |
| Moyenne | Horloge invalide affichée sans invalider le registre | refreshRemoteState transactionnel ; UI appelle cette méthode ; test retour de l'heure après anomalie observée |
| Moyenne | Représentation data class peut révéler SMS/secrets/vérificateurs | toString redacted pour enveloppes/commandes/états sensibles ; test de non-divulgation |
| Moyenne | Addition de durée non vérifiée et panne RNG non traduite | Overflow refusé avant armement ; panne/duplication RNG invalide ancien armement ; tests adaptés |
| Moyenne | BOOT_COUNT illisible peut lancer une exception dans Compose | Erreur du provider => identité inconnue ; mode distant indisponible |

Source technique de la vérification du store : [AtomicFile Android](https://developer.android.com/reference/android/util/AtomicFile)
et [implémentation AOSP](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/util/AtomicFile.java).
AtomicFile exige une synchronisation externe. Sa méthode finishWrite retourne void et
l'implémentation consultée journalise certains échecs : l'absence d'exception seule est insuffisante.
[Os.fsync](https://developer.android.com/reference/android/system/Os) est utilisé pour le répertoire.

## Propriétés désormais contrôlées dans le code

Les règles 1..5 contacts, correspondance numéro + secret, invalidation globale et phases sont
conservées. Le codec CRC détecte corruption accidentelle ; il n'est **pas un MAC** contre un
acteur capable de réécrire l'espace privé. L'état reste unique et sans secret SMS brut persisté.
La factory garantit l'unicité **dans un seul processus**. Le manifest actuel ne déclare pas
un autre processus ; ne jamais ajouter android:process sans changer le protocole de verrouillage.

Le latch d'erreur dure jusqu'à la fin du processus ; il évite qu'une UI relise immédiatement un
ancien IDLE après commit incertain. Au redémarrage, la lecture durable retrouve soit l'ancien
état non accepté, soit l'intention complète. Ce n'est pas une garantie contre rollback hostile.
Les tests injectent ces résultats ; ils ne simulent pas un vrai filesystem Android sous coupure.

Le temps est relu sous verrou juste avant mutation. Un commit disque puis la destruction peuvent
prendre du temps : la limite porte sur l'admissibilité de la commande, pas une promesse que tous
les effets soient terminés avant l'heure de fin. Sans adaptateur de confiance explicite,
PanicAdmissionService interdit l'armement et le SMS ; le build actuel reste ainsi fermé.
Les flags trustedSystemDelivery/completeMessage sont réservés aux entrées internes/test. Ils ne
constituent pas une authentification d'Intent et ne doivent jamais venir d'extras non vérifiés.

Les timeouts sont coopératifs : ils ne peuvent interrompre un appel Keystore natif bloquant.
Mesurer les adaptateurs réels et leur exécuteur avant receiver. Cancellation parent conserve
l'intention pending et demande une reprise ; elle n'est pas avalée pour continuer du réseau.

## Ce qui reste bloquant avant activation

1. **Gate effectif** : PanicAccessGate est pour l'instant un check sans consommateur crypto.
   Il ne sérialise pas l'obtention d'un handle avec le panic. Implémenter les leases/drain avant
   toute lecture/export/sync réelle ; ne pas se contenter d'appeler check au début.
2. **Durabilité Android** : tester kill avant/après sync, finish, directory sync et relecture ;
   deux appels factory, stockage plein, `.bak/.new`, registre supprimé, permissions fichiers.
   La relecture ne prouve pas seule la survie d'une coupure électrique. Android min 26 à cible 36.
3. **Provisioning** : transaction nouveau coffre + registre + inventaire des aliases, sans pouvoir
   réinitialiser l'état d'une ancienne génération. Cette API n'existe pas encore.
4. **Clés réelles** : destruction de tous aliases et handles, rotation interrompue, staging chiffré,
   récupération externe. Voir la nouvelle spécification ; aucune implémentation crypto activée.
5. **Erreurs postérieures persistantes** : le registre ne retient encore que les succès. Avant
   tout scheduler, migrer les statuts pending/retry/blocked-auth pour ne pas rejouer indéfiniment
   une 401/403 ; tous les effets futurs doivent rester idempotents, même avec deux appels concurrents.
6. **Boot/permission** : BOOT_COUNT est disponible depuis API 24 ; le compteur local n'est pas
   une attestation anti-rollback. Vérifier provider/OEM, reboot et permissions sur appareil.
   Une révocation/réautorisation entre deux observations ne peut être déclarée détectée sans preuve.
7. **Transport SMS** : normalisation actuelle par API système acceptable comme préparation UI,
   mais pas de PDU ni multipart ni vérification du broadcast audités sur appareil ; receiver absent.
8. **TDLib** : phase JNI/session distincte toujours différée (#3). Une session en mémoire peut
   relire le compte même après destruction des clés du coffre ; cette frontière reste non validée.

## Livrables de conception supplémentaires

- [Format crypto et cycle des clés](CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md), issue #1.
- [Contrat DELETE-only serveur](DELETE-ONLY-SERVER-CONTRACT.md), issue #2.
- `VaultBinding` + vecteur AAD de 120 octets : encodage uniquement.
- 7 tests de modèle serveur ajoutés aux 25 tests de modèle panic, soit **32 tests Python**.
- Tests Kotlin de régression, stores simulés et contexte AAD intégrés à la CI Android existante.

Aucun APK de livraison, permission, receiver, backend ou flag production ajouté. Le build
technique de la CI existante reste nécessaire pour compiler/linter le lot ; son artefact de test
n'est pas une release distribuée. Consulter le run du SHA contenant ce document : un run de la
base ne valide pas ce lot. La conclusion du run final est également reportée dans les issues.
