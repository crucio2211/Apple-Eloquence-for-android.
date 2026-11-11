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
  --output dist/reloq-c-android-arm64.apk
```

The included P12 is the upstream test signing key. It is not a production
release identity.
