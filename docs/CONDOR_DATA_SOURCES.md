# Phase 1 — Technische Analyse der Condor-Datenquellen

Dieses Dokument ist das Ergebnis der in §27 („Phase 1“) geforderten Machbarkeitsanalyse und die
Begründung dafür, warum die App so gebaut ist, wie sie gebaut ist.

## Kurzfassung

**Die App enthält keine erfundenen Condor-Endpunkte und keine erfundenen Flugzeiten, die als echt
ausgegeben werden.**

Es gibt ein offizielles Condor-Entwicklerportal, dessen konkreter API-Vertrag jedoch
registrierungspflichtig ist und beim Bau dieser App nicht eingesehen werden konnte. Statt
Endpunkte, Parameternamen und Response-Felder zu raten — was zu einer App führen würde, die
scheinbar funktioniert und still nichts liefert — ist die Datenschicht so gebaut, dass der Vertrag
**zur Laufzeit vom Nutzer eingetragen** wird, sobald er vorliegt.

## Was es gibt

Recherchiert wurde am 28.08.2026. Folgende öffentlich auffindbaren Condor-Angebote existieren:

| Ressource | URL | Bewertung |
| --- | --- | --- |
| Condor Developer Portal | `https://developer.condor.com/` | Existiert. Einstiegspunkt für die API-Produkte. |
| Flight Information API | `https://developer.condor.com/api/flight` | Produktseite existiert. Vertrag nicht öffentlich einsehbar. |
| Flight Offer API | `https://developer.condor.com/api/flightoffer` | Produktseite existiert („Best Flight Deals“). |
| Travel Shopping Carts API | `https://developer.condor.com/api/cart` | Buchungsseitig, für diese App nicht relevant. |
| API-Gateway | `https://api.condor.com/` | Existiert als Host. |

Ergänzend gibt es kommerzielle Dritt-Aggregatoren, die Condor-Daten mitliefern — u. a.
[Duffel](https://duffel.com/flights/airlines/condor) (Suche/Buchung, Vertrag nötig) und
[AirLabs](https://airlabs.co/condor-developer-api) (Flugstatus/Routen/Schedules, kostenpflichtig).
Für Flugpläne kommen außerdem OAG und Cirium in Frage. Alle sind vertrags- und kostenpflichtig.

## Was nicht geprüft werden konnte — und warum

Die Build-Umgebung, in der diese App entstanden ist, hat einen Egress-Filter, der `condor.com`,
`developer.condor.com` und `api.condor.com` blockiert. Es war deshalb **nicht** möglich:

* die Dokumentation der Flight Information API zu lesen,
* Endpunkt-Pfade, Query-Parameter oder Header-Namen zu verifizieren,
* das Response-Format zu bestimmen,
* Rate-Limits oder Auth-Verfahren (API-Key vs. OAuth) festzustellen,
* die Netzwerkaufrufe der öffentlichen Condor-Flugsuche zu beobachten.

Damit gilt Anforderung §3 wörtlich: *„Keine erfundenen API-Endpunkte. Keine hartcodierten
Flugzeiten. Keine Annahme, dass eine bestimmte API existiert.“*

### Zur öffentlichen Flugsuche

Auch wenn die XHR-Endpunkte der Condor-Website beobachtbar gewesen wären, wären sie keine gute
Grundlage: es sind interne Endpunkte ohne Stabilitätszusage, sie sind typischerweise durch
Bot-Schutz abgesichert, und ihre automatisierte Nutzung fällt in der Regel unter die
Nutzungsbedingungen der Website. Ein offizieller API-Vertrag oder ein Schedule-Anbieter ist der
tragfähige Weg. Die Architektur hält beide Türen offen.

## Wie die App das löst

Drei austauschbare Implementierungen des Interfaces `FlightDataSource`, in dieser Reihenfolge:

### 1. `CondorDeveloperApiDataSource` — offizieller Weg, vom Nutzer konfiguriert

Ein vollständiger HTTP-Client, dessen **Vertrag aus den Einstellungen kommt**: Basis-URL, Pfad,
Query-Parameternamen, Auth-Header, sowie die Feldnamen der Antwort und der Pfad zur Flugliste im
Response-Envelope. Nichts davon ist geraten oder vorbelegt mit einem erfundenen Endpunkt.

Solange nichts eingetragen ist, meldet die Quelle `SourceStatus.NotConfigured` mit einer
Erklärung — sie liefert nie stillschweigend nichts.

Sobald du Portalzugang hast, brauchst du nur *Einstellungen → Condor Developer API* auszufüllen.
Weicht das Response-Format stärker ab, ist `CondorDeveloperApiDataSource.mapFlights()` die einzige
Stelle, die angepasst werden muss.

### 2. `HttpFeedFlightDataSource` — der Weg, der heute funktioniert

Lädt ein JSON-Dokument nach dem unten dokumentierten **Condorino-Feed-Schema** von einer beliebigen
HTTPS-URL. Damit kannst du sofort echte Daten in die App bringen, egal woher sie stammen: aus einem
GDS-/OAG-/Cirium-Abzug, aus einem Condor-Partnervertrag, oder aus einer kleinen selbst gehosteten
Brücke, die deine Quelle in dieses Format übersetzt.

Der Feed sagt selbst, ob er live ist (`"is_live": true`) oder ein veröffentlichter Flugplan. Die App
stuft eine Provenance **nie** von sich aus hoch.

### 3. `AssetDemoFlightDataSource` — Beispieldaten, unübersehbar markiert

Damit die App auf einem frischen Gerät ohne Konfiguration bedienbar ist, liegt in
`app/src/main/assets/demo_schedule.json` ein Musterflugplan.

> ⚠️ Die Flugnummern dort beginnen mit `DEMO`, die Quelle heißt „BEISPIELDATEN – frei erfundener
> Musterflugplan. Dies sind KEINE Condor-Flugzeiten.“, jeder erzeugte Flug trägt
> `DataProvenance.DEMO`, und die App zeigt darüber einen permanenten roten Banner. Die Quelle lässt
> sich in den Einstellungen komplett abschalten.

## Das Condorino-Feed-Schema

```jsonc
{
  "schema_version": 1,
  "source": "Woher die Daten stammen – wird dem Nutzer angezeigt",
  "is_live": false,          // true nur für echte, buchbare Verfügbarkeit
  "generated_at": "2026-09-01T08:00:00Z",

  "airports": [
    {
      "iata": "LGW",
      "name": "London Gatwick",
      "city": "London",
      "country": "Vereinigtes Königreich",
      "country_code": "GB",
      "time_zone": "Europe/London"   // IANA-Zone, Pflicht – die App rät keine Zeitzone
    }
  ],

  "flights": [
    {
      "flight_number": "DE 1234",
      "airline": "Condor",
      "airline_code": "DE",
      "origin": "FRA",
      "destination": "LGW",
      "departure": "2026-09-04T18:15:00+02:00",  // ISO-8601 mit Offset oder ...Z
      "arrival":   "2026-09-04T18:35:00+01:00",
      "is_direct": true,
      "fare_cents": 12900,            // optional; weglassen wenn unbekannt, nie 0 senden
      "availability_note": "3 Plätze"  // optional
    }
  ]
}
```

Regeln, die der Parser durchsetzt:

* Ein Flughafen ohne gültige IANA-Zeitzone wird **verworfen**, nicht geraten.
* Ein Flug, dessen Flughäfen nicht im Feed deklariert sind, wird verworfen.
* Ein Flug mit unlesbarem Zeitstempel oder mit Ankunft ≤ Abflug wird verworfen.
* Verworfene Zeilen werden gezählt und dem Nutzer gemeldet — nie stillschweigend geschluckt.
* `fare_cents` bleibt `null`, wenn unbekannt; „unbekannt“ wird nie zu „0 €“.

Die App braucht pro Wochenende die Tage **Donnerstag bis Montag**, in beide Richtungen ab/nach FRA.

## Flugplan vs. Verfügbarkeit

Die App unterscheidet die beiden Begriffe konsequent über `DataProvenance`:

| Wert | Bedeutung | Anzeige |
| --- | --- | --- |
| `LIVE` | Von einer Live-Quelle geholt, jünger als 30 Minuten | grünes `LIVE` |
| `RECENTLY_UPDATED` | Live geholt, aber älter als 30 Minuten | blaues `KÜRZLICH AKTUALISIERT` |
| `SCHEDULE` | Veröffentlichter Flugplan, keine Buchbarkeitsaussage | gelbes `FLUGPLAN` |
| `CACHED` | Aus der lokalen Room-Datenbank (offline) | graues `GECACHT` |
| `MANUAL` | Vom Nutzer eingegeben (Standby-Preise) | `MANUELL` |
| `DEMO` | Beispieldaten | rotes `BEISPIELDATEN` + Warnbanner |

Ein Trip erbt immer die **schwächste** Provenance seiner beiden Legs. Beim Zurücklesen aus dem
Cache wird `LIVE` zu `CACHED` herabgestuft; `DEMO` bleibt für immer `DEMO`.

## MyID Travel / Staff Travel

Bewusst **nicht** angebunden. Die App speichert keine Zugangsdaten und ruft nichts ab (§26).
Standby-Preise trägt der Nutzer selbst ein (*Einstellungen → Standby-Preise*), pro Ziel, wahlweise
pro Segment oder als Roundtrip, optional mit separaten Steuern.

## Wenn du echte Daten willst — der kürzeste Weg

1. Auf `developer.condor.com` Zugang beantragen.
2. Den Vertrag in *Einstellungen → Condor Developer API* eintragen und die Quelle aktivieren.

oder

1. Eine Datei nach obigem Schema erzeugen (aus dem Abzug deiner Wahl) und per HTTPS bereitstellen.
2. Die URL in *Einstellungen → Eigener Flight-Feed* eintragen und die Quelle aktivieren.
3. Beispieldaten in den Einstellungen abschalten.

---

# Frei zugängliche Datenquellen anderer Anbieter

Recherche vom 28.08.2026. Die Frage war: *gibt es freie Quellen zum Abgleich, oder wenigstens eine
öffentlich einsehbare, weitreichende Liste als Datenbasis?* Beides — und beides ist inzwischen
eingebaut.

## Eingebaut

### 1. Flughafen-Referenzdatensatz (`assets/airports_reference.json`)

**6.442 Flughäfen**, gebündelt aus drei öffentlichen Quellen:

| Quelle | Lizenz | Was daraus kommt |
| --- | --- | --- |
| [OurAirports](https://github.com/davidmegginson/ourairports-data) | Public Domain | IATA- und ICAO-Code, Name, Stadt, ISO-Ländercode |
| [OpenFlights](https://github.com/jpatokal/openflights) | ODbL | IANA-Zeitzone je Flughafen |
| [IANA tzdata `zone1970.tab`](https://github.com/eggert/tz) | Public Domain | Zeitzone dort, wo OpenFlights keine hat |

Die Zeitzone ist der kritische Wert — sie entscheidet über jede angezeigte Uhrzeit. Deshalb gilt
eine strenge Reihenfolge:

1. **Kuratierte Korrektur** für Inselgruppen, deren Land mehrere Zonen hat (Madeira, Azoren,
   Kanaren) — 18 Einträge, jeder gegen die tzdata-Zonenliste des Landes geprüft.
2. **OpenFlights**, wenn es den Flughafen kennt (5.373 Einträge).
3. **tzdata-Länderregel**: hat ein Land laut `zone1970.tab` *genau eine* Zone, gilt sie für jeden
   Flughafen des Landes (1.051 Einträge). So löst sich z. B. Istanbul (LTFM) korrekt auf, das
   OpenFlights noch ohne Zone führt — die Türkei hat nur `Europe/Istanbul`.
4. **Sonst: nicht aufnehmen.** 2.359 Flughäfen sind bewusst *nicht* enthalten, weil sich ihre Zone
   nicht belegen ließ. Die App rät keine Zeitzone.

Praktischer Nutzen: ein Feed muss nur noch IATA-Codes und Zeiten liefern; Name, Land und Zeitzone
kommen aus der Referenz. Der Datensatz lässt sich nachbauen — die drei Quell-URLs stehen in der
Datei.

### 2. OpenSky Network — Abgleich mit tatsächlich geflogenen Flügen

[OpenSky](https://opensky-network.org/) betreibt eine **kostenlose, öffentliche REST-API** über
crowdgesammelte ADS-B-Empfänge. Sie beantwortet eine andere Frage als ein Flugplan, und genau darin
liegt der Wert:

* Ein Flugplan sagt: *„diese Route ist geplant."*
* OpenSky sagt: **„dieses Flugzeug ist tatsächlich geflogen, an diesem Tag, zu dieser Zeit."**

Geprüfter Vertrag:

```
GET https://opensky-network.org/api/flights/departure?airport=EDDF&begin=<unix>&end=<unix>
GET https://opensky-network.org/api/flights/arrival  ?airport=EDDF&begin=<unix>&end=<unix>
```

Antwort: JSON-Array mit `icao24`, `callsign`, `estDepartureAirport`, `estArrivalAirport`,
`firstSeen`, `lastSeen`. Flughäfen als **ICAO** (Frankfurt = `EDDF`), Zeiten als Unix-Sekunden.
HTTP 404 heißt „nichts in diesem Fenster", nicht Fehler. Anonymer Zugriff funktioniert mit engeren
Limits; ein kostenloses Konto liefert per OAuth2-Client-Credentials höhere Limits.

`OpenSkyFlightDataSource` filtert auf Condors Rufzeichen-Präfix **`CFG`** (IATA `DE`, ICAO `CFG`),
gruppiert die Beobachtungen nach Wochentag und Route und nimmt **Median**-Abflugzeit und
-Blockzeit — der Median, weil ein einzelner stark verspäteter Flug den Eintrag sonst aus seinem
echten Slot zöge. Daraus entsteht ein *beobachteter Flugplan*.

**Wichtige Einschränkung:** `firstSeen` ist der erste Transponder-Empfang, nicht die planmäßige
Abflugzeit, und nichts davon sagt etwas über Buchbarkeit. Deshalb trägt alles aus dieser Quelle
`DataProvenance.SCHEDULE` und wird in der UI als **FLUGPLAN** markiert, nie als LIVE.

Einrichten: *Einstellungen → OpenSky-Abgleich* aktivieren. Ohne Konto sofort nutzbar.

## Geprüft, aber nicht eingebaut

| Quelle | Warum nicht |
| --- | --- |
| [Duffel](https://duffel.com/flights/airlines/condor) | Echte Such- und Buchungsdaten inkl. Condor, aber vertrags- und kostenpflichtig; kein freier Zugang. |
| [AirLabs](https://airlabs.co/condor-developer-api) | Flugpläne und Status inkl. Condor, kostenpflichtig. |
| OAG, Cirium | Die Referenz für Flugpläne, rein kommerziell. |
| AviationStack | Free-Tier mit 100 Anfragen/Monat — zu wenig für eine Mehr-Wochenend-Suche, und der Free-Tier ist HTTP-only. |
| ADS-B-Communities (adsb.lol, airplanes.live) | Frei und offen, liefern aber Live-Positionen statt Flug-Aggregaten; für „welche Route wurde wann geflogen" ist OpenSky die passendere Abstraktion. |

Alle drei eingebauten Quellen und die Condor-API laufen über dasselbe `FlightDataSource`-Interface
und sind in *Einstellungen → Datenquellen* einzeln an- und abschaltbar. Die Reihenfolge ist:
Condor Developer API → eigener Feed → OpenSky → (falls erlaubt) Beispieldaten.
