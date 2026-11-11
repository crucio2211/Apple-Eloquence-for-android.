# Apple Eloquence ELF — Android implementation

The Android app implementation in this directory is based on the uploaded
ReLoq-C Android 1.2.2 source snapshot (upstream commit
`f6a1498b97d3fbf6fcb07fdb16c34ff73f44859a`). The previous experimental
Apple-ELF Android scaffold has been replaced by this implementation.

## Source layout

- `java/org/reloq/tts/`: Android TextToSpeech service, settings, dictionaries,
  update handling, and text preprocessing.
- `native/reloq_jni.c`: native ECI bridge.
- `res/`: Android engine resources and translations.
- `test/`: Java/Python checks.
- `release-signing.p12`: the upstream included test signing identity.
- `reloq-core/`: Git submodule pinned to the matching ReLoq-C source snapshot.
  It supplies the native ECI engine, OpenEVV rule library, dictionaries, and
  source-build dependencies. It is kept under this Android directory so the
  Apple ELF desktop sources remain independent.

## EVDroid text-processing integration

The Android request path now applies the same processing order and setting
semantics used by EVDroid:

1. Remove thousands-separator commas.
2. Expand emoji to their English names when enabled, using the complete
   EVDroid emoji table including Unicode Emoji 18.0 additions.
3. Compress consecutive repetitions of the same emoji name at a selectable
   threshold from 2 through 5.
4. Regroup digit runs in digits, pairs, triplets, or quadruplets mode when
   number processing is enabled.
5. Speak punctuation according to the SAPI5-derived symbol table and selected
   punctuation level.

The Text processing section in Android settings exposes the emoji switch,
compression threshold, number-processing switch and grouping mode, and
punctuation switch and level. Preferences persist in the app's existing
`settings` SharedPreferences file.

## App updates

The Android Settings screen's **App updates** action checks the GitHub Releases
API for this repository:

`https://api.github.com/repos/crucio2211/Apple-Eloquence-ELF/releases?per_page=30`

The updater accepts only ARM64 APK assets named
`reloq-c-android-arm64-MAJOR.MINOR.PATCH.apk`, whose download URLs belong to
this repository and whose GitHub asset metadata includes a SHA-256 digest.
It compares the semantic version with the installed Android `versionCode`.
When a newer package is found, it opens the GitHub asset in the browser; the
user completes the download and Android's normal installation flow.

To publish an Android update:

1. Set `android/AndroidManifest.xml`'s `versionName` to the next
   `MAJOR.MINOR.PATCH` release and keep `versionCode` increasing.
2. Push a matching tag such as `v1.2.3`.
3. The Android workflow checks that the tag matches `versionName`, builds
   `reloq-c-android-arm64-1.2.3.apk`, and uploads it to that tag's GitHub
   Release. The desktop release workflow can add its Linux tarballs to the
   same release.

**Repository visibility:** the updater makes an unauthenticated HTTPS request
from the installed app and does not contain a GitHub token. Therefore this
repository must be public for app users to read its Releases API and download
release assets. Keeping it private makes the check return an authorization/not
found response. Do not embed a personal access token in the APK.

## Build

The GitHub Actions Android workflow checks out the pinned submodule and builds
a signed ARM64 APK with JDK 17, Android API 35, and NDK 30.0.16248370.

For a local build, initialize the submodule first:

```sh
git submodule update --init --recursive
python3 android/build.py \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_HOME/ndk/30.0.16248370" \
  --java-home "$JAVA_HOME" \
  --verified-dictionaries \
  --jobs 2 \
  --output dist/reloq-c-android-arm64-1.2.2.apk
```

The included P12 is the upstream test signing key. It is not a production
release identity.
