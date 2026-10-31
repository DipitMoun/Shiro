# Phase 2 - Etat d'avancement

> Checkpoint : 2026-10-31
> Branche : phase2-matrix (base : merge b07a58e63)
> Dernier commit : f79670914 P2-06b

## Resume

Phase 2 demarree. La couche SDK Matrix est en place et testee (session, salles,
fils) derriere des interfaces SDK-free. Il reste le sync, l'envoi, l'audience
et l'UI branchee.

Avancement estime : ~50 %.

## Etat par ticket

| Ticket | Etat | Commits |
|---|---|---|
| P2-01 Spike SDK | OK | P2-01-SDK-SPIKE.md |
| P2-02 Contrats internes | OK | opencode |
| P2-03 Identite canonique | OK | opencode + 6 tests |
| P2-04 Payload org.shiro.moment | OK | opencode + 8 tests |
| P2-05a Squelette session | OK | f775a3aba |
| P2-05b Factory offline | OK | 4bf60c588 |
| P2-05c-1 Credentials store | OK | dc22b5e61 + 8 tests |
| P2-05c-2 State machine | OK | 1d3cb327c + 11 tests |
| P2-05c-3a RustSessionDelegate | OK | 2053d0fdc |
| P2-06a SocialRoomService | OK | ea092a0c1 + 11 tests |
| P2-06b RustMatrixRoomTransport | OK | f79670914 |
| P2-07 Sync + timeline | TODO prochain | - |
| P2-08 Envoi / reactions | TODO | - |
| P2-09 Audience + push | TODO | - |
| P2-10 UI overlay branchee | TODO | - |
| P2-11 Tests integration | TODO | - |
| P2-12 Build R8 + durcissement | TODO | - |

## Signatures SDK confirmees (sdk-android 26.10.23)

Obtenues par reflection probe. Ne plus re-prober, ces valeurs sont stables.

### ClientBuilder

```
ClientBuilder()
  .homeserverUrl(String): ClientBuilder
  .sqliteStore(SqliteStoreBuilder): ClientBuilder
  .threadsEnabled(boolean, boolean): ClientBuilder
  .autoEnableCrossSigning(boolean): ClientBuilder
  .autoEnableBackups(boolean): ClientBuilder
  .build(Continuation): Object          // suspend, retourne Client
```

### SqliteStoreBuilder

```
SqliteStoreBuilder(dataPath: String, cachePath: String)
  .passphrase(String): SqliteStoreBuilder
  .key(ByteArray): SqliteStoreBuilder

// Pas de .build() : le builder est passe a ClientBuilder.sqliteStore(...)
```

### Client

```
createRoom(CreateRoomParameters, Continuation): Object   // suspend, retourne String (roomId)
getRoom(String): Room?
login(username, password, initialDeviceName, deviceId, Continuation): Object
restoreSession(Session, Continuation): Object
session(): Session
userId(): String
deviceId(): String
homeserver(): String
homeserverLoginDetails(Continuation): Object            // pour MAS OAuth
loginWithOauthCallback(String, Continuation): Object    // pour matrix.org MAS
```

### Session (data class)

```
Session(userId, deviceId, accessToken, homeserverUrl, oauthData, refreshToken,
        slidingSyncVersion: SlidingSyncVersion)

// Constructeur prend SlidingSyncVersion (le type).
// Enums : SlidingSyncVersion.NATIVE, SlidingSyncVersion.NONE
```

### Room

```
id(): String
sendStateEventRaw(type: String, stateKey: String, content: String,
                  override: RequestConfigOverride?): String   // suspend
sendRaw(type: String, content: String, override: RequestConfigOverride?): String
inviteUserById(mxid: String): Unit
timelineWithConfiguration(...)      // P2-07
threadListService()                  // P2-07
subscribeToMessageLikeEvents(...)    // P2-07
subscribeToStateEvents(...)          // P2-07
```

### CreateRoomParameters (13 params nommes)

```
CreateRoomParameters(
    name, topic, isDirect, isEncrypted,
    visibility: RoomVisibility,
    preset: RoomPreset,
    invite: List<String>,
    avatar: String?,
    powerLevelContentOverride: PowerLevels,
    joinRuleOverride: JoinRule,
    historyVisibilityOverride: RoomHistoryVisibility,
    canonicalAlias: String?,
    isSpace: boolean,
)
```

### PowerLevels

```
PowerLevels(
    usersDefault, eventsDefault, stateDefault,
    ban, kick, redact, invite,
    notifications: NotificationPowerLevels?,
    events: Map<String, Int>,
    users: Map<String, Int>,
)
```

### Enums

- RoomPreset.PRIVATE_CHAT, .PUBLIC_CHAT, .TRUSTED_PRIVATE_CHAT
- RoomVisibility.Private, JoinRule.Invite, RoomHistoryVisibility.Shared

## Patterns a respecter

1. Interfaces SDK-free dans data/social/matrix/ (MatrixTransport,
   MatrixRoomTransport, SessionDelegate). Seules les impls Rust* importent
   org.matrix.rustcomponents.sdk.*.

2. SocialResult<T> partout. Success(value) / Failure(SocialError). Les
   implementations ne throw jamais pour des erreurs attendues (network, auth,
   permission, rate limit).

3. suspend sur toutes les methodes de contrat, meme si l'impl peut etre
   synchrone. Permet l'evolution sans casser les callers.

4. Fakes en test (dans app/src/test/...) : FakeSessionDelegate,
   FakeMatrixRoomTransport. Scriptables, comptent les appels.

5. InMemory* stores utilisables en production jusqu'a ce que le SQLDelight
   arrive (P2-07). Ne pas oublier de les remplacer a ce moment-la.

6. Tests JVM purs (runBlocking), pas d'androidTest. Toute la logique metier
   doit etre testable sans SDK.

7. Messages de commit : pas d'em-dash ni de backtick (PowerShell les mange
   dans git commit -m). Reste en ASCII simple.

## Prochaines etapes - P2-07

1. Definir MatrixSyncTransport (SDK-free) avec sync(request): SocialResult<SyncUpdate>
   deja declare dans SocialMatrixContracts.MatrixTransport. Reste a l'implementer
   cote Rust.

2. Probe SDK : Client.syncService(), Room.timelineWithConfiguration(...),
   TimelineListener, TimelineFocus.Thread (noms exacts a confirmer).

3. Ecrire RustMatrixSyncTransport (wrappe le Client via
   RustSessionDelegate.clientOrNull()).

4. Ecrire InMemoryMomentRepository qui ecoute les updates et projette en
   SocialThreadTimeline (testable avec un fake sync).

5. Tests : mock MatrixSyncTransport, verifier l'emission de
   Flow<SocialResult<SocialThreadTimeline>>.

## Reference

- docs/social/PHASE-2-PERT.md - planning initial
- docs/social/P2-01-SDK-SPIKE.md - decisions SDK
- docs/social/PHASE-0-SPEC.md - spec complete (salles, fils, payload)
- docs/social/PHASE-1.md - prototype UI deja valide

