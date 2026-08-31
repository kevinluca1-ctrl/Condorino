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

* **TripAdvisor: the app was shipping endpoint paths that are known not to work.** `alpha-11` made
  the 404 name the path it asked for, which confirmed `locations/v2/search` does not exist on that
  host. Rather than guess a third time, both paths now ship **blank**, and the source reports itself
  as not set up until you enter the real ones from your own RapidAPI test panel — the same way the
  Condor API has always behaved. A default that is known to fail is worse than none: it makes a
  configuration step look like a broken app. (The stored bad paths are migrated away too.)

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
varied exactly where the weekend ranking collapsed), the AeroDataBox empty-result explanations, and
TripAdvisor asking to be configured rather than guessing.

## Known limitations

The TripAdvisor endpoint paths are the one thing this app cannot fill in for you: RapidAPI's own
documentation is not reachable from where this is built, so the app asks rather than guesses. Google
Flights and AeroDataBox field mappings remain user-editable, and every failure in them now reports
what it actually saw.

## Installing

Download `condorino-alpha-12.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-11` — no uninstall needed, and this release changes no stored data.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
