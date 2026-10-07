# Pendel – Technische Analyse & Architektur

> Persönliche Route-Analytics-App: zeichnet eigene Fahrten auf, erkennt wiederkehrende Routen und
> sagt dir auf Basis deiner eigenen Daten, **welche Route wann am besten ist** – mit ehrlicher
> Angabe, wie belastbar die Aussage ist.

---

## 1. Anforderungsanalyse (verdichtet)

| Bereich | Kernanforderung | Konsequenz für die Technik |
|---|---|---|
| Aufzeichnung | GPS, Zeit, Speed, Stopps, Höhe, Modus; auto + manuell | Foreground-Service nur während einer Fahrt, Auto-Start über Activity-Transitions |
| Akku | App darf kein relevanter Verbraucher sein | GNSS nur während Fahrten, Batching, adaptive Intervalle, keine Netzwerk-Requests während der Fahrt |
| Map Matching | GPS-Ungenauigkeit, Provider austauschbar | Interface `MapMatcher`; Offline-Fallback; optional Valhalla (self-hosted bevorzugt) |
| Kreuzungen | Wartezeit probabilistisch, mit Confidence | Stopp-Detektor + Kandidaten (OSM-Ampeln **und** selbst gelernte Hotspots) + Confidence-Modell |
| Routen | Ähnliche Tracks gruppieren | Resampling + symmetrische Überdeckungs-Ähnlichkeit, pro Start-/Ziel-Ort |
| Zeitmuster | Keine starren Buckets | Kernel-gewichtete (gleitende) Statistik über die Tageszeit + Shrinkage Wochentag → Werktag |
| Statistik | Keine Übertreibung bei wenig Daten | Median, MAD, Bootstrap-Intervalle, effektive Stichprobengröße, Datenbasis-Label |
| Optimierung | 7 Zielfunktionen inkl. eigener Gewichte | Normierte Metriken × Gewichtsvektor |
| Datenschutz | Lokal, Export, Löschen, Transparenz | Room lokal, Netzwerkfunktionen standardmäßig **aus**, jede Übertragung ist in den Einstellungen beschrieben |
| Offline | Aufzeichnung + Analyse offline | Gesamte Analyse läuft im reinen Kotlin-Modul `core` ohne Netzwerk |

## 2. Was technisch geht – und was nicht

| Thema | Möglich | Nicht zuverlässig möglich | Umsetzung |
|---|---|---|---|
| Ampel rot? | Stillstand nahe Kreuzung erkennen | Aus GPS allein wissen, *warum* du standest (Rot, Fußgänger, Abbiegen, Handy) | Probabilistische Wartezeit mit Confidence; nur Ereignisse ≥ 0,3 fließen in Kreuzungsstatistik |
| Stopp-Dauer | ±2–4 s bei 1–2-s-Sampling | Sekundengenau – GNSS-Drift im Stand erzeugt Scheinbewegung | Anker-basierte Stopp-Erkennung mit Hysterese |
| Map Matching offline | Glätten, Ausreißer entfernen, gelernte Kreuzungen | Straßengenaues Matching ohne Straßengraph | Offline: `PassthroughMatcher`. Online optional: Valhalla `trace_attributes` |
| Auto-Erkennung | Activity Transition API (Rad/Gehen/Fahrzeug) | Sofortige Erkennung – Transition kommt oft erst nach 30–90 s | Erste Meter gehen ggf. verloren; manueller Start bleibt immer möglich |
| Höhe | Barometer (Pixel 8 Pro hat einen) für relative Höhe | Absolute Höhe aus GPS (±10–20 m) | Barometer-Höhe, sonst GPS-Höhe mit 3-m-Hysterese |
| „Beste Route“ bei wenig Daten | Tendenz | Signifikante Aussage | Datenbasis-Label + Bootstrap-Wahrscheinlichkeit; Aussagen erst ab Mindest-ESS |

## 3. Architektur

```
┌────────────────────────── app (Android) ────────────────────────────┐
│ ui/        Compose M3 Screens  ──► ViewModels (StateFlow)           │
│ tracking/  TrackingService (FGS, location) · ActivityTransitions    │
│            AdaptiveLocationController · BarometerSource             │
│ analysis/  TripProcessor · GlobalAnalyzer · AnalysisWorker          │
│ providers/ MapMatcher (Passthrough | Valhalla) · IntersectionSource  │
│            (Overpass) · ProviderRegistry                            │
│ data/      Room (entities, DAOs) · Repositories · DataStore Settings │
│            Export (JSON/GPX) · DemoDataGenerator                    │
│ di/        AppContainer (manuelle DI, kein Hilt nötig)              │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ reine Datentypen
┌───────────────────────────────▼─────────────────────────────────────┐
│ core (reines Kotlin/JVM, keine Android-Abhängigkeit, unit-getestet) │
│ geo/   GeoMath, LocalProjection, Polyline-Codec, Resampling, DP     │
│ track/ TrackCleaner, TripMetrics, ModeClassifier                    │
│ stops/ StopDetector, WaitEstimator, HotspotLearner                  │
│ routes/ PlaceClusterer, RouteMatcher (Clustering), PassCounter      │
│ stats/ Descriptive, Bootstrap, KernelTimeModel, DataBasis           │
│ insights/ InsightEngine (zeitabhängige Muster)                      │
│ optimize/ RouteScorer (Ziele + Gewichte)                            │
└─────────────────────────────────────────────────────────────────────┘
```

**Warum ein eigenes `core`-Modul?** Die Algorithmen sind der wertvollste und fehleranfälligste Teil.
Als reines Kotlin sind sie schnell testbar (JVM-Unit-Tests ohne Emulator), offline-fähig und
unabhängig von UI/Datenbank. Die App ist nur „Sensor → Speicher → core → Anzeige“.

**Warum kein Hilt?** Ein Modul, wenige Abhängigkeiten – ein `AppContainer` reicht, spart
Build-Zeit und KSP-Komplexität. Kann später migriert werden.

**Datenfluss einer Fahrt**

1. Auslöser: Activity-Transition `ON_BICYCLE ENTER` (auto) oder Button (manuell).
2. `TrackingService` startet als Foreground-Service (`foregroundServiceType=location`).
3. Punkte → In-Memory-Puffer → Room in Batches (alle ~20 Punkte).
4. Ende: manuell, oder auto nach 4 min Stillstand / Activity `STILL`.
5. `AnalysisWorker` (WorkManager): `TripProcessor` (Bereinigung, Metriken, Stopps, Polylines,
   optional Map Matching) → `GlobalAnalyzer` (Orte, Routen, Kreuzungen, Wartezeiten).
6. UI beobachtet Room-Flows; Statistiken werden on-the-fly im `core` berechnet (Datenmenge klein).

## 4. Technologien

| Zweck | Wahl | Begründung |
|---|---|---|
| Sprache/UI | Kotlin 2.1, Jetpack Compose, Material 3 | Modern, Dynamic Color auf dem Pixel |
| Persistenz | Room 2.6 (KSP) | Flows, Migrationen, Transaktionen |
| Einstellungen | DataStore Preferences | Asynchron, typsicher genug |
| Hintergrundarbeit | WorkManager | Analyse nach Fahrtende, robust gegen Prozess-Tod |
| Standort | Fused Location Provider (play-services-location 21) | Beste Energie/Genauigkeit, Batching, Activity Recognition |
| Karte | MapLibre Native Android 11 + OpenFreeMap-Styles | Open Source, kein API-Key, keine Kosten, GeoJSON-Layer + Heatmap |
| Charts | Eigene Compose-Canvas-Charts | Keine Abhängigkeit, volle Kontrolle über Design und Dark Mode |
| HTTP/JSON | `HttpURLConnection` + `org.json` (Android-Bordmittel) | Nur 2 optionale Requests – keine Netzwerk-Library nötig |

## 5. Datenmodell (Schema v2)

Grundregel: **Rohdaten werden nie von der Analyse verändert.** Alles Abgeleitete lässt sich jederzeit
aus den Rohdaten neu berechnen und trägt die Algorithmus-Version (`AnalysisVersion.CURRENT`).
Statistiken (Mittelwerte, Mediane, Muster) werden **nie gespeichert**, sondern immer aus den
abgeleiteten Einzelereignissen berechnet.

```
ROHDATEN
trips            id, uuid (UNIQUE), recordedStart, recordedEnd, zoneId, trigger, state,
                 userMode, activityMode, excluded, note, isDemo, createdAt
track_points     PK(tripId, t), latE7, lonE7, accDm, speedCms, bearingDeg,
                 altDm (GNSS), baroAltDm, speedAccCms, vAccDm            FK trips CASCADE
activity_events  id, time, activityType, transition

ABGELEITET (analysisVersion)
trip_analysis    PK tripId → trips CASCADE; movementStart/End, lokale Zeit, mode, Distanz, Dauer,
                 Stopps, waitS, Höhe, Start/Ziel, startPlaceId, endPlaceId, routeId,
                 polyline, signature, bikeScore, via, matchedBy
stops            id, tripId → CASCADE, Zeit, Position, Dauer, alongM, kind, resumed
wait_events      id, stopId/tripId/intersectionId → CASCADE, routeId, Zeit, Position, Dauer,
                 distanceToIntersectionM, confidence, level,
                 kind = PROBABLE_INTERSECTION_WAIT, detectionMethod
intersection_passes PK(intersectionId, tripId) → CASCADE, passTime, Wochentag, Minute
routes           id, Name (+userNamed), Farbe, Start-/Zielort, Modus, Repräsentant
analysis_runs    Version, Start/Ende, Status (Nachweis, womit die Daten berechnet wurden)

REFERENZ + NUTZERDATEN
intersections    OSM-Cache (source=OSM) bzw. gelernt (LEARNED); userName = Nutzerdaten
places           Name, Art (HOME/UNI/OTHER), userNamed
```

**Reanalyse:** Wird `AnalysisVersion.CURRENT` erhöht, stellt der Worker beim nächsten Start alle
Fahrten auf `PROCESSING` und berechnet `trip_analysis`, `stops`, `wait_events`, `intersection_passes`
und Routen neu. Fahrten bleiben dabei mit ihrer alten Analyse sichtbar, bis die neue fertig ist.

**Strecken („Ziele“):** Ein Ortspaar plus Verkehrsmittel bildet eine Strecke (z. B. Zuhause ↔ Uni,
Zuhause ↔ Sport). Routen werden nur innerhalb einer Richtung einer Strecke verglichen. Die
Hauptstrecke ist einstellbar (DataStore `primary_corridor`), Standard ist die Strecke zur Uni.

**Datenmenge:** ca. 520–730 Fahrten/Jahr × ~850 Punkte ≈ 0,5–0,6 Mio. Punkte/Jahr ≈ 25 MB/Jahr
(inkl. Index), nach 5 Jahren ≈ 120 MB. Listen, Karte und Statistik lesen **nie** `track_points`,
sondern `trip_analysis.polyline/signature`. Durchfahrten werden im Worker berechnet und gespeichert,
damit die Oberfläche keine Geometrie rechnen muss.

**Indizes:** `trips(recordedStart)` für Zeitraum-Löschen, `trips(uuid)` UNIQUE für Import-Dedup,
`trip_analysis(routeId)`, `(startPlaceId, endPlaceId)`, `movementStart`; alle FK-Spalten sind indiziert
oder führende PK-Spalte.

**Löschen:** einzelne Fahrt / Zeitraum / alles. Abhängige Zeilen werden per `ON DELETE CASCADE`
entfernt, Routen, Orte und gelernte Kreuzungen anschließend durch die globale Analyse bereinigt.

**Migrationen:** echte Room-Migrationen (`Migrations.kt`), kein `fallbackToDestructiveMigration`.
1→2 baut `trips`/`wait_events` neu auf (Kopieren → Löschen → Umbenennen), erweitert `track_points`
nur per `ADD COLUMN` (schnell bei Millionen Zeilen) und stellt den echten Aufnahmestart aus den
Rohpunkten wieder her. Tests: `app/src/androidTest/.../MigrationTest.kt`.

**Backup / Gerätewechsel:** Android-Cloud-Backup ist bewusst aus (Standortdaten). Der JSON-Export
enthält `raw` + `userData` und reicht zur vollständigen Wiederherstellung; der Import dedupliziert per
UUID und lässt alles Abgeleitete neu berechnen. CSV (eine Zeile pro Fahrt) und GPX (pro Fahrt) gibt es
für andere Werkzeuge.

## 6. Algorithmen

### 6.1 Track-Bereinigung
1. Punkte mit Genauigkeit > 35 m verwerfen (Kaltstart, Tunnel).
2. Sprünge entfernen: implizierte Geschwindigkeit > Modus-Maximum (Rad 20 m/s).
3. Geschwindigkeit: GNSS-Doppler-Speed, falls vorhanden, sonst Ableitung über ±1 Punkt.

### 6.2 Stopp-Erkennung (anker-basiert mit Hysterese)
- Stopp beginnt, wenn Speed < 0,8 m/s; Anker = Position.
- Stopp dauert an, solange Speed < 1,6 m/s **oder** Abstand zum Anker < max(10 m, Genauigkeit),
  und Abstand < 25 m.
- Mindestdauer 4 s; Stopps mit < 8 s Lücke und < 15 m Abstand werden verschmolzen (Stop-and-go
  an der Ampel).
- Klassen: `TERMINAL` (am Start/Ende), `PAUSE` (> 5 min), `STOP`.

### 6.3 Wartezeit-Confidence
Für jeden `STOP` wird der nächste Kreuzungskandidat (≤ 40 m) gesucht.
```
pDist     = exp(-d² / (2·15²))
typePrior = Ampel 0.95 · Ampel-Querung 0.9 · Stop 0.8 · Vorfahrt 0.7 · Querung 0.6 · gelernt 0.6 · Kreuzung 0.5
recurrence= Anteil der Durchfahrten mit Stopp an diesem Ort (aus Historie)
durScore  = Rampe: <3 s → 0.2, 6–150 s → 1.0, fällt bis 300 s auf 0.3
resume    = 1.0 wenn danach weitergefahren, sonst 0.3
confidence= pDist · (0.6·typePrior + 0.4·max(typePrior, recurrence)) · durScore · resume
```
≥ 0,6 „wahrscheinlich“, 0,3–0,6 „möglich“, < 0,3 „unklar“ (nicht in Kreuzungsstatistik).

### 6.4 Gelernte Kreuzungen (offline)
DBSCAN über Stopp-Mittelpunkte (ε = 20 m, mind. 3 verschiedene Fahrten). Liegt ein OSM-Knoten
≤ 25 m entfernt, wird kein Duplikat angelegt. So funktioniert die Kreuzungsanalyse **auch ganz
ohne Internet** – nur die Straßennamen fehlen dann.

### 6.5 Orte & Routen-Clustering
- Orte: Start-/Endpunkte gierig clustern (Radius 150 m). Meiste Starts 5–10 Uhr → „Zuhause“,
  meiste Ankünfte 6–11 Uhr an Werktagen → „Uni“. Umbenennbar.
- Signatur: Track auf 25-m-Abstände resampeln.
- Ähnlichkeit `sim(A,B) = min(cov(A→B), cov(B→A))`, `cov` = Anteil der Signaturpunkte von A,
  die ≤ 35 m an B's Polylinie liegen. Robust gegenüber GPS-Rauschen und kleinen Abweichungen,
  aber empfindlich gegenüber echten Umwegen (ein 400-m-Umweg auf 8 km senkt sim auf ~0,95,
  ein anderer Straßenzug über 2 km auf ~0,75).
- Zuordnung: gleiches Orte-Paar + gleicher Modus + sim ≥ 0,85 zum Repräsentanten einer Route →
  zuordnen; sonst neue Route. Repräsentant = Medoid (Fahrt mit höchster mittlerer Ähnlichkeit).
  Route-IDs bleiben stabil, Namen/Farben bleiben erhalten.

### 6.6 Statistik
- Deskriptiv: n, Mittelwert, **Median**, Min/Max, Standardabweichung, IQR, P10/P90, MAD.
- Ausreißer: |x − Median| > 3,5 · 1,4826 · MAD → markiert, fließt nicht in Mittelwert/Std ein
  (Median bleibt ohnehin robust).
- Unsicherheit: Bootstrap (B = 400, deterministischer Seed) → 80-%-Intervall des Medians.
- Zuverlässigkeit einer Route: `1 − (P90 − P10) / Median`, auf [0,1] begrenzt.
- Datenbasis: 0 „Keine Daten“, 1–4 „Vorläufige Daten“, 5–19 „Erste Tendenz“, 20–99 „Gute
  Datenbasis“, ≥ 100 „Hohe Datenbasis“. Bei gewichteten Schätzungen zählt die effektive
  Stichprobengröße `ESS = (Σw)² / Σw²`.

### 6.7 Zeitabhängige Analyse ohne starre Buckets
- Gaußscher Kernel über die Tageszeit (σ = 35 min): Eine Fahrt um 07:50 zählt für 08:00 fast
  voll, für 09:30 kaum, für 20:00 gar nicht.
- Gewichteter Median pro Route und Zeitpunkt.
- **Hierarchisches Shrinkage**: Schätzung für „Montag“ wird zum Werktags-Wert gezogen, solange
  Montags-Daten dünn sind: `m = (ESS_d·m_d + k·m_wk)/(ESS_d + k)`, k = 4.
- **InsightEngine**: Raster 05:00–23:00 in 15-min-Schritten; pro Schritt Gewinner-Route,
  Vorsprung und Bootstrap-Wahrscheinlichkeit P(Gewinner schneller). Signifikant ab P ≥ 0,8,
  Vorsprung ≥ 20 s, ESS ≥ 3 je Route. Benachbarte Schritte mit gleichem Gewinner werden zu
  Zeitfenstern zusammengefasst → „Mo 07:45–08:30: Route B ist Ø 3:25 min schneller als Route A
  (14 vs. 11 Fahrten)“; endet ein Fenster, wird „danach verschwindet der Vorteil“ erzeugt.

### 6.8 Optimale Route
Metriken je Route im aktuellen Kontext (Wochentag/Uhrzeit): erwartete Dauer (Median),
Distanz, Wartezeit, Stopps, Streuung (P90−P10), Radfreundlichkeit (Anteil Radinfrastruktur,
nur mit Map Matching; sonst neutral und markiert). Jede Metrik wird über die Kandidaten
min-max-normiert (0 = beste), Score = Σ wᵢ·normᵢ. Presets: Schnellste (Dauer 1,0), Kürzeste,
Wenigste Wartezeit, Wenigste Stopps, Zuverlässigste, Fahrradfreundlichste, Eigene
(Standard 70/15/10/5 – Dauer/Wartezeit/Distanz/Stopps).

## 7. Externe APIs (alle optional, standardmäßig aus)

| Dienst | Zweck | Übertragene Daten | Empfehlung |
|---|---|---|---|
| OpenFreeMap Tiles | Kartenhintergrund | Angezeigter Kartenausschnitt (wie jede Karten-App) | Nur beim Öffnen der Karte |
| Overpass API (OSM) | Ampeln/Querungen + Straßennamen | **Nur** ein grob gerundetes Rechteck um alle Fahrten, kein Track | Einmal pro 30 Tage, Ergebnis lokal gecacht |
| Valhalla `trace_attributes` | Map Matching, Straßen, Radinfrastruktur | **Die Fahrt (Koordinaten + Zeit)** | Selbst gehostet auf deinem Heimserver (Docker `ghcr.io/valhalla/valhalla`) – dann verlässt nichts dein Netz |
| OSRM `/match` | Alternative zu Valhalla | Fahrt | Öffentlicher Demo-Server kennt nur Auto-Profil → nur self-hosted sinnvoll |
| GraphHopper Map Matching | Alternative | Fahrt | Kommerziell/Key, nicht gewählt |

Austauschbarkeit: `MapMatcher`- und `IntersectionSource`-Interfaces; neue Provider werden in
`ProviderRegistry` registriert. Jeder Provider beschreibt selbst, was er überträgt – dieser Text wird
in den Einstellungen angezeigt.

## 8. Energie-Entscheidungen

| Entscheidung | Wirkung |
|---|---|
| GNSS **nur während einer Fahrt** | Größter Hebel; außerhalb von Fahrten verbraucht die App praktisch nichts |
| Auto-Start über Activity Transition API (Hardware-Sensor-Hub) statt Dauer-GPS/Geofence-Polling | Erkennung ohne eigene Wakeups |
| Foreground-Service nur zwischen Start und Ende | Pflicht für Standort im Hintergrund; kein Dauer-Service |
| 2 s Intervall in Bewegung, 5 s nach 60 s Stillstand | 2 s ≈ 10 m Auflösung bei 18 km/h – nötig für Kreuzungen; im Stand weniger Wakeups |
| Batching `maxUpdateDelay = 15 s` wenn UI nicht sichtbar | CPU schläft zwischen Batches, GNSS-Chip sammelt |
| Barometer mit Sensor-Batching (10 s Latenz) | Präzise Höhe für ~0 Zusatzkosten |
| Room-Writes in Batches (20 Punkte) | Weniger I/O-Wakeups |
| Keine Netzwerk-Requests während der Fahrt | Analyse/Map Matching erst danach per WorkManager |
| Auto-Stopp nach 4 min Stillstand (auto) bzw. 20 min (manuell) | Kein „vergessener“ Tracker |

Realistisch kostet aktives GNSS einige Prozent Akku pro Stunde; bei ~1 h Fahrt pro Tag bleibt die
App deutlich unter dem Display-Verbrauch. Verifizieren mit `adb shell dumpsys batterystats` /
Battery Historian in Phase 10.

## 9. Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Android-Hintergrundbeschränkungen (FGS-Start aus dem Hintergrund) | Activity-Transition-Events sind ausdrücklich ausgenommen; Auto-Modus benötigt „Standort immer erlauben“ |
| Hersteller-Akkuoptimierung killt Service | Pixel unkritisch; Hinweis + START_STICKY + Recovery offener Fahrten |
| Verspätete Activity-Erkennung | Manuell starten jederzeit möglich; erste Meter fehlen ggf. |
| GPS-Drift im Stand / in Häuserschluchten | Anker-Stopp-Erkennung, Genauigkeitsfilter, Confidence |
| Wenige Daten → falsche Schlüsse | Datenbasis-Labels, Shrinkage, Bootstrap, Mindest-ESS |
| Routen-Clustering zu fein/grob | Schwellen zentral konfigurierbar; Routen umbenennen; später manuelles Zusammenführen |
| Öffentliche OSM-Dienste (Fair-Use) | Selten, gecacht, URL konfigurierbar |
| Datenwachstum | Kompakte Punkte, Polylines für Anzeige, optionales Ausdünnen alter Rohpunkte |

## 10. MVP-Umfang (dieser Stand)

Enthalten: Projektgerüst, Room-Datenbank, Aufzeichnung (manuell + automatisch), adaptive
Location + Batching + Barometer, Analyse-Pipeline, Stopps, Wartezeit-Confidence, gelernte +
OSM-Kreuzungen, Orte, Routen-Clustering, Statistik inkl. Bootstrap, Zeitmuster/Insights,
Routenranking mit 7 Zielen, Dashboard mit Charts, Kartenansicht (Routen, Stopps, Kreuzungen,
Heatmap), Kreuzungsdetail, Fahrtenliste/-detail, Einstellungen, Datenschutzseite, JSON/GPX-Export,
Löschen, Demo-Daten.

Bewusst später (Phase 10+): Segmentanalyse zwischen Kreuzungen („wo verliere ich Zeit
zwischen Ampeln“), manuelles Zusammenführen von Routen, Geofence-Trigger für Zuhause/Uni,
Wetter als Einflussfaktor, Wear-OS-Tile, Ausdünnen alter Rohdaten, Room-Migrationstests.

## 11. Änderungen nach den ersten echten Fahrten (Analyse v2, Schema v3)

Auswertung der ersten 5 echten Aufzeichnungen (Okt. 2026) ergab vier Fehler, alle per Reanalyse aus
den Rohdaten korrigierbar:

| Problem | Ursache | Lösung |
|---|---|---|
| Ankunft nicht abgeschnitten (+9 min, +2 km) | GPS-Rauschen im Gebäude (±30–50 m, Phantom-Tempo) zählte als Bewegung | `MovementWindow`: nur anhaltende Bewegung (≥ 4 Fixes, ≤ 20 m Genauigkeit) zählt; Service beendet Auto-Fahrten 45 s nach Ankunft an bekanntem Ort |
| ~1000 Höhenmeter pro flacher Fahrt | Barometerwert pro GPS-Batch statt pro Fix, Sprünge bis 110 m | Barometer-Ringpuffer mit Zeitstempel, Median ±2 s je Fix; Analyse verwirft unplausible Höhen (> 1,5 m/s vertikal) statt Unsinn anzuzeigen |
| Kurzer Weg als Radfahrt klassifiziert | Activity-Hinweis überstimmte das Tempoprofil | Nur vom Nutzer gesetzter Modus ist bindend; Gehtempo gewinnt gegen „Radfahren erkannt“ |
| Mensa → Zuhause nicht mit Uni → Zuhause verglichen | Mensa ist eigener Ort | Ortsgruppen (`places.parentPlaceId`, Schema v3): Routen nutzen die Gruppen-Wurzel; App schlägt nahe Orte (≤ 400 m) zum Zusammenfassen vor |
