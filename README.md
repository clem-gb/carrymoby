# CarryMoby

Mod Fabric pour **Minecraft 1.21.11** : attrape un mob, pose-le sur ton épaule, et il y reste
**quoi qu'il arrive** — téléportation, changement de dimension, chute, vol, déconnexion,
redémarrage du serveur, et même ta propre mort.

## Comment ça marche

Le mob n'est pas un passager ni une entité posée sur toi : au ramassage il est **sérialisé en NBT
dans les données de sauvegarde du joueur**, puis supprimé du monde. C'est pour ça que rien ne peut
le faire tomber : il n'existe plus en tant qu'entité tant que tu le portes. Le client reçoit ce NBT
et reconstruit une copie purement graphique pour l'afficher sur l'épaule, avec sa bonne variante,
son âge, son collier, son nom, etc.

Quand tu le reposes, l'entité est recréée à l'identique depuis ce NBT : même UUID, même santé,
même inventaire, même amour de villageois.

## Utilisation

| Action | Par défaut |
| --- | --- |
| Attraper / reposer le mob visé | touche **C** (remappable dans Options → Commandes, catégorie **CarryMoby**) |

Vise un mob et appuie sur **C**. Il faut le viser comme pour le frapper : le rayon s'arrête aux
blocs et porte à 4,5 blocs.

Appuie à nouveau sur **C** pour le reposer devant toi (ou à tes pieds si l'endroit est occupé).

### Commandes

- `/carrymoby info` — ce que tu portes
- `/carrymoby drop` — lâche ton mob (dépannage)
- `/carrymoby drop <joueurs>` — lâche celui d'autres joueurs (opérateur)
- `/carrymoby reload` — recharge la configuration (opérateur)

## Configuration

`config/carrymoby.json`, créé au premier lancement :

| Clé | Défaut | Effet |
| --- | --- | --- |
| `allowHostileMobs` | `false` | autoriser zombies, creepers et compagnie |
| `maxWidth` | `1.0` | largeur de hitbox maximale, en blocs |
| `maxHeight` | `1.5` | hauteur de hitbox maximale, en blocs |
| `pickupRange` | `4.5` | portée de ramassage, en blocs |
| `allowCarryingOthersPets` | `false` | autoriser à prendre les animaux apprivoisés d'un autre joueur |
| `keepOnDeath` | `true` | `false` fait tomber le mob à l'endroit de la mort |
| `slownessFactor` | `0.0` | malus de vitesse, `0.15` = -15 % |
| `blacklist` | dragon, wither, warden | mobs interdits quoi qu'il arrive |

Les joueurs ne sont jamais transportables. Ne le sont pas non plus : les mobs invulnérables
(PNJ de serveur, marchands de map aventure), un mob tenu en laisse ou chevauché par un autre joueur,
et un villageois en pleine négociation.

## Installation

1. Installe **Fabric Loader ≥ 0.19.5** pour Minecraft 1.21.11.
2. Mets **Fabric API** dans `mods/`.
3. Mets `carrymoby-1.1.0.jar` dans `mods/`.

Le mod doit être présent **côté serveur**. Côté client il est nécessaire pour la touche et
l'affichage du mob ; un client sans le mod peut toujours se connecter, il ne verra simplement
rien sur les épaules.

## Développement

```bash
./gradlew build            # produit build/libs/carrymoby-1.1.0.jar
./gradlew runClient        # client de dev
./gradlew runServer        # serveur de dev
./gradlew runGameTest       # tests serveur, sans écran : une règle par test
./gradlew runClientGameTest # test client de bout en bout : rate, ramasse, meurt, change de dimension, repose
```

Les tests serveur (`CarryServerGameTest`) vérifient chaque règle en passant par le même point
d'entrée que la touche, donc ce qu'un client modifié pourrait tenter : pas de ramassage à travers
un mur, hors de portée ou sans viser, pas d'animal d'un autre joueur, pas de mob en laisse ou
chevauché par quelqu'un d'autre, rien en spectateur, menu ouvert ou mort, limite de fréquence,
pas de pose à travers un mur, laisse rendue, données conservées et sauvegardées, et rien de
privé (coordonnées, mémoire) envoyé aux autres joueurs.

Le test client (`CarryClientGameTest`) joue le scénario complet avec la vraie touche et vérifie
aussi ce que le client affiche. Il écrit des captures dans `build/run/clientGameTest/screenshots/`
(sans écran : `xvfb-run ./gradlew runClientGameTest`).

## Licence

MIT — voir [LICENSE](LICENSE).

## Origine

Ce mod est **écrit à 100 % par une IA** (Claude Opus 5.5, via Claude Code), à partir d'un seul
prompt de départ : un mod pour porter des mobs, qui restent sur le joueur quoi qu'il arrive
(téléportation, mort, vol), en Fabric — avec quatre questions posées en retour pour cadrer la
version de Minecraft, les mobs autorisés, le comportement à la mort et les contrôles.

Le reste s'est fait tout seul : APIs vérifiées dans les sources décompilées de 1.21.11, test
client automatisé écrit et joué en vrai, ciblage en cône ajouté après avoir constaté que viser
un poulet au rayon pur était pénible (retiré ensuite : on vise un mob comme en vanilla).

**Un seul bug a survécu jusqu'au joueur** : le mob porté avait la tête vissée à l'envers. Dans
le render state, `yRot` est le lacet de la tête *relatif au corps*, pas une rotation absolue ;
le mettre à 180 comme le `bodyRot` faisait faire un demi-tour de trop à la tête. Repéré par
l'humain sur capture d'écran, corrigé en un commit (`48b698f`).
