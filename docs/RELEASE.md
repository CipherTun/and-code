# Release guide

## Unsigned CI artifacts

GitHub Actions builds `app-release-unsigned.apk`. These are for smoke testing only.

## Signed release APK / AAB (local)

1. Create a keystore (once):

```bash
keytool -genkey -v \
  -keystore android-code-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias android-code
```

2. Add to `~/.gradle/gradle.properties` (do not commit):

```properties
ANDROID_CODE_STORE_FILE=/absolute/path/android-code-release.jks
ANDROID_CODE_STORE_PASSWORD=...
ANDROID_CODE_KEY_ALIAS=android-code
ANDROID_CODE_KEY_PASSWORD=...
```

3. Optional: wire `signingConfigs` in `app/build.gradle.kts` reading those properties, then:

```bash
./gradlew assembleRelease
# or
./gradlew bundleRelease
```

4. Verify:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Versioning

- `versionName` / `versionCode` live in `app/build.gradle.kts`
- Tag releases as `vX.Y.Z` matching `versionName`
- Update `CHANGELOG.md` (or GitHub Release notes) with user-facing changes

## Pre-release checklist

- [ ] `./gradlew testDebugUnitTest lintDebug assembleRelease`
- [ ] Manual smoke: local install, chat, permission approve/reject, remote connect
- [ ] `THIRD_PARTY_NOTICES.md` still accurate
- [ ] No secrets in git history
