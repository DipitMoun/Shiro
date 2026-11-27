# Quoi de neuf dans Shiro

Ce fichier suit la version en cours de préparation. La section **Quoi de neuf** est écrite pour
les utilisateurs et sert de texte à afficher après une mise à jour. La section **Reste à faire**
est interne et ne s'affiche pas.

À chaque release : renommer « Prochaine version » avec le numéro et la date, puis ouvrir une
nouvelle section vide au-dessus.

Dernière release publiée : 0.19.3 (code 136), le 25/11/2026.

---

## Prochaine version (en préparation)

(rien pour l'instant)

---

## 0.21.1 (27/11/2026)

### Quoi de neuf

**Sauvegardes**
- Un fichier de sauvegarde reçu par Telegram ou WhatsApp s'ouvre enfin avec « Ouvrir avec Shiro » :
  le traitement n'était branché que sur les intentions de salon Matrix, donc rien ne se passait.

**Social**
- L'activité est maintenant un carrousel de fiches : l'affiche, le titre, et la photo de la personne
  par-dessus. Tes propres ajouts y sont aussi, avec ta photo. Chaque fiche ouvre l'œuvre.
- L'activité est gardée sur le téléphone (jusqu'à 300 entrées) : elle est là dès l'ouverture et ne
  disparaît plus quand les salons répondent lentement.
- La pastille de modification de la photo de profil n'est plus coupée.

**Bibliothèque**
- Les cartes rondes « bientôt » ont un anneau qui se remplit à l'approche de la sortie, et la date
  est écrite dessous (« Ép. 11 · Demain 19:30 »). Plus de bandeau noir sur la photo.
- Les cartes « bientôt » existent aussi pour les mangas et webtoons : le prochain chapitre est
  estimé d'après le rythme des derniers (« Ch. 313 · vers lun »). Rien n'est affiché quand le rythme
  n'est pas clair ou que la série semble en pause.
- Une œuvre qu'on vient d'ajouter apparaît maintenant dans les cartes « bientôt » : une recherche
  AniList qui échouait (réseau) était retenue comme « introuvable », et les œuvres neuves passaient
  en dernier. Jusqu'à 8 cartes au lieu de 4.

---

## 0.21.0 (27/11/2026)

### Quoi de neuf

**Social**
- La page Social est plus légère : ta photo est petite, avec ton pseudo et un statut (60 caractères,
  visible 24 h par tes amis). Un appui sur la photo ouvre ta story, un « + » en ajoute une.
- Maintiens ta photo pour ouvrir tout ce qui concerne ton compte : photo, pseudo, notifications,
  partage de l'activité, QR et déconnexion.
- La liste d'amis et les stories forment une seule rangée : un anneau coloré autour d'un ami veut
  dire une story pas encore vue. Un appui ouvre sa story, un appui long le menu (bibliothèque,
  retirer). Son statut apparaît au-dessus de sa photo.
- Tu es prévenu quand un ami publie une story (une notification par ami, coupée quand l'app est ouverte).

**Notifications**
- Les notifications d'activité des amis affichent la couverture en grand quand on les déplie.
- Rappels de sortie pour les animés de ta bibliothèque : la veille à partir de 18 h, puis peu avant
  l'heure de diffusion, avec la couverture. Ils ont leur propre canal (« Rappels de sortie ») et
  s'ouvrent sur la fiche de l'animé. Pas de rappel pour les chapitres de manga : il n'existe pas de
  calendrier fiable.

**Recherche**
- La recherche dans les sources manga a maintenant la section « Catalogue » en haut (AniList, MAL,
  Kitsu), comme pour les animés, au-dessus des résultats de tes extensions.

---

## 0.20.1 (27/11/2026)

### Quoi de neuf

**Sauvegardes**
- Un fichier de sauvegarde reçu par Telegram, WhatsApp ou depuis un gestionnaire de fichiers s'ouvre
  directement avec Shiro (« Ouvrir avec » ou « Partager vers »), sans l'enregistrer d'abord à la main.

**Chat**
- Les images reçues dans un salon chiffré s'affichent (elles arrivaient en octets chiffrés, d'où la case
  grise avec l'icône d'image cassée).

**Bibliothèque**
- Les cartes rondes « bientôt » retrouvent maintenant l'identifiant AniList des œuvres ajoutées par
  la recherche multi-source, qui n'en avaient pas.

**Stories**
- Publie une photo ou une vidéo courte en story depuis la page Social : le « + » sur ton avatar, tout
  en haut. Tes amis la voient pendant 24 h dans le ruban, avec un anneau coloré tant qu'ils ne l'ont
  pas vue. Tu peux y ajouter une légende.
- Partage un extrait musical d'une œuvre de ta bibliothèque (un opening ou un ending, à partir du
  moment que tu choisis). Rien n'est envoyé : tes amis l'écoutent en direct depuis AnimeThemes. Si le
  service ne répond pas, la carte reste avec la pochette, le titre et l'artiste, sans le son.
- Un compteur d'envoi (100 Mo par 24 h, 500 Mo sur 28 jours, 10 Mo par fichier) te dit ce qu'il reste.
  Les extraits musicaux n'en consomment pas. Après 24 h, la story disparaît de l'app et son contenu
  est retiré du message ; le fichier peut toutefois rester sur le serveur.

**Chat**
- Images et GIF : envoie-les depuis le bouton image du champ de saisie. Les images reçues sont décodées
  par Android, donc plus de cases grises, et les GIF restent animés.
- « Voir l'œuvre » ouvre exactement la version qu'utilise ton ami (même extension, même source) au lieu
  de chercher. Si tu n'as pas l'extension, l'app propose de l'installer. Même chose depuis le fil
  d'activité.
- Les conversations des mangas affichent leur couverture dans l'onglet Messages.

**Bibliothèque**
- Le carrousel d'activité récente montre le numéro du prochain épisode ou chapitre sur chaque affiche
  et, pour les animés, des cartes rondes « bientôt » avec le compte à rebours. Appui long pour le
  détail. Le bouton « Reprendre » revient.
- Les couvertures grises des catégories s'affichent de nouveau.

**Sorties et extensions**
- Les dates de sortie viennent de nouveau d'AniList (la requête était refusée) : relances, cache et
  dernier résultat gardé si le réseau est mauvais.
- Nouvel écran « Santé des dépôts » (Réglages → Navigation) : il signale les dépôts qui ne répondent
  plus, les extensions que Shiro ne sait pas charger et celles dont la mise à jour est bloquée par la
  signature.
- Les extensions manga qui s'appuient sur des informations propres à un chapitre fonctionnent mieux.

**Application**
- Cet écran « Quoi de neuf » remplace la grande fenêtre.
- Les téléchargements continuent écran éteint ou dans une autre application.

**Correctifs**
- Plantage au retour arrière dans une catégorie sur Android 14 et moins.
- Plantage rare à l'ouverture de la fiche d'un animé.
- Plantage sur les sources qui répondent en compression zstd.

---

## 0.19.3 (25/11/2026)

### Quoi de neuf

**Téléchargements**
- Ils continuent écran éteint ou quand tu passes sur une autre application : le téléphone
  garde le processeur et le Wi-Fi éveillés pendant un téléchargement.
- Au premier téléchargement, une notification te propose d'autoriser Shiro à rester actif en
  arrière-plan, ce que certains téléphones exigent pour ne pas couper l'application.

**Bibliothèque**
- Les couvertures manquantes du carrousel « activité récente » (Animé et Manga) s'affichent
  de nouveau.

---

## 0.19.2 (25/11/2026)

### Quoi de neuf

**Chat**
- Le chat est refait : messages regroupés par personne, jours séparés, repère « Nouveaux
  messages » à l'ouverture, et un bouton pour revenir au dernier message.
- Les avatars de tes amis descendent sous le dernier message qu'ils ont lu. Tu vois aussi quand
  quelqu'un écrit, et tes messages affichent envoyé ou lu.
- Glisse un message vers la gauche pour y répondre, appuie longtemps pour réagir ou copier. Un
  message qui n'est pas parti se renvoie d'un appui, et ton brouillon est gardé par salon.
- Les moments anime et manga utilisent la même carte, floutée tant qu'on n'a pas touché pour
  révéler le spoiler.
- Pour partager un moment, tu choisis maintenant l'épisode ou le chapitre avec des flèches et
  une liste qui défile.
- La couverture de l'œuvre s'affiche de nouveau en fond et dans l'en-tête du chat.

**Correctifs**
- Plus de plantage de l'application sur certaines sources qui répondent en compression zstd.
- Plus de plantage en ouvrant un manga dont un chapitre a un numéro à virgule (311.01).

---

## 0.19.1 (25/11/2026)

### Quoi de neuf

**Manga**
- Les extensions manga du catalogue actuel de Keiyoushi se chargent de nouveau, y compris les
  françaises : MANGA Plus, Webtoons et bien d'autres. Le nouveau format d'extensions est pris
  en charge, sans casser les anciennes.
- Les œuvres multi-source manga fonctionnent comme celles de l'anime : le bouton Sources
  tourne pendant la recherche puis indique la couverture, un panneau liste les sources avec ce
  que chacune propose, et tu peux choisir la source principale. Chaque chapitre a des sources
  de secours si la première échoue.
- Plus d'alerte d'erreur quand on ouvre une œuvre : la fiche charge les détails et les
  chapitres en une seule demande.

**Anime**
- La fenêtre avant un téléchargement s'affiche tout de suite, avec la source de chaque vidéo,
  les langues et le poids estimé. Ton choix de source et de qualité est retenu pour les
  téléchargements groupés, et « 20 prochains épisodes » rejoint la liste.
- Les téléchargements vont plus vite : plusieurs connexions en même temps, avec le poids
  téléchargé affiché à côté du pourcentage.
- La recherche des sources est bien plus rapide.

**Social**
- Le chat existe aussi pour les mangas, avec un fil par chapitre. Tu peux partager une page :
  elle s'ouvre directement dans le lecteur chez ton ami.
- Envoyer un message depuis une notification est plus fiable, et si tu n'as pas l'œuvre en
  bibliothèque, Shiro te propose de l'ajouter pour répondre.
- Restaurer une sauvegarde ne signale plus comme erreur les dépôts déjà installés.

---

## 0.19.0

### Quoi de neuf

**Social**
- Social prend la place de « Mises à jour » dans la barre du bas, avec ta photo de profil
  comme icône. Les mises à jour restent accessibles depuis l'onglet Plus.
- Une seule page Social : ton profil, les demandes d'ami, tes amis et leur activité.
- Nouvel onglet Messages : toutes tes conversations d'œuvre au même endroit, avec les
  messages non lus.
- Activité : tu vois quand un ami ajoute une œuvre à sa bibliothèque, et tu l'ouvres d'un
  toucher. Tu peux couper le partage de ta propre activité dans les réglages Social.
- Le profil d'un ami : ses œuvres, celles que vous avez en commun et son activité récente.
- Tu peux retirer un ami.
- Crée ton compte directement depuis Shiro : le navigateur s'ouvre sur l'inscription et tu
  reviens dans l'app déjà connecté, sans rien ressaisir.
- Le salon d'une œuvre s'ouvre correctement depuis Messages et depuis les notifications.
- Change ta photo de profil directement depuis Shiro, sans passer par Element.
- Trouve tes amis par leur pseudo ou leur identifiant Matrix grâce à la recherche, avec les
  résultats qui s'affichent pendant la frappe.
- Les messages arrivent en notifications de conversation : fil des derniers messages, photo
  de l'ami, bandeau en haut de l'écran, réponse et « Marquer comme lu » sans ouvrir l'app.
- Les demandes d'ami apparaissent tout de suite, avec Accepter et Refuser directement dans la
  notification. Plus besoin de relancer l'app.
- Les messages et demandes d'ami arrivent instantanément, même écran éteint et app fermée,
  comme sur une vraie messagerie. Le contenu des messages ne passe jamais par un serveur tiers.
- Les notifications reprennent seules après un redémarrage du téléphone ou une mise à jour,
  et aucun message reçu pendant une coupure n'est perdu.
- Nouveau réglage « Notifications fiables » dans Social pour que Shiro reçoive les messages
  en arrière-plan.

**Sauvegardes**
- La restauration fonctionne de nouveau sans erreurs, y compris avec les œuvres Telegram et
  multi-source.
- Les collections et sous-collections retrouvent leur place exacte, même quand deux
  sous-collections portent le même nom.
- Les sauvegardes contiennent maintenant les bannières et descriptions des collections, les
  sources choisies pour les œuvres multi-source, les réglages Telegram et tes corrections
  manuelles Telegram (fusions, renommages, œuvres masquées).

**Bibliothèque**
- Les œuvres multi-source ne perdent plus leurs épisodes ni leur historique de visionnage quand les catalogues ne donnent pas le même nombre d'épisodes.
- Le bouton « Reprendre » s'affiche même quand le badge des épisodes non vus est désactivé.

### Reste à faire avant la release

- [ ] Tester sur téléphone : restaurer une sauvegarde créée par cette version, et une sauvegarde
      créée par l'ancienne version.
- [ ] Tester sur téléphone : envoi de la photo de profil et recherche d'amis.
- [x] Demande d'ami reçue app ouverte : popup sans relancer l'app (validé le 23/11).
- [ ] Écran d'aide « Notifications fiables » par fabricant (Infinix/Tecno/itel, Xiaomi, Oppo,
      Vivo, Huawei) : leurs gestionnaires de batterie gèlent Shiro malgré le push.
- [ ] Tester sur téléphone : notifications après redémarrage du téléphone sans ouvrir Shiro.
- [x] Message reçu écran éteint et app fermée, app ouverte, demande d'ami app fermée (validé le 24/11).
- [ ] Tester sur téléphone : historique et progression conservés sur une œuvre multi-source.
- [x] Commiter le travail social et sauvegarde.
- [x] Changer le mot de passe du keystore release (fait le 24/11, ancienne sauvegarde supprimée le 25/11).

### Pas encore fait (état du 25/11/2026)

Chat
- [x] Images et GIF dans le chat (fait le 25/11) : à valider sur téléphone (GIF animés, images chiffrées).
- [ ] Vérifier les accusés de lecture et « écrit… » avec un ami qui a aussi la 0.19.2 ou plus.
- [x] Couvertures des conversations manga dans l'onglet Messages (fait le 25/11).
- [ ] Le fil « activité » à 0 : cause jamais confirmée (compte `@shiro_moun` sur l'ancienne release ?).

Sources et dépôts
- [ ] Anime-Sama anime : installé en 14.18 depuis une autre app (autre clé de signature), donc la
      mise à jour en 16.35 du dépôt `yuzono/anime-repo` est refusée par Android. L'écran « Santé des
      dépôts » le signale ; il faut désinstaller puis réinstaller depuis ce dépôt.
- [ ] Tester l'écran « Santé des dépôts » (Réglages → Navigation) sur le téléphone.
- [x] `SChapter.memo` conservé hors base (fait le 25/11, `ChapterMemoStore`) : à valider avec une
      extension qui s'en sert.
- [ ] Lire les extensions JS (Weebox/Seanime) : abandonné pour l'instant, à rouvrir hors de l'app.

Téléchargements et mises à jour
- [ ] Vérifier sur téléphone que les téléchargements continuent écran éteint (0.19.3).
- [ ] Les patches de mise à jour ne couvrent que les 2 à 3 dernières versions : prévoir de garder
      `release-archive/` (hors git) et de ne jamais supprimer l'APK de base.
- [ ] Les testeurs qui ont l'APK reçu sur Telegram (0.19.0 de minuit) téléchargent l'APK complet :
      leur base n'est pas celle du téléphone de test.
- [ ] Diagnostic des couvertures : les échecs d'images sont dans les logs sous `ShiroImage`.

### Week-end du 27/11/2026 : ce qui est fait et ce qui reste

Fait dans la 0.20.0 : 1 et 2 (source exacte), 3 (carrousel), 5 (stories, avec le repli AnimeThemes → AniList/Jikan).
Reste : du point 4, le secours Kitsu/TVmaze, la correspondance des numéros d'épisode entre sources et
le bouton « télécharger » de l'appui long du carrousel. Pour les stories : mentions, texte sur l'image,
grille de galerie intégrée, notification de nouvelle story, et la validation sur deux comptes (quota
glissant ou fixe, un média supprimé rend-il du quota, réponses réelles d'AnimeThemes quand il revient).
Prochain gros chantier : un salon par utilisateur avec un fil par œuvre.

(Texte d'origine de la demande ci-dessous.)

1. **Retrouver la version exacte d'une œuvre grâce à l'extension.** À la création d'un salon,
   enregistrer comme paramètre le nom (le paquet) de l'extension / la source que l'auteur utilise,
   pour ne plus passer par la recherche multi-source quand un ami clique sur « Voir l'œuvre » :
   on rouvre directement la bonne source. Variante : le faire depuis le salon « activité » qui gère
   la bibliothèque, en y mettant la source de chaque œuvre. Si l'utilisateur qui clique n'a pas
   cette extension, lui proposer de l'installer.
2. **Même principe pour le clic sur les activités** (fil d'activité).
3. **Carrousel « activité récente » : « à regarder maintenant » et « bientôt »**, sans supprimer ce
   qu'il fait aujourd'hui et sans agrandir la section (même rangée, mêmes tailles de cartes).
   - Cartes « à regarder maintenant » : pastille du numéro de l'épisode suivant, point « Nouveau »
     quand il vient de sortir, petite icône si déjà téléchargé. Appui long : détail (épisode,
     source, heure de sortie, télécharger).
   - Cartes « bientôt » : **rondes** (choix visuel décidé), avec compte à rebours (« Demain »,
     « J‑2 », « 19 h »), uniquement pour les œuvres de la bibliothèque. Quand l'épisode sort et
     qu'une source l'a, la carte passe en « à regarder ».
   - Manga : seulement « à lire » (pas de calendrier de sortie fiable).
   - Le bouton « Reprendre » du carrousel ne dépend plus du réglage des grilles (fait en 0.19.8).
4. **Réparer la source des dates de sortie** (constat du 25/11, mesuré sur JoJo Steel Ball Run).
   - Bug : le calendrier demande à AniList `airingSchedule(airingAt_greater…)` sur `Media`, ce qui
     est refusé (HTTP 400 « Unknown argument ») et l'erreur est avalée : le calendrier ne reçoit
     jamais rien d'AniList. Correction : `Page.airingSchedules(mediaId_in, airingAt_greater,
     airingAt_lesser, sort: TIME)`, vérifiée (0,8 s, bonnes dates) ; repli `Media.airingSchedule
     (notYetAired: true)`.
   - Jikan (MyAnimeList) est injoignable (connexion refusée même sur la racine) : le sortir du
     calendrier. Kitsu (dates par épisode, sans heure) et TVmaze en secours.
   - Garder le dernier résultat en cache (aujourd'hui une erreur donne un calendrier vide, sans
     message) et réessayer.
   - Numérotation : les sources comptent différemment une œuvre en plusieurs parties (AniList :
     « 2nd & 3rd STAGE » repart à 1 ; Kitsu : continu). Rapprocher par ordre dans l'entrée et par
     date, pas seulement par numéro. Exemple : Steel Ball Run, épisode 10 (AniList) le 27/11/2026
     08:00 UTC, épisode 11 le 04/12/2026 08:00 UTC.
5. **Stories éphémères de 24 h** (social Matrix), cahier des charges ci-dessous.

#### Stories éphémères de 24 h, depuis la page Social (Matrix)

Les stories sont créées uniquement depuis la page Social, via un bouton « + » en haut du fil. Elles
apparaissent dans une section en haut du fil des amis et disparaissent après 24 h.

Deux types de story

1. Photo ou vidéo (galerie)
   - Envoyée dans le salon story de l'utilisateur (un salon Matrix par utilisateur, amis membres).
   - Consomme le quota Matrix : 100 Mo par 24 h, 500 Mo sur 28 jours, 10 Mo max par pièce jointe.
     Plusieurs médias possibles par story. Les médias reçus ne comptent pas dans le quota.
2. Extrait musical (AnimeThemes)
   - L'utilisateur choisit une œuvre de sa bibliothèque, puis un OP ou ED via l'API AnimeThemes, et
     un moment de départ.
   - Le message ne contient que la référence : identifiant AniList de l'œuvre, identifiant du
     thème, moment de départ. Aucun fichier, aucun lien vidéo, donc aucun quota consommé.
   - Chez l'ami, Shiro refait la résolution au moment de la lecture (appel à AnimeThemes) et lit le
     flux depuis le moment choisi, sans télécharger ni stocker le média. Si la résolution échoue :
     « impossible de charger cet extrait ».
   - Ne jamais réhéberger ni copier les fichiers AnimeThemes.

Compteur de quota (photo/vidéo uniquement)
- Enregistrer localement la taille réelle envoyée (fichier chiffré si chiffrement) et l'horodatage.
- Afficher le total des 24 dernières heures et des 28 derniers jours, avec la date approximative du
  prochain retour de quota.
- Avertir au-delà de 50 Mo en un envoi. Bloquer l'envoi si le quota restant est insuffisant, avec
  un message clair.

Expiration
- Après 24 h, la story est masquée dans l'app, puis une redaction est envoyée pour retirer le
  contenu du message. Ce n'est pas une suppression réelle du fichier côté serveur : ne pas le
  présenter comme tel dans l'UI.

Contraintes de travail
- Ne pas modifier la structure existante de la page Social hors de la zone des stories : la refonte
  UI est déjà en cours, garder les changements isolés et ciblés (extraits, pas de réécriture).
- Ne rien modifier sans montrer le diff avant.
- Travailler sur la branche `phase2-matrix`.

À vérifier avant de finaliser
- Si la fenêtre de quota (24 h et 28 jours) est glissante ou calée sur un jour fixe (non précisé
  par matrix.org).
- Si supprimer un média libère du quota : supposer que non tant que ce n'est pas testé.
- Les identifiants externes acceptés par l'API AnimeThemes pour la recherche.
- Les limites de quota ci-dessus sont celles annoncées : les confirmer sur le serveur utilisé.

### Plus tard

- [ ] Sauvegarde : liste d'amis sociale (dépend du compte Matrix, à réfléchir).
- [ ] Telegram : rendre la synchronisation globale automatique optionnelle.
- [ ] Telegram : mise à jour de l'interface canal par canal pendant une synchro.
- [ ] Telegram : partage entre amis.
- [x] Afficher ce fichier dans l'écran « Quoi de neuf » de l'app après une mise à jour
      (la section « Quoi de neuf » est copiée dans l'app au moment du build).
