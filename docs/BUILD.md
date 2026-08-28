# Build guide

## Prerequisites

* **JDK 17** (`java -version` must report 17; newer JDKs work for the Gradle run itself, but the
  output targets 17)
* **Android SDK** with platform **API 35** and current build tools
* Gradle is fetched through the bundled wrapper — no local Gradle installation is needed

Without Android Studio it is enough to point at the SDK directory:

```bash
export ANDROID_HOME=$HOME/Android/Sdk     # or wherever your SDK lives
# alternatively, create a local.properties in the project root:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

## Building

```bash
git clone https://github.com/kevinluca1-ctrl/Condorino.git
cd Condorino

./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest  # 103 unit tests
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Signing

The release variant is deliberately signed with the **debug keystore**, so CI can produce an
installable APK without secrets. For a real publication, replace the release build type's
`signingConfig` in `app/build.gradle.kts` with your own keystore:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH"))
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
```

## With Android Studio

Open the project folder, wait for the Gradle sync, and run the `app` configuration. Android Studio
downloads any missing SDK components itself.

## APK from GitHub Actions

The workflow `.github/workflows/android.yml` runs on every push and produces three artifacts:

* `condorino-debug-apk`
* `condorino-release-apk`
* `unit-test-reports` (HTML test report)

*Actions → Build APK → the run you want → Artifacts.* The workflow can also be triggered by hand
under *Actions → Build APK → Run workflow*.

Pushing a version tag additionally runs `.github/workflows/release.yml`, which publishes a GitHub
Release with both APKs attached. It runs the tests first, so a tag cannot ship a red build.

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it there; installation from unknown sources has to be
allowed for the installing app.

The debug and release variants have different application IDs
(`com.condorino.weekend.debug` and `com.condorino.weekend`) and can be installed side by side.

## Troubleshooting

**`SDK location not found`** — set `ANDROID_HOME` or create `local.properties` (see above).

**`Unsupported class file major version`** — the JDK in use is too new or too old. Switch to 17,
e.g. `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (macOS) or via `update-alternatives`
(Linux).

**The Gradle download hangs** — the wrapper fetches Gradle 8.9 from `services.gradle.org`; behind a
proxy you need `-Dhttps.proxyHost`/`-Dhttps.proxyPort` or a locally installed Gradle 8.9.

**The build fails resolving `androidx.*`** — those dependencies come from `dl.google.com` (Google
Maven). That host has to be reachable.
