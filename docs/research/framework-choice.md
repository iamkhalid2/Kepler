## Framework evaluation for OpenAudiobook (as of 2026-10-08)

### Local machine (checked)
- Apple M5 with 24 GB RAM. `adb` is installed through Homebrew. Node is v25.8.1.
- Missing: a JDK (`/usr/bin/java` is only the macOS stub), an Android SDK, Android Studio, Flutter, Kotlin, Gradle and Xcode (only the Command Line Tools are present). No device was attached.
- All three options need a JDK and the Android SDK for local builds. RN/Expo can avoid local builds with EAS cloud builds.
- An iOS port under any option needs Xcode installed.

### Background playback
- **Native (Media3 1.11.1 stable, 2026-09-10).** Every feature this app needs is built into ExoPlayer and the session library:
  - MediaSessionService / MediaLibraryService, `SilenceSkippingAudioProcessor` (fixed in 1.11.1), and speed with or without pitch change through `PlaybackParameters`.
  - Gapless playback, plus a 1.11.0 fix for gapless stalls under audio offload.
  - Custom media-button preferences (added in 1.9).
  - Gotcha: 1.11.0 throws `IllegalStateException` if MediaSession getters are called off the application looper thread. Bridged frameworks have to respect this. https://developer.android.com/jetpack/androidx/releases/media3
- **Android 17 background audio hardening** applies to all apps on Android 17. Background playback needs a visible activity or a non-SHORT_SERVICE foreground service. Apps targeting API 37 also need while-in-use capability. Blocked calls fail silently. Google says apps using Media3's MediaSessionService are "probably not affected". https://developer.android.com/about/versions/17/changes/bg-audio
- **Flutter.**
  - just_audio 0.10.6 uses Media3 ExoPlayer (it migrated to Media3 1.4.1 in 0.9.43).
  - audio_service 0.18.19 is still built on legacy androidx.media 1.6 (MediaBrowserServiceCompat), not Media3. No Media3 migration appears in its changelog. Google says the MediaCompat APIs "are no longer updated".
  - Both packages have a single maintainer (ryanheise).
  - just_audio's own feature table conflicts with itself: pitch, skip-silence and equalizer are listed as "upcoming". **UNVERIFIED** per platform.
  - https://pub.dev/packages/just_audio/changelog, https://pub.dev/packages/audio_service/changelog
- **React Native.**
  - **react-native-track-player v5 (`@rntp/player`, now 5.7.x) is commercially licensed.** It is free only for personal, non-professional or academic use. A commercial app costs €99/month or €999/year, and the free tier also excludes freelancers and nonprofits.
  - v5 requires the New Architecture and is a full rewrite. v4 stays Apache-2.0 on a legacy branch.
  - v5 advertises Android Auto browse trees. Its docs don't mention chapters, skip-silence or pitch. https://www.rntp.dev/llms.txt, https://github.com/doublesymmetry/react-native-track-player
  - expo-audio (SDK 57) has `enableBackgroundPlayback` and `setActiveForLockScreen`. It shows no evidence of an Android Auto browse tree, pitch control or skip-silence. https://docs.expo.dev/versions/latest/sdk/audio/

### Android Auto, Wear OS, widgets, headset buttons
- **Native:** MediaLibraryService plus `onGetLibraryRoot`/`onGetChildren` is Google's documented path for Android Auto. Wear OS and the lock screen pick up the media session automatically. Glance widgets are Compose-native. https://developer.android.com/media/media3/session/serve-content
- **Flutter:** audio_service lists Android Auto and CarPlay support, through the compat library. No mature Flutter package wraps Media3's MediaLibraryService. Glance widgets and Wear OS tiles need Kotlin code (general knowledge).
- **React Native:** rntp v5 (paid) or react-native-nitro-player (one maintainer, about 700 downloads a week). Widgets and Wear need native code.

### Long-running TTS generation jobs
- **Android 15+ foreground-service limits.** dataSync and mediaProcessing each get 6 hours per 24-hour period while the app is in the background, with separate budgets. When the budget runs out, `Service.onTimeout()` fires and the service must call `stopSelf()` within seconds. The budget resets when the user brings the app to the foreground. https://developer.android.com/develop/background-work/services/fg-service-timeout
- **Android 16 job quota.** Jobs that run alongside a foreground service, or that start while the app is visible and continue after it leaves view, now count against the runtime quota. This applies to WorkManager. **Gotcha:** generating while the user is listening (mediaPlayback service running) may be throttled. **UNVERIFIED** how this interacts with WorkManager's `setForeground` workers. https://developer.android.com/about/versions/16/behavior-changes-all
- **User-initiated data transfer (UIDT) jobs** (API 34+): JobScheduler only. Google says "There is currently no Jetpack library that supports UIDT jobs." They can run "for an extended period" and need a notification and a user-initiated start. They fit a "download my generated audiobook" job. https://developer.android.com/develop/background-work/background-tasks/uidt
- **Play Console:** each foreground-service type needs a declaration with a description, the user impact if deferred, and a demo video. https://support.google.com/googleplay/android-developer/answer/13392821
- **Flutter:** the `workmanager` plugin added long-running foreground-service workers in 0.9.4. UIDT and `onTimeout` would need custom Kotlin.
- **React Native:** UIDT and `onTimeout` need custom native modules (Expo Modules in Kotlin).

### EPUB parsing and read-along highlighting
- **Native:** Readium Kotlin Toolkit 3.4.0 (11 Sep; year not shown on the page) requires minSdk 24.
  - Includes the TTS navigator with a Media3 adapter, Media Overlays and the Decorations highlight API.
  - Very actively maintained.
  - Its navigator is Fragment/View based, so Compose screens need interop.
  - https://github.com/readium/kotlin-toolkit/releases
- **Flutter:**
  - flutter_readium 0.6.1 is from verified publisher kb.dk and was published about 40 hours ago. It wraps Readium and supports TTS, Media Overlays and decorations, but it is very young (4 likes, 714 downloads). https://pub.dev/packages/flutter_readium
  - flutter_epub_viewer (epub.js in a WebView, 2.x) supports highlights.
  - epub_view looks stale (a third-party tracker gives it 0 maintenance points).
- **React Native:** react-native-readium is still 5.0.0-rc.17. A New Architecture support request exists, but its status is **UNVERIFIED**. The alternative is epub.js in a WebView. https://github.com/5-stones/react-native-readium

### Audio processing and M4B export
- Media3 Transformer can concatenate audio-only sequences (`EditedMediaItemSequence.withAudioFrom`). 1.11.0 adds pitch-preserving speed change in Transformer. Crossfades are not supported. https://developer.android.com/media/media3/transformer/multi-asset
- **No Android muxer writes chapter atoms.** Media3 1.11 only *reads* Nero and QuickTime chapters. M4B chapters would mean writing a Nero `moov/udta/chpl` box after muxing (and fixing `stco`/`co64` offsets) or using FFmpeg.
- **FFmpegKit was retired in April 2025.**
  - The community fork `dev.ffmpegkit-maintained` (8.1.x, LGPL) ships only arm64-v8a and x86_64, needs minSdk 24, and keeps the `com.arthenica` API.
  - The fork's Maven Central listing mentions paid tiers for some features, so check its licence terms (**UNVERIFIED**).
  - ffmpeg-kit-next ships source only.
  - https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/ffmpeg
- **Gapless gotcha (general knowledge, UNVERIFIED for Fish output):** ExoPlayer only plays MP3 gaplessly when the file has LAME/Xing headers. Concatenating raw MP3 chunks from the API may leave audible gaps. Storing PCM/Opus or re-encoding per chapter to AAC avoids this.
- All three stacks reach these tools through Kotlin anyway.

### Database, secure storage and files
- **Database:**
  - Room 2.8 is in maintenance mode. Room 3.0 started alpha in March 2026 and is KMP (Android, iOS, JVM, and now JS/Wasm). https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
  - drift 2.35 is healthy.
  - expo-sqlite (57.x) or op-sqlite (its README still lists bundled SQLite 3.44; a CVE affects versions before 3.50.2, so verify).
- **API key storage:** AndroidX security-crypto (EncryptedSharedPreferences) was deprecated in 2025 (from memory, **UNVERIFIED**); use the Android Keystore directly instead.
- **Files (general knowledge):**
  - Import with SAF `OpenDocument` plus `takePersistableUriPermission`. The Flutter and Expo pickers copy files to cache by default, which matters for large EPUBs.
  - Keep generated audio in app-specific external storage.
  - Export through MediaStore, which has an `IS_AUDIOBOOK` column (API 29+).

### UI quality and low-end performance
- **Compose Material 3:** 1.5.0-beta01 shipped on 2026-10-07. Most Expressive APIs graduated out of experimental during the alphas (from alpha19/alpha23 on). Stable 1.4.0 keeps most of them internal. https://developer.android.com/jetpack/androidx/releases/compose-material3
- **Flutter 3.47:**
  - Material moved out of the SDK into the standalone `material_ui` package.
  - There is no official Material 3 Expressive implementation, only third-party packages (`material_3_expressive` 1.0.8, `m3e_core`).
  - Impeller uses Vulkan on API 29+ and falls back to OpenGL ES on low-end or non-Vulkan devices.
  - https://docs.flutter.dev/release/breaking-changes/material-ui-and-cupertino-ui, https://docs.flutter.dev/perf/impeller
- **Expo SDK 56:** Expo UI's Jetpack Compose APIs are now stable, so RN can render real Compose components. SDK 57 (June 30, 2026) moved to RN 0.86. SDK 55 removed the legacy architecture. https://expo.dev/changelog/sdk-57

### iOS port later
- **Flutter:** cheapest. just_audio and audio_service support iOS and CarPlay, and flutter_readium supports iOS.
- **React Native:** also cheap, but rntp is paid.
- **Native:** Compose Multiplatform iOS has been stable since 1.8.0 (May 2025), and Room supports KMP. Playback (AVFoundation) and the reader (Readium Swift) would be rewritten in Swift. Sharing domain logic, the TTS provider layer, the job pipeline and the database is realistic, at roughly 40–60% shared (estimate).

### Writing most of the code with an AI agent
- **Native:**
  - Strong typing and compiler errors give the agent good feedback.
  - Google's Android CLI 1.0 (I/O, May 2026) targets Claude Code and other agents: it installs SDK components, manages emulators, renders Compose previews, runs "Journeys" UI tests and includes a docs knowledge base. https://thenextweb.com/news/google-android-cli-agentic-coding-tools-io-2026
  - Downsides: slow Gradle builds and AGP/Kotlin version churn.
- **Flutter:** `flutter analyze` and hot reload are fast. But Auto, Glance, Wear, UIDT and M4B chapter writing all need Kotlin, so the agent works in two languages across platform channels.
- **React Native:** TypeScript helps, but JS/native boundary errors only show up at runtime, and churn is the highest (three Expo SDKs a year; SDK 56 had a Hermes V1 memory regression, fixed in 57.0.17).

### Scored comparison (1–5, weighted)

| Criterion (weight) | Kotlin + Compose (+KMP-ready) | Flutter | RN / Expo |
|---|---|---|---|
| Playback, session, Auto, Wear (25%) | 5 | 3 | 2.5 |
| Long background jobs (15%) | 5 | 3 | 2.5 |
| EPUB and read-along (15%) | 5 | 3.5 | 2.5 |
| M4B / audio processing (10%) | 4 | 3 | 3 |
| UI, M3 Expressive, performance (15%) | 5 | 4 | 3.5 |
| iOS port (10%) | 3 | 4.5 | 4 |
| AI-agent fit (10%) | 4.5 | 4 | 3 |
| Local setup cost (5%) | 3 | 3 | 4 |
| **Weighted total** | **4.80** | **3.63** | **3.10** |

### Recommendation
Native Kotlin + Jetpack Compose on Media3, Readium Kotlin, WorkManager/UIDT and Room.

- Keep the domain layer, the TTS provider interface (Fish plus future models), the generation pipeline and the database in pure-Kotlin modules with no Android imports. That keeps a later KMP/Compose Multiplatform iOS port possible.
- Choose Flutter only if an iOS release within about 6–9 months is a hard requirement, and accept writing a custom Kotlin Media3 service behind it.

### Top 3 risks of each option
- **Native:**
  1. iOS means rewriting playback and the reader in Swift.
  2. Material 3 Expressive is still in 1.5.0-beta, and Gradle/AGP churn plus slow builds slow the agent's feedback loop.
  3. Nothing is installed locally (JDK, SDK, Studio or Android CLI), and Readium's navigator needs Fragment interop in Compose.
- **Flutter:**
  1. audio_service is on legacy compat media, with one maintainer and no Media3 path for Auto or Android 17.
  2. flutter_readium is immature, and the WebView epub.js options are weak for highlighting.
  3. Auto, Glance, Wear, UIDT and chapter writing all force Kotlin anyway, so you maintain two languages.
- **React Native:**
  1. rntp v5 requires a commercial licence, and expo-audio lacks the needed features.
  2. Heavy churn: legacy architecture gone, RN 0.83 to 0.86 in under a year, and react-native-readium still a release candidate.
  3. The most native code of all, spread across Kotlin, TypeScript and a bridge, is the hardest surface for an agent to debug.

### Questions to put to the developer
- **iOS timeline:** none, 12 months, or at launch?
- **Licence and distribution:** paid, free or open source? Play Store, F-Droid or sideload? This affects the rntp licence, the FFmpeg LGPL/GPL choice and the Play foreground-service declaration.
- **API keys:** does each user bring their own Fish key, or does the app use a shared one? The second option implies a backend proxy.
- **Generation model:** generate the whole book ahead of time, or just ahead of playback? Could one book take more than 6 hours of background time? Is a server-side generation option acceptable?
- **Highlight granularity:** sentence or word level? Do Fish's API outputs include timestamps (**UNVERIFIED**), or will we need forced alignment?
- **Exports:** M4B with chapters and cover art? A shareable file? Lossless or AAC?
- **v1 scope:** Android Auto, Wear OS and widgets now or later? What min SDK (24 matches both Readium 3.4 and the FFmpeg fork)?
- **Tooling:** willing to install a JDK plus Android Studio or Android CLI? Is there a physical test device, including a low-end one?