# Third-party notices

Kepler is licensed under GPL-3.0 (see LICENSE). It uses the following third-party components. Versions are from `gradle/libs.versions.toml`.

## Libraries

| Component | Version | Licence |
|---|---|---|
| Readium Kotlin Toolkit (shared, streamer, navigator) | 3.4.0 | BSD-3-Clause |
| FsrsKt (fsrskt) | 1.0.1 | GPL-3.0 |
| AndroidX Media3 (exoplayer, session, common, transformer) | 1.11.1 | Apache-2.0 |
| Jetpack Compose (BOM, ui, foundation, material3, animation) | 2026.09.00 | Apache-2.0 |
| AndroidX Room 3 (runtime, compiler) | 3.0.3 | Apache-2.0 |
| AndroidX SQLite (bundled) | 2.7.1 | Apache-2.0 |
| AndroidX WorkManager | 2.12.0 | Apache-2.0 |
| AndroidX DataStore | 1.2.1 | Apache-2.0 |
| AndroidX Activity, Core KTX, Lifecycle, Fragment | 1.13.0 / 1.19.1 / 2.11.0 / 1.9.1 | Apache-2.0 |
| Ktor client (core, OkHttp, content negotiation, kotlinx JSON) | 3.6.0 | Apache-2.0 |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 |
| kotlinx.serialization | 1.11.0 | Apache-2.0 |
| Coil 3 (compose) | 3.6.3 | Apache-2.0 |
| jsoup | 1.23.2 | MIT |
| Test only: JUnit 4 | 4.13.2 | EPL-1.0 |
| Test only: Turbine | 1.2.1 | Apache-2.0 |
| Test only: Robolectric | 4.17 | MIT |

### Runtime dependencies pulled in by the libraries above

| Component | Licence |
|---|---|
| Kotlin standard library, JetBrains annotations, kotlinx-datetime | Apache-2.0 |
| OkHttp, Okio (via Ktor and Readium) | Apache-2.0 |
| kaml, snakeyaml-engine-kmp, urlencoder-lib (via Ktor) | Apache-2.0 |
| Guava (failureaccess, listenablefuture), JSpecify | Apache-2.0 |
| Timber, Koi, Accompanist drawablepainter (via Readium and Coil) | Apache-2.0 |
| Compose Multiplatform runtime and UI artifacts (via Coil, Readium) | Apache-2.0 |
| SLF4J API | MIT |

FsrsKt's metadata also lists the Dokka documentation plugin (with Jackson, FreeMarker and more) as a runtime
dependency. That is a packaging mistake upstream; it is excluded in `engine/srs/build.gradle.kts`, so none of it
ships in the app.

Build tooling (Android Gradle Plugin, Kotlin, KSP) is Apache-2.0 and is not distributed in the app.

## Bundled fonts

These fonts are bundled under the SIL Open Font License, Version 1.1 (https://scripts.sil.org/OFL):

- **Fraunces:** Copyright 2020 The Fraunces Project Authors (github.com/undercasetype/Fraunces)
- **Literata:** Copyright 2017 The Literata Project Authors (https://github.com/googlefonts/literata)
- **Albert Sans:** Copyright 2021 The Albert Sans Project Authors (https://github.com/usted/Albert-Sans)

The full licence text is in [docs/licenses/OFL-1.1.txt](docs/licenses/OFL-1.1.txt) and is also bundled in the app
as the asset `OFL-1.1.txt`. Reserved font names, if any, are stated in each font's own name table.

## Online services

Fish Audio and OpenRouter are used through the user's own API keys. Their terms apply to that use. No service SDKs are bundled.
