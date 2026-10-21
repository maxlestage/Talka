# Signaler une faille de sécurité

Talka est une messagerie chiffrée de bout en bout. Si vous trouvez une
faille, merci de nous la signaler **en privé** avant toute publication.

## Comment

- Sur GitHub : onglet **Security** de ce dépôt, puis **Report a
  vulnerability** (signalement privé). Seuls les responsables du dépôt le
  voient.
- Merci de ne pas ouvrir d'issue publique, ni de publier de démonstration,
  avant qu'un correctif soit disponible.

Dites-nous ce que vous avez trouvé, comment le reproduire (versions,
étapes, code), et ce qu'un attaquant pourrait en faire.

## Ce qui nous intéresse

Tout ce qui permettrait de :

- lire un message, un aperçu de notification, une pièce jointe, ou la
  mémoire chiffrée d'un appareil sans en avoir le droit ;
- se faire passer pour quelqu'un (code, clés, numéro de sécurité, groupe) ;
- savoir qui écrit à qui au-delà de ce que décrit
  [docs/SECURITE.md](docs/SECURITE.md) ;
- faire tomber le relais, vider des boîtes aux lettres, ou épuiser ses
  ressources ;
- exécuter du code, ou charger un contenu distant, dans l'application.

Hors sujet : ce qui demande un appareil déjà déverrouillé et compromis
(logiciel espion, accès administrateur), et les limites déjà décrites dans
[docs/SECURITE.md §8](docs/SECURITE.md).

## Ce que nous faisons

- Un accusé de réception sous 7 jours.
- Une première évaluation sous 14 jours.
- Un correctif dès que possible, publié avec une note qui vous remercie
  (si vous le souhaitez).

Le protocole, le modèle de menace et les vecteurs de test sont publics :
[docs/SECURITE.md](docs/SECURITE.md) et, pour un audit,
[docs/AUDIT.md](docs/AUDIT.md).
