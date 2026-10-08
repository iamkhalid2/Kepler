# App wiring report

## Missing in the interrupted wiring, now done
- Application class `KeplerApp` (own file) builds `AppContainer` in `onCreate`. Eager: `DataLayer` (one per process), `NarrationEngine` (a WorkManager job may start the process), `PlaybackClient.initialize`. Lazy: AI service, FSRS scheduler, review stats, playback client, all `*Deps`. `warmUp()` opens Room and DataStore on a background thread. No WorkManager `Configuration.Provider`: `NarrationWorker` has the stock constructor and finds the engine through `NarrationEngine.instance`.
- External intents: `nav/Incoming.kt` (pure `classifyIncoming`, `parseDeepLink`). Manifest filters for `kepler://book/<id>/s/<n>`, VIEW of `application/epub+zip` (and `.epub` names under octet-stream) and SEND of EPUB. Only `content://` is accepted. `MainActivity` queues each intent once on a Channel. `AppRoot` imports via `LibraryRepository.importBook`, then opens the Hub with the stack reset to Library. Failure shows the neutral "can't be opened" toast. Deep links to a missing book show a toast; otherwise they save the reading position and open the reader.
- Navigation fixes: `Route.Reader.nonce` and `BackStack.showReader` (a jump on top of the same reader reloads the position instead of keeping a stale view model); Setup asks for POST_NOTIFICATIONS once; `TabsHost` no longer recomposes on every playback tick; toast host (`KpToastHost`) at the root; status/navigation bar icons and window background follow the app theme.
- Manifest: `dataExtractionRules` excluding everything, `allowBackup=false`, `fullBackupContent=false`. The merged manifest was inspected: no duplicate permissions or services, FGS types dataSync (WorkManager) and mediaPlayback (PlaybackService).
- R8: `proguard-rules.pro` (Room impl, worker, playback service, Readium fragments, serializers, line numbers). Library consumer rules cover the rest; `KeplerDatabase_Impl`, workers and services are unrenamed in mapping.txt. No missing-class warnings.
- Keys: both key strings are found in the debug dex and absent from the release dex (checked by exact match without printing them).

## Verification
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` passes. 19 JVM tests (deep links, intent classification, route encoding, back stack).

## Fixes outside :app
None.

## Known gaps
- The release APK is unsigned and has never run on a device; the R8 rules are reasoned, not exercised.
- The 3D book carry between library, hub and reader is not built (spring crossfades only).
- The reader's paper theme can differ from the app theme; status bar icons follow the app theme only.
- Sheets (Explain, Define, Card) are not restored after process death.

## DESIGN ASSUMPTIONS
- Import and deep-link feedback uses the brief's toast, placed 16 dp above the tab bar (or the bottom edge off-tab).
- Opening an EPUB from another app lands on its Hub, with Library as the tab underneath.
- The notification permission is requested when Narration setup opens and on the first Listen.
