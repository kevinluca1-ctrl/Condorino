# Architektur

## Überblick

Eine Single-Module-App mit sauber getrennten Schichten. Die Abhängigkeiten zeigen ausschließlich
nach innen: `ui → domain ← data`. `domain` und `scoring` enthalten **keine** Android-Imports und
sind vollständig als reine JVM-Unit-Tests prüfbar — was auch der Grund ist, warum die gesamte
Bewertungslogik dort liegt und nicht in ViewModels.

```
com.condorino.weekend
│
├── domain                     ← reine Kotlin-Modelle, kein Android
│   ├── model                  Airport, Flight, WeekendPattern, WeekendTrip,
│   │                          Destination, StandbyPrice, Money, TripScore,
│   │                          UserPreferences, ScoreWeights, DataProvenance
│   └── repository             TripRepository, StandbyPriceRepository,
│                              FavoriteRepository, DataStatus
│
├── scoring                    ← das Herz der App, kein Android
│   ├── TimeCompatibilityCalculator   Workday-Penalty, effektive Zeit, Nächte
│   ├── TripScoringEngine             sechs gewichtete Komponenten → 0..100
│   ├── TripBuilder                   Legs → bewertete Trips + Ablehnungsgründe
│   ├── WeekendCalendar               Ankertag-Logik (welcher Freitag?)
│   ├── RandomDestinationSelector     „Surprise me“
│   └── ScoringMath                   clamp / stückweise Interpolation
│
├── data
│   ├── source                 FlightDataSource + drei Implementierungen
│   ├── local                  Room: Entities, DAOs, CondorinoDatabase
│   ├── prefs                  PreferencesStore (DataStore)
│   ├── mapper                 Entity ↔ Domain
│   ├── DestinationCatalog     redaktionelle Metadaten aus assets/
│   ├── DefaultTripRepository  Cache → Quellen → Scoring
│   └── DefaultStandbyPriceRepository / DefaultFavoriteRepository
│
├── ui
│   ├── theme / components     Dark-Theme, TripCard, ScoreBadge,
│   │                          ProvenancePill, DataStatusBar, EmptyState
│   ├── planner                PlannerViewModel (Home/Detail/Compare/Random/Favoriten)
│   ├── calendar               CalendarViewModel + Screen (Kalender + Multi-Weekend)
│   ├── settings               SettingsViewModel + Settings- und Preis-Screen
│   └── home / search / tripdetail / compare / random / favorites
│
├── navigation                 Routen + Bottom-Navigation
├── di                         AppContainer (manuelle DI)
└── core                       Formatting
```

## Warum manuelle DI statt Hilt

Bei einem Single-Module-Projekt ohne Runtime-Austausch von Implementierungen kauft ein
DI-Framework wenig und kostet einen zusätzlichen Annotation-Processor. `di/AppContainer.kt` macht
stattdessen die eine Entscheidung, auf die es hier wirklich ankommt — **die Reihenfolge der
Datenquellen** — an einer einzigen, lesbaren Stelle explizit.

Room braucht ohnehin KSP; das ist der einzige Annotation-Processor im Build.

## Datenfluss

```
   PlannerViewModel
        │  load(friday, refresh = true)
        ▼
   DefaultTripRepository
        │
        ├─1─► Room-Cache lesen  ──────────────► sofort anzeigen (Provenance CACHED)
        │
        ├─2─► FlightDataSource-Kette:
        │       CondorDeveloperApiDataSource   (nur wenn konfiguriert)
        │       HttpFeedFlightDataSource       (nur wenn konfiguriert)
        │       └ sonst: AssetDemoFlightDataSource, falls erlaubt → DEMO
        │
        ├─3─► erfolgreiche Antwort in Room persistieren + RefreshState schreiben
        │
        └─4─► TripBuilder(prefs).build(flights, friday, destinations, prices)
                  └─► TripScoringEngine pro Kandidat
                          └─► WeekendTrip mit TripScore + Begründungen
```

Der erste Schritt macht die App offline-tauglich: der Cache wird immer zuerst gerendert, der
Netzabruf läuft danach und aktualisiert nur den Zustand. Ein Fehlschlag löscht nie den Cache.

## Zeitzonen

Der Kern der Korrektheit dieser App.

* `Flight.departure` / `Flight.arrival` sind `java.time.Instant` — absolut, UTC, zonenlos.
* Jeder `Airport` trägt seine IANA-Zone (`Europe/London`, `Atlantic/Madeira`, …).
* Wanduhrzeiten entstehen ausschließlich über `departureLocal` / `arrivalLocal`, die die Zone des
  jeweiligen Flughafens anwenden — Abflug in der Zone des Origin, Ankunft in der des Ziels.
* In Room werden Epoch-Millis gespeichert, nie lokale Strings.
* Ein Flughafen ohne auflösbare Zone wird verworfen statt geraten (`FeedParser`), und beim
  Zurücklesen aus dem Cache wird ein Flug übersprungen, dessen Flughafen nicht mehr bekannt ist —
  eine geratene Zone würde jede angezeigte Uhrzeit verfälschen.
* `minSdk 26` wurde genau deshalb gewählt: `java.time` ohne Desugaring.

Getestet u. a. für Großbritannien (−1 h), Madeira (−1 h), Griechenland (+1 h) und für die
Sommer-/Winterzeit-Verschiebung derselben Wanduhrzeit.

## Das Scoring

`TripScoringEngine.score()` erzeugt sechs `ComponentScore`s. Jeder hat einen 0..100-Wert, das
angewandte Gewicht und eine Erklärung; der Gesamtwert ist die gewichtsnormalisierte Summe. Weil
normalisiert wird, kann der Nutzer einen einzelnen Regler verschieben, ohne dass sich die Skala
der übrigen still verändert.

| Komponente | Standard | Was hinein zählt |
| --- | --- | --- |
| Flugzeit-Komfort | 25 % | Workday-Penalty des Hinflugs, Puffer nach Feierabend, Nachtankunft, Spätheit des Rückflugs |
| Aufenthaltsqualität | 20 % | effektive Stunden vor Ort, Nächte gegen Min/Max |
| Wochenend-Kompatibilität | 20 % | Muster-Priorität **und** tatsächlich verlorene Arbeitszeit |
| Logistik | 10 % | Flugdauer gegen Maximum, Transferzeit, Nonstop, späte Heimkehr |
| Kosten | 15 % | Standby-Roundtrip der bevorzugten Klasse gegen Budget |
| Destination Quality | 10 % | redaktionelle Faktoren, gefiltert auf die gewählten Zieltypen |

### Die zwei zentralen Größen

**Workday-Penalty.** Der früheste Abflug, der keine Arbeitszeit kostet, ist
`Arbeitsende + Fahrzeit zum FRA + Flughafenpuffer` (Standard 17:00 + 45 + 90 = **19:15**). Jede
Minute davor ist verlorene Arbeitszeit; ein voller Arbeitstag (8 h) entspricht der Penalty 1,0.
Sie wirkt zweifach: sie senkt den Flugzeit-Komfort *und* sie erhöht den effektiven Urlaubsbedarf
in der Wochenend-Kompatibilität. Das ist der Grund, warum ein Freitag-13:00-Flug deutlich
abstürzt und nicht nur „etwas unbequemer“ ist.

**Effektive Zeit vor Ort.**

```
nutzbarer Beginn = Ankunft            + Transfer Flughafen → Stadt
nutzbares Ende   = Rückflug-Abflug    − Flughafenpuffer − Transfer Stadt → Flughafen
```

Für das Beispiel aus dem Briefing (FRA 18:15 → LGW 18:35, zurück So 19:35, Transfer 45 min,
Puffer 90 min) ergibt das exakt die dort genannten **46 h**.

### Urlaubstage als Ordnungsprinzip

Die vom Briefing vorgegebene Prioritätsreihenfolge Fr→So, Do→So, Fr→Mo, Do→Mo ist im Modell nicht
willkürlich hinterlegt, sondern folgt daraus, was ein Muster an Urlaub kostet: die beiden
Sonntags-Muster kosten null Tage, die beiden Montags-Muster je einen. Deshalb trägt
`WeekendPattern` ein Feld `vacationDaysRequired`, das in der UI direkt angezeigt wird.

## Fehlerbehandlung

Eine leere Liste ist nie eine Antwort. `TripBuilder` zählt Ablehnungsgründe mit
(`RejectionReason`) und wählt den **aussagekräftigsten** für die Anzeige — nicht den häufigsten.
Sonst würde „kein Hinflug am Donnerstag“ jedes Mal die viel nützlichere Meldung „Standby-Preis
über deinem Budget“ übertönen.

Netz- und Konfigurationsfehler sind im Typsystem getrennt: `FlightSearchResult.NotConfigured`
(kein Fehler, sondern eine Einrichtungsaufgabe) gegen `FlightSearchResult.Failure`. Die
Repository-Kette probiert die Quellen der Reihe nach und sammelt beide Arten ein, damit der Banner
sagen kann, was zu tun ist.

## Erweiterbarkeit

`FlightDataSource` kennt das Wort „Condor“ nicht. Eine weitere Airline anzubinden heißt: eine
Implementierung schreiben und sie in `AppContainer.liveSources` einhängen. `Flight` trägt
`airline` und `airlineCode` bereits mit, `Money` trägt die Währung explizit.
