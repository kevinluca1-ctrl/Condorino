# Condorino `alpha-10`

Tenth alpha: two more bugs fixed — the update checker offering every release to the users already
running it, and AeroDataBox reporting a RapidAPI throttle as if the account had run out of quota —
plus Settings UI improvements: a named changelog button and collapsible sections. Everything in
`alpha-09` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-09).

## Fixed

* **"Check for updates" offered the version you were already running as if it were new.** The
  release workflow bakes a build's own release timestamp in at *build start*, but GitHub stamps a
  release's `published_at` only once that build finishes and the release is created — a few
  minutes later. Comparing those two timestamps meant a release always looked slightly newer than
  the build packaged inside it, so the app offered every release to the exact users already
  running it. Release identity is now checked by tag first — a release whose tag matches the one
  baked into this build **is** this build, whatever either timestamp says — and only falls back to
  the timestamp comparison for a build with no tag of its own (a CI or local build). "You have the
  latest version" now names the version: *"alpha-10 is the newest version, and it is already
  installed."*

* **AeroDataBox: "RapidAPI limit reached" fired even nowhere near the monthly quota.** Confirmed
  against a real account showing 5% usage. The real cause: a search can send up to 16 chunked
  requests back to back with no pacing between them, which reliably trips a RapidAPI Basic plan's
  own **per-second** gateway throttle — a short-lived limit completely separate from, and far
  stricter than, the monthly quota shown in the RapidAPI dashboard. Two changes: chunk requests
  within one search are now paced (150 ms apart) so this is tripped far less often in the first
  place, and the message itself no longer implies the account is out of quota — it says a
  short-term rate limit was hit, points at the dashboard rather than assuming it, and includes the
  server's own `Retry-After` wait when it sends one.

## Added

* **Changelog button** in Settings → Updates, next to "Check now" — opens this build's own release
  notes on GitHub directly, rather than the generic releases list.
* **Collapsible Settings sections.** With close to twenty sections — most holding an API field
  mapping set once and never touched again — every section now starts collapsed to a single
  heading and expands on tap, so the screen opens as a scannable list rather than a very long
  scroll. Updates stays open by default, since it is the one section actually used regularly. Each
  section remembers its own open/closed state across navigating away and rotation.

7 new unit tests (232 total, was 224), including one confirming a release matching the installed
build's own tag is never offered as an update regardless of the timestamp skew that caused the bug,
and coverage for the reworded AeroDataBox rate-limit message and its request pacing.

## Known limitations

Unchanged from `alpha-09`.

## Installing

Download `condorino-alpha-10.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-09` — no uninstall needed, and this release changes no stored data.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
