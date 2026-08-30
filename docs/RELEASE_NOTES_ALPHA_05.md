# Condorino `alpha-05`

Fifth alpha: TripAdvisor travel recommendations, and a unified RapidAPI key. Everything in
`alpha-04` still applies — see its [release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-04).

## Added

* **"Nearby" highlights on the trip detail screen.** A compact card next to the standby and commercial prices shows a handful of well-rated attractions, restaurants and places to stay near the destination — fetched on demand, one destination at a time, never automatically for every trip on screen. Purely informational: it never affects a trip's score or ranking, the same way the Google Flights price check doesn't. Built the same way that source was: the RapidAPI listing this talks to (a long-running TripAdvisor-data API) could not be reached from where this was built to verify its exact contract, so every endpoint path, parameter name and response field name in Settings → TripAdvisor is a clearly-labelled, user-editable best guess — not an invented fact. Runs as two chained requests (resolve the destination's city name to TripAdvisor's own location id, then fetch highlights for it).
* **One shared RapidAPI key.** Settings → RapidAPI now holds a single key used by both Google Flights and TripAdvisor (and any future RapidAPI-hosted source), matching how RapidAPI itself works — one account-level key valid across every API that account has subscribed to — instead of asking for the same key twice. Upgrading from an earlier alpha carries your already-entered Google Flights key forward automatically.

## Fixed

* **A category-guessing false positive**, caught by this release's own test suite before it shipped: the heuristic that infers a highlight's category (attraction/restaurant/hotel) from free text checked for the substring "thing" (meant to catch "things to do"), which also matched inside "something", "nothing" and "everything" — misclassifying generic category text as an attraction. Replaced with the more specific phrase "things to do".

158 unit tests (was 146), 12 of them new coverage for the TripAdvisor request chain and JSON mapping.

## Known limitations

Unchanged from `alpha-04`, plus: TripAdvisor's field names are an unverified best guess pending real RapidAPI access — correct them in Settings from your own account's Test Endpoint panel once you have one, the same as Google Flights.

## Installing

Download `condorino-alpha-05.apk` and open it on the phone (installation from unknown sources has to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update over `alpha-04` — the stable signing key introduced there means no uninstall is needed for this or future updates.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing setup.
