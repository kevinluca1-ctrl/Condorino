# Condorino `alpha-02`

Second alpha. Everything in `alpha-01` still applies — see its [release notes](https://github.com/kevinluca1-ctrl/Condorino/releases/tag/alpha-01) for the full feature list. This release is bug fixes, honesty improvements, and two new capabilities, driven by feedback from actually running `alpha-01` on a device.

## Fixed

* **Bottom navigation could dead-end on Home.** Two screens navigated with a plain `navigate()` instead of the tab bar's own back-stack pattern, which could leave a tab unresponsive after visiting Settings or jumping to a weekend from the calendar. All navigation to a bottom tab now goes through one shared helper.
* **The favourite heart didn't always update.** Toggling a favourite updated the stored set but not the trip list already on screen; the heart only changed after the next full reload. It now updates immediately.
* **OpenSky could report "no flights" while hiding a real error.** Any unexpected HTTP response (not just the documented empty-result case) was silently treated as "nothing to show," which looked identical to genuinely no data — including with valid credentials. Failures are now reported as what they are, and a rejected token is no longer mislabelled as "are you offline?".
* **A settings toggle could silently revert.** Several settings fields watch the same underlying storage object, and a race between two of them updating at once could let one change get lost. Every settings write now goes through an update path that can't lose a concurrent one.
* **Tied "best weekend" scores sorted arbitrarily.** Ties (common with sample data, which repeats one weekly pattern) now break soonest-first instead of falling out of floating-point rounding.

## Added

* **In-app updates.** The app checks this repository's releases once a day, downloads a newer APK in the background, and notifies you when it's ready to install — tapping the notification opens Settings, where the install is one tap away. Entirely unauthenticated (GitHub's public releases API) and never bypasses Android's own "allow installs from this app" gate or its install-confirmation dialog. Configurable: manual check, auto-check on/off, Wi-Fi-only.
* **Standby-price export/import.** Settings → Standby prices now has Export/Import, using Android's own document picker — save the prices you've entered to your own Google Drive, Dropbox, or local storage, in a small documented JSON format, with no new account or API key required from the app itself.
* **Settings: General and About sections.** General adds "reset preferences to defaults" and "clear cached flight data" (your saved prices and favourites are untouched by either). About shows the installed version and release tag, links to the source and releases on GitHub, and credits for the data sources in use.
* **Masked secrets.** The OpenSky client secret, Condor API key, and feed auth value are now masked password fields with a show/hide toggle, instead of plain text on screen.
* **A clearer path to working OpenSky credentials.** OpenSky retired password-based login in favour of a separately generated OAuth2 API client; the Client ID field now points at where to get one, and a rejected credential now shows the actual reason instead of a generic failure.

## Fixed (found from OpenSky's own current docs)

Re-checked the OpenSky integration against OpenSky's live documentation and found the most likely actual cause of "OpenSky reports nothing even with correct credentials":

* **Credits, not credentials.** OpenSky now bills `/flights/*` requests from a daily/hourly credit quota, and the cost is steep once a single request's time window crosses into a second calendar day — this app was requesting multi-day windows, which could exhaust an entire day's quota in one search. Requests now stay under 24 hours each, which is both far cheaper and, per OpenSky's own table, in the lowest cost bracket.
* **A 401 no longer means "your credentials are wrong."** OpenSky's tokens last 30 minutes, and OpenSky's own guidance is that a 401 on a data request means the token just expired — refresh and retry, don't treat it as rejected. The app now does exactly that once before reporting an actual denial.
* **A rate limit now says when to try again**, using OpenSky's own `Retry-After` value, instead of just "HTTP 429."

125 unit tests (was 111), 8 of them new coverage for the retry and chunking behaviour above.

## Known limitations

Unchanged from `alpha-01`: no live availability without Condor API access, the observed OpenSky timetable approximates rather than exactly matches scheduled departure times, destination profile factors are editorial judgement rather than measurement, and there are unit tests only, no instrumentation tests.

## Installing

Download `condorino-alpha-02.apk` and open it on the phone (installation from unknown sources has to be allowed for the installing app; Android 8.0/API 26 or newer). If you already have `alpha-01` installed, this installs as an update over it — or use the new in-app updater once you're on `alpha-01` or later.

The `-debug.apk` has its own application ID (`com.condorino.weekend.debug`) and can be installed alongside. Both APKs are signed with the debug keystore, as in `alpha-01` — see `docs/BUILD.md` for a real signing setup.
