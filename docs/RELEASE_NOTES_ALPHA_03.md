# Condorino `alpha-03`

Third alpha: a Google Flights price comparison, a real fix for a data-integrity bug in the demo-data
toggle, and a broad overnight stress-test/cleanup pass. Everything in `alpha-02` still applies — see
its [release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-02).

## Fixed

* **"Allow demo data" off didn't actually remove demo data.** Turning the toggle off only stopped *new* demo fetches — flights already cached from demo data kept showing up in every search indefinitely, because demo-tagged data deliberately never ages into looking like real cached data (so it can never be mistaken for it). Disabling the toggle now actively purges cached demo flights and, as a second, independent safeguard, every read of the flight cache filters out demo rows whenever the toggle is off — so this can't regress even if the purge timing ever changes.
* **Settings accepted nonsensical numbers.** Max flight time, min/max nights, budget, and the travel-time buffer fields took any integer, including 0 or negative — which could silently soft-lock the trip search into permanent empty results with no obvious cause. These now clamp to sane ranges, the same way the OpenSky lookback field already did.

## Added

* **Google Flights price comparison.** A "Check price" button on the trip detail screen looks up what a normal paying ticket for that exact trip would cost today via the Google Flights listing on RapidAPI, plus carry-on inclusion when the source reports it — purely informational, shown next to the standby price, not folded into the trip score. Queried on demand, one trip at a time, never automatically for every trip on screen. Built the same way the Condor Developer API source was: the RapidAPI contract could not be verified from where this was built, so every endpoint/parameter/field name is a clearly-labelled, user-editable starting point in Settings → Google Flights, not an invented fact.
* **A genuinely free, no-signup live data source is now one tap away.** OpenSky's anonymous mode already shipped fully defaulted (Frankfurt, Condor's callsign prefix, working URLs) and needed no typed configuration — it just needed to be switched on. The demo-data banner, and the empty state shown when no source is configured at all, now offer an "Enable free live data (OpenSky)" action that does exactly that without a trip to Settings.

## Stress-test / cleanup pass

An unsupervised overnight review of the scoring engine, every data source, the repository layer, and every screen's ViewModel, looking specifically for correctness bugs and rough edges rather than new features:

* Traced the scoring engine's piecewise interpolation (`ScoringMath.piecewise`) end to end against the derived breakpoints that depend on user preferences (`maxFlightMinutes`) to confirm it stays correct even when those breakpoints collide with fixed anchors — it does, by sorting its input, but this was previously unverified by any test. Now covered by 10 new regression tests (out-of-order input, duplicate-x segments, NaN handling), which is also why `maxFlightMinutes` now has a 60-minute floor: below that, a user-entered value can genuinely collide with fixed points in the comfort curve.
* Reviewed every `FlightDataSource`/`CommercialPriceSource` implementation, the trip builder, the time-compatibility and scoring math, the feed parser, and the airport reference loader for null-handling, timezone correctness, and empty/negative-duration edge cases. No further correctness bugs found — the codebase's existing edge-case handling (empty destination lists, DST boundaries, midnight-crossing arrivals, blank config fields) held up.

146 unit tests (was 136).

## Known limitations

Unchanged from `alpha-02`, plus: Google Flights' field names are an unverified best guess pending real RapidAPI access — correct them in Settings from your own account's Test Endpoint panel once you have one.

## Installing

Download `condorino-alpha-03.apk` and open it on the phone (installation from unknown sources has to be allowed for the installing app; Android 8.0/API 26 or newer). Installs as an update over `alpha-01`/`alpha-02`, or use the in-app updater.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing setup.
