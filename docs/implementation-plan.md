# Implementation plan: Simple widget on Lawnchair

Three reported problems, four root causes, two repositories. See `adr/0001` for the theming
decision and `adr/0002` for the widget configuration decision. The layout bug below needs no
ADR — there is no decision in it, only a missing modifier.

## Root causes

| Reported problem | Cause | Where | Launcher involved? |
| --- | --- | --- | --- |
| No "New Page" button | `LazyVerticalGrid` with no `weight`, so it consumes the whole column and pushes the button past the bottom edge of a non-scrolling `Column` | `MainActivity.kt:43-68` | No |
| Editor is black on black | Unknown. Light-only platform theme is the best candidate and is a real bug regardless | `themes.xml:4` | No |
| Config screen closes on tap | `substring(0, 20)` on a string shorter than 20 characters | `TextAppsWidgetConfigActivity.kt:200` | No |
| ...and leaves a dead widget | Cancel path does not remove the widget the V2 flow already added | `Launcher.java:996` | Yes, Lawnchair 15/16 only |

The first is why the button vanished as she added pages. The third is why the screen closes,
and it is data-dependent, which is why it used to work. Only the fourth is a genuine
Lawnchair interaction, and it is inherited Launcher3 code rather than anything Lawnchair
wrote.

## Changes

### Simple — `MainActivity.kt`

Give the grid a weight so the button keeps its space:

```kotlin
PageList(Modifier.weight(1f))
```

with `PageList` taking a `modifier: Modifier = Modifier` and passing it to `LazyVerticalGrid`.
Replace the hardcoded `Color.Gray` on the "Page N" label with
`MaterialTheme.colorScheme.onSurfaceVariant`.

### Simple — `TextAppsWidgetConfigActivity.kt`

Replace the crashing truncation at `:200`:

```kotlin
val labels = d.joinToString { it.first }
val text = if (labels.length > 20) labels.take(20) + "…" else labels
```

Parse defensively in `onSave` (`:91-92`) with `toIntOrNull` and `toFloatOrNull`, and bail out
rather than crash if either is null. Add a `fontSizeErr` check alongside the existing
`fieldErr`, feed it to the font size field's `isError`, and include it in the Save button's
`enabled` condition so the invalid state cannot reach `onSave` at all. Convert the dropdown
width through `LocalDensity` instead of treating pixels as dp (`:197`). Drop the redundant
outer `MaterialTheme { }` at `:82`.

### Simple — `PreferencesManager.kt`

`removeWidget` writes `"Widget_hide_"` where every other call site uses `"widget_hide_"`, so
the hide-page preference is never removed and leaks to whatever widget next receives that
recycled id. Fix the capitalisation and remove the font size key too. Change `getNumber`'s
default from `1` to `0`, since page 0 is the one that exists when a single page has been
created.

### Simple — `res/values/themes.xml`, `res/values-night/themes.xml`

Per ADR 0001: night variant plus `android:forceDarkAllowed="false"` on both.

### Lawnchair — `src/com/android/launcher3/Launcher.java`

Per ADR 0002: clean up the pending widget in the `RESULT_CANCELED` branch of
`completeTwoStageWidgetDrop`, using `ModelWriter.deleteWidgetInfo`. Keep the change small and
self-contained enough to send upstream.

## Verification

There is no Android SDK in this environment, so nothing here can be compiled or run from the
session. Everything below has to run on a real device or emulator.

1. `./gradlew assembleDebug` in Simple. Catches the Kotlin-level mistakes, which is most of
   the risk in these edits.
2. Install and open Simple with four or more pages. The "New Page" button should be visible in
   portrait. Before the fix, rotating to landscape should make it reappear — that is the
   cheapest confirmation that the layout diagnosis is right, and it is worth doing first,
   before any code changes.
3. Create a page whose apps' names join to fewer than twenty characters, drop the widget, and
   tap "App Display Number". The dropdown should open instead of the screen closing.
4. Enter junk in the font size field. Save should be disabled rather than crashing.
5. On Lawnchair 15/16, drop any widget with a configuration screen and press back. No tile
   should be left behind. Repeat with Simple's screen to confirm the original report.
6. `adb logcat` during step 3 on the unfixed build, to capture the
   `StringIndexOutOfBoundsException` and confirm the diagnosis rather than assuming it.

## TODO

- [ ] Confirm the layout diagnosis by rotating to landscape on the unfixed build
- [ ] Capture a logcat stack trace from the configuration screen crash
- [ ] Get a screenshot of the black-on-black editor, plus the four observations in ADR 0001
- [ ] `MainActivity`: weight on the grid, themed grey label
- [ ] `TextAppsWidgetConfigActivity`: safe truncation, defensive parsing, font size validation, density conversion, drop the redundant theme wrapper
- [ ] `PreferencesManager`: key typo, font key removal, default page 0
- [ ] `themes.xml` plus a `values-night` variant, force-dark opt-out
- [ ] Lawnchair: cancel-path cleanup in `completeTwoStageWidgetDrop`
- [ ] Build both, install on her phone, walk through the verification steps
- [ ] File an upstream issue describing the root cause, citing #5124 and the retry commit
- [ ] Decide whether to send the Lawnchair fix upstream, and to which project

## Out of scope

`SelectAppActivity` returns to the main screen with
`context.startActivity(Intent(context, MainActivity::class.java))` rather than `finish()`,
which stacks a new activity on every save. Using `finish()` properly would mean making the
page list reactive, since `PageList` reads `DataManager.loadData()` once per composition and
has no way to refresh. That is a real bug but it is not one of the three she reported, and it
is a larger change than anything above.

`AppDropdownInput.kt` is dead code — defined, never called, and carrying the same pixel-as-dp
bug. Worth deleting, but not while we are trying to keep the diff reviewable against upstream.

## Follow-on: custom display names (ADR 0003)

Separate stream of work, but it has a hard ordering constraint against the fixes above. The
feature exists to make names shorter, and the configuration screen crashes on
`substring(0, 20)` exactly when a page's joined labels are shorter than twenty characters, so
the config screen fix has to land first or the feature makes an existing crash more likely.

The identity fix is worth pulling forward regardless of whether the feature ships. Comparing
`Pair(label, package)` instead of `package` at `SelectAppActivity.kt:95` means an app that
renames itself appears unticked when its page is reopened, and is then silently dropped on
save.

- [ ] Pin down what "group" means, then write the ADR for page identity and ordering
- [ ] Give pages stable identifiers and reference widgets by id, not by position
- [ ] Compare and select on package name alone, not on the whole pair
- [ ] Replace the delimited string with JSON under a new preferences key
- [ ] Migrate the legacy string on first read, leave the old key as a backup
- [ ] Store names and cached labels in maps keyed by package
- [ ] Resolve display names in `provideGlance`, refreshing the label cache
- [ ] Add the rename step after app selection, probably carrying ordering too
- [ ] Fix the format comment at `DataManager.kt:5`, which documents the fields in the wrong order
