# Resilience Vault — inventaire de destruction et couverture

Statut : préparation GPT-5.6, **aucune suppression de production nouvellement activée**.

| Capacité / copie | État code au 24/09 | Panic réel | Test restant avant activation |
| --- | --- | --- | --- |
| KEK de lecture Android | `AndroidVaultKek` sait créer/charger/supprimer un alias lié au journal | Non raccordé | appareil API 26–29 et 30+, reboot, verrouillage, suppression/erreur |
| Alias de rotation KEK | registre candidat peut conserver plusieurs aliases | Non raccordé | transaction ajout/suppression + crash entre les deux aliases, revue GPT-6 |
| Keyset époque E | spécifié seulement | aucun | sérialisation Tink chiffrée + AAD + restauration |
| Keysets objet D | spécifiés seulement | aucun | streaming réel, coupures, bad tag, autre coffre |
| Kit R | design seulement, doit rester hors téléphone après export | hors panic local | import volontaire + archive indépendante + essai intégral |
| Staging privé | non implémenté | aucun | chiffrement au repos + sentinelle plaintext + purge |
| Credential lecture/upload cloud | non implémenté | aucun | révocation séparée et comportement hors ligne |
| Capability DELETE + KEK dédiée | contrat serveur seulement | aucun | backend réel, tombstone/purge, reboot, auth bloquée |
| État TDLib Telegram | port préparé, natif désactivé | aucun | audit de session/révocation ; ne pas confondre avec destruction E |
| Leases READ/EXPORT/SYNC/RESTORE | `VaultAccessLeaseManager` implémenté | drain abstrait prêt | raccorder aux vrais handles crypto et tester interruption |

## Registre candidat

`VaultSecurityRegistryRecord` est une **brique de préparation** indépendante des keysets. Elle
porte l’identité du coffre/génération, une révision monotone et la liste canonique de tous les
aliases Keystore connus. Le store Android est dans `noBackupFilesDir`, sans méthode reset/delete.

Il n’est volontairement raccordé ni à `AndroidVaultKek`, ni au provisioning, ni au panic.
Avant raccordement, GPT-6 doit revoir : ordre transactionnel journal ↔ registre ↔ Keystore,
migration d’un appareil déjà provisionné, règle de retrait d’un ancien alias après rotation,
et comportement récupération/nouvelle génération.

## Limite à ne pas masquer

Un hash local détecte une corruption accidentelle mais ne prouve pas la fraîcheur contre un
attaquant capable de restaurer un ancien fichier privé. Le registre ne remplace donc ni le
journal, ni les AAD Tink, ni le head de manifeste, ni le tombstone serveur.
