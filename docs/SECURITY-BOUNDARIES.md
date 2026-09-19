# Frontières de sécurité

## Périmètre possible

Après audit, Resilience Vault pourra détruire ses clés, ses fichiers privés, sa session TDLib et son coffre distant. Les suppressions dans des arbres SAF nécessitent les droits Android correspondants et une activation explicite.

## Hors périmètre

Une application Android ordinaire ne peut pas arbitrairement lire/effacer la base privée de Signal officiel ou Telegram officiel, désinstaller silencieusement d’autres apps, garantir l’écrasement physique de la flash ou supprimer le cloud sans réseau.

## Panic hors ligne

La destruction locale doit précéder le réseau. Si DELETE cloud échoue, seul un credential à capacité DELETE minimale peut rester pour le retry ; jamais une clé de lecture.

## Telegram

La session TDLib du coffre est distincte du client Telegram officiel. Le panic ne supprime pas les messages du compte Telegram par défaut. Les Secret Chats présents uniquement dans un autre client ne sont pas récupérés par une nouvelle session.
