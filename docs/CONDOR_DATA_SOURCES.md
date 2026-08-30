# Phase 1 — Technical analysis of the Condor data sources

This document is the result of the feasibility analysis required by §27 (“Phase 1”) and the
reasoning behind why the app is built the way it is.

## Summary

**The app contains no invented Condor endpoints and no invented flight times passed off as real.**

There is an official Condor developer portal, but its actual API contract requires registration and
could not be inspected while this app was built. Rather than guessing endpoints, parameter names
and response fields — which would produce an app that appears to work and quietly returns nothing —
the data layer is built so that the contract is **entered by the user at runtime**, once they have
it.

## What exists

Researched on 2026-08-28. The following publicly discoverable Condor offerings exist:

| Resource | URL | Assessment |
| --- | --- | --- |
| Condor Developer Portal | `https://developer.condor.com/` | Exists. Entry point for the API products. |
| Flight Information API | `https://developer.condor.com/api/flight` | Product page exists. Contract not publicly inspectable. |
| Flight Offer API | `https://developer.condor.com/api/flightoffer` | Product page exists (“Best Flight Deals”). |
| Travel Shopping Carts API | `https://developer.condor.com/api/cart` | Booking-side, not relevant to this app. |
| API gateway | `https://api.condor.com/` | Exists as a host. |

In addition there are commercial third-party aggregators that carry Condor data — among them
[Duffel](https://duffel.com/flights/airlines/condor) (search/booking, contract required) and
[AirLabs](https://airlabs.co/condor-developer-api) (flight status/routes/schedules, paid). For
timetables, OAG and Cirium are also candidates. All of them require a contract and payment.

## What could not be verified — and why

The build environment this app was created in has an egress filter that blocks `condor.com`,
`developer.condor.com` and `api.condor.com`. It was therefore **not** possible to:

* read the Flight Information API documentation,
* verify endpoint paths, query parameters or header names,
* determine the response format,
* establish rate limits or the auth method (API key vs. OAuth),
* observe the network calls made by Condor's public flight search.

Requirement §3 therefore applies literally: *“No invented API endpoints. No hardcoded flight times.
No assuming that a particular API exists.”*

### On the public flight search

Even if the Condor website's XHR endpoints had been observable, they would be a poor foundation:
they are internal endpoints with no stability guarantee, they are typically behind bot protection,
and automated use of them generally falls under the site's terms of use. An official API contract
or a schedule provider is the viable route. The architecture keeps both doors open.

## How the app solves this

Five swappable implementations of the `FlightDataSource` interface, in this order:

### 1. `CondorDeveloperApiDataSource` — the official route, configured by the user

A complete HTTP client whose **contract comes from Settings**: base URL, path, query parameter
names, auth header, plus the response field names and the path to the flight list inside the
response envelope. None of it is guessed or pre-filled with an invented endpoint.

As long as nothing is entered, the source reports `SourceStatus.NotConfigured` with an explanation
— it never silently returns nothing.

Once you have portal access, you only need to fill in *Settings → Condor Developer API*. If the
response format differs substantially, `CondorDeveloperApiDataSource.mapFlights()` is the only
place that has to be adapted.

### 2. `AeroDataBoxFlightDataSource` — real scheduled/live flights, on RapidAPI

Talks to AeroDataBox's "Airport Flights (FIDS)" endpoint on RapidAPI (host
`aerodatabox.p.rapidapi.com`): one request returns the actual scheduled departures and arrivals for
Frankfurt over a local time window, in both directions at once. Unlike `OpenSkyFlightDataSource`
below this is a **direct query for the exact weekend asked**, not a statistical reconstruction from
historical observations — which is why it ranks ahead of the generic feed and well ahead of OpenSky.

AeroDataBox's own documentation site could not be reached from the environment this was built in
(blocked by network egress, the same limitation as every other RapidAPI source in this app) — but
unlike `TripAdvisorRecommendationSource` below, this endpoint and its response shape are widely
documented and cross-referenced across multiple independent public sources, so `AeroDataBoxConfig`'s
defaults are a considerably more confident reconstruction than most other RapidAPI defaults here —
**still not a verified contract**, though: *Settings → AeroDataBox* exposes every field name for you
to correct once you have real RapidAPI access, and `AeroDataBoxFlightDataSource.mapFlights()` is the
only place that interprets them. A request's local time window is capped
(`AeroDataBoxConfig.windowHours`, default 12h) since lower RapidAPI subscription tiers are commonly
reported to reject a much wider one; a query spanning a whole weekend is therefore split into a few
chunked requests rather than one long one of uncertain validity.

The airport's FIDS response covers every airline flying through it, not just Condor — *Settings →
Airlines* is what narrows that down to Condor plus whichever Lufthansa Group carriers are opted in
(see `Airlines` in `domain/model/Airline.kt`); a row whose operating carrier isn't selected is
dropped the same way one with an unresolvable airport or time already is.

### 3. `HttpFeedFlightDataSource` — the route that works today

Loads a JSON document following the **Condorino feed schema** documented below from any HTTPS URL.
This lets you put real data into the app immediately, wherever it comes from: a GDS/OAG/Cirium
extract, a Condor partner contract, or a small self-hosted bridge that translates your source into
this format.

The feed states for itself whether it is live (`"is_live": true`) or a published timetable. The app
**never** upgrades a provenance on its own.

### 4. `OpenSkyFlightDataSource` — cross-check against flights actually flown, de-prioritized

Free and usable without an account, but ranked **last** of the real sources and disabled by default:
it reconstructs a timetable from historical ADS-B observations rather than answering the exact
weekend asked, and OpenSky's own anonymous-tier daily credit quota is easy to exhaust with too wide
a request — an earlier version of this source did exactly that (a single search's request burst
could burn the whole day's quota and take long enough to look like it had hung). See the second half
of this document and that source's own class doc for the quota-safety limits now in place (a request
cooldown, a lower default look-back, and a hard cap on chunked requests).

Like AeroDataBox above, OpenSky's ADS-B feed covers every callsign at the airport — *Settings →
Airlines* narrows it to Condor plus whichever Lufthansa Group carriers are opted in. Observations are
grouped by weekday, route **and airline** before the median is taken, so a Condor and a Lufthansa
flight sharing a weekday and route are never blended into one wrong, averaged entry.

### 5. `AssetDemoFlightDataSource` — sample data, unmistakably flagged

So the app is usable on a fresh device with no configuration, a specimen timetable ships in
`app/src/main/assets/demo_schedule.json`.

> ⚠️ The flight numbers there start with `DEMO`, the source is called “SAMPLE DATA - an invented
> specimen timetable. These are NOT Condor flight times and say nothing about real availability.”,
> every flight produced carries `DataProvenance.DEMO`, and the app shows a permanent red banner
> above it. The source can be switched off entirely in Settings.

### 6. `GoogleFlightsPriceSource` — commercial comparison price, on demand

Separate from the four `FlightDataSource` implementations above: it doesn't search a timetable, it
prices one already-decided trip's exact dates against what a normal paying passenger would be
charged today — useful if standby doesn't work out. See `CommercialPriceSource` for why this is a
different interface, and the app's trip detail screen for the "Check price" button that triggers it.

It talks to the "Google Flights" API published on RapidAPI by DataCrawler. That listing's playground
page could not be reached from the environment this was built in (blocked by network egress), so —
same situation and same fix as `CondorDeveloperApiDataSource` above — **every endpoint path,
parameter name and response field name in `GoogleFlightsApiConfig` is a best-effort reconstruction
from public search-engine snippets, not a verified contract**. Nothing is hard-coded as fact:
*Settings → Google Flights* exposes every one of those names for you to correct once you have real
RapidAPI access, and `GoogleFlightsPriceSource.mapQuote()` is the only place that interprets them.

The carry-on/baggage fields in particular had no confirmed example anywhere in the researched
snippets, so their defaults ship blank on purpose — until you fill them in, the app reports "not
reported by source" for carry-on rather than guessing.

Queried strictly on demand — one trip, one tap — never automatically for every trip on screen, since
this is a metered third-party subscription and firing it for every candidate trip would burn through
a RapidAPI quota for data most of it would never be looked at. It is not wired into cost scoring:
it is informational, shown next to the standby price rather than folded into `TripScore`.

### 7. `TripAdvisorRecommendationSource` — nearby highlights, on demand

Same family as `GoogleFlightsPriceSource` above, both in what it answers and in how it's built. It
doesn't search a timetable or price a trip — it answers "now that I've picked this city, what's
worth seeing?", for the one destination the trip detail screen is already showing, via a compact
"Nearby" card. See `TravelRecommendationSource` for the interface.

It talks to the `tripadvisor-scraper` listing on RapidAPI (by pradeepbardiya13; host
`tripadvisor-scraper.p.rapidapi.com`) in **two chained requests**: first resolve the destination's
city name to TripAdvisor's own internal location id, then ask for nearby attractions using that id.
Neither that listing's playground page nor its docs could be reached from the environment this was
built in (blocked by network egress), so the endpoint paths and field names in `TripAdvisorApiConfig`
below are instead a reconstruction from an earlier, longer-running "Travel Advisor" API by apidojo,
which wraps the same underlying TripAdvisor data in the same two-step shape — **a best-effort
reconstruction, not a verified contract for this specific listing**. Nothing is hard-coded as fact:
*Settings → TripAdvisor* exposes every one of those names for you to correct once you have real
RapidAPI access, and `TripAdvisorRecommendationSource.mapLocationId()` / `.mapHighlights()` are the
only two places that interpret them.

The category field in particular had no confirmed example anywhere in the researched snippets, so
its default ships blank on purpose — until you fill it in, every highlight is shown as "Other"
rather than guessing what kind of place it is.

Queried strictly on demand — one destination, one tap — for the same quota reason as Google Flights.
Also not wired into cost or destination scoring: purely informational, shown next to the standby and
commercial prices rather than folded into `TripScore` or `Destination`'s hand-curated profile
factors.

### RapidAPI key, shared

`AeroDataBoxFlightDataSource`, `GoogleFlightsPriceSource` and `TripAdvisorRecommendationSource` all
run over RapidAPI, and RapidAPI itself works on one account-level key valid across every API that
account has subscribed to — the app follows the same shape rather than asking three times:
*Settings → RapidAPI* holds one key used by all three sources (and any future RapidAPI-hosted one),
while each source keeps its own host, paths and field names, since those genuinely differ per API.

### Airline selection, shared

`AeroDataBoxFlightDataSource` and `OpenSkyFlightDataSource` both query the *airport*, not any one
airline — AeroDataBox's FIDS endpoint and OpenSky's ADS-B feed both return every airline flying
through FRA, Condor included but not exclusively. *Settings → Airlines* is the one place this app
decides which of those to actually keep: Condor always, plus whichever Lufthansa Group carrier
(`Airline` entries in `domain/model/Airline.kt` — Lufthansa, SWISS, Austrian, Brussels, Eurowings,
Discover, Edelweiss, Air Dolomiti, Lufthansa City Airlines) the user has opted in, each individually.
New installs start with Lufthansa Group carriers all off, so an existing install's results don't
change on upgrade until the user turns one on. The airline codes and names themselves are public
designator data — the same kind of fact as the bundled airport reference dataset — not an invented
API contract, so they are bundled directly rather than left for the user to fill in; see the doc
comment on `Airlines` for when this was last cross-checked.

Both sources apply the selection client-side, after receiving the airport's full response — neither
endpoint accepts an airline filter as a request parameter, so this doesn't change how much either
one fetches, only which of the rows it keeps. `CondorDeveloperApiDataSource`, `HttpFeedFlightDataSource`
and the bundled demo data are unaffected by this selection: the first can only ever return Condor's
own official schedule, the second is already whatever its operator curated, and the third is
placeholder data, not real flights for any airline.

## The Condorino feed schema

```jsonc
{
  "schema_version": 1,
  "source": "Where the data comes from - shown to the user",
  "is_live": false,          // true only for real, bookable availability
  "generated_at": "2026-09-01T08:00:00Z",

  "airports": [
    {
      "iata": "LGW",
      "name": "London Gatwick Airport",   // optional if the bundled reference knows the code
      "city": "London",
      "country_code": "GB",
      "time_zone": "Europe/London"        // IANA zone, required - the app never guesses one
    }
  ],

  "flights": [
    {
      "flight_number": "DE 1234",
      "airline": "Condor",
      "airline_code": "DE",
      "origin": "FRA",
      "destination": "LGW",
      "departure": "2026-09-04T18:15:00+02:00",  // ISO-8601 with offset, or ...Z
      "arrival":   "2026-09-04T18:35:00+01:00",
      "is_direct": true,
      "fare_cents": 12900,             // optional; omit when unknown, never send 0
      "availability_note": "3 seats"   // optional
    }
  ]
}
```

Rules the parser enforces:

* An airport with no valid IANA time zone is **discarded**, not guessed.
* For any airport the bundled reference knows, the reference supplies the name, country and time
  zone — one consistent set of labels rather than a mix of whatever each feed writes. A feed's own
  declaration still covers anything the reference does not have, so the `airports` block is
  optional for well-known codes.
* A flight whose airports are declared in neither the feed nor the reference is discarded.
* A flight with an unreadable timestamp, or with arrival ≤ departure, is discarded.
* Discarded rows are counted and reported to the user — never silently swallowed.
* `fare_cents` stays `null` when unknown; “unknown” never becomes “0 €”.

Per weekend the app needs **Thursday through Monday**, in both directions from and to FRA.

## Timetable vs. availability

The app distinguishes the two concepts consistently through `DataProvenance`:

| Value | Meaning | Display |
| --- | --- | --- |
| `LIVE` | Fetched from a live source, less than 30 minutes old | green `LIVE` |
| `RECENTLY_UPDATED` | Fetched live, but older than 30 minutes | blue `RECENTLY UPDATED` |
| `SCHEDULE` | Published timetable, says nothing about bookability | yellow `TIMETABLE` |
| `CACHED` | From the local Room database (offline) | grey `CACHED` |
| `MANUAL` | Entered by the user (standby prices) | `MANUAL` |
| `DEMO` | Sample data | red `SAMPLE DATA` + warning banner |

A trip always inherits the **weakest** provenance of its two legs. Reading back from the cache
downgrades `LIVE` to `CACHED`; `DEMO` stays `DEMO` forever.

## MyID Travel / staff travel

Deliberately **not** integrated. The app stores no credentials and calls nothing (§26). Standby
prices are entered by the user (*Settings → Standby prices*), per destination, either per segment
or as a round trip, optionally with separate taxes.

## If you want real data — the shortest route

1. Request access at `developer.condor.com`.
2. Enter the contract under *Settings → Condor Developer API* and enable the source.

or

1. Produce a file following the schema above (from the extract of your choice) and serve it over
   HTTPS.
2. Enter the URL under *Settings → Custom flight feed* and enable the source.
3. Switch the sample data off in Settings.

Either way, press **Test** on the source afterwards: it calls the real endpoint and repeats the
answer verbatim, so a wrong URL or a rejected key is stated rather than inferred.

---

# Freely accessible data sources from other providers

Research from 2026-08-28. The question was: *are there free sources to cross-check against, or at
least a publicly inspectable, comprehensive list to use as a data basis?* Both — and both are now
built in.

## Built in

### 1. Airport reference dataset (`assets/airports_reference.json`)

**6,442 airports**, bundled from three public sources:

| Source | Licence | What comes from it |
| --- | --- | --- |
| [OurAirports](https://github.com/davidmegginson/ourairports-data) | public domain | IATA and ICAO code, name, city, ISO country code |
| [OpenFlights](https://github.com/jpatokal/openflights) | ODbL | IANA time zone per airport |
| [IANA tzdata `zone1970.tab`](https://github.com/eggert/tz) | public domain | time zone where OpenFlights has none |

The time zone is the critical value — it decides every time the app displays. Hence a strict
precedence:

1. **Curated correction** for island groups whose country has several zones (Madeira, the Azores,
   the Canaries) — 18 entries, each checked against the country's tzdata zone list.
2. **OpenFlights**, where it knows the airport (5,373 entries).
3. **tzdata country rule**: if a country has *exactly one* zone according to `zone1970.tab`, it
   applies to every airport in that country (1,051 entries). This is how Istanbul (LTFM) resolves
   correctly, which OpenFlights still carries without a zone — Turkey only has `Europe/Istanbul`.
4. **Otherwise: do not include it.** 2,359 airports are deliberately *absent* because their zone
   could not be established. The app never guesses a time zone.

Practical benefit: a feed only has to supply IATA codes and times; name, country and time zone come
from the reference. The dataset can be rebuilt — the three source URLs are in the file.

Country names are not stored at all: they are derived from the ISO code at render time, so they
follow the device language rather than being frozen into the file.

### 2. OpenSky Network — cross-check against flights actually flown

[OpenSky](https://opensky-network.org/) runs a **free, public REST API** over crowd-sourced ADS-B
receptions. It answers a different question from a timetable, and that is exactly where its value
lies:

* A timetable says: *“this route is planned.”*
* OpenSky says: **“this aircraft actually flew, on this day, at this time.”**

Verified contract:

```
GET https://opensky-network.org/api/flights/departure?airport=EDDF&begin=<unix>&end=<unix>
GET https://opensky-network.org/api/flights/arrival  ?airport=EDDF&begin=<unix>&end=<unix>
```

Response: a JSON array with `icao24`, `callsign`, `estDepartureAirport`, `estArrivalAirport`,
`firstSeen`, `lastSeen`. Airports as **ICAO** (Frankfurt = `EDDF`), times as Unix seconds. HTTP 404
means “nothing in this window”, not an error. Anonymous access works with tighter limits; a free
account gives higher limits via OAuth2 client credentials against

```
POST https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token
```

A rejected client ID or secret is reported as a failure — the source never falls back to anonymous
access quietly, because that looks exactly like “my credentials do not work”.

**Tokens and credits (re-verified 2026-08-29).** A token lasts 30 minutes; OpenSky's own guidance is
that a 401 on a data request means it just expired, not that the credentials are wrong, so the
source refreshes once and retries before reporting a real denial. `/flights/*` also now bills from a
daily/hourly credit quota, and the cost per request is not flat: a request whose window stays under
24 hours is cheap, but one that merely crosses into a second calendar day costs several times more.
Earlier builds requested multi-day windows and could burn a whole day's quota — sometimes more than
one — in a single search, which is the most likely actual explanation for "OpenSky reports nothing
even with correct credentials". The source now requests many short (<24h) windows instead; a 429 is
reported with OpenSky's own `Retry-After` value rather than a bare HTTP code. One documentation
oddity: `/flights/departure`'s own page currently says its interval "must cover more than two days",
the reverse of `/flights/arrival`'s "must not be larger than two days" — almost certainly a
documentation error, since it would make requesting a small window from that one endpoint
impossible. The source does not trust either reading blindly: a 400 from the departure endpoint
specifically triggers one retry with a several-day window before that chunk is given up on.

`OpenSkyFlightDataSource` filters on Condor's callsign prefix **`CFG`** (IATA `DE`, ICAO `CFG`),
groups the observations by weekday and route, and takes the **median** departure time and block
time — the median, because a single heavily delayed flight would otherwise drag the entry out of
its real slot. The result is an *observed timetable*.

**Important limitation:** `firstSeen` is the first transponder reception, not the scheduled
departure time, and none of it says anything about bookability. Everything from this source
therefore carries `DataProvenance.SCHEDULE` and is marked **TIMETABLE** in the UI, never LIVE.

**De-prioritized (2026-08-30).** An early version of this source defaulted to a 6-week look-back,
which at a 20-hour chunk window needed roughly 51 requests *per direction* — enough, in a single
search, to both burn the anonymous tier's entire daily credit quota and take long enough as 100+
sequential blocking requests to look like the app had hung. Fixed with several changes together:
requests are now cached across calls for `FETCH_COOLDOWN` (6h) rather than repeated on every
refresh trigger; the default look-back dropped from 6 weeks to 2, and the Settings maximum from 12
weeks to 4; and `MAX_CHUNKS` bounds a single fetch to well under the daily quota even at that new
maximum. OpenSky also moved to **last** in `AppContainer.liveSources` (behind AeroDataBox in
particular — see above — which answers the exact weekend asked directly rather than needing this
kind of historical reconstruction at all), and the one-tap "Enable free live data (OpenSky)" action
that used to sit on the demo-data banner was removed, since it was steering users straight into the
source most likely to run into its own rate limit; the banner now points at Settings instead, where
every source — OpenSky included — can still be enabled with one tap.

Setting it up: enable *Settings → OpenSky cross-check*. Usable immediately without an account — every
field (base URL, token URL, home airport `EDDF`, callsign prefix `CFG`) already ships with a working
default, so flipping the toggle alone is enough.

## Evaluated but not built in

| Source | Why not |
| --- | --- |
| [Duffel](https://duffel.com/flights/airlines/condor) | Real search and booking data including Condor, but contract-bound and paid; no free access. |
| [AirLabs](https://airlabs.co/condor-developer-api) | Timetables and status including Condor, paid. |
| OAG, Cirium | The reference for timetables, purely commercial. |
| AviationStack | Free tier with 100 requests/month — too few for a multi-weekend search, and the free tier is HTTP-only. |
| ADS-B communities (adsb.lol, airplanes.live) | Free and open, but they serve live positions rather than flight aggregates; for “which route was flown when”, OpenSky is the better-suited abstraction. |

All the built-in sources and the Condor API run through the same `FlightDataSource` interface and
can be enabled and disabled individually under *Settings → Data sources*. The order is: Condor
Developer API → AeroDataBox → custom feed → OpenSky → (if allowed) sample data.
