# Apple Eloquence for Android — implementation and build guide

Apple Eloquence for Android is an ARM64 Android application and system Text-to-Speech (TTS) service. This directory contains the Java application, native bridge, Android resources, tests, and build tooling.

## Upstream sources and attribution

This Android implementation is a feature-enhanced adaptation of existing open-source work. It does not claim that the underlying ECI engine or all Android application components were independently created here.

- **[Mudb0y/Apple-Eloquence-ELF](https://github.com/Mudb0y/Apple-Eloquence-ELF)** — the Apple Eloquence ELF conversion project and its early Android integration scaffold. Its project-authored converter code is MIT-licensed. Its license separately describes Apple's extracted and converted binary artifacts, which remain subject to Apple's terms.
- **[hozosch/ReLoq-C](https://github.com/hozosch/Reloq-C)** — the Android application and native pronunciation/acoustic core used as the principal integration baseline. The `reloq-core` submodule is pinned to upstream commit [`639b69eb51d0e05c51f6dcce62fb2004f32b9471`](https://github.com/hozosch/Reloq-C/commit/639b69eb51d0e05c51f6dcce62fb2004f32b9471). ReLoq C's original code is GPL-3.0-or-later; see its upstream [LICENSE](https://github.com/hozosch/Reloq-C/blob/639b69eb51d0e05c51f6dcce62fb2004f32b9471/LICENSE) and [NOTICE](https://github.com/hozosch/Reloq-C/blob/639b69eb51d0e05c51f6dcce62fb2004f32b9471/NOTICE).
- **[NVDA-IBMTTS-Driver](https://github.com/davidacm/NVDA-IBMTTS-Driver)** and other referenced projects retain their own attribution and license terms wherever their work is incorporated.

The enhancements maintained in this repository include EVDroid-compatible number, emoji, and punctuation processing, camel-case handling, regular-expression dictionary support, and the Android application's integration and update flow. Upstream components remain credited to their respective authors. These links are source attributions, not statements of official affiliation or endorsement.

## App overview

| Item | Value |
|---|---|
| Application | Apple Eloquence |
| Package name | `org.reloq.tts` |
| Manifest version | `1.2.5` |
| ABI | ARM64 (`arm64-v8a`) |
| Minimum Android API | 26 (Android 8.0) |
| Target Android API | 35 |
| TTS service | `org.reloq.tts.ReLoqService` |

The app currently provides 15 language variants, eight voice profiles, and output sample rates from 8 kHz to 48 kHz. See the root [README](../README.md) for the end-user feature summary.

## Source layout

- `java/org/reloq/tts/` — Android TTS service, settings UI, voice catalogue, dictionary management, text processing, and app update handling.
- `native/reloq_jni.c` — JNI bridge between Android Java code and the native ECI engine.
- `res/` — Android resources, localized strings, and TTS engine descriptor.
- `reloq-core/` — pinned ReLoq C source submodule, including the native engine, OpenEVV rule library, dictionaries, and build dependencies.
- `test/` and `test_*.py` — Java and Python regression tests.
- `build.py`, `codegen.py`, and `verify_apk.py` — native/application build, generated source, and package validation.
- `release-signing.p12` — upstream-provided test signing identity, not a private production signing key.

## Android text processing

Incoming speech text passes through the Android-side text-processing pipeline. Its options are exposed in the **Text processing** section of the settings screen.

- **Emoji:** speaks emoji by name and can compress repeated occurrences of the same emoji name.
- **Numbers:** optional number processing with digits, pairs, triplets, or quadruplets grouping.
- **Camel case:** optionally splits camel-case words into readable word boundaries.
- **Punctuation:** optional punctuation processing with None, Some, Most, or All levels.
- **Regular-expression dictionary:** separate settings screen for enabling, importing, editing, exporting, and managing regular-expression entries. Its enable switch is kept inside the regular-expression settings rather than in the main settings screen.

Preferences are stored in the app's existing `settings` SharedPreferences file. The text-processing classes have standalone host-side regression tests and are also included in the Android workflow.

## Speech settings

The settings app provides:

- Android-controlled or app-controlled language and voice.
- Independent Android rate and pitch control.
- Manual speed can use either native ECI units (0–250) or native words per minute (1–6000); Android-controlled speed remains independent. Rate boost is available.
- Manual pitch can use the ECI scale or an exact base frequency in hertz (1–24,000), independently of Android pitch control.
- Eight voice profiles with model, head-size, roughness, breathiness, inflection, and volume controls.
- Output sample rates of 8, 11.025, 16, 22.05, 32, 44.1, and 48 kHz.
- An optional equalizer with Balanced, Warm, Bright, Smooth, and Custom presets; five independently configurable filters provide frequency, gain, and bandwidth controls. EQ is off by default and frequencies at or above the Nyquist limit are inactive.
- Pause behavior, abbreviation dictionary, phrase prediction, ECI tags, original question intonation, and mellow mode. **Use original Eloquence acoustic timing (5 ms)** can be selected per language; Castilian Spanish and Greek default to the original 5 ms clock, while other languages retain the 1 ms clock unless changed.

Changes are saved as settings are adjusted. The app includes a speech test field and Stop button. To use the service system-wide, select **Apple Eloquence** in Android's Text-to-speech settings.

## Pronunciation dictionaries

The dictionary manager handles native Main, Root, and Abbreviations volumes, plus NVDA-format personal speech dictionaries. It supports community dictionaries and user-provided dictionary files.

Choose the language and format when importing. Eloquence files use Main, Root, or Abbreviations and Windows-1252; NVDA files use UTF-8 and can apply to all voices or one selected voice. Format detection can be automatic or explicit. Personal NVDA entries run independently of community dictionaries and are applied in order before native pronunciation and optional word segmentation. Existing native Eloquence dictionary sources remain unchanged. Device-protected snapshots retain imported NVDA files so dictionaries continue working before the first unlock after restart.

Later personal files take priority over earlier ones. Dictionary activation uses immutable snapshots, and regular-expression dictionaries remain in their separate processing layer and settings screen.

The ReLoq C submodule supplies the dictionary engine and related source. Preserve its license and third-party notices when distributing modified builds.

## App update mechanism

The **App updates** activity checks this repository's GitHub Releases API:

`https://api.github.com/repos/crucio2211/Apple-Eloquence-for-android./releases?per_page=30`

The activity reads published release metadata, rejects drafts and prereleases, validates the ARM64 APK filename, version, repository download URL, file size, and GitHub-provided SHA-256 metadata, then compares the candidate version with the installed Android `versionCode`. An optional background checker uses Android JobScheduler to check over any network, with a periodic schedule and a first check; users can disable automatic checks. On Android 13 and later, notifications require the user's notification permission. Tapping an update notice opens the update screen and browser-based download flow.

When a newer stable package is available, the app opens the asset in the browser. The browser downloads it and Android's normal package installation flow handles installation. The app does not silently install packages and does not embed a GitHub personal access token.

### Automatic build publishing

Every successful Android build on `main` (including a manual workflow run started on `main`) does both of the following:

1. Uploads the APK as a GitHub Actions artifact retained for 14 days.
2. Creates or updates the rolling GitHub prerelease tagged `android-main`, replacing its APK asset with the newest successful main build.

This rolling prerelease is intended for testing and is deliberately ignored by the in-app stable update checker. Pull-request builds and diagnostic/test artifacts are not published as end-user Releases.

### Publishing a stable Android update

1. Update `versionName` and increase `versionCode` in `AndroidManifest.xml`.
2. Push a matching tag, for example `v1.2.5`.
3. The Android workflow verifies that the tag matches `versionName`, builds `apple-eloquence-android-arm64-1.2.5.apk`, and uploads it to the stable GitHub Release tagged `v1.2.5`.

The release page is [Apple Eloquence for Android — GitHub Releases](https://github.com/crucio2211/Apple-Eloquence-for-android./releases). The installed app checks stable releases only; the rolling `android-main` testing prerelease is not offered as a normal app update.

The installed app makes an unauthenticated HTTPS request. The release repository must remain public for users to access its Releases API and APK assets. Do not place a personal access token in the APK.

## Build requirements

The automated Android APK build runs on an Ubuntu 24.04 x86-64 GitHub Actions runner. Required tools for a local build:

- Python 3
- GNU Make
- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.1
- Android NDK 30.0.16248370

Initialize the pinned native source submodule:

```sh
git submodule update --init --recursive
```

Set the SDK, NDK, and JDK locations, then run:

```sh
python3 android/build.py \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_HOME/ndk/30.0.16248370" \
  --java-home "$JAVA_HOME" \
  --verified-dictionaries \
  --jobs 2 \
  --output dist/apple-eloquence-android-arm64-1.2.5.apk
```

Omit `--verified-dictionaries` when the build should independently refresh the current community dictionary data rather than use an already verified local snapshot. The build script creates the native ARM64 library, generates Java sources, compiles and packages the application, signs the APK, and validates its install-critical resources, signatures, ZIP alignment, and ARM64 ELF layout.

## Automated Android workflow

The workflow at `.github/workflows/android.yml` runs for relevant pushes, pull requests, matching version tags, and manual dispatches. It:

1. Checks out the repository and pinned submodule.
2. Sets up JDK 17, Android SDK Platform 35, Build Tools 35.0.1, and NDK 30.0.16248370.
3. Tests app-update metadata, stable-versus-prerelease filtering, and version ordering.
4. Runs the Android text-processing regression tests.
5. Builds and verifies the signed ARM64 APK.
6. Uploads the APK as a 14-day workflow artifact.
7. On successful builds from `main`, publishes or replaces the APK in the rolling `android-main` prerelease.
8. On a matching `vMAJOR.MINOR.PATCH` tag, publishes the APK to that stable versioned Release.

The separate `.github/workflows/bionic-lab.yml` workflow is a manual diagnostic workflow. Its temporary test libraries and system images remain Actions artifacts and are not distributed as app releases.

A successful host-side build and test run does not replace installation, TalkBack accessibility, or speech testing on a physical ARM64 Android device.

## Validation and device testing

The repository includes host-side tests for settings, text processing, native transport, ARM64 landing/stack behavior, APK identity, version ordering, and APK packaging rules. These tests help catch regressions in the build and processing layers; they do not prove that every phone, language, voice, or audio route behaves identically.

For release verification, install the resulting APK on an ARM64 device, select Apple Eloquence as the system TTS engine, test representative languages and voice profiles, exercise the text-processing controls, and check dictionary import/reload behavior with the intended screen reader.

## License notes

Licensing is component-specific:

- The Apple Eloquence ELF project-authored converter code is MIT-licensed, subject to the binary-artifact and third-party notices in its upstream license.
- ReLoq C source incorporated into the Android integration is GPL-3.0-or-later. Its license and notice are retained in the pinned source submodule.
- Apple-provided Eloquence binaries and derived artifacts retain the applicable Apple terms.
- Other third-party code and data retain their own copyright notices and license terms.

The root LICENSE must not be interpreted as relicensing the ReLoq C-derived Android components or the Apple binaries. Preserve all relevant upstream license files and notices when distributing source or APKs.
