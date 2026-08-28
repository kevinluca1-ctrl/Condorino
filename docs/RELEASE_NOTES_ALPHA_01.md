# Condorino `alpha-01`

Erste installierbare Alpha des Wochenendtrip-Planers ab Frankfurt (FRA).

> ⚠️ **Alpha.** Ohne eingerichtete Datenquelle zeigt die App **Beispieldaten** – erfundene
> Flugzeiten mit Flugnummern `DEMO …`, hinter einem permanenten roten Banner. Das ist Absicht, kein
> Bug: die App gibt niemals erfundene Daten als echte Flüge aus.

## Was drin ist

**Wochenend-Scoring.** Vier Reisemuster (Fr → So, Do → So, Fr → Mo, Do → Mo) mit einem
nachvollziehbaren Score aus sechs gewichteten Komponenten. Leitgrößen sind die *Workday Penalty*
(wie viel Arbeitszeit ein Abflug kostet) und die *effektive Zeit vor Ort* (Ankunft + Transfer bis
Rückflug − Puffer − Transfer). Jede Zahl lässt sich in der Detailansicht aufschlüsseln.

**Screens.** Home mit Wochenendauswahl · Filter (Tage, Klasse, Preis, Mindestscore, Zieltyp) ·
Trip-Detail mit Zeitbilanz und Score-Zerlegung · Kalender mit Sterne-Bewertung und freier
Von/Bis-Auswahl · Zielvergleich · „Surprise me" mit sechs Modi · Favoriten · Standby-Preisverwaltung.

**Datenquellen** – alle einzeln schaltbar, in dieser Reihenfolge:

1. **Condor Developer API** – vollständiger Client, dessen Vertrag du in den Einstellungen einträgst,
   sobald du Zugang zu `developer.condor.com` hast. Es sind **keine Endpunkte geraten**.
2. **Eigener HTTPS-Feed** – dokumentiertes JSON-Schema, funktioniert sofort.
3. **OpenSky Network** – kostenlos und ohne Konto nutzbar: welche `CFG`-Flüge ab FRA in den letzten
   Wochen *tatsächlich* geflogen sind. Daraus leitet die App einen beobachteten Flugplan ab. Immer
   als FLUGPLAN gekennzeichnet, nie als LIVE.
4. **Beispieldaten** – abschaltbar.

**Flughafen-Referenz.** 6.442 Flughäfen aus OurAirports (Public Domain), OpenFlights (ODbL) und
IANA tzdata, jeder mit belegter Zeitzone. Flughäfen ohne belegbare Zone sind bewusst nicht
enthalten. Feeds müssen daher nur IATA-Codes liefern.

**Zweisprachig.** Deutsch und Englisch (US), 297 Strings je Sprache. Datumsformate sind in beiden
Sprachen Tag-vor-Monat – **MM/DD/YYYY wird nirgends verwendet** – und Uhrzeiten durchgängig
24-Stunden. Ab Android 13 pro App umstellbar (Einstellungen → Sprache).

**Hell und dunkel.** Design-Umschaltung System / Hell / Dunkel, ohne Neustart.

**Datenschutz.** Keine Konten, keine Tracking-SDKs. Lokal gespeichert werden nur Einstellungen,
selbst eingegebene Standby-Preise, Favoriten und ein Flugdaten-Cache. MyID Travel wird nicht
abgefragt und es werden keine Zugangsdaten gespeichert.

## Installation

`condorino-alpha-01.apk` herunterladen und auf dem Telefon öffnen. „Installation aus unbekannten
Quellen" muss für die installierende App erlaubt sein. Mindestens Android 8.0 (API 26).

Die `-debug.apk` hat eine eigene Application-ID (`com.condorino.weekend.debug`) und lässt sich
parallel installieren.

> Beide APKs sind mit dem **Debug-Keystore** signiert, damit CI ohne Secrets etwas Installierbares
> liefern kann. Für eine echte Veröffentlichung braucht es einen eigenen Keystore – siehe
> `docs/BUILD.md`.

## Erste Schritte

1. **Einstellungen → Arbeitszeiten** an dein Leben anpassen. Der berechnete „früheste sinnvolle
   Abflug" steht direkt darunter (Standard 19:15 = 17:00 + 45 min + 90 min).
2. **Einstellungen → OpenSky-Abgleich** aktivieren, um echte beobachtete Flüge zu bekommen.
3. **Einstellungen → Standby-Preise** für deine Stammziele füllen.
4. **Beispieldaten abschalten**, sobald eine echte Quelle liefert.

## Bekannte Einschränkungen

* Keine Live-Verfügbarkeit ohne Condor-API-Zugang – OpenSky sagt, was geflogen *wurde*, nicht was
  buchbar *ist*.
* Der beobachtete Flugplan basiert auf Transponder-Empfängen; `firstSeen` liegt nahe an, aber nicht
  exakt auf der planmäßigen Abflugzeit.
* Die Zielprofile (Nightlife-, Strand-, Kultur-Faktor) sind redaktionelle Einschätzungen in
  `assets/destination_profiles.json`, keine Messwerte.
* Nur Unit-Tests, keine Instrumentation-Tests.
