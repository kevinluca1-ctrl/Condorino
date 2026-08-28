# Condorino `alpha-01`

First installable alpha of the weekend-trip planner from Frankfurt (FRA).

> ⚠️ **Alpha.** With no data source configured the app shows **sample data** — invented flight
> times with flight numbers `DEMO …`, behind a permanent red banner. That is deliberate, not a bug:
> the app never passes invented data off as real flights.

## What's in it

**Weekend scoring.** Four trip patterns (Fri → Sun, Thu → Sun, Fri → Mon, Thu → Mon) with a
transparent score built from six weighted components. The guiding quantities are the *workday
penalty* (how much working time a departure costs) and the *effective time on site* (arrival +
transfer through to the return flight − buffer − transfer). Every number can be broken down in the
detail view.

**Screens.** Home with weekend selection · filters (days, cabin, price, minimum score, destination
type) · trip detail with a time budget and score breakdown · calendar with star ratings and a free
from/to range · destination comparison · “Surprise me” with six modes · favourites · standby-price
management.

**Data sources** — each switchable individually, in this order:

1. **Condor Developer API** — a complete client whose contract you enter in Settings once you have
   access to `developer.condor.com`. **No endpoints are guessed.**
2. **Custom HTTPS feed** — documented JSON schema, works immediately.
3. **OpenSky Network** — free and usable without an account: which `CFG` flights from FRA
   *actually* flew over the past few weeks. The app derives an observed timetable from that. Always
   labelled TIMETABLE, never LIVE.
4. **Sample data** — switchable off.

Every source has a **Test** button that calls the real endpoint and repeats the answer verbatim,
so a rejected credential is stated rather than inferred.

**Airport reference.** 6,442 airports from OurAirports (public domain), OpenFlights (ODbL) and IANA
tzdata, each with a documented time zone. Airports whose zone could not be established are
deliberately absent. Feeds therefore only have to supply IATA codes.

**Bilingual.** German and English (US). Date formats are day-before-month in both languages —
**MM/DD/YYYY is used nowhere** — and times are 24-hour throughout. Switchable per app from
Android 13 on (Settings → Language).

**Light and dark.** Theme switching between system / light / dark, without a restart.

**Privacy.** No accounts, no tracking SDKs. Stored locally are only settings, standby prices you
entered yourself, favourites and a flight-data cache. MyID Travel is not queried and no credentials
are stored.

## Installing

Download `condorino-alpha-01.apk` and open it on the phone. Installation from unknown sources has
to be allowed for the installing app. Android 8.0 (API 26) or newer.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside.

> Both APKs are signed with the **debug keystore**, so CI can deliver something installable without
> secrets. A real publication needs your own keystore — see `docs/BUILD.md`.

## First steps

1. **Settings → Working hours**: adapt them to your life. The resulting “earliest sensible
   departure” is shown directly underneath (19:15 by default = 17:00 + 45 min + 90 min).
2. **Settings → OpenSky cross-check**: switch it on to get real observed flights.
3. **Settings → Standby prices**: fill them in for your regular destinations.
4. **Switch the sample data off** as soon as a real source delivers.

## Known limitations

* No live availability without Condor API access — OpenSky says what *was* flown, not what *is*
  bookable.
* The observed timetable is based on transponder receptions; `firstSeen` is close to, but not
  exactly, the scheduled departure time.
* The destination profiles (nightlife, beach, culture factors) are editorial judgements in
  `assets/destination_profiles.json`, not measurements.
* Unit tests only, no instrumentation tests.
