# Pendel

Persönliche **Route Analytics** für den Weg zur Uni: Pendel zeichnet deine Fahrten energiesparend auf,
erkennt wiederkehrende Routen und zeigt dir aus deinen eigenen Daten, welche Route unter welchen
Bedingungen am besten ist. Dabei sagt die App immer dazu, wie belastbar eine Aussage ist.

Architektur, Algorithmen, Risiken und Akku-Entscheidungen: siehe [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Projekt öffnen

1. Ordner `Pendel` in Android Studio öffnen und den Gradle-Sync abwarten (AGP 8.7, Kotlin 2.1, Gradle 8.11 Wrapper).
   Wenn Android Studio ein AGP-/Bibliotheks-Update vorschlägt, kannst du das annehmen.
2. Auf dem Pixel starten (minSdk 29, targetSdk 35).
3. Zum Ausprobieren tippst du in der Übersicht auf **Demo-Daten erzeugen**. Die App erzeugt dann rund
   10 Wochen realistische Fahrten in Karlsruhe (Durlach → HKA) mit GPS-Rauschen, Ampelphasen und
   Berufsverkehr. Sie durchlaufen exakt dieselbe Analyse-Pipeline wie echte Fahrten und lassen sich in
   den Einstellungen mit einem Tipp wieder löschen.

Tests:

```
./gradlew :core:test                        # Algorithmen, reines Kotlin
./gradlew :app:connectedDebugAndroidTest    # Room-Migrationen, auf dem Pixel/Emulator
```

Beim ersten Build schreibt Room `app/schemas/.../2.json`. Die Datei gehört ins Repo (wie `1.json`),
weil die Migrationstests sie brauchen.

## Module

```
core/  reines Kotlin – alle Algorithmen, unit-getestet
  geo/        Haversine, lokale Projektion, Polyline-Codec, Resampling, Douglas-Peucker
  track/      Bereinigung, Metriken, Verkehrsmittel, TripAnalyzer
  stops/      Stopp-Erkennung, Wartezeit-Confidence, gelernte Kreuzungen (DBSCAN)
  routes/     Orte, Routen-Clustering, Durchfahrten
  stats/      Median/MAD/Quantile, Bootstrap, Kernel-Zeitmodell, Datenbasis
  insights/   Routenstatistik, zeitabhängige Muster, Kreuzungsstatistik
  optimize/   Zielfunktionen & Gewichtung
  analysis/   GlobalAnalysis (Orte → Routen → Kreuzungen → Wartezeiten)
  demo/       physikalisch plausible Demo-Fahrten
app/   Android (Compose, Room, Fused Location, WorkManager, MapLibre)
  tracking/   Foreground-Service, adaptive Location, Activity Transitions, Barometer
  analysis/   TripProcessor, GlobalAnalyzer, AnalysisWorker
  providers/  MapMatcher (Offline | Valhalla), Overpass-Kreuzungen
  data/       Room, Repositories, DataStore, Export (JSON/GPX), Demo
  ui/         Übersicht, Karte, Routen, Fahrten, Kreuzung, Einstellungen, Datenschutz
```

## Optional: Valhalla auf dem Heimserver (Map Matching)

Damit bekommst du Straßennamen, eine echte Straßen-Geometrie und eine Bewertung der Radinfrastruktur.
Deine Tracks verlassen dabei dein Heimnetz nicht.

```bash
docker run -dt --name valhalla -p 8002:8002 \
  -v $PWD/valhalla:/custom_files \
  -e tile_urls=https://download.geofabrik.de/europe/germany/baden-wuerttemberg/karlsruhe-regbez-latest.osm.pbf \
  ghcr.io/valhalla/valhalla-scripted:latest
```

Danach in der App unter **Einstellungen → Online-Dienste → Map Matching → Valhalla** die URL
`http://<server-ip>:8002` eintragen und **Neu analysieren** antippen.

## Akkuverbrauch messen (Phase 10)

```bash
adb shell dumpsys batterystats --reset
# … eine Fahrt aufzeichnen …
adb bugreport bugreport.zip   # in Battery Historian öffnen
```

## Status

| Phase | Inhalt | Stand |
|---|---|---|
| 1 | App-Gerüst, Navigation, Room | ✅ |
| 2 | Aufzeichnung (manuell + automatisch) | ✅ |
| 3 | GPS-Track kompakt speichern | ✅ |
| 4 | Routen erkennen | ✅ (core getestet) |
| 5 | Stopps & Wartezeiten | ✅ (core getestet) |
| 6 | Statistik, Zeitmuster | ✅ (core getestet) |
| 7 | Karte | ✅ |
| 8 | Routenvergleich | ✅ |
| 9 | Optimale Route | ✅ |
| – | Datenarchitektur v2: Roh-/Analyse-Trennung, Versionierung, Migration + Tests, Import, Zeitraum-Löschen | ✅ |
| – | Mehrere Ziele (Strecken-Tabs) + Hauptstrecke | ✅ |
| 10 | Polishing, Akku-Messung, Segmentanalyse | offen |

**Hinweis:** Die Android-Schicht wurde ohne Android-SDK geschrieben, weil Googles Maven-Repository in der
Build-Umgebung nicht erreichbar war. Datenschicht, Analyse und Provider sind gegen Stubs kompiliert, der
`core` wurde echt kompiliert und getestet. UI und Tracking-Service sind sorgfältig geprüft, aber noch
nicht in Android Studio gebaut. Falls beim ersten Build etwas hakt, schick einfach die Fehlermeldung.
