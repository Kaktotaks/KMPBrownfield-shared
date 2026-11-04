# Tasks — product spec (identical on iOS and Android)

The starting point of the migration (step 0): two fully independent native apps built from this
spec — same screens, same strings, same business rules. Domain and Data were kept UI-free and named
identically on both platforms so they could later be extracted into the KMP `shared` module.
Since then `Clock` was renamed to `DateProvider`, and the Domain, Data and ViewModel layers live in
[KMPBrownfield-shared](https://github.com/Kaktotaks/KMPBrownfield-shared).

## Layers (same names on both platforms)
- Domain/: `Task`, `Priority`, `TaskValidator`, `ValidationError`, `TaskFilter` (+ `StatusFilter`, `SortOrder`), `StatsCalculator`, `TaskStats`, `Clock` (today provider)
- Data/: `TaskApi` (network), `TodoDto`, `TaskRepository` (single in-memory source of truth)
- UI/: one folder per screen with its ViewModel: TaskList, TaskDetail, TaskEditor, Stats + root navigation
- Unit tests for Domain (validator, filter, stats) on both platforms.

## Model
Task: id: Int, title: String, notes: String, priority: Priority, dueDate: Date (date only, no time), isDone: Bool
Priority: low, medium, high (display "Low", "Medium", "High"; sort weight high=3, medium=2, low=1)

## Network
GET https://jsonplaceholder.typicode.com/todos?_limit=20
DTO: { userId: Int, id: Int, title: String, completed: Bool }
Mapping (deterministic, identical on both platforms):
- id = id, title = title, notes = "", isDone = completed
- priority = [low, medium, high][id % 3]
- dueDate = today + ((id % 14) - 3) days   (so some are overdue)
Loaded once on app start by TaskRepository. States: loading (spinner), error (text "Couldn't load tasks." + button "Retry"), loaded.
After loading, all changes (add/edit/toggle/delete) live in memory only. New task id = (max existing id) + 1, or 1 if empty.

## Business rules
TaskValidator.validate(title, notes, dueDate, originalDueDate?) -> list of errors, in this order:
1. title trimmed length < 3 -> "Title must be at least 3 characters."
2. title trimmed length > 60 -> "Title must be at most 60 characters."
3. notes length > 500 -> "Notes must be at most 500 characters."
4. dueDate < today -> "Due date can't be in the past." — EXCEPT when editing and dueDate == originalDueDate (unchanged overdue date is allowed).
Title is saved trimmed.

Overdue: !isDone && dueDate < today.

TaskFilter.apply(tasks, query, status, sort):
- query: trimmed, case-insensitive "contains" on title; empty query = no filtering
- status: All | Active (!isDone) | Done (isDone)
- sort: DueDate (dueDate asc, then id asc) | Priority (weight desc, then dueDate asc, then id asc)
Defaults: query "", status All, sort DueDate.

StatsCalculator.calculate(tasks, today) -> TaskStats:
- total, done, active = total - done
- completionPercent = total == 0 ? 0 : round(done * 100 / total) (round half up, Int)
- byPriority counts: low, medium, high (all tasks)
- overdue count (per rule above)

## Navigation
Bottom tab bar with 2 tabs: "Tasks" (list icon), "Stats" (chart icon).
Tasks tab: list -> push Task detail. "+" in top bar opens editor as modal sheet ("New task").
Detail: "Edit" opens editor sheet ("Edit task") prefilled. Delete -> confirmation -> pop back to list.

## Screens
1. Task list — title "Tasks"
   - Search field, placeholder "Search tasks"
   - Segmented control: "All" | "Active" | "Done"
   - Sort menu in top bar: "Sort by due date" | "Sort by priority"
   - Row: leading circle toggle (filled checkmark when done) that toggles isDone without opening detail;
     title (strikethrough + secondary color when done); below: "Due 29 Oct 2026" and, if overdue, red "Overdue";
     trailing priority badge (Low=gray, Medium=orange, High=red)
   - Empty state when filtered list is empty: "No tasks"
   - Loading / error states as in Network.
2. Task detail — title "Task"
   - Title (large), status "Done" / "Active", priority badge, "Due <date>" (+ red "Overdue"), notes (or "No notes" in secondary color)
   - Button "Mark as done" / "Mark as active"
   - Top bar "Edit"
   - Destructive button "Delete task" -> confirmation dialog "Delete task?" with "Delete" (destructive) / "Cancel"
3. Task editor (sheet) — title "New task" or "Edit task"
   - Fields: "Title" text field, "Notes" multiline, "Priority" segmented (Low/Medium/High, default Medium), "Due date" date picker (date only, default today)
   - Validation errors shown in red under the form, live as the user types
   - Top bar: "Cancel" (dismiss) and "Save" (disabled while there are errors)
4. Stats — title "Stats"
   - "Completed" big number "<completionPercent>%" with a progress bar
   - Rows: "Total", "Done", "Active", "Overdue"
   - Section "By priority": "High", "Medium", "Low" counts

Date format everywhere: "d MMM yyyy" in en_US (e.g. "29 Oct 2026").
