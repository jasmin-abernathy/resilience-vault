# Resilience Vault — récupération après panic et suppression distante

Statut : décision de sécurité proposée le 24 septembre 2026. **Aucune récupération de production n'est encore implémentée ni validée.**

## Limite fondamentale

Le panic prévu détruit la capacité locale de lecture puis demande la suppression du coffre distant. Si **toutes** les copies des ciphertexts ou **toutes** les copies indépendantes des clés de récupération ont été détruites, la récupération cryptographique est impossible. Un serveur tombstoné ne doit jamais rouvrir la même génération sur présentation d'un ancien kit. Il faut donc distinguer explicitement :

1. **Panic avec récupération préparée** : la copie distante active est supprimée, mais une archive chiffrée indépendante et un kit R exporté hors appareil ont été vérifiés avant le panic. L'archive ne doit pas être accessible avec la capability DELETE de ce coffre.
2. **Effacement irréversible** : aucune archive indépendante utilisable n'est conservée, ou l'utilisateur détruit aussi les archives et tous les kits. L'app doit dire que la récupération est impossible, y compris pour son éditeur.

La copie originale dans un dossier SAF ou dans Signal/Telegram officiel n'est pas effacée automatiquement par Resilience Vault. Elle peut permettre un nouvel import, mais **ce n'est pas une restauration garantie du coffre** : l'utilisateur peut l'avoir perdue ou supprimée.

## Architecture minimale du mode récupérable

- Kit R secret explicitement exporté hors appareil, sur support contrôlé par l'utilisateur ; aucun envoi silencieux au cloud. Au moins une seconde copie du kit sur support distinct est recommandée. La perte ou compromission du kit doit être signalée clairement.
- Archive **chiffrée** hors du périmètre de la capability DELETE du coffre actif : conteneurs d'objets complets, enveloppes de récupération, manifeste authentifié par les clés du coffre et ancre de fraîcheur. Une simple liste d'objets ou un manifeste sans blobs ne suffit pas.
- Deux emplacements indépendants pour les archives, si l'utilisateur veut une résilience à la panne d'un support. Ne jamais présenter « deux répertoires sur le même disque » comme deux copies indépendantes.
- Vérification après fermeture des flux : compte et empreinte SHA-256 des conteneurs, chaînage et head du manifeste, tailles, identité/génération/époque, puis **restauration d'essai intégrale** dans un staging privé chiffré. Un simple test d'ouverture du kit ne prouve pas que tous les objets sont récupérables.
- Les archives chiffrées peuvent révéler taille, nombre, date et corrélation des objets. Leur accès doit être protégé indépendamment. Ne jamais exporter des keysets D/E/R en clair hors du kit conçu à cet effet.

## Préparation avant activation du panic distant

L'UI doit distinguer « suppression du cloud actif » et « effacement de toutes les copies ». Avant d'affirmer qu'un coffre restera récupérable, vérifier et enregistrer localement : kit R exporté et relu, archive(s) complètement écrite(s), restauration d'essai réussie sur le **head exact** à supprimer, et périmètre de suppression excluant ces archives. Si une copie a changé depuis l'essai ou si le head a avancé, la preuve expire ; une nouvelle sauvegarde et un nouvel essai sont requis.

Ne **pas** ajouter une condition de réseau ou de présence d'archive à la destruction locale urgente : elle doit pouvoir s'exécuter hors ligne. Si l'utilisateur n'a jamais préparé de récupération, afficher dès l'armement et au retour dans l'app que la suppression distante peut être irréversible. Une vérification de readiness ne doit pas permettre à un SMS distant d'élargir ses droits ni d'annuler un panic déjà admis.

## Restauration après panic

1. Sur une installation propre, importer volontairement le kit R et l'archive ; ne pas contourner le gate panic ni réactiver l'ancienne génération tombstonée.
2. Lire l'identité attendue depuis le kit et son ancre, puis vérifier les headers et AAD contre cette identité ; ne jamais croire un vaultId proposé par l'archive.
3. Vérifier manifeste, chaîne, séquence, hachages et tous les flux **avant** de publier un fichier à l'utilisateur. Le kit ancre un head passé et ne prouve pas l'absence d'un snapshot plus récent retenu par un adversaire.
4. Restaurer dans un nouveau coffre avec **nouveaux vaultId/génération/KEK/identifiants d'objets**. Rechiffrer ou rewrapper selon un protocole audité ; ne jamais réutiliser l'ancien scope DELETE ni permettre un upload vers une génération tombstonée.
5. Préserver le résultat de l'essai et les erreurs par objet sans plaintext dans les logs. Un export SAF partiel reste explicitement partiel et ne remplace pas les originaux silencieusement.
6. Après succès vérifié, demander à l'utilisateur quoi faire des anciennes archives et du kit R ; ne pas les supprimer automatiquement tant que le nouveau coffre et son nouveau kit ne sont pas testés.

## Tests à exiger

- Copie d'archive absente, incomplète, corrompue, déplacée ou appartenant à une autre génération : refus sans création d'une nouvelle clé de l'ancien coffre.
- Kit erroné, ancien kit après rotation, ancien head, objet manquant, segment final tronqué, mauvaise AAD et manifeste rollback : échec explicite sans plaintext exposé.
- Crash/stockage plein à chaque étape de l'import, du staging, du nouveau provisioning et de l'export SAF ; reprise sans réutilisation d'une ancienne génération et sans écrasement des originaux.
- Panic local hors ligne suivi de DELETE tardif ; archive indépendante conservée et archive placée par erreur sous le même scope DELETE : seul le premier cas peut être présenté comme récupérable.
- Tombstone serveur vérifié : aucune ancienne URL, capability ou sauvegarde serveur ne peut ressusciter la génération supprimée ; la récupération crée une génération neuve.
- Test sur appareil réel avec kit et archive **déconnectés du téléphone initial**, puis restauration intégrale sur un second appareil. Vérifier l'absence de keyset/plaintext sur disque et réseau par sentinelle.

## Implications pour le code actuel

`PanicRecoveryCoordinator` appelle toujours `deleteRemoteVault()` après la destruction locale ; ce code n'offre aucun mode récupérable à lui seul. `VaultProvisioningPolicy` et le journal préparé ne créent pas encore de coffre. Le futur adaptateur ne doit jamais interpréter un booléen « kit exporté » comme preuve suffisante : il doit lier la preuve d'essai au head exact et aux copies réellement indépendantes.

Ne pas lever `PRODUCTION_CRYPTO_READY` ni commercialiser une promesse de récupération avant un exercice complet de restauration sur appareil et la revue indépendante du pipeline.
