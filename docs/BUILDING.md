# Building from source

This document describes how to build the APK for this project.

## Requirements

| Tool | Version / notes |
| --- | --- |
| JDK | 17 or newer |
| Android SDK | Platform/compile SDK as configured in `gradle.properties` (e.g. platform 36) |
| Android NDK | Version pinned in `gradle.properties` (used for native components) |
| Gradle | Use the included wrapper (`./gradlew`) |

Minimum/target SDK versions, NDK version, and other build settings are defined in
`gradle.properties` and the module Gradle files.

## Setup

```sh
git clone <this-repo-url>
cd opencode-termux

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Install required SDK components / accept licenses as needed
# sdkmanager "platforms;android-36" "ndk;<version-from-gradle.properties>"
# yes | sdkmanager --licenses
```

The Termux bootstrap archives are downloaded during the build as configured by the project.

## Build commands

```sh
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```

APK outputs are written under:

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

### Build a single ABI (faster)

To build only specific ABIs and avoid unnecessary splits/downloads:

```sh
TERMUX_BUILD_ABIS=arm64-v8a ./gradlew assembleDebug
```

You can also adjust split APK behavior via Gradle properties/environment as defined in
`app/build.gradle` (for example `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS`).

### App name override

The displayed app name can be overridden at build time:

```sh
TERMUX_APP_NAME="OpenCode Termux" ./gradlew assembleDebug
```

## Signing

`app/build.gradle` defines signing configurations (debug/release/upload as applicable). Release
signing typically requires a keystore and properties that are not stored in the repository. If a
required release keystore is missing, configure signing appropriately before producing a
distributable release build.

## Native components / OpenCode integration

- Native code is built with the Android NDK as part of the Gradle build (see `app/src/main/cpp/`).
- The OpenCode runtime bootstrap/repair logic lives in
  `app/src/main/java/com/termux/app/OpenCodeBootstrap.java` and related classes (e.g.
  `ElfPatch`), which handle runtime installation, ELF interpreter patching, launcher setup, and
  self-healing.

## Continuous integration

This repository includes a GitHub Actions workflow:

- `.github/workflows/build-arm64-v8a.yml` — builds an `arm64-v8a` debug APK and uploads it as a
  workflow artifact.

You can download artifacts from the Actions run page in GitHub, or via the GitHub CLI, for
example:

```sh
gh run list
gh run download <run-id>
```

## Debugging runtime installation

If OpenCode installation/runtime setup fails on-device, inspect:

- `~/.opencode/setup.log`
- `~/.opencode/last-error.txt`
- `~/.opencode/diagnostics.txt`

Application logs can also be viewed with `adb logcat` (filter by relevant tags such as the
OpenCode bootstrap/logger tags used by the app).
