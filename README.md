# Apple Eloquence for Android

**Offline Eloquence text-to-speech for Android, with configurable voices, pronunciation dictionaries, and expanded text-processing features.**

Apple Eloquence for Android is an Android application and system Text-to-Speech (TTS) service. It provides a settings interface for selecting languages and voice profiles, adjusting speech output, managing pronunciation dictionaries, and configuring additional text-processing features.

This project is a feature-enhanced Android integration built on existing open-source work. It adapts the Android application and native speech foundation from ReLoq C and draws on the Apple Eloquence ELF project by Mudb0y for the Apple Eloquence conversion lineage and early Android integration reference. The project maintains its own Android integration and feature additions; it does not claim authorship of the upstream engine or inherited components.

## Project details

| Item | Details |
|---|---|
| Application name | Apple Eloquence |
| Android package | `org.reloq.tts` |
| Manifest version | `1.2.2` |
| Supported ABI | ARM64 (`arm64-v8a`) |
| Minimum Android version | Android 8.0 (API 26) |
| Android TTS integration | Selectable system speech engine |
| Speech operation | Offline after installation and setup |

The application currently includes **15 language variants** and **eight voice profiles**. Speech output can be configured from 8 kHz to 48 kHz.

## Features

### Languages and voices

The current language catalogue includes:

- Mandarin Chinese — Mainland and Taiwan variants
- German
- English — British and American variants
- Spanish — Castilian and Latin American variants
- Finnish
- French — France and Canadian variants
- Italian
- Japanese
- Korean
- Brazilian Portuguese
- Greek

Eight voice profiles are available: **Reed, Shelly, Bobby, Rocko, Glen, Sandy, Grandma, and Grandpa**.

### Voice and speech settings

- Choose whether Android or the app controls language and voice.
- Independent Android speech-rate and pitch controls.
- Manual speed in words per minute, rate boost, and manual pitch.
- Exact base-pitch entry in hertz.
- Voice-model controls for model, head size, roughness, and breathiness.
- Inflection and voice-volume controls.
- Seven sample rates: 8, 11.025, 16, 22.05, 32, 44.1, and 48 kHz.
- Pause behavior, abbreviation expansion, phrase prediction, and optional ECI-tag processing.
- Original question intonation for supported language profiles.
- Optional mellow mode at supported output rates.

### Text processing

The Android settings include controls for:

- Speaking emoji by name and compressing repeated emoji names.
- Number processing with digit, pair, triplet, or quadruplet grouping.
- Splitting camel-case words.
- Punctuation processing with None, Some, Most, and All levels.
- A dedicated regular-expression dictionary settings screen with its own enable switch and dictionary editor.

### Pronunciation dictionaries

The dictionary manager supports Main, Root, and Abbreviations volumes, optional community dictionary data, and user-provided dictionary files. Select a language and volume, import files, change source priority, reload files, or remove files. Later user files take priority over earlier files.

Regular-expression dictionaries are managed separately from the native pronunciation-volume manager.

## Install and use

1. Open the [GitHub Releases page](https://github.com/crucio2211/Apple-Eloquence-for-android./releases).
2. Download the published ARM64 APK when a release is available.
3. Install the APK using Android's normal package installer.
4. Open **Apple Eloquence** and configure the language, voice, and speech settings.
5. Open Android's Text-to-speech settings and select **Apple Eloquence** as the preferred engine.
6. Return to the app and use **Read test text** to test speech. Use **Stop** to interrupt the test.

Speech synthesis is designed to operate offline. Internet access is used for online functions such as dictionary updates and checking for application releases.

## App updates

Open **App updates** from the Apple Eloquence settings screen and select **Check for updates**. The app checks this repository's GitHub Releases API for a newer ARM64 APK. When an update is available, it opens the release asset in the browser; the browser and Android's normal installation flow handle the download and installation.

Release APK filenames use this pattern:

`reloq-c-android-arm64-MAJOR.MINOR.PATCH.apk`

The Android release workflow builds and uploads the APK when a matching `vMAJOR.MINOR.PATCH` tag is pushed. The tag must match the app's manifest `versionName`. The installed application uses the public Releases API and does not contain a personal GitHub token.

## Upstream projects and attribution

This project is an independent, feature-enhanced Android integration. It is not an official Apple application and is not affiliated with or endorsed by the upstream authors.

- **[Mudb0y — Apple Eloquence ELF](https://github.com/Mudb0y/Apple-Eloquence-ELF)**: the Apple Eloquence ELF conversion project and its early Android integration scaffold. The project-authored converter code is MIT-licensed. Its license distinguishes that code from extracted or converted Apple binary artifacts, which remain subject to Apple's terms.
- **[hozosch — ReLoq C](https://github.com/hozosch/Reloq-C)**: the Android application and native pronunciation/acoustic core used as the principal Android integration baseline. This repository's `android/reloq-core` submodule is pinned to upstream commit [`f6a1498b97d3fbf6fcb07fdb16c34ff73f44859a`](https://github.com/hozosch/Reloq-C/commit/f6a1498b97d3fbf6fcb07fdb16c34ff73f44859a).
- **[NVDA-IBMTTS-Driver](https://github.com/davidacm/NVDA-IBMTTS-Driver)** and other upstream projects retain their own attribution and license terms wherever their code or data is used.

The enhancements maintained here include EVDroid-compatible number, emoji, and punctuation processing, camel-case handling, regular-expression dictionary support, and the Android application's integration and update flow. Upstream code remains credited to its respective authors.

## Build and development

Android application source, native integration, resources, tests, and build tooling are under the `android/` directory. The native ReLoq C source is included as a pinned Git submodule.

The automated Android build uses:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.1
- Android NDK 30.0.16248370

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

See [Android implementation and build notes](android/README.md) for the source layout, build requirements, test commands, and release workflow.

## Licensing

Licensing is component-specific. The root MIT license does **not** replace the licenses of inherited or bundled components.

- The project-authored Apple Eloquence ELF converter and related files are covered by the root MIT license, subject to its explicit binary-artifact and third-party notices.
- The Android integration incorporates ReLoq C code, licensed under GPL-3.0-or-later. See the pinned upstream [LICENSE](https://github.com/hozosch/Reloq-C/blob/f6a1498b97d3fbf6fcb07fdb16c34ff73f44859a/LICENSE) and [NOTICE](https://github.com/hozosch/Reloq-C/blob/f6a1498b97d3fbf6fcb07fdb16c34ff73f44859a/NOTICE).
- The Apple Eloquence binary artifacts retain the restrictions and terms described in the upstream Apple Eloquence ELF license and notices.
- Separately licensed third-party code and data retain their own license terms.

Preserve the applicable upstream license files, copyright notices, and third-party notices when modifying or distributing the application.
