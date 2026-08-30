# Condorino `alpha-08`

Eighth alpha: search now covers Lufthansa Group carriers alongside Condor, and standby prices are
scoped per airline. Everything in `alpha-07` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-07).

## Added

* **Lufthansa Group carrier selection.** Settings → Airlines now lists the nine Lufthansa Group
  carriers — Lufthansa, SWISS, Austrian, Brussels, Eurowings, Discover, Edelweiss, Air Dolomiti, and
  Lufthansa City Airlines — as individually selectable/deselectable chips, off by default. Condor
  itself is always searched and is not a toggle: it remains the app's permanent baseline regardless
  of what else is selected.
  Filtering only applies to the two sources that structurally see every airline at an airport —
  AeroDataBox and OpenSky. The official Condor Developer API, the custom feed, and demo data are
  already scoped to Condor by nature and are unaffected. OpenSky's per-route/weekday timetable
  reconstruction now also groups by airline, so two carriers on the same route and day are never
  blended into one observation; its in-memory cache invalidates whenever the selection changes so a
  toggle takes effect immediately instead of waiting out the existing cooldown.
* **Airline-scoped standby prices.** A destination can now hold more than one manually entered
  standby price — one per airline actually flying it — since a Lufthansa Group fare and a Condor fare
  to the same city are commonly priced differently on staff travel. The Standby Prices screen shows
  an airline chip picker per destination (once more than one airline applies to it) to add or edit
  each one separately; the collapsed card summary always shows Condor's own price. Trip scoring only
  ever picks up the price entered for the specific airline operating that flight — never another
  airline's as a stand-in. Export/import carries the airline tag through the JSON file; an export
  written before this feature (no airline field at all) still imports exactly as it always did, as a
  Condor price, so no existing export needs any changes.

14 new unit tests (187 total, was 173) cover the Lufthansa Group airline model, AeroDataBox/OpenSky
airline filtering, and standby-price airline tagging including backward-compatible import of an
export written before this feature existed.

## Known limitations

Unchanged from `alpha-07`. As with every RapidAPI source, AeroDataBox's and OpenSky's airline/ICAO
field names are a confident but still unverified reconstruction pending real account access — correct
them in Settings from your own account's Test Endpoint panel if a real response doesn't match.

## Installing

Download `condorino-alpha-08.apk` and open it on the phone (installation from unknown sources has to
be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update over
`alpha-07` — no uninstall needed.

Standby prices are stored in a Room database whose schema changed in this release (a new composite
key to support one price per airline); the app's existing "cache is disposable" upgrade policy
applies, so **export your prices first** (Standby Prices → Export) if you want to be safe, then
import them back after updating — an export from before this feature imports unchanged, as Condor
prices.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
