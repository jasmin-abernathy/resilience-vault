# Resilience Vault — matrice de validation Android / matériel

Cette matrice sépare explicitement ce que les tests JVM peuvent prouver de ce qui exige Android
instrumenté, émulateur ou téléphone physique. **Aucun test JVM ne valide Keystore/StrongBox.**

| Sujet | JVM | Instrumenté / émulateur | Téléphone physique | Critère de sortie |
| --- | --- | --- | --- | --- |
| AtomicFile : write/relire | fake IO / transitions | vraie API, kill avant/après finishWrite | oui | état ancien ou nouveau, jamais succès inventé |
| AtomicFile : stockage plein | fake erreur | quota/disque simulé si possible | oui | store fail-closed, aucune recréation |
| AtomicFile : reboot | non | snapshot/restart partiel | **obligatoire** | état repris sans régénérer de clé |
| Keystore API 26–29 | non | émulateur API ciblée utile | **obligatoire au moins un appareil** | create/load/delete conformes, erreurs distinguées de l’absence |
| Keystore API 30+ | non | émulateur utile | **obligatoire** | mêmes garanties + comportement verrouillage |
| Appareil verrouillé | non | partiel | **obligatoire** | auth/accès conforme au contrat, pas de fallback |
| Authentification locale | non | partiel | **obligatoire** | échec explicite, aucune clé claire de secours |
| Alias absent | modèle seulement | oui | oui | absent confirmé ≠ erreur Keystore |
| Rotation 2 aliases | modèle registre | oui | **obligatoire** | ancien alias conservé jusqu’au commit sûr, puis suppression vérifiée |
| Suppression alias échouée | fake exception | oui | **obligatoire** | panic reste incomplet / retryable, jamais “supprimé” |
| Crash pendant provisioning | JVM journal | oui | oui | aucune recréation implicite sous même identité |
| Panic hors ligne | JVM coordinator | oui | **obligatoire** | clés locales détruites avant réseau ; retry DELETE séparé |
| Récupération second appareil | politique pure | staging test | **obligatoire** | nouveau vaultId/génération, aucune résurrection tombstonée |
| Sentinelle plaintext/keyset | scans unitaires possibles | inspection fichiers/logcat/réseau | **obligatoire** | sentinelle absente des surfaces persistantes et réseau |

## StrongBox

`SecretKey.getEncoded() == null` signifie seulement que la clé n’est pas exportée via cette API.
Cela **ne prouve pas** que la clé est stockée dans StrongBox ni même dans un composant matériel.
La présence et l’usage réel de StrongBox doivent être interrogés via les API Android appropriées,
avec comportement de repli documenté sans fausse promesse matérielle.

## Journal de test

Pour chaque test matériel, conserver : modèle appareil, version Android/API, SHA exact testé,
état verrouillé/déverrouillé, résultat attendu/observé et logs expurgés. Ne jamais joindre de
clé, kit R, token cloud, phrase SMS ou données utilisateur réelles.
