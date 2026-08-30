# Condorino `alpha-06`

Sixth alpha: a fix for OpenSky burning through its own usage quota, a new AeroDataBox data source
that answers the exact weekend asked directly, and TripAdvisor repointed at the listing the app is
actually meant to use. Everything in `alpha-05` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-05).

## Fixed

* **OpenSky burning through its daily quota and appearing to hang.** With the shipped defaults
  (6-week look-back, 20-hour request chunks) a single search could fire 100+ sequential requests —
  enough to both exhaust OpenSky's anonymous daily credit quota in one go and take long enough to
  look like the app had frozen before it eventually failed. Fixed with several changes together: a
  fetched, aggregated observation set is now cached across calls for six hours instead of being
  re-fetched on every refresh trigger (weekend navigation, pull-to-refresh, cold start); the default
  look-back dropped from 6 weeks to 2, and the Settings maximum from 12 weeks to 4; and a hard cap
  now bounds how many requests a single fetch can ever make.

## Changed

* **OpenSky de-prioritized.** It now ranks last among the app's real data sources — behind the new
  AeroDataBox source and the custom feed — since it reconstructs a timetable from historical
  observations rather than answering the exact weekend asked, and its own usage quota is worth
  conserving. The one-tap "Enable free live data (OpenSky)" action that used to sit on the demo-data
  banner and empty state is gone; both now point at Settings instead, where every source — OpenSky
  included — can still be turned on with one tap. Settings' own copy for OpenSky now says plainly
  where it sits in the order and why.
* **TripAdvisor repointed at `tripadvisor-scraper.p.rapidapi.com`**, the RapidAPI listing the app is
  actually meant to use, rather than the longer-running "Travel Advisor" listing an earlier alpha
  defaulted to. The field-mapping mechanism this source uses was already fully user-editable and
  stays that way — nothing else about how it works changes, and an already-entered RapidAPI key
  carries over automatically since the key is shared across every RapidAPI source.

## Added

* **`AeroDataBoxFlightDataSource`** — a new real-time data source over AeroDataBox's "Airport
  Flights" endpoint on RapidAPI. Unlike OpenSky, this asks directly "what does Condor have scheduled
  out of Frankfurt this exact weekend?" rather than reconstructing an average from history, which is
  why it now ranks second, right after the official (but access-gated) Condor Developer API. Like
  every other RapidAPI source in this app, its exact field names could not be verified against a live
  account from where this was built — but this endpoint is unusually well documented across
  independent public sources, so its defaults are a considerably more confident starting point than
  most; correct any of them in Settings → AeroDataBox from your own account's Test Endpoint panel if
  a real response doesn't match. Uses the same shared RapidAPI key as Google Flights and TripAdvisor.

15 new unit tests (173 total, was 158) cover AeroDataBox's request chunking, URL building, the
airline filter, and the generic JSON field mapping — including the departures/arrivals-are-mirror-
images shape and the timestamp-format quirk normalisation.

## Known limitations

Unchanged from `alpha-05`, plus: AeroDataBox's field names are a confident but still unverified
reconstruction pending real RapidAPI access — correct them in Settings from your own account's Test
Endpoint panel once you have one, the same as every other RapidAPI source. Some RapidAPI accounts may
need more than the free "Basic" plan to reach this particular endpoint; a denied request is worth
checking against your subscription's plan before assuming the field mapping itself is wrong.

## Installing

Download `condorino-alpha-06.apk` and open it on the phone (installation from unknown sources has to
be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update over
`alpha-05` — no uninstall needed.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
