# Condorino `alpha-09`

Ninth alpha, and a bug-fix release: it repairs standby prices, which `alpha-08` broke for every
trip, and makes the Google Flights source explain itself when it can't return a price. Everything
in `alpha-08` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-08).

## Fixed

* **Standby prices read "not set" on every trip, even right after importing a price file.** This
  was a regression introduced by `alpha-08`'s per-airline pricing. Prices are stored against an
  airline, and a trip was matched to one by comparing that airline's code to the flight's — as
  plain text. But the app's sources do not agree on which designator they report: the official
  Condor Developer API says `DE` (Condor's IATA code) while AeroDataBox and OpenSky say `CFG`
  (its ICAO code), and the bundled demo schedule uses a deliberately fake `XX`. An imported file
  stored its prices under `CFG`, so a flight arriving as `DE` or `XX` matched nothing and every
  trip showed "not set", with no way to tell why.

  Codes are now *resolved* before being compared, so either designator finds the same airline, and
  prices are normalised to one designator when saved. A flight whose airline the app cannot
  identify at all — demo data, or a custom feed using its own codes — counts as unattributed
  rather than as some other airline, so a Condor price still applies to it. What has **not**
  changed is the rule the per-airline split exists for: one identified airline never borrows
  another's fare, so a Lufthansa flight with only a Condor price entered still shows nothing
  rather than a figure that would misstate the cost.

  **No action is needed on your side** — no re-import, no re-entry. Existing prices are matched
  correctly as soon as you update.

* **The same mismatch could silently hide flights.** AeroDataBox's airline filter compared the
  selected airlines (held as ICAO codes) against whatever the configured airline field returned.
  Since that field is user-editable and a response may carry the IATA code instead, the filter
  could drop every row and report an empty airport with nothing to explain it. It now resolves
  both sides too.

* **"Add standby price" on a trip now opens on that trip's airline** rather than always on Condor.
  Entering a Lufthansa trip's fare under Condor produced a price that trip would never use.

* **Google Flights: "The response contained no usable price" now relays what the API actually
  said.** `alpha-08` made that failure describe where the field mapping gave up, which revealed the
  real cause: the API answers HTTP 200 with an error envelope (`status` / `message`) rather than an
  error status code, so what looked like a mapping problem was usually the API declining the
  request — most often the RapidAPI subscription not covering this endpoint. The app now shows that
  message verbatim, and only falls back to describing the field mapping when the response carries
  no explanation of its own.

## Known limitations

Unchanged from `alpha-08`. Google Flights, AeroDataBox and TripAdvisor field names all remain a
best-effort reconstruction rather than a verified contract — but a failure in any of them now says
what it actually saw, so correcting the mapping from your own RapidAPI test panel is guesswork-free.

31 new unit tests (224 total, was 193), including an end-to-end one that imports a price file
written before per-airline pricing and asserts the price shows against a trip — the exact path that
was broken. Each fix here is covered by a test that fails against `alpha-08`'s code.

## Installing

Download `condorino-alpha-09.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-08` — no uninstall needed, and this release changes no stored data, so no export is
needed beforehand this time.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
