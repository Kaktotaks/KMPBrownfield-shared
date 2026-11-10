Map & daily routes: tasks get an optional time and place, and shared now owns all the map logic. **Source compatible for Swift and Kotlin callers**, additive apart from the database migration.

## What's new in 0.9.0
- **Task time and place.** `Task.time` (optional local time) and `Task.place` (`Place` = `GeoPoint` + address, optional).
  Repository API: `setPlace`, `clearPlace`, `tasksOn(date)`, and `add(..., voiceNote, time, place)`; `update(task)` now writes
  time and place too. The old `TaskItem` constructors still work (secondary constructors).
- **Database schema v2.** SQLDelight migration `1.sqm` with a `2.db` snapshot; `verifyMigrations` is part of `check`. Existing
  tasks and voice notes are kept on upgrade from 0.8.x. A fresh or upgraded database gets the **demo route** once
  (`DemoRoute`: a fixed start point and ten addresses in Dnipro, coordinates resolved with Nominatim).
- **Routing.** `RoutingApi` with `OsrmRoutingApi` (driving routes) and `StraightLineRouter` (fallback, drawn dashed in the apps).
- **Geocoding.** `Geocoder` with `NominatimGeocoder` (address search and reverse geocoding; 1 request/s, proper User-Agent) and
  `PlaceSearchViewModel` (500 ms debounce, 3+ characters) for the Editor's Place row.
- **Map style.** `MapStyle.json(dark)` generates the MapLibre style (light and dark) from the token table, plus
  `MapStyle.palette(dark)` and `MapStyle.routePalette(dark)` for route colours, widths and dashes.
- **`MapViewModel` + `MapUiState`.** Day selection, stops in time order, legs, next stop and "minutes away", past/overdue
  rules, route states (`Idle`, `Loading`, `Ready`, `Fallback`, `Empty`), long-press new task, move pin, mark done, delete and retry. The apps
  contain no routing, geocoding or past/overdue logic.
- **Editor.** `TaskEditorViewModel.onTimeChange` and `onPlaceChange`; `voiceVm.bindTo(mapVm)` for the new-task-at-location sheet.
- **Time helpers.** `FixedTimeProvider` (freezes "now" for debug and screenshots), `SystemTimeProvider`, and Swift-side
  `IosDates` helpers.
- **Docs.** `docs/MAP.md`: API overview, SKIE notes and how to swap the OSRM, Nominatim and tile hosts.

## Fixes
None.

## Breaking changes
None for callers. The first launch after the upgrade migrates the database to schema v2 and seeds the demo route once.

## Requirements
Unchanged: iOS 15+, Android minSdk 24.

## How to consume
See the [README](https://github.com/Kaktotaks/KMPBrownfield-shared#readme): iOS via Swift Package
Manager (product `Shared`), Android via `com.example.tasks:shared:0.9.0` from GitHub Packages.
