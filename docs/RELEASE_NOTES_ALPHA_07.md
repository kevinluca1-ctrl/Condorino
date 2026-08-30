# Condorino `alpha-07`

Small follow-up to `alpha-06`, fixing one upgrade bug it shipped with. Everything in `alpha-06` still
applies — see its [release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-06).

## Fixed

* **TripAdvisor 404 on an upgraded install.** `alpha-06` repointed TripAdvisor's *default* host at
  `tripadvisor-scraper.p.rapidapi.com`, but Settings persists every field of that config the moment
  any one of them is touched (even just flipping "API active" on) — so an install that had ever
  opened TripAdvisor settings before `alpha-06` already had the old, now-dead
  `travel-advisor.p.rapidapi.com` host written to disk, and a changed default never overrides an
  already-stored value. The result: every request to that host came back HTTP 404. Fixed by treating
  that one specific stale, unmodified value as "never actually customized" on read, so the new
  default reaches upgrading installs too — a host you typed in yourself, including the new default,
  is left exactly as you set it either way.

## Installing

Download `condorino-alpha-07.apk` and open it on the phone (installation from unknown sources has to
be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update over
`alpha-06` — no uninstall needed. If TripAdvisor was 404ing for you on `alpha-06`, this update alone
fixes it with no Settings changes required.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
