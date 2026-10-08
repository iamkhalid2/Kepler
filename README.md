<img src="docs/design/brand/kepler-mark.svg" width="96" height="96" alt="Kepler mark">

# Kepler

**Read. Listen. Remember.**

Kepler is an open-source Android app that helps you internalize books. It combines three things:

- **Read:** an EPUB reader for reflowable and fixed-layout books.
- **Listen:** an AI audiobook generator and player (Fish Audio or Android system TTS) with sentence-synced read-along.
- **Remember:** an AI study layer (ask the book, recaps, concepts, cards) with FSRS spaced review.

Non-fiction is a first-class citizen. AI features only use text up to your reading position, so they do not spoil the book.

## Status

Early development. Version 0.0.1. Not yet released on any store.

## Build from source

No Android Studio is required.

Requirements:

- JDK 21
- Android SDK with platform 37 and build-tools 37.0.0
- `ANDROID_HOME` pointing at the SDK

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Library/Android/sdk   # or your SDK path
./gradlew :app:assembleDebug                     # debug APK
./gradlew :app:installDebug                      # install on a connected device
```

Run the tests:

```sh
./gradlew :core:model:test :core:spine:test :engine:ai:test :engine:srs:test \
  :core:database:testDebugUnitTest :engine:tts:testDebugUnitTest \
  :engine:playback:testDebugUnitTest :engine:epub:testDebugUnitTest
```

## Bring your own keys

Kepler does not ship API keys. To use AI and voice features, add your own:

- **Fish Audio** key for high-quality generated voices.
- **OpenRouter** key for ask, recap, concepts and cards. Free `:free` models work.

Keys are entered in the app and stored encrypted on the device. They are never logged or written to files. Android system TTS works without any key.

## Project docs

- [Product and engineering decisions](docs/decisions.md)
- [Plan](docs/PLAN.md)
- [Design language](docs/design/shelf-language.md)
- [Releasing](docs/RELEASING.md)

## Contributing

Issues and pull requests are welcome. Please keep dependencies GPL-3.0 compatible: no AGPL code and no proprietary SDKs or Google Play services. Third-party licences are listed in [NOTICE.md](NOTICE.md).

## Licence

Kepler is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3, as published by the Free Software Foundation. See [LICENSE](LICENSE).
