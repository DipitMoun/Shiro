# Phase 1 â€” Prototype UI local

> Statut : Phase 1 clÃ´turÃ©e â€” UI validÃ©e, dette nettoyÃ©e, tests JVM et build OK.
> Objectif : valider l'overlay social et ses interactions avec des donnÃ©es
> factices, sans Matrix, sans compte et sans backend Shiro.

## PÃ©rimÃ¨tre

- Overlay paysage au-dessus du player en pause.
- EntrÃ©e debug unique dans les contrÃ´les du player.
- Colonne gauche de 45 % avec un fondu continu sombre â†’ transparent.
- Sidebar compacte : amis ayant un message prÃ¨s du moment + `Nouveau chat`.
- Chat natif sans header, sans label d'audience, sans pins et sans bouton
  `Publier Ã  mes amis`.
- Messages avec timecodes cliquables et rÃ©actions.
- Affiche native 2:3 avec uniquement le titre de l'Å“uvre.
- Fond plein Ã©cran : cover floutÃ©e, opaque en pause ; la vidÃ©o ne transparaÃ®t
  pas derriÃ¨re l'affiche.
- AperÃ§u rÃ©el de 30 s branchÃ© sur MPV (`ab-loop-a` / `ab-loop-b`), son coupÃ©.
- Pause de l'aperÃ§u â†’ retour Ã  l'affiche ; `Reprendre` ferme l'overlay.
- DonnÃ©es factices Kevin / Allan / David / Toi.

## DÃ©cisions de design validÃ©es

- Un fil par auteur + Å“uvre + Ã©pisode ; le moment est une ancre temporelle.
- La liste de gauche ne montre que les amis auteurs proches du moment.
- `Nouveau chat` crÃ©e/sÃ©lectionne le fil personnel.
- Le chat n'affiche ni header, ni audience, ni pins.
- Le premier message porte le moment ; les autres peuvent porter un timecode.
- Pas de bouton de publication dans le composer.
- L'affiche est au format natif 2:3 et ne contient que le titre.
- La limite gauche/droite est un dÃ©gradÃ©, sans bord vertical.
- Les panneaux sont plein Ã©cran : pas de bande en haut, en bas ou Ã  gauche.
- Pendant l'aperÃ§u, l'opacitÃ© gÃ©nÃ©rale de la couche sociale est abaissÃ©e.

## Fichiers

- `app/src/main/java/eu/kanade/tachiyomi/ui/social/SocialModels.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/social/SocialMomentOverlay.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/social/SocialModelsTest.kt`

## IntÃ©gration

- `PlayerActivity` hÃ©berge l'overlay au-dessus de `PlayerControls`.
- `PlayerControls` / `TopRightPlayerControls` reÃ§oivent un callback optionnel
  `onSocialClick`.
- Le bouton n'est exposÃ© qu'en `BuildConfig.DEBUG`.
- Aucun changement du layout ou du style du lecteur en dehors du bouton debug.

## Limites volontaires

- Les messages, rÃ©actions et brouillons ne sont pas persistÃ©s.
- Aucun appel Matrix, aucun compte, aucun backend.
- La cover peut Ãªtre chargÃ©e par Coil depuis l'URL locale de l'Å“uvre.
- Les modes de dÃ©mo restent un outil de dÃ©veloppement, pas une fonctionnalitÃ©
  utilisateur.
- La couche sociale n'est pas encore exposÃ©e en release.

## Validation

- Tests JVM sociaux : **23/23 PASS** (`SocialModelsTest` 16, `SocialUiStateTest` 7).
- Tests Telegram : **tous PASS**.
- `:app:compileDebugKotlin` : **OK**.
- `:app:assembleDebug` : **OK**.
- APK : `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- SHA-256 : `468A9827144795DCEA0131D3036B1A9693027D60518817385454CA2CA86B0501`
- Test manuel : reportÃ© Ã  la demande du produit ; la validation appareil sera
  faite aprÃ¨s les phases d'intÃ©gration.

## Prochaine phase

L'intÃ©gration Matrix (salles, Ã©vÃ©nements `org.shiro.moment`, invitations,
E2EE et notifications UnifiedPush/ntfy) reste hors Phase 1. Elle dÃ©marrera
aprÃ¨s validation de cette clÃ´ture locale.

---

## Validation post-opencode (2026-10-31)

Cette section documente l'etat de la phase 1 apres l'integration du travail opencode (~26 k lignes hors perimetre social, cf. commit `30ff2b02e`).

### Preuves de validation

- `:app:compileDebugKotlin` : **SUCCESSFUL** (2026-10-31, `--no-configuration-cache`)
- `SocialModelsTest` : **16/16 PASS**
- `SocialUiStateTest` : **7/7 PASS**
- Total social : **23/23 PASS**
- `:app:assembleDebug` : **non execute en cloture** -- bloque par D8/R8 8.9.32 + Kotlin 2.x metadata (bug externe, non lie au code social). Ticket phase 2.
- APK de reference phase 1 : `468A9827144795DCEA0131D3036B1A9693027D60518817385454CA2CA86B0501` (produit avant integration opencode, cf. section "Validation" ci-dessus).

### Notes de build

- `org.gradle.configuration-cache=false` ajoute pour contourner l'incompatibilite du plugin `shortcut-helper` 0.1.2 (`Task.project` non supporte par config-cache).
- Commande de verification fiable : `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*Social*" --no-configuration-cache`.
- `:app:assembleDebug` a stabiliser en debut de phase 2 (upgrade AGP/R8 ou exclusion ciblee).

### Decision

Phase 1 **cloturee** sur la base des preuves `compileDebugKotlin` + `23/23` tests, conformement au perimetre de ce document. L'APK de reference reste `468A98...`. Aucun changement fonctionnel du code social depuis.