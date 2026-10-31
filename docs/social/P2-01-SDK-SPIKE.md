# P2-01 — Spike Matrix Rust SDK Android

> Statut : Terminé — **GO** pour `org.matrix.rustcomponents:sdk-android:26.10.23` derrière une interface `SocialRepository`.
> Date : 2026-10-31.
> Périmètre : Recherche et cadrage technique (Phase 2). Aucun fichier de build ou code source modifié.
> Vérification indépendante : `maven-metadata.xml` et le POM `26.10.23` ont été récupérés depuis Maven Central le 2026-10-31 ; version `latest/release = 26.10.23`, licence Apache-2.0, JNA `5.18.1@aar` confirmé.

## 1. Résumé exécutif

**Réponse** — **GO** pour l'intégration de **`org.matrix.rustcomponents:sdk-android:26.10.23`** (publié le 23 octobre 2026 sur Maven Central), qui expose les bindings Kotlin UniFFI officiels de `matrix-rust-sdk` (`matrix-sdk-ffi` + `matrix-sdk-ui`). Toutes les exigences verrouillées en Phase 0 (`PHASE-0-SPEC.md`) sont couvertes nativement : création de salles E2EE (`m.megolm.v1.aes-sha2`) avec `PowerLevels`, fils par épisode (`TimelineFocus.Thread`), injection et lecture du payload personnalisé `org.shiro.moment` v1 (`sendWithExtraContent` / `latestJson`), réactions (`toggleReaction`), épingles `org.shiro.pins` (`sendRaw`), sauvegarde de clés/Recovery (`Encryption`), et push HTTP `event_id_only` (`setPusher`). Le SDK doit être isolé derrière un contrat `SocialRepository` (P2-02) avec un store SQLite chiffré dédié, sans couplage au lecteur MPV ni à SQLDelight.

## 2. Bindings Android officiels et coordonnées Maven (Octobre 2026)

- **Artefact Maven Central** : `org.matrix.rustcomponents:sdk-android:26.10.23` (packaging `aar`, publié le 2026-10-23, commit `matrix-rust-sdk` `8de215a0aa` ; dernière nightly : `26.10.28-nightly` du 2026-10-29).
- **Artefact alternatif (bas niveau uniquement)** : `org.matrix.rustcomponents:crypto-android` (machine Olm/Megolm sans client HTTP, ni sync, ni timeline — insuffisant pour Shiro).
- **Dépendances transitives déclarées dans le POM (`26.10.23`)** :
  - `net.java.dev.jna:jna:5.18.1@aar` (`runtime` — pont FFI natif requis par UniFFI).
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3` (`runtime`, promu en `1.10.2` par le BOM Shiro).
  - `org.jetbrains.kotlin:kotlin-stdlib:1.9.25` (`compile`, compatible Kotlin `2.4.0` de Shiro).
  - `androidx.annotation:annotation:1.9.1` (`runtime`, identique à Shiro).
- **Architecture interne** : compilé via `cargo xtask kotlin build-android-library` (profil `small-release-stripped`, LTO activé) produisant `libmatrix_sdk_ffi.so` et les sources Kotlin générées par `uniffi-bindgen` dans le package `org.matrix.rustcomponents.sdk`.

## 3. Couverture fonctionnelle vis-à-vis de `PHASE-0-SPEC.md`

### 3.1 Salles, Power Levels, Événements personnalisés et Fils (`m.thread`)
- **Activation des threads côté client** : `ClientBuilder().threadsEnabled(enabled = true, threadSubscriptions = true)`.
- **Création de salle auteur + œuvre (§4.1–4.3)** : `Client.createRoom(CreateRoomParameters)` supporte nativement `isEncrypted = true` (`m.megolm.v1.aes-sha2`), `joinRuleOverride = JoinRule.Invite`, `historyVisibilityOverride = RoomHistoryVisibility.Shared`, `invite`, et `powerLevelContentOverride = PowerLevels(...)` (permettant de fixer `events["org.shiro.room"] = 100`, `events["org.shiro.pins"] = 100`, `invite = 100`, `stateDefault = 50`).
- **État initial `org.shiro.room` (§4.2)** : envoyé juste après création via `Room.sendStateEventRaw(eventType = "org.shiro.room", stateKey = "", content = json)` et lu/observé via `Room.stateEvents(...)` / `Room.subscribeToStateEvents(...)` (PR `#6350`).
- **Fil par épisode (§4.4)** :
  - Création du root d'épisode et envoi des moments avec payload `org.shiro.moment` v1 : `Timeline.sendWithExtraContent(msg, extraContentJson = "{\"org.shiro.moment\":{...}}")` (PR `#6812`) ou `Room.sendRaw("m.room.message", fullContentJson)` (#4788).
  - Focus et pagination d'un fil d'épisode : `Room.timelineWithConfiguration(TimelineConfiguration(focus = TimelineFocus.Thread(rootEventId = rootId), ...))`. Sur une timeline focalisée sur un thread, `Timeline.send(...)`, `Timeline.sendWithExtraContent(...)` et `Timeline.sendReply(msg, inReplyToEventId)` remplissent automatiquement la relation `m.relates_to` (`rel_type = "m.thread"`, PR `#5427`).
  - Découverte des fils existants (si cache local vide) : `Room.threadListService()` (`ThreadListService`, PR `#6311`, mars 2026) fournit une liste paginée et réactive de `ThreadListItem` (`rootEvent`, `latestEvent`, `numReplies`), complétée par `Room.loadOrFetchEventWithRelations(eventId, listOf(RelationType.Thread))`.
- **Lecture du payload `org.shiro.moment` (§3.1, §3.5)** : `EventTimelineItem` expose `eventTypeRaw: String?` et `lazyProvider.latestJson(): String?` (PR `#6387` / `#6424`), qui retourne le JSON complet de la dernière révision (éditions `m.replace` incluses). Le client parse `content["org.shiro.moment"]` avec `kotlinx.serialization` et retombe sur `content.body` si `v > 1` ou absent.
- **Réactions, éditions, redactions et épingles (§3.5, §6)** :
  - Réactions : `Timeline.toggleReaction(itemId, key)` et agrégation directe dans `EventTimelineItem.reactions`.
  - Édition / Redaction : `Timeline.edit(...)` / `Room.redact(eventId, reason)`. Pour éditer un moment en conservant le nouveau `org.shiro.moment` dans `m.new_content`, utiliser `Room.sendRaw("m.room.message", replacePayloadJson)`.
  - Épingles « moment clé » (§6.2) : événement timeline chiffré `org.shiro.pins` envoyé via `Room.sendRaw(eventType = "org.shiro.pins", content = pinsJson)`. Dans la timeline, il apparaît comme `MsgLikeKind.Other` avec `eventTypeRaw == "org.shiro.pins"` et son JSON est lu via `lazyProvider.latestJson()`.

### 3.2 Chiffrement E2EE, Sauvegarde de clés, Cross-Signing et Sync (§7)
- **Store chiffré SQLite** : `SqliteStoreBuilder(dataPath, cachePath).passphrase(...)` (ou `.key(byteArray)` généré et stocké dans Android Keystore) passé à `ClientBuilder.sqliteStore(...)`. Crée `matrix-sdk-crypto.sqlite3`, `matrix-sdk-state.sqlite3`, `matrix-sdk-event-cache.sqlite3` et `matrix-sdk-media.sqlite3` (chiffrés via SQLCipher embarqué dans le binaire Rust).
- **Key Backup & Recovery** : `ClientBuilder` configure `autoEnableCrossSigning(true)`, `autoEnableBackups(true)`, `backupDownloadStrategy(BackupDownloadStrategy.AfterDecryptionFailure)`. Le contrôleur `Client.encryption()` expose :
  - `enableRecovery(waitForBackupsToUpload, key, progressListener): String` (retourne la clé Base58),
  - `recover(recoveryKey)` et `recoverAndFixBackup(recoveryKey)`,
  - `backupStateListener`, `recoveryStateListener`, `verificationStateListener`,
  - `startDehydratedDevices(...)` (MSC3814).
- **Vérification SAS / QR** : `Client.getSessionVerificationController()` (`SessionVerificationController`) gère les requêtes entrantes/sortantes, les emojis/décimales SAS (`startSasVerification`, `approveVerification`) et `LoginWithQrCodeHandler`.
- **Synchronisation (Sliding Sync vs Sync v2)** :
  - `Client.syncService()` utilise Simplified Sliding Sync (MSC4186, natif sur Synapse ≥ 1.163 et `matrix.org`).
  - **Fallback Homeserver BYO** : `Client.syncV2(settings, listener)` et `Client.syncOnceV2(settings)` (PR `#6359`) permettent de synchroniser via `/_matrix/client/v3/sync` standard si un homeserver auto-hébergé ne supporte pas encore MSC4186.
- **Push UnifiedPush + ntfy (§7.1)** : `Client.setPusher(...)` prend nativement `PusherKind.Http(HttpPusherData(url = "https://ntfy.sh/_matrix/push/v1/notify", format = PushFormat.EventIdOnly, defaultPayload = null))`. Au réveil du push, `Client.notificationClient(NotificationProcessSetup.SingleProcess)` récupère et déchiffre l'événement.

## 4. Contraintes Android : Build, NDK, ABI, Taille APK, R8, Interop Kotlin

| Critère | Configuration Shiro actuelle | `org.matrix.rustcomponents:sdk-android:26.10.23` | Compatibilité / Action requise |
|---|---|---|---|
| `minSdk` / `compileSdk` | `minSdk = 26`, `compileSdk = 36` | `minSdk = 21`, `compileSdk = 34` | **Compatible** sans adaptation. |
| NDK / Build Rust | NDK `27.1.12297006` | AAR précompilé (NDK `29.0.14206865` en CI) | **Aucune compilation Rust ni NDK requise** en consommateur Maven. |
| ABIs supportées | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` | **Alignement 1:1** avec `splits.abi` de `app/build.gradle.kts`. |
| Taille AAR / APK | Splits par ABI + APK universel | AAR 4 ABIs ≈ **100,6 Mo** (`libmatrix_sdk_ffi.so` + `libjnidispatch.so`) | **+22 à +26 Mo** sur l'APK `arm64-v8a` ; **+90 à +100 Mo** sur l'APK universel. |
| Conflits SQLite | `requery:sqlite-android` (`libsqlite3x.so`) | SQLite/SQLCipher compilé statiquement dans `libmatrix_sdk_ffi.so` | **Aucun conflit de symbole `.so`** ni collision avec SQLDelight. |
| R8 / ProGuard | `isMinifyEnabled = true` (`-dontobfuscate`) | `consumer-rules.pro` vide dans l'AAR `sdk-android` | **Action obligatoire** : ajouter les règles `-keep` JNA/UniFFI dans `app/proguard-rules.pro`. |
| Interop Kotlin & GC | Kotlin `2.4.0`, Coroutines `1.10.2`, JVM 17 | Fonctions `suspend`, callbacks listener, `AutoCloseable` (`use`) | Envelopper les listeners en `callbackFlow` et fermer les objets FFI (`Room`, `Timeline`, `TaskHandle`). |

**Règles R8 obligatoires à ajouter en P2-05 dans `app/proguard-rules.pro`** (réf. Element X commit `8aa186d`) :
```proguard
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
```

## 5. Comparaison des options d'intégration

| Critère | Option 1 : **`org.matrix.rustcomponents:sdk-android` + Adapter Shiro (Recommandé)** | Option 2 : Fork / Build local `matrix-rust-sdk` FFI (`cargo-ndk`) | Option 3 : Alternatives (`matrix-android-sdk2` / `Trixnity`) |
|---|---|---|---|
| **Principe** | Consommer l'AAR Maven officiel + wrapper Kotlin mince dans Shiro (`SocialRepository`). | Cloner `matrix-rust-sdk`, écrire son propre crate UniFFI et compiler via `cargo-ndk`. | Ancien SDK Kotlin Element (`1.6.50`) ou SDK KMP `Trixnity` (`5.9.2`). |
| **Maintenance & CI** | Zéro toolchain Rust en CI ; mises à jour hebdomadaires par l'équipe Element X. | Lourd : Rust + `cargo-ndk` sur Windows/CI, temps de build +15 min, maintenance FFI. | `matrix-android-sdk2` en maintenance minimale (Element Classic) ; `Trixnity` hors spec §7.2. |
| **Support Spec Phase 0** | **100 %** (`sendWithExtraContent`, `sendRaw`, `TimelineFocus.Thread`, `ThreadListService`, E2EE). | 100 %, mais sans avantage puisque `matrix-sdk-ffi` expose déjà `sendRaw` et `latestJson`. | `matrix-android-sdk2` : pas de Sliding Sync natif, dette héritée ; `Trixnity` : UI timeline à écrire. |
| **Licence** | **Apache-2.0** (compatible GPLv3/Apache de Shiro). | **Apache-2.0**. | **Apache-2.0** (`trixnity-messenger` est AGPL-3.0). |
| **Verdict** | **Retenu** | **Rejeté** (surcoût CI injustifié) | **Rejeté** (`PHASE-0-SPEC.md` §7.2 impose Matrix Rust SDK) |

> **Note sur les wrappers Element X (`io.element.android.libraries.matrix.*`)** : ils ne sont pas publiés sur Maven Central en tant que bibliothèque autonome et le dépôt `element-x-android` est sous licence **AGPL-3.0-only OR Commercial**. Shiro doit donc consommer directement `org.matrix.rustcomponents:sdk-android` (Apache-2.0) et écrire son propre wrapper léger autour de `Client`, `Room` et `Timeline`.

## 6. Décision GO / NO-GO et ordre minimal d'implémentation

### Décision : **GO**
Aucun bloqueur technique ni juridique. L'artefact `org.matrix.rustcomponents:sdk-android:26.10.23` (Apache-2.0) répond à l'intégralité du cahier des charges Phase 0 sans nécessiter de compilation Rust locale.

### Points d'attention à verrouiller dès P2-02 / P2-05
1. **Authentification Homeserver (OAuth 2.0 vs Password)** : depuis avril 2025, `matrix.org` utilise Matrix Authentication Service (MAS / OAuth 2.0). `P2-05` doit supporter `Client.homeserverLoginDetails()`, `Client.login(...)` (pour homeservers auto-hébergés classiques) **et** `Client.urlForOAuth(...)` / `loginWithOAuthCallback(...)` (ou jeton d'accès personnel en dev) si l'utilisateur cible `matrix.org`.
2. **Gestion mémoire FFI** : chaque objet UniFFI (`Client`, `Room`, `Timeline`, `TaskHandle`) détient un `Arc` Rust. Toujours annuler les `TaskHandle` à la fermeture de l'overlay et appeler `.close()` / `.use {}` pour éviter les fuites natives.
3. **Déclaration explicite de JNA AAR** : ajouter `implementation("net.java.dev.jna:jna:5.18.1@aar")` à côté de `sdk-android` pour garantir l'inclusion de `libjnidispatch.so`.

### Ordre minimal d'implémentation (Phase 2)
1. **P2-02 — Contrats & Frontière** : définir `SocialRepository`, `MatrixSessionManager`, et le feature flag sans importer `org.matrix.rustcomponents.sdk` dans `domain` ou `PlayerViewModel`.
2. **P2-03 & P2-04 (en parallèle)** : implémenter la résolution canonique `workId`/`episodeKey` (SQLDelight) et le sérialiseur/validateur Kotlinx de `org.shiro.moment` v1 + `org.shiro.pins`.
3. **P2-05 — Dépendance Gradle, R8 & Session E2EE** : ajouter `sdk-android:26.10.23` + `jna:5.18.1@aar` + règles R8 ; initialiser `ClientBuilder` (`threadsEnabled(true, true)`, `SqliteStoreBuilder` avec clé Android Keystore, `autoEnableCrossSigning(true)`, `autoEnableBackups(true)`), login/restore, et Recovery Key onboarding.
4. **P2-06 — Salles & Fils** : créer la salle `(owner_mxid, local_work_id)` via `createRoom` + `sendStateEventRaw("org.shiro.room")`, gérer les invitations 1er degré, et créer/résoudre le root de fil par épisode.
5. **P2-07 & P2-08 — Sync, Timeline & Envoi** : brancher `SyncService` (avec fallback `syncV2`), ouvrir `Room.timelineWithConfiguration(TimelineFocus.Thread(rootId))`, convertir `TimelineListener` en `Flow`, extraire `latestJson()`, et câbler `sendWithExtraContent`, `sendReply`, `toggleReaction`, `sendRaw("org.shiro.pins")`.
6. **P2-09 à P2-12** : gestion d'audience/bannière d'ajout d'œuvre, branchement de `SocialMomentOverlay`, tests unitaires/smoke E2EE et durcissement.

## 7. Sources

1. **Maven Central — `org.matrix.rustcomponents:sdk-android`** (v`26.10.23`, publié le 2026-10-23, POM & dépendances) : <https://central.sonatype.com/artifact/org.matrix.rustcomponents/sdk-android> / <https://repo1.maven.org/maven2/org/matrix/rustcomponents/sdk-android/>
2. **GitHub Releases & Build — `matrix-org/matrix-rust-components-kotlin`** (`sdk-v26.10.23` du 2026-10-23, `sdk/sdk-android/build.gradle`, `scripts/build.sh`) : <https://github.com/matrix-org/matrix-rust-components-kotlin/releases>
3. **Code source FFI — `matrix-org/matrix-rust-sdk` (`bindings/matrix-sdk-ffi`)** (`CHANGELOG.md`, `client_builder.rs`, `client.rs`, `room/mod.rs`, `timeline/mod.rs`, `timeline/configuration.rs`, `timeline/threads.rs`, `encryption.rs`, `store.rs`, consultés sur `main` en oct. 2026) : <https://github.com/matrix-org/matrix-rust-sdk/tree/main/bindings/matrix-sdk-ffi>
4. **Element X Android — Intégration de référence & R8** (`gradle/libs.versions.toml`, `libraries/matrix/impl/build.gradle.kts`, `app/build.gradle.kts`, commits `8aa186d` et `c5bb18c`, oct. 2026) : <https://github.com/element-hq/element-x-android>
5. **Matrix Docs — SDKs Overview & `matrix-rust-sdk`** (état au 2026-10-18 : MSC4186 Sliding Sync, MAS OAuth 2.0, Cross-signing MSC4153, Trixnity `5.9.2`, `matrix-android-sdk2` `1.6.50`) : <https://matrixdocs.github.io/docs/sdks/overview> et <https://matrixdocs.github.io/docs/sdks/matrix-rust-sdk>
