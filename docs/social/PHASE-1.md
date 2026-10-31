# Phase 1 — Prototype UI local

> Statut : Phase 1 clôturée — UI validée, dette nettoyée, tests JVM et build OK.
> Objectif : valider l'overlay social et ses interactions avec des données
> factices, sans Matrix, sans compte et sans backend Shiro.

## Périmètre

- Overlay paysage au-dessus du player en pause.
- Entrée debug unique dans les contrôles du player.
- Colonne gauche de 45 % avec un fondu continu sombre → transparent.
- Sidebar compacte : amis ayant un message près du moment + `Nouveau chat`.
- Chat natif sans header, sans label d'audience, sans pins et sans bouton
  `Publier à mes amis`.
- Messages avec timecodes cliquables et réactions.
- Affiche native 2:3 avec uniquement le titre de l'œuvre.
- Fond plein écran : cover floutée, opaque en pause ; la vidéo ne transparaît
  pas derrière l'affiche.
- Aperçu réel de 30 s branché sur MPV (`ab-loop-a` / `ab-loop-b`), son coupé.
- Pause de l'aperçu → retour à l'affiche ; `Reprendre` ferme l'overlay.
- Données factices Kevin / Allan / David / Toi.

## Décisions de design validées

- Un fil par auteur + œuvre + épisode ; le moment est une ancre temporelle.
- La liste de gauche ne montre que les amis auteurs proches du moment.
- `Nouveau chat` crée/sélectionne le fil personnel.
- Le chat n'affiche ni header, ni audience, ni pins.
- Le premier message porte le moment ; les autres peuvent porter un timecode.
- Pas de bouton de publication dans le composer.
- L'affiche est au format natif 2:3 et ne contient que le titre.
- La limite gauche/droite est un dégradé, sans bord vertical.
- Les panneaux sont plein écran : pas de bande en haut, en bas ou à gauche.
- Pendant l'aperçu, l'opacité générale de la couche sociale est abaissée.

## Fichiers

- `app/src/main/java/eu/kanade/tachiyomi/ui/social/SocialModels.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/social/SocialMomentOverlay.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/social/SocialModelsTest.kt`

## Intégration

- `PlayerActivity` héberge l'overlay au-dessus de `PlayerControls`.
- `PlayerControls` / `TopRightPlayerControls` reçoivent un callback optionnel
  `onSocialClick`.
- Le bouton n'est exposé qu'en `BuildConfig.DEBUG`.
- Aucun changement du layout ou du style du lecteur en dehors du bouton debug.

## Limites volontaires

- Les messages, réactions et brouillons ne sont pas persistés.
- Aucun appel Matrix, aucun compte, aucun backend.
- La cover peut être chargée par Coil depuis l'URL locale de l'œuvre.
- Les modes de démo restent un outil de développement, pas une fonctionnalité
  utilisateur.
- La couche sociale n'est pas encore exposée en release.

## Validation

- Tests JVM sociaux : **23/23 PASS** (`SocialModelsTest` 16, `SocialUiStateTest` 7).
- Tests Telegram : **tous PASS**.
- `:app:compileDebugKotlin` : **OK**.
- `:app:assembleDebug` : **OK**.
- APK : `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- SHA-256 : `468A9827144795DCEA0131D3036B1A9693027D60518817385454CA2CA86B0501`
- Test manuel : reporté à la demande du produit ; la validation appareil sera
  faite après les phases d'intégration.

## Prochaine phase

L'intégration Matrix (salles, événements `org.shiro.moment`, invitations,
E2EE et notifications UnifiedPush/ntfy) reste hors Phase 1. Elle démarrera
après validation de cette clôture locale.
