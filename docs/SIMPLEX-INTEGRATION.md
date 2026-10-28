# SimpleX Chat — sauvegarde et effacement coopératif (préparation)

Statut : **connecteur d'export manuel implémenté ; sauvegarde distante et effacement de SimpleX NON activés**.
Ce document n'est pas une promesse de sécurité en production.

## Ce que RV peut faire aujourd'hui

1. Dans SimpleX : Réglages > « Phrase secrète et export de la base ».
2. Arrêter le chat, définir une phrase secrète de base connue et conservée séparément, puis **Exporter la base**.
3. Choisir le fichier ZIP exporté dans RV, via Android `ACTION_OPEN_DOCUMENT` (lecture seule).
4. RV conserve uniquement l'URI et son autorisation SAF. Le connecteur teste l'accès et la signature de début ZIP, **sans l'extraire ni la transmettre**.
5. Supprimer la sélection dans RV révoque son autorisation SAF lorsque possible ; cela **n'efface pas** le ZIP original.

La validation de l'entête ZIP ne prouve **ni** l'origine SimpleX, **ni** le chiffrement intégral de l'archive (médias inclus), **ni** sa restaurabilité. Le ZIP source reste potentiellement sensible et hors du périmètre du panic RV. La phrase secrète SimpleX n'est jamais demandée par RV. L'utilisateur doit gérer et supprimer prudemment les copies manuelles.

Source officielle : https://simplex.chat/docs/guide/chat-profiles.html
Gestion des données : https://simplex.chat/docs/guide/managing-data.html

## Architecture cible, non implémentée

```
SimpleX officiel -- export explicite ZIP --> URI SAF lecture seule
             --> validation source --> chiffrement RV audité --> archive distante
                                                        |
                                   kit de récupération hors téléphone
                                                        |
                                    restauration testée sur autre appareil

Panic RV --- consentement temporaire + commande authentifiée ---
         --> API d'effacement coopérative *dans* SimpleX (à définir avec SimpleX)
         --> effacement de la base et des médias SimpleX, avec résultat vérifiable
```

**Attention à la contradiction de rétention :** le panic RV peut supprimer le coffre RV distant (DELETE-only). Si l'on veut récupérer SimpleX *après* ce panic, sa copie chiffrée doit vivre dans **un périmètre de récupération séparé**, qui ne sera pas supprimé par ce DELETE ; la clé de récupération ne doit jamais être uniquement sur l'appareil. Ne pas activer ce flux avant consentement explicite à ce compromis. Sans séparation, « supprimer et restaurer » est une promesse fausse.

La sauvegarde doit :
- chiffrer les octets exportés avant réseau avec les primitives Tink auditées ; aucune confiance accordée au chiffrement interne éventuel du ZIP ;
- inclure les médias réellement exportés, un manifeste authentifié et un horodatage utile, sans présumer que les derniers messages sont présents ;
- être considérée fiable seulement après commit du manifeste **et** un test réel de restauration avec la phrase secrète SimpleX et le kit de récupération ;
- utiliser une copie de récupération séparée des données effacées par le panic ;
- ne pas importer deux fois le même état SimpleX sur deux appareils actifs : des connexions peuvent être perturbées (cf. guide SimpleX).

## Effacement de l'application SimpleX officielle

RV n'a pas accès aux données privées de `chat.simplex.app` et ne peut pas la désinstaller silencieusement sur Android ordinaire. Aucun AccessibilityService, root, ADB, gestionnaire d'appareil, permission globale de stockage ou receiver exporté n'est autorisé pour contourner le sandbox.

L'application SimpleX sait supprimer sa propre base et ses médias par son interface. Une automatisation exige une **coopération explicite et auditée de SimpleX** (API interne dédiée, action authentifiée/consentie, anti-rejeu, accusé de résultat, interopérabilité vérifiée). **Un message reçu via SimpleX, un SMS ou un lien ne constitue jamais à lui seul une autorisation d'effacement**.

`SimplexErasePreflight` exprime des conditions nécessaires à une future revue, mais il n'existe aucun moteur de suppression dans RV : aucun de ses booléens ne déclenche une action. Il faudra distinguer effacement logique, suppression des médias, révocation des sessions, désinstallation Android avec UI, confirmation partielle, crash/réexécution et limites des copies ailleurs.

## Tests et revue avant activation

- Validation ZIP : extension, signature, fichier tronqué, fournisseur SAF inaccessible, permission révoquée.
- Vérifier réellement import SimpleX et conservation des profils/médias ; mot de passe perdu => récupération impossible.
- Sauvegarde en streaming chiffré avec arrêt du processus à chaque frontière, rotation, Android Keystore, réseau hors ligne.
- Test du panic RV et de la **survie** de la copie de récupération séparée.
- Effacement coopératif : autorisation temporaire, révocation, expiration/reboot, demande falsifiée/rejouée, double demande, concurrente, hors-ligne, appareil éteint ; aucun résultat « réussi » sans preuve côté SimpleX.
- Audit indépendant + tests sur appareil avant exposition d'un bouton destructif.

## État et interdictions

Aucune API d'effacement SimpleX officielle vérifiée ; aucun transport/receveur ajouté ; aucune tâche réseau ; aucun effacement automatique ; aucune build de production annoncée.
