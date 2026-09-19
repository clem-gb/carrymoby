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
| Attraper / reposer le mob visé | touche **C** (remappable dans Options → Commandes) |

Vise un mob et appuie sur **C**. Le viseur est tolérant : si le rayon rate, le mod prend le mob
carryable le plus proche du centre de l'écran dans un cône de 30°, jusqu'à 4,5 blocs.

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
| `keepOnDeath` | `true` | `false` fait tomber le mob à l'endroit de la mort |
| `slownessFactor` | `0.0` | malus de vitesse, `0.15` = -15 % |
| `blacklist` | dragon, wither, warden | mobs interdits quoi qu'il arrive |

Les joueurs ne sont jamais transportables.

## Installation

1. Installe **Fabric Loader ≥ 0.19.5** pour Minecraft 1.21.11.
2. Mets **Fabric API** dans `mods/`.
3. Mets `carrymoby-1.0.0.jar` dans `mods/`.

Le mod doit être présent **côté serveur**. Côté client il est nécessaire pour la touche et
l'affichage du mob ; un client sans le mod peut toujours se connecter, il ne verra simplement
rien sur les épaules.

## Développement

```bash
./gradlew build            # produit build/libs/carrymoby-1.0.0.jar
./gradlew runClient        # client de dev
./gradlew runServer        # serveur de dev
./gradlew runClientGameTest # test automatisé : ramasse, meurt, change de dimension, repose
```

Le test client (`src/gametest`) joue le scénario complet et écrit des captures dans
`build/run/clientGameTest/screenshots/`.
