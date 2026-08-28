# Architecture

## Overview

A single-module app with cleanly separated layers. Dependencies point inwards only:
`ui → domain ← data`. `domain` and `scoring` contain **no** Android imports and are fully testable
as plain JVM unit tests — which is also why all of the scoring logic lives there and not in
ViewModels.

```
com.condorino.weekend
│
├── domain                     ← pure Kotlin models, no Android
│   ├── model                  Airport, Flight, WeekendPattern, WeekendTrip,
│   │                          Destination, StandbyPrice, Money, TripScore,
│   │                          UserPreferences, ScoreWeights, DataProvenance
│   └── repository             TripRepository, StandbyPriceRepository,
│                              FavoriteRepository, DataStatus
│
├── scoring                    ← the heart of the app, no Android
│   ├── TimeCompatibilityCalculator   workday penalty, effective time, nights
│   ├── TripScoringEngine             six weighted components → 0..100
│   ├── TripBuilder                   legs → scored trips + rejection reasons
│   ├── WeekendCalendar               anchor-day logic (which Friday?)
│   ├── RandomDestinationSelector     “Surprise me”
│   └── ScoringMath                   clamp / piecewise interpolation
│
├── data
│   ├── source                 FlightDataSource + four implementations
│   ├── reference              AirportReferenceCatalog (6,442 public airports)
│   ├── local                  Room: entities, DAOs, CondorinoDatabase
│   ├── prefs                  PreferencesStore (DataStore)
│   ├── mapper                 entity ↔ domain
│   ├── DestinationCatalog     editorial metadata from assets/
│   ├── DefaultTripRepository  cache → sources → scoring
│   └── DefaultStandbyPriceRepository / DefaultFavoriteRepository
│
├── ui
│   ├── theme / components     light+dark theme, TripCard, ScoreBadge,
│   │                          ProvenancePill, DataStatusBar, EmptyState,
│   │                          SearchField, AirportSearch
│   ├── planner                PlannerViewModel (home/detail/compare/random/favourites)
│   ├── calendar               CalendarViewModel + screen (calendar + multi-weekend)
│   ├── settings               SettingsViewModel + settings and price screens
│   ├── text                   turns domain values into localised sentences
│   └── home / search / tripdetail / compare / random / favorites
│
├── work                       WeekendRefreshWorker (daily cache warm-up)
├── navigation                 routes + bottom navigation
├── di                         AppContainer (manual DI)
└── core                       Formatting, MoneyInput
```

## Why manual DI instead of Hilt

In a single-module project with no runtime swapping of implementations, a DI framework buys little
and costs an extra annotation processor. `di/AppContainer.kt` instead makes the one decision that
really matters here — **the order of the data sources** — explicit in a single readable place.

Room needs KSP anyway; that is the only annotation processor in the build.

## Data flow

```
   PlannerViewModel
        │  load(friday, refresh = true)
        ▼
   DefaultTripRepository
        │
        ├─1─► read Room cache  ───────────────► show immediately (provenance CACHED)
        │
        ├─2─► FlightDataSource chain:
        │       CondorDeveloperApiDataSource   (only when configured)
        │       HttpFeedFlightDataSource       (only when configured)
        │       OpenSkyFlightDataSource        (cross-check, always SCHEDULE)
        │       └ otherwise: AssetDemoFlightDataSource, if allowed → DEMO
        │
        ├─3─► persist a successful answer in Room + write RefreshState
        │
        └─4─► TripBuilder(prefs).build(flights, friday, destinations, prices)
                  └─► TripScoringEngine per candidate
                          └─► WeekendTrip with TripScore + reasoning
```

The first step is what makes the app work offline: the cache is always rendered first, the network
call runs afterwards and only updates the state. A failure never clears the cache.

### Range search

`searchWeekend` covers one weekend, `refreshRange` the whole calendar period. The latter is not a
luxury: the cache only holds weekends the user has already opened — without `refreshRange` a
three-month overview would be empty by construction. Because every data source takes a date range
anyway, the overview costs **one** request, not thirteen.

`WeekendRefreshWorker` calls the same method once a day on Wi-Fi for the next eight weekends. It
fills the cache and nothing else: no notifications, no actions.

## Time zones

The core of this app's correctness.

* `Flight.departure` / `Flight.arrival` are `java.time.Instant` — absolute, UTC, zoneless.
* Every `Airport` carries its IANA zone (`Europe/London`, `Atlantic/Madeira`, …).
* Wall-clock times only ever arise through `departureLocal` / `arrivalLocal`, which apply the zone
  of the airport in question — departure in the origin's zone, arrival in the destination's.
* Room stores epoch millis, never local strings.
* An airport with no resolvable zone is discarded rather than guessed (`FeedParser`), and when
  reading back from the cache a flight whose airport is no longer known is skipped — a guessed zone
  would corrupt every time the app displays.
* `minSdk 26` was chosen for exactly this reason: `java.time` without desugaring.

Tested for, among others, the United Kingdom (−1 h), Madeira (−1 h), Greece (+1 h) and for the
summer/winter shift of the same wall-clock time.

## The scoring

`TripScoringEngine.score()` produces six `ComponentScore`s. Each has a 0..100 value, the weight
applied and an explanation; the total is the weight-normalised sum. Because it is normalised, the
user can move a single slider without silently changing the scale of the others.

| Component | Default | What goes into it |
| --- | --- | --- |
| Flight-time comfort | 25 % | outbound workday penalty, buffer after work, night arrival, lateness of the return |
| Stay quality | 20 % | effective hours on site, nights against min/max |
| Weekend compatibility | 20 % | pattern priority **and** working time actually lost |
| Logistics | 10 % | flight duration against the maximum, transfer time, non-stop, late arrival home |
| Cost | 15 % | standby round trip in the preferred cabin against the budget |
| Destination quality | 10 % | editorial factors, filtered to the selected destination types |

### The two central quantities

**Workday penalty.** The earliest departure that costs no working time is
`end of work + travel time to FRA + airport buffer` (by default 17:00 + 45 + 90 = **19:15**). Every
minute before that is working time lost; a full working day (8 h) corresponds to a penalty of 1.0.
It acts twice: it lowers flight-time comfort *and* it raises the effective holiday requirement in
weekend compatibility. That is why a Friday 13:00 departure falls sharply rather than being merely
“a bit less convenient”.

**Effective time on site.**

```
usable start = arrival             + transfer airport → city
usable end   = return departure    − airport buffer − transfer city → airport
```

For the example in the brief (FRA 18:15 → LGW 18:35, back Sun 19:35, transfer 45 min, buffer
90 min) this yields exactly the **46 h** quoted there.

### Holiday days as the ordering principle

The priority order the brief prescribes — Fri→Sun, Thu→Sun, Fri→Mon, Thu→Mon — is not stored
arbitrarily in the model; it follows from what each pattern costs in holiday: the two Sunday
patterns cost zero days, the two Monday patterns one each. `WeekendPattern` therefore carries a
`vacationDaysRequired` field, which is shown directly in the UI.

## Error handling

An empty list is never an answer. `TripBuilder` counts rejection reasons (`RejectionReason`) and
picks the **most informative** one for display — not the most frequent. Otherwise “no outbound
flight on Thursday” would drown out the far more useful “standby price is over your budget” every
time.

Network and configuration errors are separated in the type system: `FlightSearchResult.NotConfigured`
(not an error but a setup task) versus `FlightSearchResult.Failure`. The repository chain tries the
sources in order and collects both kinds, so the banner can say what to do.

Data sources also expose `selfTest()`, wired to a **Test** button per source in Settings. It calls
the real endpoint and repeats what came back, so “my credentials do not work” has an answer rather
than an inference — an OpenSky token rejection is reported as a failure instead of silently falling
back to anonymous access.

## Localisation

`domain` and `scoring` emit structured values, never sentences: `TripInsight`, `ComponentDetail`,
`RejectionReason`, `EmptyReason`, `CalendarMessage`, `SkippedRow`. The `ui/text` layer turns those
into the reader's language. That keeps the scoring engine free of Android and of any one language,
and it is why adding English (US) touched no scoring code.

Data sources are the deliberate exception: they already do I/O and hold a `Context`, and their
messages are diagnostics full of HTTP codes and counts, so they resolve strings through
`SourceStrings` instead.

Airport names come from the bundled public reference, and country names are derived from the ISO
code at render time, so they follow the device language rather than being frozen into a data file.

## Extensibility

`FlightDataSource` does not know the word “Condor”. Adding another airline means: write an
implementation and hook it into `AppContainer.liveSources`. `Flight` already carries `airline` and
`airlineCode`, and `Money` carries the currency explicitly.
