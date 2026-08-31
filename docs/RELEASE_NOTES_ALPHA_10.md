# Condorino `alpha-10`

Tenth alpha: two more bugs fixed — the update checker offering every release to the users already
running it, and AeroDataBox reporting a RapidAPI throttle as if the account had run out of quota —
plus a named changelog button, collapsible Settings sections, and the results of a full pass over
the app for data safety and Android correctness. Everything in `alpha-09` still applies — see its
[release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-09).

## Fixed

* **"Check for updates" offered the version you were already running as if it were new.** The
  release workflow bakes a build's own release timestamp in at *build start*, but GitHub stamps a
  release's `published_at` only once that build finishes and the release is created — a few
  minutes later. Comparing those two timestamps meant a release always looked slightly newer than
  the build packaged inside it, so the app offered every release to the exact users already
  running it. Release identity is now checked by tag first — a release whose tag matches the one
  baked into this build **is** this build, whatever either timestamp says — and only falls back to
  the timestamp comparison for a build with no tag of its own (a CI or local build). "You have the
  latest version" now names the version: *"alpha-10 is the newest version, and it is already
  installed."*

* **AeroDataBox: "RapidAPI limit reached" fired even nowhere near the monthly quota.** Confirmed
  against a real account showing 5% usage. The real cause: a search can send up to 16 chunked
  requests back to back with no pacing between them, which reliably trips a RapidAPI Basic plan's
  own **per-second** gateway throttle — a short-lived limit completely separate from, and far
  stricter than, the monthly quota shown in the RapidAPI dashboard. Two changes: chunk requests
  within one search are now paced (150 ms apart) so this is tripped far less often in the first
  place, and the message itself no longer implies the account is out of quota — it says a
  short-term rate limit was hit, points at the dashboard rather than assuming it, and includes the
  server's own `Retry-After` wait when it sends one.

## Added

* **Your standby prices can no longer be lost.** They are the one thing in this app that cannot be
  fetched again — every other table is a cache, but these are typed in by hand from MyID Travel and
  exist nowhere else. Until now the database was their only home, and that database is opened with
  `fallbackToDestructiveMigration`: any future schema change drops every row, as would clearing the
  app's storage or a rare corruption. Each price is now also written to a plain JSON copy beside
  the database, in exactly the same format the export button produces, and the app restores from it
  automatically on launch **if and only if** the database has no prices at all. It can therefore
  only ever put back prices that were lost — never overwrite, duplicate or resurrect one you still
  have, including one you deliberately deleted. Nothing to switch on, and nothing changes if you
  never lose anything.

* **Settings and prices now actually survive moving to a new phone.** The backup rules named the
  `sharedpref` domain, which quietly backed up nothing at all: this app keeps its settings in
  DataStore, and DataStore does not write there. The rules now name the file domain that DataStore
  and the new price copy really use, so a restored device brings your configuration and prices with
  it. (This does mean the stored RapidAPI key travels with the backup, which is what a restored
  phone is expected to do; Android encrypts that backup against the device credential, and the app
  holds no MyID Travel credentials at all.)

* **Changelog button** in Settings → Updates, next to "Check now" — opens this build's own release
  notes on GitHub directly, rather than the generic releases list.
* **Collapsible Settings sections.** With close to twenty sections — most holding an API field
  mapping set once and never touched again — every section now starts collapsed to a single
  heading and expands on tap, so the screen opens as a scannable list rather than a very long
  scroll. Updates stays open by default, since it is the one section actually used regularly. Each
  section remembers its own open/closed state across navigating away and rotation.

## Also hardened

Findings from a full pass over the app, none of them user-visible on their own:

* Every broad `catch` around suspending work now rethrows `CancellationException` first. Nothing
  reaches those clauses today, but `CancellationException` *is* an `Exception`: the day a suspending
  call moves inside one of those blocks, a cancelled search would silently be reported as a failed
  one instead. The planner also states the same requirement where it writes results, so a search
  the user has already moved on from can never paint over the weekend they moved to.
* An overall 60-second ceiling per HTTP call. Connect and read timeouts only bound individual
  socket operations, so a server trickling one byte at a time could previously hold a request — and
  the screen waiting on it — open indefinitely without tripping either.
* Filter, date-range and price-card selections now survive rotation and being backgrounded, rather
  than resetting.
* The new collapsible section headers meet the 48dp minimum touch target.

17 new unit tests (242 total, was 224), covering the update-tag identity check, the AeroDataBox
rate-limit rewording and request pacing, and the price safety net — including that it restores after
total loss, stays out of the way whenever any price survives, and never resurrects a price deleted
on purpose.

## Known limitations

Unchanged from `alpha-09`.

## Installing

Download `condorino-alpha-10.apk` and open it on the phone (installation from unknown sources has
to be allowed for the installing app; Android 8.0/API 26 or newer). Installs cleanly as an update
over `alpha-09` — no uninstall needed, and this release changes no stored data.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed
alongside. Both APKs are signed with the debug keystore — see `docs/BUILD.md` for a real signing
setup.
