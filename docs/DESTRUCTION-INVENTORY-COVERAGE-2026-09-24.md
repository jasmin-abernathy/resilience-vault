# Resilience Vault — inventaire de destruction et couverture

Statut : préparation GPT-5.6, **aucune suppression de production nouvellement activée**.

| Capacité / copie | État code au 24/09 | Panic réel | Test restant avant activation |
| --- | --- | --- | --- |
| KEK de lecture Android | `AndroidVaultKek` sait créer/charger/supprimer un alias lié au journal | Non raccordé | API 26–29 et 30+, reboot, verrouillage, erreur de suppression |
| Alias de rotation KEK | registre candidat conserve plusieurs aliases | Non raccordé | crash entre ajout / rewrap / retrait ; erreur Keystore ≠ absence |
| Keyset époque E | spécifié seulement | aucun | enveloppe Tink authentifiée, rotation, corruption, restauration |
| Keysets objet D | spécifiés seulement | aucun | streaming réel, segment tronqué, bad tag, objet autre coffre |
| Handles E/D en RAM | leases abstraites disponibles | drain abstrait prêt | raccorder vrais handles, fermeture/cancellation, zéroïsation best effort |
| Manifeste/index/inventaire local | partiellement spécifié | aucun | chiffrer sous capacité couverte par panic, rollback/head |
| Staging privé | non implémenté | aucun | chiffrement au repos, sentinelle plaintext, purge après destruction |
| Credential lecture/upload cloud | non implémenté | aucun | retrait local + révocation serveur séparée, reboot/hors ligne |
| Capability DELETE + KEK dédiée | contrat serveur seulement | aucun | tombstone/purge, 401/403/429/5xx, reboot, auth bloquée |
| Kit R exporté hors téléphone | contrat/design seulement | volontairement hors panic local | import volontaire, rotation, second support, restauration intégrale |
| Archive chiffrée indépendante | contrat/design seulement | volontairement hors DELETE actif | complétude, binding, scope indépendant, corruption, essai intégral |
| État TDLib Telegram | port préparé, natif désactivé | aucun | audit session/révocation ; distinct des clés E/D |
| Leases READ/EXPORT/SYNC/RESTORE | `VaultAccessLeaseManager` implémenté | drain abstrait prêt | raccorder vrais handles et tester interruption |
| Originaux SAF / sauvegarde Signal / données Telegram officielles | hors coffre Vault | **hors pouvoir d’effacement de l’app** | UI/documentation doivent l’indiquer sans ambiguïté |
| Exports manuels, captures, presse-papiers, autres apps | hors contrôle Vault | hors panic | ne jamais promettre leur effacement |

## Règles de destruction

1. Fermer l’admission et drainer les leases avant destruction des capacités locales de lecture.
2. Parcourir l’inventaire durable de **tous** les aliases actifs et en rotation.
3. Une exception Keystore ou un résultat incertain ne vaut jamais “alias absent”.
4. Détruire/rendre inaccessibles les clés locales avant toute dépendance réseau.
5. Purger ensuite staging, index et métadonnées locales couvertes.
6. Retirer/révoquer les credentials de lecture/upload séparément.
7. Conserver seulement la capability DELETE dédiée nécessaire aux retries post-panic, sans droit
   de lecture, puis la supprimer selon le contrat après preuve de purge.
8. Les originaux choisis via SAF, les données Signal/Telegram et les copies externes restent hors
   de cette garantie d’effacement ; ils peuvent exiger une action utilisateur distincte.

## Registre candidat

`VaultSecurityRegistryRecord` reste une brique indépendante des keysets. Il porte vault/génération,
révision monotone et liste canonique des aliases. Il n’est volontairement raccordé ni au
provisioning ni au panic avant revue GPT-6 de la transaction, de la migration et de la rotation.

## Limite anti-rollback

Le hash local du registre détecte la corruption accidentelle, pas le rollback hostile. Il ne
remplace ni les AAD Tink, ni l’ancre de head, ni le tombstone serveur.
