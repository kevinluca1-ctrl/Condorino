# Condorino ✈️

**Wochenendtrip-Planer ab Frankfurt (FRA).**

Condorino beantwortet eine einzige Frage, und die möglichst gut:

> „Wohin kann ich dieses Wochenende fliegen, ohne Urlaubstage zu brauchen — und welcher Trip ist
> insgesamt am attraktivsten?“

Die App zeigt keine Flüge, sie **bewertet Wochenenden**. Aus Abflugzeiten, Rückflugzeiten,
Aufenthaltsdauer, Fahrzeit zum Flughafen, Reisemuster, Standby-Preisen und Zielprofilen berechnet
sie pro Trip einen nachvollziehbaren Score von 0 bis 100. Die wichtigste Kennzahl ist nicht der
Ticketpreis, sondern: *wie gut passt dieser Trip in eine normale Arbeitswoche?*

---

## ⚠️ Zuerst: die Sache mit den Live-Daten

**Die App liefert keine echten Condor-Flugdaten aus, solange du keine Datenquelle einrichtest.**

Condor betreibt ein Entwicklerportal (`developer.condor.com`) mit einer Flight Information API,
deren konkreter Vertrag aber registrierungspflichtig ist und beim Bau dieser App nicht eingesehen
werden konnte. Endpunkte zu raten hätte eine App ergeben, die scheinbar funktioniert und still
nichts findet. Stattdessen:

* die Datenschicht ist vollständig gebaut und austauschbar,
* der Condor-API-Vertrag wird **in den Einstellungen eingetragen**, sobald du ihn hast,
* alternativ lädt die App jeden HTTPS-Feed nach einem dokumentierten JSON-Schema,
* und ohne konfigurierte Quelle zeigt sie **Beispieldaten hinter einem permanenten roten Banner**,
  mit Flugnummern, die mit `DEMO` beginnen.

Die Details — was es gibt, was nicht geprüft werden konnte, und wie du echte Daten einspeist —
stehen in **[docs/CONDOR_DATA_SOURCES.md](docs/CONDOR_DATA_SOURCES.md)**.

---

## Was die App kann

**Wochenend-Scoring.** Vier Reisemuster in der Prioritätsreihenfolge des Briefings:
Fr → So, Do → So, Fr → Mo, Do → Mo. Die Reihenfolge folgt daraus, was ein Muster an Urlaub kostet
(0, 0, 1, 1 Tage) — das steht auf jeder Karte.

**Arbeitszeit als Leitgröße.** Aus Arbeitsende (17:00), Fahrzeit zum FRA (45 min) und
Flughafenpuffer (90 min) berechnet die App den frühesten Abflug, der *keine* Arbeitszeit kostet —
standardmäßig **19:15**. Alles davor ist verlorene Arbeitszeit und wird doppelt bestraft: im
Flugzeit-Komfort und im effektiven Urlaubsbedarf.

**Effektive Zeit vor Ort.** Nicht die reine Zeitdifferenz, sondern Ankunft + Transfer bis
Rückflug − Flughafenpuffer − Transfer. Für das London-Beispiel des Briefings ergibt das exakt die
dort genannten 46 Stunden.

**Weitere Screens.** Kalender mit Sterne-Bewertung pro Wochenende und einer „Beste Wochenenden“-
Rangliste über bis zu sechs Monate · Zielvergleich (bis zu vier nebeneinander) · „Surprise me“ mit
sechs Modi (zufällig, Top 10, unter Budget, Sonnenziel, Citytrip, bester Score) · Favoriten ·
Filter nach Reisetagen, Klasse, Preis, Mindestscore und Zieltyp.

**Standby-Preise.** Pro Ziel eintragbar, Economy/Business, Hin-/Rückflug getrennt, wahlweise pro
Segment oder als Roundtrip, mit optionalen Steuern. Die App fragt MyID Travel **nicht** ab und
speichert **keine** Zugangsdaten.

**Ehrliche Zustände.** Jede Angabe trägt ihre Herkunft: `LIVE`, `KÜRZLICH AKTUALISIERT`,
`FLUGPLAN`, `GECACHT`, `MANUELL` oder `BEISPIELDATEN`, dazu immer „Zuletzt aktualisiert: HH:MM“.
Ohne Netz zeigt die App den Cache statt abzustürzen. Leere Listen gibt es nicht — stattdessen den
Grund, warum nichts passt.

---

## APK bekommen

**Fertig gebaut aus CI:** Der GitHub-Actions-Workflow `Build APK` baut bei jedem Push Debug- und
Release-APK. Unter *Actions → Build APK → letzter Lauf → Artifacts* liegen
`condorino-debug-apk` und `condorino-release-apk` zum Download.

**Selbst bauen:** siehe **[docs/BUILD.md](docs/BUILD.md)**. Kurzfassung:

```bash
git clone https://github.com/kevinluca1-ctrl/Condorino.git
cd Condorino
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen: JDK 17 und das Android SDK (Platform 35). Auf dem Telefon „Installation aus
unbekannten Quellen“ erlauben.

---

## Erste Schritte in der App

1. **Öffnen.** Home zeigt sofort das kommende Wochenende mit den bestbewerteten Trips.
2. **Einstellungen → Arbeitszeiten** an dein Leben anpassen (Arbeitsende, Fahrzeit, Puffer). Der
   berechnete „früheste sinnvolle Abflug“ wird direkt darunter angezeigt.
3. **Einstellungen → Standby-Preise**: für deine Stammziele die MyID-Travel-Preise eintragen.
   Ohne Preis wird die Kostenkomponente neutral bewertet und die App sagt das auch.
4. **Einstellungen → Datenquellen**: echte Datenquelle einrichten und Beispieldaten abschalten.
5. **Score-Gewichtung** verschieben, wenn dir z. B. Kosten wichtiger sind als Aufenthaltsdauer.

---

## Technik

Kotlin · Jetpack Compose · Material 3 · MVVM/Clean · Coroutines + Flow · Room · DataStore ·
OkHttp/Retrofit · kotlinx.serialization · WorkManager · Gradle Kotlin DSL ·
`minSdk 26` / `compileSdk 35`.

`minSdk 26` ist eine bewusste Entscheidung: damit steht `java.time` ohne Desugaring zur Verfügung,
und korrekte Zeitzonenrechnung ist in dieser App keine Nebensache.

Aufbau und Begründungen: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

### Tests

76 Unit-Tests über Pattern-Erkennung, Workday-Penalty, effektive Aufenthaltsdauer,
Nächte-Berechnung über Mitternacht, Zeitzonen (UK, Madeira, Griechenland, Sommer/Winter),
Kosten-Scoring, Zufallsauswahl, Feed-Parsing und die Rangfolge-Fälle aus dem Briefing:

```bash
./gradlew testDebugUnitTest
```

### Datenschutz

Keine Konten, keine Tracking-SDKs, keine Analytics. Lokal gespeichert werden ausschließlich:
Einstellungen, selbst eingegebene Standby-Preise, Favoriten und ein Flugdaten-Cache.
Netzwerkzugriffe gehen ausschließlich an die von dir selbst konfigurierten Datenquellen.

### Externe Datenquellen

| Quelle | Rolle | Status |
| --- | --- | --- |
| Condor Developer API (`developer.condor.com`) | primär vorgesehen | Vertrag vom Nutzer einzutragen |
| Eigener HTTPS-Feed (Condorino-Feed-Schema) | funktioniert sofort | URL vom Nutzer einzutragen |
| `assets/demo_schedule.json` | Beispieldaten | mitgeliefert, rot markiert, abschaltbar |
| `assets/destination_profiles.json` | redaktionelle Zielbewertungen | mitgeliefert, editierbar |
| MyID Travel | **nicht** angebunden | Preise manuell |
