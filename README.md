# Condorino ✈️

**Weekend-trip planner from Frankfurt (FRA).**

Condorino answers a single question, and tries to answer it well:

> “Where can I fly this weekend without spending holiday — and which trip is the most attractive
> overall?”

The app does not list flights, it **scores weekends**. From departure times, return times, length
of stay, travel time to the airport, trip pattern, standby prices and destination profiles it
computes a transparent score from 0 to 100 per trip. The headline figure is not the ticket price
but: *how well does this trip fit into an ordinary working week?*

---

## ⚠️ First: the thing about live data

**The app ships no real Condor flight data until you configure a data source.**

Condor runs a developer portal (`developer.condor.com`) with a Flight Information API, but its
actual contract requires registration and could not be inspected while this app was built. Guessing
endpoints would have produced an app that appears to work and quietly finds nothing. Instead:

* the data layer is fully built and swappable,
* the Condor API contract is **entered in Settings** as soon as you have it,
* **AeroDataBox** (RapidAPI) answers the exact weekend asked directly, from real scheduled/live
  airport departure and arrival data,
* alternatively the app reads any HTTPS feed following a documented JSON schema,
* **OpenSky Network** reports for free and without an account which flights from FRA *actually*
  flew over the past few weeks — the app derives an observed timetable from that; it's the
  lowest-priority source, since it reconstructs a timetable rather than answering the exact
  weekend, and its own free-tier quota is worth conserving,
* and with no source configured it shows **sample data behind a permanent red banner**, with flight
  numbers that start with `DEMO`.

AeroDataBox and OpenSky both see every airline at the airport, not just Condor — **Settings →
Airlines** decides which to keep: Condor always, plus any Lufthansa Group carrier (Lufthansa,
SWISS, Austrian, Brussels Airlines, Eurowings, Discover, Edelweiss, Air Dolomiti, Lufthansa City
Airlines) individually turned on. Off by default, so nothing changes for you until you opt one in.

The details — what exists, what could not be verified, and how to feed in real data — are in
**[docs/CONDOR_DATA_SOURCES.md](docs/CONDOR_DATA_SOURCES.md)**.

---

## What the app does

**Weekend scoring.** Four trip patterns, in the priority order from the brief: Fri → Sun,
Thu → Sun, Fri → Mon, Thu → Mon. The order follows from what each pattern costs in holiday
(0, 0, 1, 1 days) — and that is printed on every card.

**Working time as the guiding measure.** From end of work (17:00), travel time to FRA (45 min) and
airport buffer (90 min), the app computes the earliest departure that costs *no* working time —
**19:15** by default. Anything earlier is working time lost, and is penalised twice: in flight-time
comfort and in the effective holiday requirement.

**Effective time on site.** Not the raw difference, but arrival + transfer through to the return
flight − airport buffer − transfer. For the London example in the brief this yields exactly the
46 hours quoted there.

**More screens.** Calendar with a star rating per weekend and a best-weekends ranking across up to
six months · destination comparison (up to six side by side) · “Surprise me” with six modes
(random, top 10, under budget, sun destination, city break, best score) · favourites · filters by
travel days, cabin, price, minimum score and destination type.

**Standby prices.** Enterable per destination *and per airline*, economy/business, outbound and
inbound separately, either per segment or as a round trip, with optional taxes. The app does **not**
query MyID Travel and stores **no** credentials. Because these are typed in by hand and exist
nowhere else, every change is also written to a second local copy, which the app restores from
automatically if the database is ever emptied — and they can be exported to, and imported from, a
plain JSON file you keep wherever you like.

**Airport reference.** 6,442 airports from public datasets (OurAirports, OpenFlights, IANA tzdata),
each with a documented time zone. Airports whose zone could not be established are deliberately
absent — the app never guesses a time zone. A feed therefore only has to supply IATA codes.

**Bilingual.** German and English (US). Date formats are day-before-month in both languages,
**MM/DD/YYYY is used nowhere**, and times are 24-hour throughout. Switchable per app from
Android 13 on.

**Light and dark.** System / Light / Dark, switchable without a restart.

**In-app updates.** Once a day the app checks this repository's own GitHub Releases, downloads a
newer APK in the background over Wi-Fi (configurable) and notifies you — one tap installs it. Only
works for a build produced by the release workflow (it needs to know its own release date to
compare against); a CI or hand-built APK says so plainly instead of guessing.

**Honest states.** Every figure carries its provenance: `LIVE`, `RECENTLY UPDATED`, `TIMETABLE`,
`CACHED`, `MANUAL` or `SAMPLE DATA`, always alongside “Last updated: HH:MM”. With no network the
app shows the cache instead of crashing. There are no empty lists — instead you get the reason why
nothing matched.

---

## Getting the APK

**Release:** the current alpha is under
[Releases](https://github.com/kevinluca1-ctrl/Condorino/releases) — download the
`condorino-<tag>.apk` from the newest release and open it on the phone.

**Prebuilt from CI:** the `Build APK` workflow builds a debug and a release APK on every push.
Under *Actions → Build APK → latest run → Artifacts* you will find `condorino-debug-apk` and
`condorino-release-apk`.

**Build it yourself:** see **[docs/BUILD.md](docs/BUILD.md)**. In short:

```bash
git clone https://github.com/kevinluca1-ctrl/Condorino.git
cd Condorino
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17 and the Android SDK (Platform 35). On the phone, allow installation from
unknown sources.

---

## First steps in the app

1. **Open it.** Home immediately shows the coming weekend with the best-scoring trips.
2. **Settings → Working hours**: adapt them to your life (end of work, travel time, buffer). The
   resulting “earliest sensible departure” is shown directly underneath.
3. **Settings → Standby prices**: enter the MyID Travel prices for your regular destinations.
   Without a price the cost component is scored neutrally, and the app says so.
4. **Settings → Data sources**: set up a real data source and switch the sample data off. Each
   source has a **Test** button that calls the real endpoint and repeats the answer verbatim.
5. **Score weighting**: shift it if, say, cost matters more to you than length of stay.

---

## Technology

Kotlin · Jetpack Compose · Material 3 · MVVM/Clean · Coroutines + Flow · Room · DataStore ·
OkHttp · kotlinx.serialization · WorkManager · Gradle Kotlin DSL ·
`minSdk 26` / `compileSdk 35`.

WorkManager warms the cache once a day on Wi-Fi for the next eight weekends, so the app shows
something useful the moment it opens — offline too.

`minSdk 26` is a deliberate decision: it makes `java.time` available without desugaring, and
correct time-zone arithmetic is not a side issue in this app.

Structure and reasoning: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

### Tests

252 unit tests covering pattern detection, the workday penalty, effective length of stay, counting
nights across midnight, time zones (UK, Madeira, Greece, summer/winter), cost scoring, random
selection, feed parsing, price-field text handling, airport search ranking, update-release selection,
standby-price export/import (including per-airline tagging and backward compatibility with exports
written before multi-airline pricing existed), the OpenSky token-refresh and credit-safe chunking
behaviour, the AeroDataBox chunked-window request building and generic JSON field mapping, the
Google Flights and TripAdvisor URL building and generic JSON field mapping (including TripAdvisor's
two-step location-then-highlights request chain), Lufthansa Group airline selection and
filtering, matching a flight to the right standby price across the IATA/ICAO designators that
different sources report, distinguishing a genuine "you're on the latest version" from a false
update offer caused by build/publish clock skew, the standby-price safety net that restores
hand-typed prices after the database loses them, the scoring engine's piecewise interpolation
against out-of-order and duplicate breakpoints, date formats (including the guarantee that English
never formats month-first) and the ranking cases from the brief:

```bash
./gradlew testDebugUnitTest
```

### Privacy

No accounts, no tracking SDKs, no analytics. Stored locally are only: settings, standby prices you
entered yourself, favourites and a flight-data cache. Network access goes exclusively to the data
sources you configured yourself.

### External data sources

| Source | Role | Status |
| --- | --- | --- |
| Condor Developer API (`developer.condor.com`) | intended primary source | contract entered by the user |
| [AeroDataBox](https://rapidapi.com/aedbx-aedbx/api/aerodatabox) (RapidAPI) | real scheduled/live flights for the exact weekend | RapidAPI key entered by the user |
| Custom HTTPS feed (Condorino feed schema) | works immediately | URL entered by the user |
| [OpenSky Network](https://opensky-network.org/) | cross-check: flights actually flown; lowest priority | free, account optional |
| [OurAirports](https://github.com/davidmegginson/ourairports-data) | airport codes, cities, countries | public domain, bundled |
| [OpenFlights](https://github.com/jpatokal/openflights) | time zone per airport | ODbL, bundled |
| [IANA tzdata](https://github.com/eggert/tz) | time zone per country | public domain, bundled |
| `assets/demo_schedule.json` | sample data | bundled, flagged in red, switchable |
| `assets/destination_profiles.json` | editorial destination ratings | bundled, editable |
| MyID Travel | **not** integrated | prices entered manually |
