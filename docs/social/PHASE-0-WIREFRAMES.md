# Phase 0 — Wireframes et interactions

> Mode paysage exclusif. Overlay plein écran au-dessus du player en pause.
> Les schémas sont indicatifs : ils fixent la hiérarchie, pas les pixels exacts.

## 0. Modèle à ne pas confondre

Un fil = **un auteur + un épisode**. Le rail liste des **auteurs** qui ont un fil
sur cette œuvre, pas des contributions fusionnées. Les fils de plusieurs auteurs
au même moment sont **regroupés visuellement**, jamais fusionnés : chaque
conversation garde sa salle et son audience.

`Publier à mes amis` crée/alimente le fil de l'utilisateur dans sa propre salle ;
ce n'est pas un fil d'actualité séparé, et ce n'est pas une copie automatique du
fil d'un autre.

## 1. Principes UX

1. **Le moment reste le centre.** La vidéo, l'épisode et le timecode ne doivent
   jamais disparaître derrière le chat.
2. **Le fil est une couche, pas un écran séparé.** On peut lire, répondre et
   changer de conversation sans quitter le moment.
3. **Une seule colonne de navigation.** La rail d'avatars à gauche sert à changer
   d'auteur ; le panneau de droite sert à lire/écrire.
4. **Pas de texte directement sur une image en mouvement.** Le panneau de fil est
   suffisamment opaque pour rester lisible.
5. **L'audience est toujours visible.** On ne doit jamais se demander si l'on
   parle aux amis de Kevin ou aux siens.
6. **Le mouvement est optionnel.** La boucle peut être mise en pause ; le son est
   coupé par défaut ; le mode « mouvement réduit » fige la vidéo.
7. **Pas de cartes dans des cartes.** Un overlay, un rail, un panneau.

## 2. Layout général

### 2.1 Téléphone 16:9 / 20:9

```text
┌──────────────────────────────────────────────────────────────┐
│  [affiche floutée + dégradé sombre à gauche]                 │
│                                                              │
│  ┌────┐   ┌──────────────────────────────────────────────┐   │
│  │ K  │   │  Kevin · Frieren · S1E1 · 01:40             │   │
│  │ A  │   │  Amis de Kevin                              │   │
│  │ D  │   │                                              │   │
│  │    │   │  [01:40] Kevin  « Cette scène… »     ▶      │   │
│  │    │   │  [12:10] Allan  « Le plan suivant… » ▶      │   │
│  │    │   │                                              │   │
│  │    │   │  [ 🕐 Ancrer ] [ Répondre ici ] [ Publier ]  │   │
│  └────┘   └──────────────────────────────────────────────┘   │
│                                                              │
│  ▶ Reprendre          ↺ Revoir le moment          🔇         │
└──────────────────────────────────────────────────────────────┘
```

Dimensions cibles :

- rail : 88–96 dp de large, avatars 44–48 dp ;
- panneau : 360–420 dp, ou 38–42 % de la largeur ;
- marge de sécurité : 16–24 dp ;
- hauteur du composer : 56 dp fermé, 120–180 dp avec pièce jointe ;
- coins du panneau : 20–24 dp côté gauche ;
- dégradé gauche : 280–360 dp de large, noir 70 % → transparent.

Sur 20:9, le panneau peut monter à 420 dp. Sur tablette pliable, garder le rail
et le panneau, mais laisser plus de place à la vidéo.

### 2.1.1 Breakpoints

| Classe | Largeur | Rail | Panneau | Scène min. |
|---|---:|---:|---:|---:|
| Compact court | < 840 dp, 360 dp de haut | 72 dp | 320 dp | 96 dp |
| Compact | < 840 dp, 400–450 dp | 76 dp | 336 dp | 96 dp |
| Medium | 840–1199 dp | 88 dp | 380 dp | 120 dp |
| Expanded | ≥ 1200 dp | 96 dp | 420 dp | 160 dp |

Règles :

- le panneau fait 42 % de la largeur en compact, borné entre 304 et 336 dp ;
- en medium, 38 %, borné entre 360 et 400 dp ;
- en expanded, 420 dp fixes, largeur de texte max 52 caractères ;
- le rail ne descend pas sous 72 dp ;
- si la scène ferait moins de 96 dp de large, le panneau peut passer au-dessus
  de l'affiche, mais jamais au-dessus d'une vidéo en lecture ;
- en boucle, le panneau reste visible sur les largeurs normales ; si la scène
  est trop étroite, il se replie en poignée `N messages` et la vidéo garde la
  priorité.

### 2.2 Zones

```text
┌──────────────────────────────────────────────────────────────┐
│ zone safe top                                                │
│ ┌────────┬───────────────────────────────┬────────────────┐ │
│ │ rail   │ vidéo / affiche               │ fil            │ │
│ │ 88 dp  │ flexible                      │ 380 dp         │ │
│ └────────┴───────────────────────────────┴────────────────┘ │
│ zone safe bottom                                             │
└──────────────────────────────────────────────────────────────┘
```

Le panneau de fil ne doit pas recouvrir le bouton `Reprendre` ni le timecode.

## 3. État A — Pause sans fil

```text
┌──────────────────────────────────────────────────────────────┐
│  [affiche de l'œuvre, floutée, assombrie]                    │
│                                                              │
│  Frieren                                                     │
│  S1E1 · 01:40                                                │
│                                                              │
│              ▶  Reprendre l'épisode                          │
│              ↺  Revoir le moment                             │
│                                                              │
│  ┌────┐                                                      │
│  │ +  │  Aucun fil sur ce moment                             │
│  └────┘  Sois le premier à en parler.                        │
│          [ Publier à mes amis ]                              │
└──────────────────────────────────────────────────────────────┘
```

Comportement :

- l'affiche est une version floutée/assombrie de la cover, pas une capture
  d'écran (évite les spoilers) ;
- `Reprendre` ferme l'overlay et reprend à l'ancre ;
- `Revoir le moment` passe en état B (boucle) ;
- `Publier à mes amis` crée le premier fil de l'utilisateur pour cet épisode.

## 4. État B — Pause avec fil sélectionné

```text
┌──────────────────────────────────────────────────────────────┐
│  [affiche]                                                    │
│                                                              │
│  ┌────┐   ┌──────────────────────────────────────────────┐   │
│  │ K  │   │  Kevin · Frieren · S1E1 · 01:40             │   │
│  │ A  │   │  Amis de Kevin · 12 messages                 │   │
│  │ D  │   │  ─────────────────────────────────────────   │   │
│  │    │   │  [01:40] Kevin  « Cette scène est… »   ▶     │   │
│  │    │   │  [01:40] Allan  « Je n'avais pas vu… »       │   │
│  │    │   │  [12:10] Kevin  « Le plan suivant… »   ▶     │   │
│  │    │   │  ─────────────────────────────────────────   │   │
│  │    │   │  [ 🕐 Ancrer ] [ Répondre ici ] [ Publier ]  │   │
│  └────┘   └──────────────────────────────────────────────┘   │
│                                                              │
│  ▶ Reprendre          ↺ Revoir le moment          🔇         │
└──────────────────────────────────────────────────────────────┘
```

### Rail

- avatar + prénom pour l'élément sélectionné ;
- avatars seuls pour les autres ;
- point de non-lu ;
- badge `•` si l'ami a un fil à ce moment ;
- groupes :

```text
À ce moment
  K  Kevin
  A  Allan
Sur cette œuvre
  D  David
  ...
```

Un ami qui a un fil sur l'œuvre mais pas à ce moment reste visible dans le second
groupe. Sélectionner son fil le plus récent ne doit pas faire perdre l'ancre
courante sans confirmation visuelle : le header du moment se met à jour.

### Panneau

- header : auteur, œuvre, épisode, timecode ;
- ligne d'audience : `Amis de Kevin` ;
- messages avec timecode en pastille ;
- bande `Moments` au-dessus ou sous le header :

```text
Moments  [01:40] [12:10] [18:30] [22:05]
```

- jusqu'à 3 messages épinglés par fil, avec `★` et fond légèrement plus clair ;
- composer fixe en bas.

L'UI affiche `S1E1` ; le stockage canonique est `S01E001`.

## 5. État C — Boucle 15/15

```text
┌──────────────────────────────────────────────────────────────┐
│  [vidéo en boucle, 15 s avant / 15 s après]                  │
│                                                              │
│  ┌────┐   ┌──────────────────────────────────────────────┐   │
│  │ K  │   │  Kevin · S1E1 · 01:40                       │   │
│  │ A  │   │  [01:40] Kevin  « Cette scène… »             │   │
│  │ D  │   │  [01:40] Allan  « Je n'avais pas vu… »       │   │
│  │    │   │                                              │   │
│  │    │   │  [ Répondre ici ] [ Publier à mes amis ]     │   │
│  └────┘   └──────────────────────────────────────────────┘   │
│                                                              │
│  ⏸ Pause     🔇 Son    00:15 / 00:30    15 s avant/après    │
└──────────────────────────────────────────────────────────────┘
```

Comportement :

- fondu de 250–300 ms entre l'affiche et la vidéo ;
- la boucle démarre au moment `positionMs - 15000`, clampée à 0 ;
- elle s'arrête à `positionMs + 15000`, clampée à la durée ;
- son coupé par défaut ; bouton `🔇` pour activer ;
- `Pause` arrête la boucle et revient à l'affiche (état A/B) ;
- le panneau de fil reste accessible pendant la boucle ;
- la vidéo peut être légèrement assombrie derrière le panneau.

### Indicateur de fenêtre

Afficher une petite barre :

```text
09:25 ─────●───── 09:55
```

Le point est l'ancre. Si le moment est proche du début ou de la fin, la fenêtre
est tronquée et l'indicateur le montre.

## 6. État D — Composer ouvert / clavier

```text
┌──────────────────────────────────────────────────────────────┐
│  [vidéo figée ou très assombrie]                              │
│                                                              │
│  ┌────┐   ┌──────────────────────────────────────────────┐   │
│  │ K  │   │  Kevin · S1E1 · 01:40                       │   │
│  │ A  │   │  … messages …                                │   │
│  │ D  │   │  ┌────────────────────────────────────────┐  │   │
│  │    │   │  │ Écrire…                                │  │   │
│  │    │   │  └────────────────────────────────────────┘  │   │
│  │    │   │  [ Répondre ici ]  [ Publier à mes amis ]     │   │
│  └────┘   └──────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ clavier Android                                        │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

Comportement :

- le clavier pousse le panneau vers le haut ;
- la boucle se fige sur l'image (pas de mouvement derrière le texte) ;
- le rail se replie en avatars 40 dp si la largeur manque ;
- le header du moment reste visible ;
- `Répondre ici` est l'action par défaut si on est dans le fil d'un autre ;
- `Publier à mes amis` est l'action par défaut si on est dans son propre fil ;
- après envoi, la boucle reprend si elle était active ;
- le brouillon est conservé par fil et par cible à la fermeture du clavier ou de
  l'overlay ; il n'ouvre pas de dialogue destructif.

### Bandeau d'audience

Avant l'envoi, afficher clairement :

```text
Vous répondez dans le fil de Kevin.
Visible par les amis de Kevin.
```

ou :

```text
Vous publiez dans votre fil.
Visible par vos amis.
```

Ce bandeau est obligatoire, pas une infobulle.

## 7. État E — Plusieurs auteurs au même moment

```text
┌──────────────────────────────────────────────────────────────┐
│  [vidéo / affiche]                                            │
│                                                              │
│  ┌────┐   ┌──────────────────────────────────────────────┐   │
│  │ K  │   │  Moment S1E1 · 01:40                        │   │
│  │ A  │   │  Conversations                               │   │
│  │ D  │   │  [Kevin 12] [Allan 8] [David 3]              │   │
│  │    │   │  ─────────────────────────────────────────   │   │
│  │    │   │  Fil de Kevin · amis de Kevin                │   │
│  │    │   │  …                                           │   │
│  └────┘   └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

- chaque conversation a sa propre audience ;
- les compteurs sont séparés ;
- une vue agrégée des réactions peut être affichée, mais pas des messages
  fusionnés ;
- sélectionner un auteur change le panneau, pas l'ancre vidéo ;
- si l'auteur sélectionné n'a pas de fil à ce moment, afficher :

```text
Pas encore de fil à ce moment.
Voir les autres moments de Kevin sur Frieren.
```

## 8. État F — Bande `Moments`

```text
┌──────────────────────────────────────────────────────────────┐
│  Kevin · Frieren · S1E1                                      │
│  Amis de Kevin                                               │
│                                                              │
│  Moments                                                     │
│  [★ 01:40] [12:10] [18:30] [★ 22:05]                         │
│                                                              │
│  [01:40] Kevin  « Cette scène… »                       ▶     │
│  [12:10] Allan  « Le plan suivant… »                   ▶     │
└──────────────────────────────────────────────────────────────┘
```

- tri par timecode, pas par date ;
- étoile = moment clé épinglé ; maximum 3 étoiles par fil ;
- clic sur un moment : seek + boucle + scroll vers le message ;
- les moments sans message épinglé restent dans la bande s'ils ont un message
  ancré ;
- maximum visible : 5–7 pastilles, puis scroll horizontal.

## 9. État G — Vide, chargement, erreur

### Aucun fil

```text
┌──────────────────────────────────────────────┐
│  Aucun fil sur cette œuvre.                  │
│  Sois le premier à partager un moment.       │
│  [ Publier à mes amis ]                      │
└──────────────────────────────────────────────┘
```

### Aucun ami

```text
┌──────────────────────────────────────────────┐
│  Tes amis n'ont pas encore de fil ici.       │
│  Invite-les à rejoindre Shiro.               │
│  [ Inviter un ami ]                          │
└──────────────────────────────────────────────┘
```

### Chargement

- cache-first : si un fil est déjà chargé, il s'affiche immédiatement ;
- skeleton de 3 lignes dans le panneau après 150 ms, seulement sans cache ;
- rail avec avatars gris ;
- pas de spinner plein écran ;
- header du moment déjà visible.

### Erreur

```text
┌──────────────────────────────────────────────┐
│  Impossible de charger ce fil.               │
│  Vérifie ta connexion Matrix.                │
│  [ Réessayer ]                               │
└──────────────────────────────────────────────┘
```

### Hors ligne

- bandeau fin `Hors ligne — les messages seront envoyés plus tard` ;
- composer actif ;
- file d'attente locale ;
- les messages non envoyés ont un état `⏳`.

### Notifications instantanées

L'instantané est obligatoire via UnifiedPush + ntfy.

- à l'onboarding, détecter le distributeur UnifiedPush ;
- s'il manque : écran clair `Pour recevoir les moments en instantané, installe
  ntfy` avec un bouton d'installation ;
- si le homeserver refuse le pusher : état `Notifications instantanées
  indisponibles` + diagnostic, pas de faux fallback ;
- WorkManager ne doit jamais être présenté comme instantané ;
- une notification ne contient pas le texte déchiffré : `Nouveau moment dans
  Frieren`, puis ouverture de la salle/thread.

## 10. Interactions

| Geste | Action |
|---|---|
| Tap sur un avatar | Sélectionne le fil de cet auteur. |
| Tap sur un timecode | Lance la boucle 15/15. |
| Appui long sur un timecode | Menu `Aller au moment`, `Revoir 30 s`, `Copier`. |
| Tap sur `Revoir le moment` | Passe en mode boucle. |
| Tap sur `Pause` | Revient à l'affiche. |
| Tap sur `Reprendre` | Ferme l'overlay et reprend à l'ancre. |
| Tap sur `Répondre ici` | Ouvre le composer ciblé sur le fil courant. |
| Tap sur `Publier à mes amis` | Ouvre le composer ciblé sur son propre fil. |
| Tap sur une réaction | Ajoute/retire `m.reaction`. |
| Appui long sur un message | Menu `Réagir`, `Épingler` (auteur), `Copier`. |
| Tap sur un moment de la bande | Seek + boucle + scroll. |
| Back | Ferme le clavier, puis l'overlay ; le player reste en pause. |
| Rotation | Reste en paysage ; pas de passage portrait. |
| Perte de focus | La boucle se met en pause si l'app passe en arrière-plan. |

## 11. Accessibilité

- contraste texte/fond ≥ 4.5:1 ;
- zones tactiles ≥ 48 dp ;
- labels TalkBack : `Fil de Kevin, 12 messages, amis de Kevin` ;
- timecodes annoncés `Moment à 1 minute 40` ;
- bouton mute avec état `Son coupé` / `Son activé` ;
- sous-titres visibles si activés ;
- respect de `Réduire les animations` : pas de fondu, boucle figée sur l'image ;
- pas d'information portée uniquement par la couleur ;
- taille de texte système respectée ;
- pas d'auto-play audio.

## 12. Pièges à éviter

- chat directement sur une image en mouvement sans scrim ;
- panneau de fil trop transparent ;
- rail avec avatars seuls sans nom pour l'élément sélectionné ;
- confusion entre `Répondre ici` et `Publier à mes amis` ;
- audience masquée ou en petit texte ;
- messages de plusieurs audiences fusionnés en une seule liste ;
- boucle audio par défaut ;
- boucle qui continue quand le clavier est ouvert ;
- overlay qui masque complètement l'épisode ;
- pastille de présence ou mention « live » alors que le fil est asynchrone ;
- transformer l'overlay en feed infini avec recherche ou tendances ;
- perdre le brouillon à la fermeture du clavier ou de l'overlay ;
- expiration ou disparition silencieuse d'un fil ;
- épinglage global qui s'applique à tous les épisodes ;
- timecode non cliquable ou non structuré ;
- seek qui sort des bornes de l'épisode ;
- animation de boucle qui ignore le mode mouvement réduit.
