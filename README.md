<div align="center">

<img src="docs/design/brand/kepler-mark.svg" width="108" height="108" alt="Kepler mark" />

# Kepler

**Read. Listen. Remember.**

*A private, open-source Android app designed to help you deeply read, listen to, and truly internalize books.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%209.0%2B%20(API%2028%2B)-green.svg)](app)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-blue.svg)](https://developer.android.com/jetpack/compose)

</div>

---

## Overview

Most ebook readers simply display text. Audiobook players stream narration without sentence context. Note apps collect highlights that fade from memory.

**Kepler** is a native Android application built around a single premise: **books should be internalized, not just consumed.** It unites an exquisite reflowable EPUB reader, an AI-powered voice synthesizer with sentence-level read-along, and an active recall study loop powered by the FSRS-6 spaced repetition algorithm and contextual LLMs.

Non-fiction is treated as a first-class citizen: the app maps out arguments, synthesizes chapter packs, tests your comprehension, and guarantees through strict boundary fencing that AI insights **never spoil content past your current reading position**.

---

## Core Pillars

```
┌────────────────────────────────────────────────────────────────────────┐
│                                 KEPLER                                 │
├───────────────────┬────────────────────────────┬───────────────────────┤
│     1. READ       │         2. LISTEN          │      3. REMEMBER      │
│  ───────────────  │  ────────────────────────  │  ───────────────────  │
│  • EPUB & PDF     │  • Fish Audio (S2.1 Pro)   │  • FSRS-6 Flashcards  │
│  • Geist/Literata │  • Offline System TTS      │  • The AI Fence       │
│  • Book Map       │  • Synced Read-Along       │  • Chapter Packs      │
│  • FTS5 Search    │  • Media3 Playback Engine  │  • Selection AI       │
│  • Lookups        │  • Auto & Silence Skip     │  • "Previously on..." │
└───────────────────┴────────────────────────────┴───────────────────────┘
```

### 1. Read
- **Reflowable & Fixed-Layout EPUB:** Powered by Readium with custom CSS transformations and sentence-span injection.
- **Editorial Typography:** Bundled with *Geist* and *Literata* typefaces, custom paper themes (Day, Dusk, Night, Sepia), granular margins, and line-height controls.
- **The Text Spine Coordinate System:** Every paragraph and sentence is mapped to deterministic sentence IDs, ensuring that bookmarks, highlights, audio sync, and study cards survive re-pagination and re-flows.
- **Book Map:** Structural visualization of non-fiction arguments and chapter layouts.
- **Full-Text In-Book Search:** Instant keyword and passage indexing via SQLite FTS5.
- **Offline Lookups:** Instant WordNet definitions and Wikipedia integration directly from selected text.
- **Footnote Popups:** Non-intrusive modal overlays so you never lose your reading position.

### 2. Listen
- **High-Fidelity AI Narration:** Generates natural narration using Fish Audio (S2.1 Pro), with customizable narration styles, full cast voices for fiction, and dedicated quote voices for non-fiction.
- **100% Offline System TTS:** Full support for on-device Android text-to-speech engines without requiring network connectivity or API keys.
- **Synchronized Read-Along:** Custom `ReadAlongPlayer` highlights sentences in real-time as the narration plays, offering seamless switching between reading and listening.
- **Professional Playback Controls:** Media3 ExoPlayer engine with speed adjustment (0.5×–3.0×), automatic silence skipping, sleep timer, headset controls, lock screen media notifications, and Android Auto support.
- **Background Audio Generation:** Generates voice tracks in the background via Android WorkManager with live progress rings, ETA estimation, and cost calculations.

### 3. Remember
- **FSRS-6 Spaced Repetition:** Flashcard review engine implementing the state-of-the-art Free Spaced Repetition Scheduler (FSRS) to guarantee long-term retention with optimal study load.
- **The AI Fence:** Strict cryptographic-style boundary ensuring LLM prompts only ingest text up to your current reading position, making recaps, questions, and concept cards completely spoiler-free.
- **Chapter Packs:** Single cached inference per chapter generating comprehensive summaries, core takeaways, active recall check-in questions, X-Ray concept cards, and pronunciation guides.
- **Selection AI:** Select any sentence or paragraph in the reader to instantly **Explain**, **Ask**, or generate targeted study cards.
- **"Previously on..." Recaps:** When returning to a book after 3 or more days away, Kepler streams a concise recap of preceding events or key concepts before you resume.
- **Daily Review & Study Habits:** Dedicated home dashboard widgets tracking daily reviews due, active weekly streak dots, and total recall stats.
- **Open Study Exports:** Export annotations and flashcards to Markdown / Obsidian, Anki, Readwise, and JSON.

---

## Home Dashboard & UX Design

Kepler adheres to the **Material 3 Expressive** design system with organic, tactile motion, comprehensive haptics, and adaptive color palettes:

- **Continue Card:** Displays your current book with a dynamic 3D-angled cover tilt, position progress bar, mode chip (Reading vs. Listening), and one-tap resume.
- **Now-Bar Mini Player:** Persistent audio controller docked above navigation tabs that morphs fluidly into the full-screen player.
- **3D Bookcase & Grid Views:** Switch between a modern, responsive cover grid with audio badges or a physical-style 3D rendered bookcase with leaning spines and shelf planks.
- **ColorOS / OxygenOS Optimization:** Integrated background execution guides for OEM battery savers (OPPO, OnePlus, realme) to safeguard background narration generation.

---

## Privacy & Offline First

Kepler is built with strict privacy and data ownership guarantees:

- **Zero Tracking:** No analytics, no telemetry, no tracking SDKs, and no ads.
- **Bring Your Own Keys (BYOK):**
  - **Fish Audio:** Required only for generated voice models.
  - **OpenRouter:** For AI study features (Ask, Recap, Concepts, Cards). Fully compatible with free `:free` models (e.g. `nvidia/nemotron-3-super-120b-a12b:free`).
- **Hardware-Backed Encryption:** API keys are encrypted at rest on your device using Android Keystore and `EncryptedSharedPreferences`. Keys are never logged, synced, or included in backups.
- **Offline Core:** The EPUB reader, WordNet dictionary, FSRS flashcard scheduler, and system TTS function completely offline without internet access.

---

## Architecture & Codebase

The project is structured into clear modular layers separating domain models, engines, and UI features:

```
OpenAudiobook/
├── app/                      # Application entry point, Nav3 navigation shell, DI container
├── core/
│   ├── model/                # Pure Kotlin domain entities, repository interfaces, engines
│   ├── database/             # Room 3 entities, SQLite FTS5, DataStore, Keystore crypto
│   ├── designsystem/         # Material 3 Expressive tokens, Kp components, 3D BookScene
│   └── spine/                # Text spine segmentation, ordinal locator math
├── engine/
│   ├── epub/                 # Readium 3.4 integration, EPUB streaming & transform
│   ├── tts/                  # Fish Audio client, Android system TTS engine
│   ├── playback/             # Media3 ExoPlayer, MediaSession, ReadAlongPlayer coordinator
│   ├── ai/                   # OpenRouter streaming client, model prober, AI fence, prompts
│   └── srs/                  # FSRS-6 spaced repetition algorithm implementation
└── feature/
    ├── library/              # Home dashboard, Bookcase/Grid library, Hub, Settings
    ├── reader/               # Readium reading view, typography sheets, selection actions
    ├── player/               # Fullscreen player, speed dialogs, chapter list
    └── study/                # Learn screen, flashcard review loop, notebook, search
```

---

## Building from Source

Kepler can be built completely from the command line without Android Studio.

### Prerequisites

- **JDK 21**
- **Android SDK** with platform `android-37` and build-tools `37.0.0`
- `ANDROID_HOME` pointing to your Android SDK installation directory

### Build & Install

```bash
# Set environment variables
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Library/Android/sdk   # macOS default path

# Build the debug APK
./gradlew :app:assembleDebug

# Install on a connected Android device or emulator
./gradlew :app:installDebug
```

The resulting APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Running Tests

Run the full test suite across all modules:

```bash
./gradlew testDebugUnitTest
```

Or target specific core and engine modules:

```bash
./gradlew :core:model:test \
          :core:spine:test \
          :core:database:testDebugUnitTest \
          :engine:epub:testDebugUnitTest \
          :engine:playback:testDebugUnitTest \
          :engine:tts:testDebugUnitTest \
          :engine:ai:test \
          :engine:srs:test \
          :feature:library:testDebugUnitTest \
          :feature:study:testDebugUnitTest
```

---

## Documentation

Comprehensive architecture, research, and design specifications are located in the [`docs/`](docs/) directory:

- [Product & Architectural Decisions](docs/decisions.md) — Binding record of technical and product choices (#1–#112).
- [Master Plan & Roadmap](docs/PLAN.md) — Scope, deliverables, and release phases.
- [Design Language & Tokens](docs/design/shelf-language.md) — Visual styling, haptic patterns, and motion tokens.
- [Release Process](docs/RELEASING.md) — Build signing, F-Droid metadata, and release checklist.

---

## Contributing

Contributions, bug reports, and pull requests are warmly welcomed! Please ensure all code and dependencies maintain strict **GPL-3.0 compatibility** (no AGPL code, no proprietary SDKs, and no Google Play Services).

Third-party dependencies and their respective licenses are cataloged in [NOTICE.md](NOTICE.md).

---

## License

Kepler is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License version 3** as published by the Free Software Foundation. See the [LICENSE](LICENSE) file for complete details.
