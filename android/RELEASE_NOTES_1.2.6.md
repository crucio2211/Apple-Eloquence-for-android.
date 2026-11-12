# Apple Eloquence for Android 1.2.6

**Stable release — Android ARM64 (`arm64-v8a`)**

## Included changes

- NVDA-format personal speech dictionary import and processing using UTF-8 files, with language and voice scoping.
- Personal NVDA dictionaries are retained in device-protected snapshots for use before the first unlock after a restart.
- Original Eloquence acoustic timing is configurable per language. Castilian Spanish and Greek default to the original 5 ms clock; other languages retain the 1 ms clock unless changed.
- Existing native Eloquence pronunciation dictionaries, EVDroid-compatible number, emoji, punctuation and camel-case processing, and regular-expression dictionary support remain integrated.

## Package details

| Item | Value |
|---|---|
| Application | Apple Eloquence |
| Version | 1.2.6 |
| Version code | 11020006 |
| Package | `org.reloq.tts` |
| ABI | `arm64-v8a` |
| Minimum Android | Android 8.0 (API 26) |
| Target Android | Android 15 (API 35) |

## Validation

The release workflow runs the version-ordering, settings, Direct Boot, translation, EVDroid text-processing, native ARM64 build, APK signing, and package-verification checks before publishing the stable release asset.

This is a stable release, not the rolling `android-main` testing prerelease. The in-app update checker uses stable GitHub Releases only.
