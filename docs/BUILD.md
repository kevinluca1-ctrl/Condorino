# Build-Anleitung

## Voraussetzungen

* **JDK 17** (`java -version` muss 17 zeigen; neuere JDKs funktionieren für den Gradle-Lauf, das
  Kompilat zielt aber auf 17)
* **Android SDK** mit Platform **API 35** und aktuellen Build-Tools
* Gradle wird über den mitgelieferten Wrapper geladen — keine lokale Gradle-Installation nötig

Ohne Android Studio genügt es, das SDK-Verzeichnis bekannt zu machen:

```bash
export ANDROID_HOME=$HOME/Android/Sdk     # oder wo dein SDK liegt
# alternativ eine local.properties im Projektwurzelverzeichnis anlegen:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

## Bauen

```bash
git clone https://github.com/kevinluca1-ctrl/Condorino.git
cd Condorino

./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest  # 76 Unit-Tests
```

Unter Windows `gradlew.bat` statt `./gradlew`.

### Signierung

Die Release-Variante ist bewusst mit dem **Debug-Keystore** signiert, damit CI ohne Secrets eine
installierbare APK erzeugen kann. Für eine echte Veröffentlichung ersetzt du in
`app/build.gradle.kts` den `signingConfig` des Release-Buildtyps durch einen eigenen Keystore:

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

## Mit Android Studio

Projektordner öffnen, Gradle-Sync abwarten, `app` als Run-Configuration starten. Android Studio
lädt fehlende SDK-Komponenten selbst nach.

## APK aus GitHub Actions

Der Workflow `.github/workflows/android.yml` läuft bei jedem Push und legt drei Artefakte ab:

* `condorino-debug-apk`
* `condorino-release-apk`
* `unit-test-reports` (HTML-Testbericht)

*Actions → Build APK → gewünschter Lauf → Artifacts.* Der Workflow lässt sich unter
*Actions → Build APK → Run workflow* auch manuell auslösen.

## Installieren

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oder die APK aufs Telefon kopieren und dort öffnen; „Installation aus unbekannten Quellen“ muss
für die installierende App erlaubt sein.

Debug- und Release-Variante haben unterschiedliche Application-IDs
(`com.condorino.weekend.debug` bzw. `com.condorino.weekend`) und lassen sich parallel installieren.

## Troubleshooting

**`SDK location not found`** — `ANDROID_HOME` setzen oder `local.properties` anlegen (siehe oben).

**`Unsupported class file major version`** — es läuft ein zu neues oder zu altes JDK. Auf 17
stellen, z. B. `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (macOS) oder über
`update-alternatives` (Linux).

**Gradle-Download hängt** — der Wrapper lädt Gradle 8.9 von `services.gradle.org`; hinter einem
Proxy braucht es `-Dhttps.proxyHost`/`-Dhttps.proxyPort` oder eine lokal installierte Gradle-8.9.

**Build schlägt beim Auflösen von `androidx.*` fehl** — die Abhängigkeiten kommen von
`dl.google.com` (Google Maven). Der Host muss erreichbar sein.
