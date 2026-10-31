# Phase 0 — Spécification de la couche sociale Matrix

> Statut : décisions de conception verrouillées pour le prototype.
> Dernière révision : 2026-10-30.
> Périmètre : modèle de données, salles/fils, audiences, timecodes, permissions,
> notifications et contrat UI. Aucun code de production dans ce document.

Documents liés :

- [Wireframes et interactions](PHASE-0-WIREFRAMES.md)
- [Critères d'acceptation](PHASE-0-ACCEPTANCE.md)
- [JSON Schema `org.shiro.moment`](schema/org.shiro.moment.v1.schema.json)
- [Exemple d'événement Matrix](schema/example-message.json)

## 1. Décisions verrouillées

| Sujet | Décision |
|---|---|
| Granularité | Un fil par **auteur + œuvre + épisode** (modèle A). |
| Moment | Une **ancre temporelle** dans le fil, pas un fil séparé. |
| Salle | Une salle par **auteur + œuvre**. |
| Audience | Amis de **1er degré** uniquement. Pas d'amis d'amis. |
| Invitation | Tous les amis directs sont invités ; ceux qui n'ont pas l'œuvre sont invités à l'ajouter. |
| Rejoindre | Invitation → join → réponse. Pas de knock en v1. |
| Réponse | `Répondre ici` reste dans l'audience de l'auteur du fil. |
| Publication | `Publier à mes amis` crée/alimente le fil de l'utilisateur. |
| Multi-auteurs | Chaque auteur a son propre fil et sa propre audience ; les fils sont regroupés visuellement par moment. |
| Expiration | Aucune expiration native. L'historique reste consultable. |
| Privé | Aucun message privé dans le fil auteur. Le privé se fera en DM séparé, plus tard. |
| Boucle | 30 s autour du moment : **15 s avant / 15 s après**. |
| Son | Coupé par défaut dans la boucle ; bouton mute/unmute. |
| Pause | Mettre la boucle en pause ramène à l'affiche de l'œuvre. |
| Timecode | Les messages peuvent porter un ou plusieurs timecodes cliquables, façon YouTube. |
| Message important | Un message ancré peut être épinglé comme « moment clé ». Jusqu'à 3 pins par fil. |
| Épinglage | Auteur du fil uniquement, via power level. |
| Orientation | Mode paysage exclusif pour l'overlay. |
| E2EE | Chiffrement de bout en bout dès la création des salles. |
| Push | **UnifiedPush + ntfy obligatoire pour l'instantané** ; FCM rejeté. |
| Homeserver | Homeserver Matrix existant, configurable ; pas de backend Shiro. |

## 2. Identité canonique

Deux amis peuvent avoir la même œuvre depuis des sources différentes, avec des
identifiants locaux différents. L'identité doit être indépendante de la base
locale.

### 2.1 `workId` et table d'alias

Ordre de préférence pour l'identifiant principal :

1. `anilist:<id>` ;
2. `mal:<id>` ;
3. `anidb:<id>` ou `tvdb:<id>` ;
4. `tg:<chatId>:<slug>` pour Telegram ;
5. `local:<hash>` pour les fichiers locaux.

Champs :

```text
work.id       string                 identifiant principal de l'expéditeur
work.ids      map<string,string>     tous les alias connus
work.title    string?                affichage
work.format   string?                TV, MOVIE, OVA, ONA, SPECIAL, OTHER
work.year     int?
```

Sources des identifiants canoniques :

- les enregistrements de tracking AniList/MAL, s'ils existent ;
- les métadonnées de reconnaissance Telegram (`TelegramWorkOverrides`), qui
  peuvent porter un `anilistId`/provider ID ;
- à défaut, un fallback **spécifique à la source** :
  `local:<hash(sourceId | animeUrl)>`, qui ne permet pas de regroupement
  inter-sources. C'est volontaire : pas de fausse fusion.

`Anime` ne contient pas directement d'ID AniList/MAL : il faut passer par le
tracker ou les métadonnées de reconnaissance. Sans identifiant commun, les
moments restent séparés.

Résolution locale :

- table `work_alias(scheme, remote_id) → local_work_id` ;
- on cherche une correspondance exacte dans `work.ids` ;
- si aucune correspondance n'existe, on crée une œuvre locale et on enregistre
  les alias connus ;
- **jamais de correspondance par titre, année ou durée** ;
- si aucun identifiant commun n'est résoluble, les moments restent séparés :
  *fail-closed*, pas de fusion approximative.

Ainsi, un événement `anilist:154587` se rattache chez un destinataire qui ne
connaît que `mal:52991` si l'alias local existe.

### 2.2 Identité Telegram et locale

- Telegram : `tg:<chatId>:<slug>` ; stable tant que le canal et le slug ne changent
  pas. L'épisode est dérivé de `(season, number)`.
- Local : `local:<sha256(nom normalisé + taille)>` tronqué. Ce n'est pas garanti
  stable après renommage/déplacement. Les moments locaux sont traités comme
  approximatifs sur un autre appareil.
- Une œuvre Telegram sans AniList/MAL reste identifiable si les deux amis sont
  dans le même canal et partagent le même slug. Sinon, elle reste séparée.

### 2.3 `episodeKey`

Forme canonique stockée :

```text
S<season 2 chiffres>E<épisode 3-4 chiffres>[.décimal]
S00E001              spécial
MAIN                 film ou OVA avec sa propre entrée
```

Exemples : `S01E001`, `S01E003`, `S01E003.5`, `S02E012`, `S00E001`, `MAIN`.

Champs :

```text
episode.key        string
episode.season     int?
episode.number     double?
episode.kind       regular | special | film | ova | recap | unknown
episode.numbering  season | absolute | unknown
episode.title      string?
```

Règles :

- l'épisode est une entrée de catalogue, pas un fichier ;
- un film/OVA avec sa propre entrée AniList/MAL est une œuvre distincte avec
  `episode.key = "MAIN"` ;
- un spécial rattaché à la même entrée utilise `S00E001` ;
- un épisode sans numéro fiable utilise `S00E000` ou `numbering = unknown` ;
- l'UI affiche `S1E1`, le stockage garde `S01E001`.

### 2.4 `sourceKey`, position et durée

```text
source.key        string     hash/identifiant stable de la version
source.kind       stream | download | local | telegram
source.lang       string?    BCP-47
source.durationMs long?      durée de la version, si connue
positionMs        long       position du moment
beforeMs          int        défaut 15000
afterMs           int        défaut 15000
```

`sourceKey` :

- HTTP : `sha256(sourceId | animeUrl normalisée | episodeUrl normalisée)` ;
- Telegram : `tg:<chatId>:<messageId>` ou clé de fichier stable ;
- local : `local:<hash(uri)>`.

Le message ne stocke pas d'URL brute.

### 2.5 Dérive entre sources

Le mode est calculé au rendu, pas stocké dans le message :

| Cas | Mode | Rendu |
|---|---|---|
| Même `sourceKey` | exact | seek exact. |
| Sources différentes, écart de durée ≤ 2 s | exact | seek exact, confiance forte. |
| Sources différentes, écart > 2 s | approximatif | heure d'origine + badge `≈`. |
| Durée inconnue | approximatif | heure d'origine + badge `≈`. |

- **Pas de recalage automatique par ratio de durée en v1.** Un générique retiré
  ou un montage différent rendrait la mise à l'échelle fausse.
- Recalage manuel local possible : `source_offset(local_work_id, episode_key,
  source_key, offset_ms)`, appliqué si l'offset reste raisonnable (≤ 120 s).
- L'offset n'est pas fédéré.
- Un moment approximatif ne déclenche pas de seek silencieux vers un mauvais
  passage : l'utilisateur voit le badge et peut confirmer.
- La fenêtre de regroupement de 30 s absorbe les dérives de quelques secondes.

## 3. Schéma Matrix `org.shiro.moment` v1

Le schéma machine est dans
[schema/org.shiro.moment.v1.schema.json](schema/org.shiro.moment.v1.schema.json).
Le payload est porté par un `m.room.message` `m.text` ; les clients Matrix qui
ne connaissent pas le champ personnalisé affichent le `body`.

### 3.1 Exemple complet

```json
{
  "type": "m.room.message",
  "room_id": "!aBcD:matrix.org",
  "sender": "@alice:matrix.org",
  "event_id": "$moment1",
  "origin_server_ts": 1793389331000,
  "content": {
    "msgtype": "m.text",
    "body": "▶ 12:34, 18:02 — Ce plan-là, et la musique juste après. (Sousou no Frieren · S01E003)",
    "m.relates_to": {
      "rel_type": "m.thread",
      "event_id": "$threadRoot",
      "is_falling_back": true,
      "m.in_reply_to": { "event_id": "$threadRoot" }
    },
    "org.shiro.moment": {
      "v": 1,
      "work": {
        "id": "anilist:154587",
        "ids": {
          "anilist": "154587",
          "mal": "52991",
          "anidb": "17617"
        },
        "title": "Sousou no Frieren",
        "format": "TV",
        "year": 2023
      },
      "episode": {
        "key": "S01E003",
        "number": 3.0,
        "season": 1,
        "kind": "regular",
        "numbering": "season",
        "title": "Le pays des mages"
      },
      "moments": [
        { "positionMs": 754000 },
        { "positionMs": 1082000, "label": "La musique.", "lang": "fr" }
      ],
      "note": "Ce plan-là, et la musique juste après.",
      "source": {
        "key": "aniyomi:123456:fr",
        "kind": "stream",
        "lang": "fr",
        "durationMs": 1440000
      },
      "capturedAt": "2026-10-30T19:42:11Z",
      "spoiler": false,
      "client": { "name": "Shiro", "version": "0.18.2.1" }
    }
  }
}
```

### 3.2 Champs

| Champ | Type | Requis | Règle |
|---|---|:---:|---|
| `v` | int | oui | `= 1` ; version inconnue → fallback `body`. |
| `work.id` | string | oui | `anilist:`, `mal:`, `anidb:`, `tvdb:`, `tg:`, `local:`. |
| `work.ids` | map | non | Alias connus ; `ids[scheme]` doit correspondre à `work.id`. |
| `work.title` | string | non | ≤ 200 caractères. |
| `work.format` | string | non | `TV`, `MOVIE`, `OVA`, `ONA`, `SPECIAL`, `OTHER`. |
| `work.year` | int | non | 1900–2200. |
| `episode.key` | string | oui | `S01E003`, `S00E001`, `MAIN`. |
| `episode.number` | double | non | ≥ 0 ; décimales autorisées. |
| `episode.season` | int | non | ≥ 0 ; `0` = spécial. |
| `episode.kind` | string | non | `regular`, `special`, `film`, `ova`, `recap`, `unknown`. |
| `episode.numbering` | string | non | `season`, `absolute`, `unknown`. |
| `episode.title` | string | non | ≤ 200 caractères. |
| `moments` | array | oui | 1 à 5 entrées, triées par position. |
| `moments[].positionMs` | long | oui | ≥ 0 ; < `source.durationMs` si connu. |
| `moments[].label` | string | non | ≤ 280 caractères. |
| `moments[].lang` | string | non | BCP-47. |
| `moments[].beforeMs` | int | non | Défaut 15000, max 60000. |
| `moments[].afterMs` | int | non | Défaut 15000, max 60000. |
| `note` | string | non | ≤ 2000 caractères ; texte principal. |
| `source.key` | string | oui | Identifiant de version. |
| `source.kind` | string | non | `stream`, `download`, `local`, `telegram`. |
| `source.lang` | string | non | BCP-47. |
| `source.durationMs` | long | non | > 0. |
| `capturedAt` | string | oui | RFC 3339 UTC. |
| `spoiler` | boolean | non | Défaut `false`. |
| `client` | object | non | `{name, version}`. |

### 3.3 Fallback texte

Le `body` est obligatoire et lisible sans Shiro :

```text
▶ 12:34, 18:02 — Ce plan-là, et la musique juste après. (Sousou no Frieren · S01E003)
```

Construction :

```text
"▶ " + times.join(", ") + (note ? " — " + note : "") + " (" + (work.title ?: work.id) + " · " + episode.key + ")"
```

- `times` : `M:SS` ou `H:MM:SS`, arrondi à la seconde.
- Si `spoiler = true`, préfixer `⚠ `.
- `body` ≤ 2500 caractères.
- Au moins `note` ou un `label` doit être non vide.
- Payload sérialisé ≤ 8 Ko.

### 3.4 Parsing des timecodes saisis

Formats acceptés :

```text
01:40
1:40
1:02:30
```

Regex indicative :

```regex
(?<![\d:])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])
```

Règles :

- valider `0 <= position <= duration` si la durée est connue ;
- un timecode invalide reste du texte ;
- un timecode saisi manuellement est `approx` tant qu'il n'est pas ancré au player ;
- un timecode capturé par `Ancrer au moment` est exact pour la source courante.

### 3.5 Édition et redaction

- Édition : `m.replace` avec `m.new_content` contenant le nouveau `body` et le
  nouveau `org.shiro.moment`. Le client lit `m.new_content` en priorité.
- Redaction : l'événement disparaît ; le moment disparaît de la bande `Moments`.
- Un root redacted n'invalide pas les moments : chaque payload est auto-descriptif.
- Si `v > 1`, fallback `body`, pas d'interprétation partielle.

## 4. Salles, fils et adhésion

### 4.1 Salle par auteur + œuvre

```text
Salle : Sousou no Frieren — Alice
Type  : invite-only, E2EE
Membres : Alice + ses amis de 1er degré qui ont accepté
```

- Une salle par couple `(owner_mxid, local_work_id)`.
- Création à la première publication d'un moment pour cette œuvre.
- Nom : `<work.title> — <displayName auteur>` ; fallback `Shiro — <workId>`.
- Topic : `Shiro · <workId> · fil de <owner_mxid>`.
- Avatar : couverture de l'œuvre.
- Pas d'alias public, pas d'annuaire, pas de `m.room.canonical_alias`.
- Pas de `m.space` par œuvre en v1.

### 4.2 État initial

Créer dans `initial_state`, dans cet ordre :

| Événement | Contenu |
|---|---|
| `m.room.name` | nom ci-dessus. |
| `m.room.topic` | topic ci-dessus. |
| `m.room.avatar` | `mxc://` de la couverture. |
| `m.room.join_rules` | `{"join_rule":"invite"}`. |
| `m.room.history_visibility` | `{"history_visibility":"shared"}`. |
| `m.room.guest_access` | `{"guest_access":"forbidden"}`. |
| `m.room.power_levels` | voir §4.3. |
| `m.room.encryption` | `m.megolm.v1.aes-sha2`. |
| `org.shiro.room` | métadonnées minimales. |

`org.shiro.room`, `state_key = ""` :

```json
{
  "v": 1,
  "kind": "author-work",
  "work": {
    "id": "anilist:154587",
    "ids": { "anilist": "154587", "mal": "52991" },
    "title": "Sousou no Frieren"
  },
  "owner": "@alice:matrix.org",
  "createdAt": "2026-10-30T19:40:00Z",
  "visibility": "invite",
  "minAppVersion": "0.19.0"
}
```

Le chiffrement doit être présent **avant** tout message et avant les invitations.
Les state events Matrix ne sont pas chiffrés : ne jamais y mettre de contenu
sensible (pas de titre d'épisode, pas de message privé).

### 4.3 Power levels

| Paramètre | Valeur |
|---|---:|
| `users_default` | 0 |
| `users.{owner}` | 100 |
| `events_default` | 0 |
| `state_default` | 50 |
| `invite` | 100 |
| `kick` / `ban` / `redact` | 50 |
| `events."m.room.encryption"` | 100 |
| `events."org.shiro.room"` | 100 |
| `events."org.shiro.pins"` | 100 |
| `events."m.room.name"/"m.room.topic"/"m.room.avatar"` | 50 |

Les amis peuvent écrire des messages, des moments et des réactions. Ils ne
peuvent pas inviter, changer l'état, épingler ou modifier le chiffrement.

### 4.4 Fil par épisode

Un thread Matrix par `(salle, episode_key)`. Le root est un `m.room.message`
ordinaire, sans payload de moment :

```json
{
  "msgtype": "m.text",
  "body": "Fil · Sousou no Frieren · S01E003",
  "org.shiro.thread": {
    "v": 1,
    "workId": "anilist:154587",
    "episodeKey": "S01E003",
    "episodeNumber": 3.0,
    "season": 1,
    "kind": "regular",
    "createdBy": "@alice:matrix.org"
  }
}
```

- Tous les moments de l'épisode utilisent `m.relates_to.rel_type = "m.thread"`
  vers ce root.
- Une réponse utilise le même thread et `m.in_reply_to` vers le moment concerné ;
  elle ne porte pas de payload `org.shiro.moment`.
- Le root est créé paresseusement, au premier moment de l'épisode.
- Un root redacted n'invalide pas les moments ; un nouveau root peut être créé
  pour le même `episode_key`, les moments restent rattachés logiquement.
- Le mapping `(room_id, episode_key) → root_event_id` est gardé localement dans
  `thread_binding`. On ne crée pas de state event d'index partagé, car il ne
  serait pas chiffré.
- En cas de cache local vide, le client retrouve le root via les relations
  `m.thread` des messages déjà synchronisés.

### 4.5 Invitation et premier degré

Définition locale du 1er degré :

- contacts Shiro explicites ;
- interlocuteurs de DM marqués `is_direct: true` ;
- exclusion des bloqués, comptes désactivés et identifiants non-Matrix.

Règles :

- La liste est figée au moment de l'invitation. Aucune invitation transitive.
- Les nouveaux amis ajoutés plus tard sont invités à la prochaine création de
  fil ou lors de la synchronisation suivante ; on ne réinvite pas les membres
  déjà présents.
- Ne jamais parcourir les membres d'une salle pour auto-inviter leurs contacts.
- Un ami qui quitte n'est pas réinvité automatiquement.
- `knock` désactivé en v1.
- L'invitation contient un `reason` court et non spoilant : `Shiro · anilist:154587`.
- À l'arrivée, le client résout `work.id` dans la bibliothèque locale. Si l'œuvre
  est absente, bannière « Ajouter l'œuvre » avec deep link.
- L'auteur ne sait pas qui possède l'œuvre : la détection est locale au destinataire.
- `history_visibility: shared` : un invité qui rejoint voit l'historique. À
  documenter dans l'UI d'invitation.

### 4.6 Pas d'expiration

- Aucun TTL, aucun `m.room.retention`, aucun message éphémère.
- L'historique reste jusqu'à redaction ou politique du homeserver.
- L'UI peut masquer/mettre en sourdine un vieux fil sans le supprimer.
- Les notifications des vieux fils sont filtrées côté client.

## 5. Multi-auteurs et audiences

### 5.1 Invariant

Chaque auteur possède ses propres salles `(auteur, œuvre)`. Aucun message n'est
copié, forwardé, miroir ou relié entre salles. Le regroupement est une projection
locale en lecture seule des salles dont l'utilisateur est déjà membre.

- Alice et Bob commentent la même scène dans deux salles distinctes.
- Chloé, amie des deux, voit un groupe de deux moments ; elle ouvre chaque
  contribution dans sa salle d'origine.
- Dave, ami d'Alice seulement, ne voit que le moment d'Alice. Il ne doit jamais
  voir un compteur, une identité ou un extrait de Bob.
- Répondre dans le fil d'Alice reste dans l'audience d'Alice.
- `Publier à mes amis` écrit dans la salle de l'utilisateur.
- Aucune réaction, réponse ou épingle n'est répliquée entre salles.

### 5.2 Regroupement local

Le regroupement est local, non fédéré :

```text
même work résolu + même episodeKey + |positionMs₁ - positionMs₂| ≤ 15 000 ms
```

- La tolérance de 15 s correspond à la demi-fenêtre de 30 s.
- Aucune clé `momentKey` n'est envoyée dans Matrix.
- Deux moments à 01:40 et 01:55 peuvent être groupés ; à 01:40 et 02:10, non.
- Le regroupement ne fusionne jamais les messages : il affiche plusieurs cartes.
- Si l'œuvre n'est pas résolue de façon commune, pas de regroupement.
- Un utilisateur membre d'une seule salle ne voit que cette salle ; pas de
  compteur global toutes audiences.

### 5.3 Rendu

- Badge du groupe : nombre de contributions et avatars des auteurs.
- Dépliage : une carte par salle source, avec auteur, extrait, timecodes,
  réactions de cette salle.
- Tap sur une carte : ouvre la salle + thread + moment, seek à `positionMs`.
- Toute action depuis la carte s'écrit dans la salle source.
- L'UI indique l'audience : « Chez Alice ».

## 6. Réactions et épinglage

### 6.1 Réactions

Utiliser `m.reaction` standard :

```json
{
  "type": "m.reaction",
  "content": {
    "m.relates_to": {
      "rel_type": "m.annotation",
      "event_id": "$moment1",
      "key": "🔥"
    }
  }
}
```

- Cible : l'événement de moment entier.
- Un message multi-timecodes ne peut pas être réactionné timecode par timecode.
- Jeu rapide : `🔥`, `😂`, `😮`, `😢`, `👏`, `❤️`.
- Comptage par salle et par événement.
- Pas d'agrégation inter-salles en v1.
- Pas d'événement `org.shiro.reaction` : `m.reaction` suffit.
- Une réaction est chiffrée comme le reste du contenu.

### 6.2 Épinglage « moment clé »

`m.room.pinned_events` est un état de **salle**, pas de thread, et il n'est pas
chiffré. Il est donc ignoré pour les moments.

Stockage retenu : événement `org.shiro.pins` dans le fil, avec power level 100
(auteur uniquement). Le contenu est chiffré ; l'événement n'est pas un state event.

```json
{
  "type": "org.shiro.pins",
  "content": {
    "v": 1,
    "threadRoot": "$threadRoot",
    "eventIds": ["$moment1", "$moment2"],
    "updatedBy": "@alice:matrix.org",
    "updatedAt": "2026-10-30T20:10:00Z"
  }
}
```

- Maximum **3 pins** par fil.
- L'auteur ajoute/retire un pin en renvoyant la liste complète.
- Une quatrième épingle est refusée par l'UI avec « Maximum 3 moments clés » ;
  on ne remplace pas silencieusement un ancien pin.
- Désépinglage : liste sans l'événement, ou liste vide.
- Les clients gardent le dernier événement `org.shiro.pins` valide de l'auteur.
- Si une cible est redacted, elle est ignorée et retirée de la liste à la prochaine
  mise à jour ; l'UI propose de la retirer.
- Le pin n'est pas un `m.room.pinned_events` : il ne s'affiche pas dans les pins
  d'Element.
- Un ami qui tente d'envoyer `org.shiro.pins` reçoit `403` (power level 100).
- L'UI affiche 0 à 3 étoiles dans la bande `Moments`.

## 7. Notifications, chiffrement, homeserver

### 7.1 Notifications

L'instantané est **obligatoire**. Le transport retenu est **UnifiedPush + ntfy**,
pas un push différé.

| Option | Backend Shiro | Dépendances | Décision |
|---|---|---|---|
| UnifiedPush + ntfy | non | distributeur UnifiedPush (ex. ntfy) | **MVP obligatoire** |
| WorkManager | non | Android | rattrapage uniquement, pas instantané |
| FCM + Sygnal | oui/tiers | GMS, Firebase, Sygnal | **rejeté** |
| WebSocket permanent | non | service de premier plan | **rejeté** (non fiable, batterie) |

Fonctionnement :

- l'app détecte un distributeur UnifiedPush ;
- si aucun distributeur n'est présent, l'onboarding explique qu'il est requis
  pour l'instantané et propose d'installer ntfy (deep link F-Droid/Play) ;
- l'utilisateur active les notifications ;
- l'app enregistre un pusher Matrix HTTP :

```json
{
  "app_id": "io.github.DipitMoun.shiro",
  "pushkey": "<endpoint UnifiedPush>",
  "kind": "http",
  "app_display_name": "Shiro",
  "device_display_name": "<modèle>",
  "lang": "fr",
  "append": false,
  "data": {
    "url": "https://ntfy.sh/_matrix/push/v1/notify",
    "format": "event_id_only"
  }
}
```

- `format: event_id_only` : la notification ne contient que `event_id`, `room_id`
  et les compteurs ; l'app ouvre la salle, synchronise et déchiffre ;
- `app_id` stable ; `append: false` ; une seule entrée par appareil ;
- le `pushkey` est un secret de type capability URL : ne jamais le logger ;
- désinscription (`kind: null`) à la déconnexion, puis réenregistrement au login ;
- `ntfy.sh` voit les métadonnées de push, pas le contenu chiffré ;
- auto-hébergement de ntfy possible (TLS, `base-url`, ACL `up*` en écriture) ;
- l'URL de gateway doit être HTTPS et se terminer exactement par
  `/_matrix/push/v1/notify` ;
- ntfy peut renvoyer `rejected` si le topic n'a plus d'abonné depuis 12 h ;
  le pusher doit alors être réenregistré ;
- prévoir un **push de test** avant de déclarer l'instantané opérationnel ;
- états d'onboarding : absent → installé non sélectionné → sélectionné sans
  endpoint → pusher enregistré → push de test reçu → prêt ;
- erreurs à traiter : `400` (payload), `404` (pusher non supporté), `429`
  (rate limit), `rejected` (topic expiré), réseau, permission notifications,
  batterie restreinte ;
- un seul pusher par appareil ; supprimer l'ancien avant d'en créer un nouveau ;
- si le homeserver refuse les pushers ou si le distributeur disparaît, l'app
  affiche un état explicite « notifications instantanées indisponibles » et
  n'essaie pas de faire passer WorkManager pour de l'instantané ;
- WorkManager ne sert qu'à rattraper les messages manqués, pas à promettre
  l'instantané ;
- Doze/OEM peuvent retarder la livraison malgré UnifiedPush ; le distributeur
  doit être exempté d'optimisation de batterie.

FCM est rejeté : dépendance GMS/Firebase, projet à opérer, et le Sygnal de
matrix.org ne connaît pas l'`app_id` de Shiro. Un Sygnal dédié contredirait
« pas de backend Shiro ».

### 7.2 Chiffrement

- E2EE activé dès la création des salles.
- `m.room.encryption` avec `m.megolm.v1.aes-sha2`.
- Matrix Rust SDK avec store chiffré local.
- Sauvegarde de clés obligatoire à l'onboarding.
- Vérification des appareils par SAS/QR ; Cross-Signing géré par le SDK.

Impacts :

- push : contenu illisible côté serveur ; `event_id_only` obligatoire ;
- multi-appareil : chaque appareil doit être vérifié ;
- perte de clés = perte de l'historique ; clé de récupération exportable ;
- pas de recherche serveur ; index local chiffré ;
- les state events (nom, topic, room metadata) ne sont pas chiffrés : ne rien
  y mettre de sensible ;
- les invitations et raisons sont visibles du serveur : pas de titre d'épisode ;
- aucun bot/pont en MVP.

La seule dégradation acceptable est de reporter la fonctionnalité, pas de livrer
des salles en clair présentées comme privées.

### 7.3 Homeserver et identité

- Homeserver Matrix existant, pas de backend Shiro.
- Prototype : `matrix.org` ou homeserver de dev.
- Production : homeserver configurable.
- Compte Matrix nécessaire ; pas de compte Shiro.
- Graphe d'amis de 1er degré local à Shiro (code d'ami / invitation).
- Pas d'annuaire global, pas de découverte transitive.

## 8. Contrat UI

Le détail est dans [PHASE-0-WIREFRAMES.md](PHASE-0-WIREFRAMES.md).

Résumé :

- overlay paysage sur le player en pause ;
- affiche floutée + dégradé à gauche ;
- rail d'avatars à gauche, groupes `À ce moment` / `Sur cette œuvre` ;
- panneau de fil à droite, audience visible ;
- bande `Moments` triée par timecode ;
- composer `Répondre ici` / `Publier à mes amis` ;
- boucle 15/15, mute, pause → affiche ;
- jusqu'à 3 moments clés épinglés par fil ;
- états vides, chargement, erreur, clavier.

## 9. Points d'intégration code

Points identifiés :

- `PlayerViewModel.pos` / `PlayerViewModel.duration` : position/durée en
  secondes (`Float`), à convertir en millisecondes pour le schéma.
- `PlayerViewModel.currentAnime` / `currentEpisode` : contexte.
- `PlayerViewModel.seekTo(position: Int, precise: Boolean)` : seek en secondes.
- `PlayerActivity.setVideo(...)` : chargement de la vidéo.
- `Episode` : `id`, `animeId`, `url`, `episodeNumber`, `totalSeconds`,
  `lastSecondSeen`. **Pas de champ saison** : la saison vient de
  `Anime.seasonNumber` / de la hiérarchie de saison.
- `Anime` : `id`, `source`, `url`, `seasonNumber`, `title`. **Pas d'ID
  AniList/MAL direct** : la résolution passe par le tracker ou les métadonnées
  de reconnaissance.
- `MainActivity.startPlayerActivity(...)` : ouverture du player.
- Le source Telegram utilise `telegram://...` en interne ; la couche sociale ne
  doit pas parser cette URI, elle travaille sur l'identité canonique.
- Aucun code Matrix n'existe aujourd'hui dans le dépôt ; la Phase 1 part d'une
  couche neuve.

Recommandation : introduire un **adapter social** qui produit un
`SocialMomentContext` (workId, episodeKey, positionMs, durationMs, sourceKey)
sans coupler Matrix au player ni à Telegram.

```text
PlayerViewModel / PlayerActivity
        │
        ▼
MomentContextAdapter  ──►  SocialOverlayHost
        │                       │
        ▼                       ▼
SocialMomentContext      SocialRepository (Matrix)
```

- `MomentContextAdapter` : extrait `Anime`/`Episode`/`pos`/`duration`, calcule
  `sourceKey`, ne fait aucun appel réseau.
- `SocialOverlayHost` : couche Compose au-dessus du player, paysage.
- `SocialRepository` : salles, fils, messages, synchronisation Matrix.
- Aucun import Matrix dans `PlayerViewModel` ou dans les sources anime.

Tables locales minimales :

| Table | Rôle |
|---|---|
| `social_work` | Résolution canonique locale. |
| `work_alias` | `scheme, remote_id → local_work_id`. |
| `contact` | Amis de 1er degré. |
| `room_binding` | `(owner_mxid, local_work_id) → room_id`. |
| `thread_binding` | `(room_id, episode_key) → root_event_id`. |
| `moment_index` | Index local des moments pour l'overlay. |
| `source_offset` | Recalage manuel local. |

## 10. Risques et points ouverts

- Onboarding Matrix : BYO homeserver ou futur homeserver Shiro ?
- Résolution AniList/MAL hors tracking : API ou saisie manuelle ?
- Identité locale/Telegram : hash stable et slugs Telegram.
- Support des threads dans `matrix-sdk-ui` : spike Phase 1.
- Sauvegarde de clés multi-appareil : UX de récupération.
- Fan-out des invitations et rate limits.
- Épingles : maximum 3 par fil ; l'UI refuse la quatrième et ne remplace pas
  silencieusement un ancien moment clé.
- Agrégation des réactions inter-salles : v2 ou jamais.
- Rétention : politique du homeserver à documenter.
- Divulgation de la liste des membres entre amis : inviter plusieurs amis dans
  une même salle révèle leurs MXID entre eux ; à documenter dans l'UI.
- E2EE et nouveaux appareils : l'historique peut être illisible sans sauvegarde
  de clés ; l'onboarding doit être clair.
- Dérive de timecode : badge `≈`, recalage manuel.
- Distributeur UnifiedPush absent ou retiré : l'instantané n'est pas disponible ;
  l'app doit l'expliquer, pas le masquer.
- Absence de modération serveur : block/mute/signalement hors fil.
- Spoilers dans nom/topic/reason : titre d'épisode interdit dans `reason`.
- Performance : boucle vidéo + chat + dégradés en paysage.

## 11. Critères d'acceptation

Voir [PHASE-0-ACCEPTANCE.md](PHASE-0-ACCEPTANCE.md).
