# Releasing Kepler

## Versioning

- `versionName` in `app/build.gradle.kts` uses semantic versioning (`MAJOR.MINOR.PATCH`). Pre-1.0 builds are `0.x.y`.
- `versionCode` is an integer that must increase with every published build. Bump it on every release.
- Each release gets a fastlane changelog at `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Keep it under 500 characters.
- Tag the release as `vX.Y.Z` on `main` once CI is green. The tag triggers `.github/workflows/release.yml`.

## Signing

- Release builds are signed with an upload keystore. Never commit it.
- The release workflow reads these GitHub Actions secrets:
  - `ANDROID_KEYSTORE_BASE64`: the keystore, base64-encoded.
  - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- The keystore is decoded to `$RUNNER_TEMP` for the build and deleted afterwards.
- `app/build.gradle.kts` must define a `release` signing config that reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment. Confirm this before the first tag.
- Keep an offline backup of the keystore. Losing it means losing the ability to update the app.

## GitHub release

- The workflow attaches `Kepler-vX.Y.Z.apk` to a GitHub release with auto-generated notes.
- Check that the APK installs on a device with minSdk 28 before publishing.

## Google Play notes

- Declare each foreground service type in the manifest, and in the Play Console foreground-service declaration. Playback uses the media playback type. Audiobook generation uses the data sync type. Confirm the final list against the manifest before submitting.
- Declare the notification and background-audio use in the Play data safety form. API keys stay on the device, and generated text is sent only to the provider the user chose.
- Play is a later target. The first public distribution is GitHub releases.

## F-Droid notes

- The app is a single FOSS flavor with no proprietary SDKs and no Google Play services. Keep it that way, so F-Droid can build it from source.
- F-Droid reads metadata from `fastlane/metadata/android/en-US/`. Keep `title.txt`, `short_description.txt`, `full_description.txt` and the changelogs current.
- Reproducible builds need the Gradle wrapper, the pinned SDK platform and build-tools versions, and no network-fetched build inputs beyond Maven.
- Optional AI models are downloaded at runtime and are never bundled.
