# Contrats exécutables de revue

Ces fichiers ne sont pas importés par Android. Ils permettent de vérifier les décisions des spécifications avant portage, sans supprimer de fichier, appeler le réseau ou utiliser de secret réel.

Python 3.11+ ; le test de primitive utilise `cryptography==46.0.0` (environnement de revue), aucune dépendance ajoutée à Android.

```bash
python3 -m unittest discover -s security_review/tests -p 'test_contracts.py' -v
python3 -m unittest discover -s security_review/tests -p 'test_aead_vectors.py' -v
```

Les clés/IV de tests sont des fixtures publiques constantes, jamais des valeurs pour production. Le vecteur RVLT est déterministe ; les vecteurs AES-GCM portent sur la primitive, pas sur les ciphertexts Tink. L'interopérabilité Tink reste à tester dans le futur adaptateur Java/Kotlin.

Limites : le modèle suppose un verrou partagé et des effets persistants atomiques. Il ne prouve pas la concurrence réelle, les garanties fsync/Keystore, le nettoyage mémoire, le worker Android, le backend HTTP ou TDLib. Les erreurs à ces frontières doivent être injectées à nouveau dans l'implémentation. Aucun appel destructif réel n'est ajouté dans cette passe.
