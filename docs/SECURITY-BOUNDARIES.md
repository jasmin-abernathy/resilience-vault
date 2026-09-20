# Frontières de sécurité

## Périmètre possible

Après audit, Resilience Vault pourra détruire ses clés, ses fichiers privés, sa session TDLib et son coffre distant. Les suppressions dans des arbres SAF nécessitent les droits Android correspondants et une activation explicite.

## Hors périmètre

Une application Android ordinaire ne peut pas arbitrairement lire/effacer la base privée de Signal officiel ou Telegram officiel, désinstaller silencieusement d’autres apps, garantir l’écrasement physique de la flash ou supprimer le cloud sans réseau.

## Panic hors ligne

La destruction locale doit précéder le réseau. Si DELETE cloud échoue, seul un credential à capacité DELETE minimale peut rester pour le retry ; jamais une clé de lecture.

## Telegram

La session TDLib du coffre est distincte du client Telegram officiel. Le panic ne supprime pas les messages du compte Telegram par défaut. Les Secret Chats présents uniquement dans un autre client ne sont pas récupérés par une nouvelle session.

## Contact de confiance / SMS

Le déclenchement distant est une capacité optionnelle et temporaire, désactivée par défaut.

L'utilisateur peut autoriser **de 1 à 5 contacts** pendant une même fenêtre d'armement temporaire.

Chaque contact autorisé :
- ne peut ni armer, ni prolonger, ni réarmer le mode ;
- ne reçoit aucun accès au coffre ;
- dispose de son **propre secret one-shot** ;
- peut uniquement présenter, pendant la fenêtre active, son secret pour demander le même panic que le bouton local.

Dès qu'un des contacts déclenche valablement le panic, **l'armement entier est consommé** : tous les secrets des autres contacts deviennent immédiatement invalides.

Un secret SMS n'est pas considéré comme confidentiel après transmission : chaque secret doit être one-shot, consommé atomiquement, distinct par contact et remplacé à chaque nouvel armement.

Le numéro expéditeur ne constitue pas à lui seul une preuve suffisante. L'audit doit considérer l'usurpation/rejeu et la combinaison numéro + secret.

Aucune permission SMS ne doit apparaître dans le build principal tant que le canal de distribution et la politique correspondante ne sont pas validés.
