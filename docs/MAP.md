# Map & daily routes (shared 0.9.0)

Everything that is not drawing lives here: model + database, demo data, routing, geocoding, the
MapLibre style, the "past / overdue / next stop / leave by" logic and the whole screen state.
The apps render `MapViewModel.uiState` and forward gestures. Spec: `design-handoff/map/MAP_HANDOFF.md`.

Package map (`com.example.tasks.shared`):

| Package | Types |
|---|---|
| `domain` | `GeoPoint`, `Place`, `Geo` (haversine), `DniproBounds`, `Task.time/place/scheduledAt/isPast/isOverdueAt`, `TimeProvider`, `SystemTimeProvider`, `FixedTimeProvider` |
| `data` | `TaskRepository` (+ `setPlace`, `clearPlace`, `tasksOn`, `add(..., time, place)`), `DemoRoute` |
| `routing` | `RoutingApi`, `OsrmRoutingApi`, `StraightLineRouter`, `Route`, `Leg` |
| `geocoding` | `Geocoder`, `NominatimGeocoder` |
| `map` | `MapStyle`, `MapPalette`, `RoutePalette` |
| `presentation` | `MapViewModel`, `MapUiState`, `MapStop`, `RouteState`, `MoveState`, `NewTaskDraft`, `DayLabel`, `PlaceSearchViewModel`, `TaskEditorViewModel` (+ time / place) |

## Wiring

```kotlin
// Android
val vm = MapViewModel(repository, OsrmRoutingApi(), NominatimGeocoder("en"), SystemTimeProvider)
```
```swift
// iOS
let vm = MapViewModel(repository: repo, routing: OsrmRoutingApi(), geocoder: NominatimGeocoder(language: "uk"), time: SystemTimeProvider.shared)
for await state in vm.uiState { render(state) }   // call vm.clear() in deinit
```

`NominatimGeocoder(language)` is only the `accept-language` of reverse geocoding; `search` takes its own
language (use the app language for both).

## MapUiState

| Field | Meaning |
|---|---|
| `day`, `dayLabel` (`TODAY/TOMORROW/YESTERDAY/NONE`), `start` | the viewed day; `start` is always `DemoRoute.START` (Dobrovoltsiv Ln 6) |
| `stops: [MapStop]` | routed stops in time order (`order` 1…n), then pins without a time (`order == nil`, no leg) |
| `MapStop` | `task`, `place`, `order?`, `isPast`, `isOverdue`, `isDone`, `isNext`, `legFromPrevious?`, `legIsPast` |
| `route: RouteState` | `Idle` (nothing to draw yet / only unscheduled pins), `Loading` (request slower than 300 ms), `Ready(route)`, `Fallback(route)` (straight lines, no durations), `Empty` (no place on this day) |
| `stopsCount`, `routedStopsCount`, `totalDurationMinutes?`, `totalDistanceMeters?` | summary; both totals are `nil` unless `Ready` |
| `nextStop?`, `nextStopMinutesAway?` | first not-done stop with `scheduledAt >= now`; minutes from now (rounded up) |
| `leaveBy?`, `startIsPast` | first routed stop time − first leg (floored to the minute); `startIsPast` picks "Left" vs "Leave by" |
| `selectedTaskId?`, `selectedStop?`, `sheetExpanded` | selection / route sheet |
| `move: MoveState?` | `taskId`, `originalPlace`, `candidate`, `candidateAddress?`, `isResolvingAddress` |
| `newTaskDraft: NewTaskDraft?` | `point`, `address?`, `isResolvingAddress`, `defaultTime` |
| `tilesOffline`, `routeFailed` | chips; `tilesOffline` is set by the app via `reportTilesOffline` |
| `isTasksLoading` | the repository is still loading |

Actions: `previousDay / nextDay / selectDay`, `selectTask / clearSelection / setSheetExpanded`,
`longPress / cancelNewTask / saveNewTask / validateNewTask`, `startMove / updateMoveCandidate /
confirmMove / cancelMove`, `markDone / delete`, `retry / reportTilesOffline`.

Behaviour worth knowing:

- **Route rebuild** happens on day change and on any change of the routed stops (place, time, add,
  delete). Marking done does not rebuild. Debounced 300 ms; the previous request is cancelled;
  `Loading` only after the request has run 300 ms. A failed request becomes `Fallback`.
  Results are cached in memory by point list (fallbacks are not cached, so `retry()` works).
- **`now`** comes from the injected `TimeProvider` and is refreshed every 30 s while `uiState` is collected.
- **Move**: the selection stays while moving; hide the task sheet while `move != nil`. `updateMoveCandidate`
  reverse-geocodes after 400 ms of silence; `confirmMove` waits for a pending lookup, keeps the task's time
  and rebuilds the route. When no address can be found, the place is stored as `"48.4601, 35.0512"`.
- **New task**: `defaultTime` = next full half hour after now when viewing today, 09:00 on other days.
  `saveNewTask` returns false (draft stays) when the title/notes are invalid. The date is never an error
  (you can add to a past day). Voice: `voiceVm.bindTo(mapVm)` (a `VoiceNoteTarget.Draft` view model); recordings
  of a cancelled draft are deleted.
- **Select** collapses the route sheet (`sheetExpanded = false`).
- Text stays native: shared exposes numbers, enums and times only.

## Debug hooks (frozen "now")

`FixedTimeProvider` is both a `TimeProvider` and a `DateProvider`. Pass it to the repository and to `MapViewModel`
and "now" is frozen everywhere:

```swift
let clock = FixedTimeProvider(year: 2026, month: 11, day: 4, hour: 12, minute: 40)
let repo = TasksIos.shared.createRepository(api: KtorTaskApi(), dateProvider: clock, logger: appLogger)
```
Because the demo route is dated relative to the repository's `DateProvider.today()`, a fresh database seeded under
the frozen clock gets its demo stops on 4 Nov 2026 (the reference screenshots).

Failure state: `OsrmRoutingApi(baseUrl: "https://invalid.invalid")` makes every route request fail → `Fallback`.

## Database (schema v2)

`migrations/1.sqm` adds `due_time` (ISO `HH:mm`), `place_lat`, `place_lng`, `place_address` to `TaskEntity`.
`databases/2.db` is the committed snapshot; `verifyCommonMainTasksDatabaseMigration` (part of `check`) checks
`1.sqm` against `1.db`. Existing rows keep `NULL` time/place and untouched voice-note columns.
`TaskRepository.update(task)` now writes time and place too (the task carries the full value); voice columns are
still only written by `setVoiceNote` / `clearVoiceNote`.

Regenerate the snapshot for the next schema change: `./gradlew generateCommonMainTasksDatabaseSchema`.

## Demo route

`DemoRoute` gives up to 5 not-done tasks of today and 4 of tomorrow a time and an address, once per database
(Meta `demo_route_v1 = 1`), on the first seed and once on existing installs. It only touches tasks that have
neither a time nor a place; if a day has too few candidates, other free not-done tasks are moved to it.
Coordinates were resolved with Nominatim on 2026-11-09 (OSM element ids are in `DemoRoute.kt`); a test asserts all
points are inside lat 48.40–48.53, lng 34.90–35.15.

## Swapping hosts

Each host is named in exactly one place:

| Service | Constant | Override |
|---|---|---|
| Routing (OSRM) | `OsrmRoutingApi.DEFAULT_BASE_URL` = `https://router.project-osrm.org` | `OsrmRoutingApi(baseUrl = "https://osrm.example.com")` |
| Geocoding (Nominatim) | `NominatimGeocoder.DEFAULT_BASE_URL` = `https://nominatim.openstreetmap.org` | `NominatimGeocoder(baseUrl = "...", language = "en")` |
| Tiles + glyphs (OpenFreeMap) | `MapStyle.TILES_URL`, `MapStyle.GLYPHS_URL` | change the constants (style JSON is generated code) |

The public OSRM demo server and public Nominatim are fine for a portfolio app. For production host your own:
Nominatim's usage policy requires a real User-Agent (`NominatimGeocoder.USER_AGENT`) and at most one request
per second, which `NominatimGeocoder` enforces itself.

## Map style

`MapStyle.json(dark)` → MapLibre style spec v8, generated from the §4 token table (`MapStyle.palette(dark)`).
iOS: write it to Caches and pass the file URL as `styleURL`. Android: `Style.Builder().fromJson(...)`.
Layer ids are `MapStyle.LAYER_IDS`; add route layers below `MapStyle.ROUTE_BELOW_LAYER` (the first label layer).
`MapStyle.routePalette(dark)` has the route colours, widths and dashes of §3.2. Layer / class / font names and the
glyph URL were copied from OpenFreeMap's `liberty` style (2026-11-09). Labels use Noto Sans (no Inter glyphs) and
`name_en` falling back to `name`.

## Swift (SKIE) notes

Checked by type-checking Swift against the debug XCFramework:

- Types keep their Kotlin names (`MapViewModel`, `MapUiState`, `MapStop`, `Place`, `GeoPoint`, `Route`, `Leg`).
  `Task` stays `TaskItem`; the 6- and 7-argument `TaskItem` initialisers still exist.
- `uiState` is an `AsyncSequence`; `RouteState` is exhaustive via `switch onEnum(of: state.route)`;
  `DayLabel` is a real Swift enum.
- Nullable `Int` / `Double` / `Long` fields box (`stop.order?.intValue`, `leg.durationSeconds?.doubleValue`).
- `kotlinx.datetime` types are exported as `Kotlinx_datetimeLocalDate` / `Kotlinx_datetimeLocalTime` /
  `Kotlinx_datetimeLocalDateTime`. Use `IosDates.shared.localTime(hour:minute:)`, `toLocalTime(date:)`,
  `toDate(localDate:localTime:)` to bridge with `Date` (date pickers).
- `suspend` functions (`geocoder.reverse`, `search`, `routing.route`) are `async throws`.
- Constants are instance properties: `MapStyle.shared.ROUTE_BELOW_LAYER`, `DemoRoute.shared.START`.
- Swift cannot implement the Kotlin `fun interface`s (`TimeProvider`, `DateProvider`) without subclassing
  `NSObject`: use `SystemTimeProvider.shared` / `FixedTimeProvider(...)`.
