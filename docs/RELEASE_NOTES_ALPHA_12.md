# Condorino `alpha-12`

Twelfth alpha. The diagnostics added in `alpha-11` did their job: two of these fixes are things the
APIs themselves told us, in as many words, once the app started relaying what they actually said.
Everything in `alpha-11` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-11).

## Fixed

* **Google Flights never had a chance of working: it was sending the wrong travel class.** The app
  asked for class `1` or `3` — Google Flights' own numeric codes — and the RapidAPI listing replied,
  verbatim, *"Travel class must be one of: ECONOMY, PREMIUM_ECONOMY, BUSINESS, or FIRST."* That
  message only became visible in `alpha-11`, and it is the whole answer. The defaults now send the
  named classes, and because Settings had already written the numeric ones to disk, a one-time
  migration replaces them — nothing to correct by hand.

* **TripAdvisor now uses the listing's real endpoints instead of a reconstruction.** `alpha-11`
  made the 404 name the path it asked for, which confirmed `locations/v2/search` does not exist on
  that host; the published API reference for the Tripadvisor Scraper listing then supplied what
  does. Attractions are read from `tripadvisor/attractions/list` with the place in `query`, and the
  review count and link fields carry their documented names. The stored wrong paths are migrated
  away, so an existing install picks this up without anything to correct by hand.

  The reference also settles something the old two-step chain was paying for: those endpoints
  "accept a location name, a full TripAdvisor URL, or an entity ID". Resolving a city to
  TripAdvisor's internal id first was therefore an entire request bought for nothing, and it is now
  skipped — **one destination costs one call instead of two**, which is the difference between 100
  and 200 lookups on a free plan that allows 200 a month. The two-step path is still there and
  still tested; setting a location-search path switches it back on for a host that needs it.

* **AeroDataBox said "no flights found" without saying why.** Three very different things produce
  that: the airport genuinely returned nothing, rows came back that no field mapping could read, or
  they read fine and every flight belonged to an airline that is not selected. Only the first is
  about the window; the other two are settings you can correct — but only if told which. The message
  now says which, and in the third case names the airline codes actually present, which is also how
  a mis-mapped airline field gives itself away.

* **A source that is simply switched off no longer says so twice.** Testing one could only repeat
  the sentence already shown above it.

## Changed

* **The calendar now answers "where can you go?" rather than "which weekends are good?"** Ranking
  weekends looked reasonable and was close to useless: two of the three real sources describe a
  *repeating weekly timetable*, so every Friday in a range offers the same flights at the same times
  and scores identically — which is exactly what the screen showed, the same destination and the
  same score four times over. The question with an answer is the other one. Each destination now
  appears once, at its own best weekend, with how many weekends it flies at all; and when every
  weekend in the range genuinely is equivalent, the screen says so outright instead of implying an
  order that carries no information.

14 new unit tests (269 total, was 252), covering the destination ranking (including that it stays
varied exactly where the weekend ranking collapsed), the AeroDataBox empty-result explanations, the
shipped TripAdvisor defaults matching the published reference, and the single-call lookup alongside
the two-step chain it replaces.

## Known limitations

TripAdvisor's *response envelope* is the one value still inferred rather than read off its
reference, which documents the parameters exactly but shows a sample body only for a `detail` call.
The app assumes the attractions response is the array itself; if it turns out to be wrapped, the
failure names the keys that actually came back and Settings → TripAdvisor is where you correct it.
Google Flights and AeroDataBox field mappings remain user-editable in the same way, and every
failure in them now reports what it actually saw.

## Installing

Download `condorino-alpha-12.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-11` — no uninstall needed, and this release changes no stored data.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
