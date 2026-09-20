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

Le contact autorisé :
- ne peut ni armer, ni prolonger, ni réarmer le mode ;
- ne reçoit aucun accès au coffre ;
- peut uniquement présenter, pendant une fenêtre active, le secret requis pour demander le même panic que le bouton local.

Le secret SMS n'est pas considéré comme confidentiel après transmission : il doit être one-shot, consommé atomiquement et remplacé à chaque nouvel armement.

Le numéro expéditeur ne constitue pas à lui seul une preuve suffisante. L'audit doit considérer l'usurpation/rejeu et la combinaison numéro + secret.

Aucune permission SMS ne doit apparaître dans le build principal tant que le canal de distribution et la politique correspondante ne sont pas validés.
