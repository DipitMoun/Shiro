# Quoi de neuf dans Shiro

Ce fichier suit la version en cours de préparation. La section **Quoi de neuf** est écrite pour
les utilisateurs et sert de texte à afficher après une mise à jour. La section **Reste à faire**
est interne et ne s'affiche pas.

À chaque release : renommer « Prochaine version » avec le numéro et la date, puis ouvrir une
nouvelle section vide au-dessus.

Dernière release publiée : 0.19.3 (code 136), le 25/11/2026.

---

## Prochaine version (en préparation)

### Quoi de neuf

**Correctif**
- « Voir l'œuvre » dans une conversation ouvre l'œuvre exacte : l'app cherche dans les animes et les
  mangas, garde le titre le plus proche (synonymes compris), et une œuvre dont le titre n'est qu'un
  morceau du bon (« Le Retour » pour « Le retour du clan Hwasan ») n'est plus prise pour elle.

**Chat**
- Tu peux envoyer des images et des GIF : le bouton image à côté du champ de saisie, ou
  « Image ou GIF » dans le menu +. Les grosses photos sont réduites avant l'envoi, les GIF
  gardent leur animation, et un appui sur une image l'ouvre en grand.
- Quand tu ouvres la conversation d'une œuvre que tu n'as pas, le bouton « Voir l'œuvre » tourne
  pendant la recherche et ouvre la bonne œuvre (plus « Le retour » à la place de « Le retour du
  clan Hwasan »). La bannière est aussi refaite.
- Les conversations des mangas affichent leur couverture dans l'onglet Messages.

**Extensions**
- Nouvel écran « Santé des dépôts » (Réglages → Navigation) : il dit si chaque dépôt répond, s'il
  utilise une version d'extensions que Shiro ne sait pas charger, et signale les extensions dont la
  mise à jour est bloquée parce qu'elles viennent d'une autre source (il suffit alors de les
  désinstaller puis de les réinstaller depuis le bon dépôt). Une vérification hebdomadaire prévient
  par notification quand quelque chose change.
- Les extensions manga qui s'appuient sur des informations propres à un chapitre (nouveau
  format) fonctionnent mieux : ces informations sont désormais conservées.

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

### Plus tard

- [ ] Sauvegarde : liste d'amis sociale (dépend du compte Matrix, à réfléchir).
- [ ] Telegram : rendre la synchronisation globale automatique optionnelle.
- [ ] Telegram : mise à jour de l'interface canal par canal pendant une synchro.
- [ ] Telegram : partage entre amis.
- [x] Afficher ce fichier dans l'écran « Quoi de neuf » de l'app après une mise à jour
      (la section « Quoi de neuf » est copiée dans l'app au moment du build).
