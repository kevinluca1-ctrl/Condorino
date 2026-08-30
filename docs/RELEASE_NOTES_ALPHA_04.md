# Condorino `alpha-04`

Fourth alpha: one critical fix, reported directly from a real install attempt. Everything in
`alpha-03` still applies — see its [release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-03).

## Fixed

* **"App not installed."** Every previous release (`alpha-01` through `alpha-03`) was signed with a *different, randomly generated* debug key, because the build relied on Android Gradle Plugin's implicit debug signing config — which auto-creates a fresh keystore the first time it's needed on any machine that doesn't already have one. GitHub Actions runners are ephemeral, so that happened on every single release build. Android refuses to install an APK as an "update" over an existing app with the same package name if the signing key doesn't match, which is exactly what "App not installed" means here, with no more specific reason surfaced. A stable keystore (`app/debug.keystore`, standard non-secret debug credentials) is now committed to the repo and wired in explicitly, so every build — this one and every one after it — is signed the same way.

**⚠️ One-time step if you have any earlier version installed:** because this is the *first* release with a stable signature, it still doesn't match `alpha-01`–`alpha-03`. **Uninstall Condorino before installing this one.** From `alpha-04` onward, updates will install cleanly over each other without needing this again.

## Installing

1. If you have any earlier Condorino build installed, uninstall it first (see above).
2. Download `condorino-alpha-04.apk` and open it on the phone.
3. Android will show its standard "this app wasn't scanned by Play Protect" warning — expected for any app installed outside the Play Store, not specific to this app. Choose "Install anyway" / "Install without scanning" to proceed.
4. Installation from unknown sources has to be allowed for the app you're installing with (e.g. your file manager or browser); Android 8.0/API 26 or newer.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed alongside. See `docs/BUILD.md` for a real signing setup before publishing anywhere beyond sideloading.
