---
titre: Projet Shiro - Plan de developpement
version: 0.4
statut: Actif
base: Aniyomi (aniyomiorg/aniyomi)
licence: Apache 2.0
derniere_revision: 2026-10-23
---

# Projet Shiro

> Fork d'Aniyomi avec couche sociale Telegram.
> Document de reference - v0.3

## Table des matieres

1. [Vision](#vision)
2. [Decisions actees](#decisions-actees)
3. [Non-objectifs](#non-objectifs)
4. [Architecture cible](#architecture-cible)
5. [Strategie cherry-pick](#strategie-cherry-pick)
6. [Plan par phases](#plan-par-phases)
7. [Decisions a trancher](#decisions-a-trancher)
8. [Veille concurrentielle](#veille-concurrentielle)

---

## Etat d'avancement (2026-10-23)

| Phase | Statut | Commentaire |
|-------|--------|-------------|
| Phase 0 - Fondations | TERMINEE | Renommage Shiro, icone, update checker desactive, CI GitHub Actions |
| Phase 1 - Cherry-picks | TERMINEE | Categories imbriquees + fixes backup multi-origines |
| Phase 2 - Coeur social Telegram | PARTIELLE (~40%) | Squelette OK, 5 items restants (voir detail) |
| Phase 2.5 - Refonte UI Spotify-like | TERMINEE | Non prevue au plan initial, ajoutee en cours de route |
| Phase UI-2 - Outil GIF FFmpeg | REPORTEE | Apres test APK de la refonte UI |
| Phase 3 - Explorations | NON COMMENCEE | - |

### Detail Phase 2 (Telegram) - Reste a faire
- Parsing intelligent des noms de fichiers (regroupement par oeuvre via AniList)
- Branchement du lecteur (videoListParse retourne vide actuellement)
- Notifications natives (WebSocket en premier plan + WorkManager en arriere-plan)
- Partage d'extensions entre amis
- Commentaires et reactions natives dans Shiro

### Detail Phase 2.5 (Refonte UI) - Termine
- Cartes collection format carre (3 covers + 1 fleche)
- Clic cover ouvre l'oeuvre, clic fleche/espace ouvre la collection
- Ecran collection recursif (banniere + desc + stats + sous-collections + oeuvres)
- Stats Anime : "X titres - Y/Z ep. - Nh vues"
- Banniere locale (fichier + galerie, chemin stocke en JSON)
- Edition banniere + description via dialog
- Carrousel en haut (oeuvres + collections recentes avec %)
- FAB + BottomSheet navigation rapide

### Detail Phase UI-2 (Outil GIF) - Reporte
- Scope : FFmpeg deja present dans le projet, picker video, slider 15s, sauvegarde banniere
- Effort estime : 2-3h
- Bloque par : test APK de la refonte UI d'abord

---

## Vision

Shiro est un fork d'Aniyomi qui conserve sa base fonctionnelle (lecture d'anime et manga via extensions) et y ajoute une couche sociale pair-a-pair via Telegram : partage d'extensions configurees entre amis, synchronisation des reactions et commentaires en temps reel, notifications Android natives avec deep-link vers la position de lecture exacte (WebSocket en premier plan, WorkManager en arriere-plan).

L'app n'a pas de serveur. Tout passe par des bots Telegram, des canaux prives, et des sessions Telegram individuelles pour la lecture.

---

## Decisions actees

### Nom
Shiro. "Kuro" est deja pris par Zykrave/aniyomi-Kuro. "Thot" est ecarte. Shiro garde la coherence : Mihon / TachiyomiSY / Komikku / Kuro / Shiro.

### Base de code
Aniyomi upstream (aniyomiorg/aniyomi). On garde la main sur l'architecture et on integre selectivement des features par cherry-pick.

### Strategie d'enrichissement
Cherry-pick cible depuis les forks existants, categorise en A (direct), B (adaptation), C (non cherry-pickable).

### Securite
- Token du bot : chiffre via Android Keystore, revocable via /revoke BotFather
- Jamais de partage de session Telegram utilisateur (acces total au compte + invalidation concurrente)
- API Bot limitee a 20 Mo par fichier : suffisant pour les notifications, pas pour la lecture (qui passe par le compte de l'ami)
- WorkManager : intervalle minimum 15 min, execution non precise (Doze peut retarder a 30 min ou plus). Sur certains OEM (Xiaomi, Huawei, Oppo, Vivo), un swipe depuis les apps recentes peut etre traite comme force-stop et bloquer WorkManager. Limite Android, pas de Shiro. A documenter cote utilisateur.

---

## Non-objectifs

Explicitement hors scope :

- Pas de serveur backend. Zero relais, zero cloud, zero compte Shiro.
- Pas de refonte UI complete (a la Kuro) ni de changements UI majeurs avant la v2. On garde l'UI Aniyomi actuelle (lecteur, bibliotheque, navigation) pour ne pas ajouter de risque pendant la phase social.
- Pas de Chromecast / Android TV en v1.
- Pas de light novels en v1.
- Pas de flux multimedia TMDB/AniList en v1.
- Pas de trackers autres que ceux deja supportes par Aniyomi en v1.

Ces features pourront etre envisagees en v2+ si la traction utilisateur le justifie.

---

## Architecture cible

    +------------------+     +-----------------+     +-------------+
    | Coeur Aniyomi    |     | Couche social   |     | Cherry-     |
    | (lecture,        |     | Telegram        |     | picks       |
    | extensions,      |     | (Shiro-         |     | (backups,   |
    | bibliotheque)    |     | specific)       |     | nested...)  |
    +--------+---------+     +--------+--------+     +------+------+
             |                        |                     |
             v                        v                     v
    +------------------+     +-----------------+
    | Canal prive      |<----| Canal Telegram  |
    | (Kevin)          |     | (ami, lecture)  |
    +------------------+     +-----------------+

Flux cles :
1. Kevin partage une "extension Shiro" a Allan = archive chiffree (token du bot de Kevin + lien canal + metadonnees).
2. Allan importe l'extension. Son app associe le bot a Kevin, rejoint le canal avec son propre compte.
3. Allan reagit dans le lecteur. Son app envoie la reaction au relais WebSocket de Kevin (authentifie par le token du bot). Si Kevin est en premier plan : reception instantanee. Sinon : reception au prochain tick WorkManager (15 min) ou a la prochaine ouverture.
4. Kevin clique -> deep-link Shiro vers la position exacte du fichier dans son lecteur.

---

## Strategie cherry-pick

Voir l'analyse detaillee generee par opencode (Shiro-cherry-pick-analysis.md).

### Categorie A - Cherry-pick direct (a prioriser)
- Restauration de backups (Mihon / TachiyomiSY / Komikku) - Tadami
- Categories imbriquees - Aniyomi Plus
- Discord Rich Presence - Animetail
- Data Saver - Animetail
- AniList Dashboard - Aniyomi Plus

### Categorie B - Cherry-pick avec adaptation
- PiP avance - Animiru
- Choix de piste audio - Tadami
- Support Torrent (TorrServer) - Aniyomi Plus
- Themes Kuro (si simple) - Kuro

### Categorie C - Non cherry-pickable
- Refonte UI complete - Kuro
- Flux multimedia TMDB/AniList - Animetail
- Support Chromecast - Tadami / Animetail
- Support light novels - Tadami

---

## Plan par phases

Legende : Complexite (Faible / Moyenne / Elevee), Besoin (Essentiel / Important / Optionnel), Impact (Fort / Moyen / Faible).

### Phase 0 - Fondations (identite Shiro)

A faire avant tout. Non negociable.

| # | Tache | Complexite | Besoin | Impact |
|---|-------|:----------:|:------:|:------:|
| 0.1 | Renommer rootProject.name -> Shiro (settings.gradle.kts) | Faible | Essentiel | Fort |
| 0.2 | Changer applicationId -> com.tonnom.shiro (app/build.gradle.kts) | Faible | Essentiel | Fort |
| 0.3 | Changer app_name -> Shiro (strings.xml) | Faible | Essentiel | Fort |
| 0.4 | Remplacer icone de l'app (mipmap) | Faible | Important | Moyen |
| 0.5 | Reecrire README.md (description, credits fork, licence) | Faible | Important | Moyen |
| 0.6 | Desactiver le verificateur de mises a jour d'Aniyomi | Faible | Essentiel | Fort |
| 0.7 | Verifier conformite Apache 2.0 (mentions originales) | Faible | Essentiel | Moyen |
| 0.8 | Renommer dossier projet (Thot -> Shiro) et depot GitHub | Faible | Essentiel | Fort |

### Phase 1 - Cherry-picks categorie A

Gain rapide, faible effort, features visibles.

| # | Tache | Source | Complexite | Besoin | Impact |
|---|-------|--------|:----------:|:------:|:------:|
| 1.1 | Restauration de backups elargie | Tadami | Moyenne | Important | Moyen |
| 1.2 | Categories imbriquees | Aniyomi Plus | Moyenne | Optionnel | Faible |
| 1.3 | Discord Rich Presence | Animetail | Faible | Optionnel | Faible |
| 1.4 | Data Saver | Animetail | Faible | Optionnel | Moyen |
| 1.5 | AniList Dashboard | Aniyomi Plus | Elevee | Optionnel | Moyen |

### Phase 2 - Coeur social Telegram

Le vrai differenciateur. Le prototype doit etre fait tot pour valider les risques.

| # | Tache | Complexite | Besoin | Impact |
|---|-------|:----------:|:------:|:------:|
| 2.1 | Onboarding guide : creation du bot via BotFather | Moyenne | Essentiel | Fort |
| 2.2 | Stockage chiffre du token bot (Android Keystore) | Moyenne | Essentiel | Fort |
| 2.3 | Format "extension Shiro" (archive chiffree : token + lien canal + meta) | Elevee | Essentiel | Fort |
| 2.4 | Import d'une extension Shiro (dechiffrement, association a un contact) | Elevee | Essentiel | Fort |
| 2.5 | Bot d'ami : envoi des reactions vers le relais du destinataire, authentifie par le token du bot | Elevee | Essentiel | Fort |
| 2.6 | Notification Android native + deep-link vers la position de lecture exacte | Moyenne | Important | Fort |
| 2.6bis | Gestion cycle de vie WebSocket : connexion onStart, deconnexion onStop, reconnexion backoff exponentiel | Moyenne | Essentiel | Fort |
| 2.6ter | WorkManager periodic 15 min pour polling du relais en arriere-plan (limite Android) | Moyenne | Essentiel | Fort |
| 2.6quater | Relais WebSocket : deploiement Cloudflare Worker ou ntfy (gratuit, 100k req/jour) | Moyenne | Important | Fort |
| 2.7 | UI des reactions/commentaires inline dans le lecteur (overlay) | Moyenne | Important | Fort |
| 2.8 | Lien d'invitation au canal integre dans l'extension | Faible | Important | Moyen |
| 2.9 | Lecture du contenu du canal par l'ami via son propre compte Telegram | Moyenne | Essentiel | Fort |
| 2.10 | Signature cryptographique des messages pour anti-spoofing du pseudo | Moyenne | Important | Moyen |
| 2.11 | Commande /revoke accessible depuis l'app | Faible | Important | Moyen |

### Phase 2.5 - Refonte UI Spotify-like (Terminee)

| # | Tache | Statut |
|---|-------|--------|
| 2.5.1 | Composant CollectionCard (carre 3 covers + fleche) | FAIT |
| 2.5.2 | Ecran collection recursif (header + sous-cols + oeuvres) | FAIT |
| 2.5.3 | Stats Anime avec heures vues (via GetEpisodesByAnimeId) | FAIT |
| 2.5.4 | Banniere locale (fichier dans filesDir/banners/) + galerie | FAIT |
| 2.5.5 | Edition banniere + description (CollectionEditDialog) | FAIT |
| 2.5.6 | Carrousel RecentActivityCarousel (oeuvres + coll recentes, %) | FAIT |
| 2.5.7 | FAB + BottomSheet navigation rapide | FAIT |
| 2.5.8 | Collections racines en CollectionCard | FAIT |

### Phase UI-2 - Outil GIF (Reportee apres test APK)

| # | Tache | Statut |
|---|-------|--------|
| UI-2.1 | Picker video locale (ActivityResultContracts.OpenDocument) | A FAIRE |
| UI-2.2 | Slider selection 15s (debut + fin) | A FAIRE |
| UI-2.3 | Extraction GIF via FFmpeg-kit deja present | A FAIRE |
| UI-2.4 | Sauvegarde comme banniere locale (saveBannerFile) | A FAIRE |

### Phase 3 - Explorations
| # | Tache | Complexite | Besoin | Impact |
|---|-------|:----------:|:------:|:------:|
| 3.1 | PiP avance | Moyenne | Optionnel | Moyen |
| 3.2 | Choix de piste audio | Moyenne | Optionnel | Moyen |
| 3.3 | Support Torrent (TorrServer) | Elevee | Optionnel | Moyen |
| 3.4 | Notation/evaluation collaborative des episodes | Elevee | Optionnel | Moyen |
| 3.5 | Recommandations croisees ("ce que regardent tes amis") | Elevee | Optionnel | Moyen |

---

## Decisions a trancher

- [ ] Nom definitif : Shiro (recommande) vs autre (Kuro ecarte, Thot ecarte)
- [ ] Format de l'extension Shiro : .zip renomme, format binaire custom, JSON signe ?
- [ ] Signature des messages : par bot (deja authentifie par Telegram) ou par cle Ed25519 dediee ?
- [ ] Backend : zero serveur (100% pair-a-pair via bots + Telegram) ou ajout d'un relais optionnel ?
- [ ] Cible initiale : Android seulement, ou envisager un portage desktop (Tauri/Compose Multiplatform) plus tard ?
- [x] Priorite Phase 1 avant Phase 2 : cherry-pick d'abord (faible risque), social ensuite (vrai differenciateur mais base stable requise).

---

## Veille concurrentielle

Forks etudies (analyse detaillee en cours) :

| Fork | Philosophie | Distinction principale |
|------|-------------|------------------------|
| Animiru | Epure, anime-only | Suppression du manga, PiP integre |
| Animetail | Couteau suisse | Cast, torrent, Discord RPC, recherche avancee |
| Tadami | Gestion et diffusion | Backups multi-apps, choix piste audio |
| Kuro | Experience visuelle | Refonte UI/UX complete (Compose) |
| Aniyomi Plus | Organisation et decouverte | Dashboard AniList, categories imbriquees, torrent |

Aucun ne propose de couche sociale ou de partage pair-a-pair. C'est le creneau de Shiro.

---

## Prochains chantiers (priorises)

1. **Test APK** de la refonte UI (bloquant pour valider 2.5)
2. **Outil GIF FFmpeg** (Phase UI-2) apres validation UI
3. **Parsing intelligent Telegram** : regrouper `Bleach_01.mkv` + `Bleach_02.mkv` sous un seul anime "Bleach" avec episodes, via FileNameParser d'Aniyomi + AniList
4. **Branchement lecteur** : `videoListParse` doit retourner les URLs Telegram pour ExoPlayer
5. **Test reel auth Telegram** : numero + code + 2FA sur telephone (jamais teste)
6. **Notifications** : WorkManager 15 min en arriere-plan + WebSocket quand app ouverte
7. **Partage d'extensions entre amis** : format `.shiro`, import/export
8. **Commentaires/reactions natives** : stockage local + synchro

---

## Historique des revisions
| Version | Date | Changement |
|---------|------|------------|
| 0.1 | (initial) | Plan de base, phases 0-3, decisions a trancher |
| 0.2 | (precedente) | Renommage Thot -> Shiro, strategie cherry-pick, categorisation A/B/C, non-objectifs, architecture cible, veille concurrentielle |
| 0.3 | (precedente) | Notifications Android natives (WebSocket + WorkManager) au lieu de Telegram, relais WebSocket optionnel, contrainte UI figee (pas de refonte avant v2), note OEM/Doze, priorite Phase 1 > Phase 2 actee |
| 0.4 | (cette version) | Refonte UI Spotify-like terminee (CollectionCard, header recursif, banniere locale + galerie, stats heures, carrousel), outil GIF FFmpeg reporte apres test APK, Phase 2 Telegram documentee comme partielle (~40%) |
