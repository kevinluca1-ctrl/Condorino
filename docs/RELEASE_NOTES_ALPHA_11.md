# Condorino `alpha-11`

Eleventh alpha: the error messages reported from the device now say something useful, the
destination lists are readable, Settings is navigable, and every flight says which airline it is
on. Everything in `alpha-10` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-10).

## Fixed

* **Google Flights reported a field-mapping problem when the API had actually refused the
  request.** `alpha-10` already relayed an API error envelope in the API's own words — but only
  when the explanation was a plain string. Real gateways also send it as a list (validation errors)
  or nested one level down, and those fell straight through to a mapping diagnosis describing
  entirely the wrong problem. All three shapes are read now, and a genuinely short message is no
  longer discarded either.

* **"TripAdvisor responded with HTTP 404. ()"** — two separate bugs in one line. The empty
  parentheses came from treating a *blank* technical detail as present: some servers send an empty
  HTTP reason phrase, so the check for null was never enough. And a 404 from a RapidAPI host does
  not mean "place not found", it means that host has no such endpoint — so the message now says
  that, and names the path it actually asked for, which is the one thing that makes it fixable
  from Settings.

* **"OpenSky rate limit reached. Try again in 83337 seconds."** Exact, and useless — nobody reads
  that as "tomorrow". Waits are now shown in the largest unit that still means something: `45 s`,
  `12 min`, `2 h 15 min`, `23 h 8 min`, `1 d 4 h`. The same applies to AeroDataBox.

* **AeroDataBox still hitting HTTP 429.** `alpha-10` paced chunked requests 150 ms apart, which is
  still about seven a second — above what a free plan's per-second gate allows. Pacing is now
  400 ms, and rather than failing outright, a 429 is retried once after the wait the gateway asks
  for (bounded, so a long wait never hangs the screen). Most of these now end as a successful
  search instead of an error to act on.

## Added

* **Every flight carries its airline code, and tapping it spells the name out.** Which airline a
  trip is on decides which standby price applies to it, so it belongs on the card rather than
  buried — but a two-letter designator is opaque to everyone except a staff traveller. The tag
  reads the name from the app's own airline list, so it says "Condor" whichever designator the
  source happened to report, and screen readers get the full name outright.

* **Destination names now always carry their airport code.** The comparison list had four entries
  reading simply "London" with nothing to tell them apart, and the reference data names some
  airports after the village they sit in rather than the city they serve. Everywhere a destination
  is offered as a choice it is now "London (LHR)".

* **Compare is grouped by country.** One flat run of chips was a wall to read; the destinations now
  sit under a country heading with its flag, sorted by country and then by name.

## Changed

* **Settings is navigable rather than merely collapsible.** Making the sections collapse in
  `alpha-10` left the screen as twenty near-invisible 10-point all-caps labels, which had quietly
  become the only navigation in it. Each section is now a proper filled row with a readable
  sentence-case title, and it keeps its one-line description visible while closed — so a heading
  answers "what is in here" without being opened. The sections are also gathered under four
  headings — App, Flight data, Your trips, Privacy and about — so the API plumbing sits together
  and out of the way of the handful of settings actually adjusted day to day.

10 new unit tests (252 total, was 242), covering the additional error-envelope shapes, the human
retry wait, the 429 retry actually recovering a search, and destination labels staying unique.

## Known limitations

Unchanged from `alpha-10`. The RapidAPI field mappings remain a best-effort reconstruction rather
than a verified contract — but every failure in them now reports what it actually saw, so
correcting one from your own RapidAPI test panel is guesswork-free.

## Installing

Download `condorino-alpha-11.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-10` — no uninstall needed, and this release changes no stored data.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
