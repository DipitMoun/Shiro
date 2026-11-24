# Publier une version de Shiro

L'app détecte seule les nouvelles versions (au lancement et toutes les 12 h en arrière-plan),
te prévient par une notification, puis télécharge **seulement ce qui a changé** (un patch d'une
dizaine de Mo au lieu de l'APK complet). Rien ne se compile sur GitHub : tu compiles chez toi et
tu publies des fichiers.

## À chaque version

1. Dans `app/build.gradle.kts`, monte `versionCode` (et `versionName`). L'app ne propose une
   mise à jour que si `versionCode` est plus grand que celui installé.
2. Mets à jour la section « Quoi de neuf » de `WHATS-NEW.md` : c'est le texte affiché au testeur.
3. Compile : `./gradlew :app:assembleRelease`
4. Prépare la publication : `node tools/release/make-release.js`
   - il crée `dist/release/` avec les APK, les patches et `latest.json` ;
   - il garde chaque APK dans `release-archive/<versionCode>/`, pour fabriquer les patches de la
     version suivante. **Ne supprime pas ce dossier** (il n'est pas dans git).
5. Sur GitHub, crée une release nommée `v<versionName>` (par exemple `v0.19.1`) et glisse **tous**
   les fichiers de `dist/release/`, `latest.json` compris. Publie.

Les testeurs dont l'app est à jour de la version précédente reçoivent le patch ; les autres
reçoivent l'APK complet.

## Bon à savoir

- Adresse lue par l'app : `https://github.com/DipitMoun/Shiro/releases/latest/download/latest.json`
  (la dernière release publiée). Pour changer d'hébergeur, mets `SHIRO_UPDATE_MANIFEST_URL=...`
  dans `local.properties` avant de compiler, et passe `--base-url` au script.
- Un patch est vérifié par empreinte avant d'être installé, et Android refuse de toute façon un
  APK signé par une autre clé. Si quoi que ce soit échoue, l'app télécharge l'APK complet.
- Le patch est petit tant que les bibliothèques natives (Matrix, ffmpeg, Telegram…) ne changent
  pas. Si on les met à jour, le patch grossit d'autant.
- Vérification de l'outil : `node tools/release/selftest.js`
