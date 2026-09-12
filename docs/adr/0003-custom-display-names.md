# ADR 0003: Custom display names for apps

**Status:** Draft. Reframed by ADR 0004 — this is now a design input for a new app rather
than a change to an existing one, so the migration and legacy-format sections fall away.
The identity, naming and resolution decisions carry over unchanged.

## Context

Simple shows an app's system label, which is sometimes the wrong length for a screen whose
entire point is a short list of words. "Files by Google" is the example that prompted this;
"Files" is what belongs on the page. We want to let the user override the displayed name.

Today an entry is a `Pair<String, String>` of `(label, packageName)`, captured in
`SelectAppActivity` from `loadLabel(packageManager)` at the moment the app is ticked, and
persisted by `DataManager` into a single delimited string. So the model already stores a
per-entry display string, which makes this look like a small feature. It isn't, because two
things in the current code treat that string as something other than a display name.

### The stored label is part of the app's identity

`SelectAppActivity.kt:95` decides which checkboxes are ticked with:

```kotlin
selected[i] = data.contains(sortedPackages[i])
```

`data` holds `Pair(storedLabel, package)` and `sortedPackages[i]` is
`Pair(currentSystemLabel, package)`, so `List.contains` compares **both** halves through
`Pair.equals`. An entry is only recognised as selected while its stored label still matches
the system label exactly. Then `:148` saves `packages.filterIndexed { i, _ -> selected[i] }`,
which are the *current system labels* — so every edit-and-save round trip overwrites whatever
was stored.

A custom name would therefore fail twice: the renamed app would appear unticked when its page
is opened, and saving would write the system label back over the custom one. The feature is
not merely unsupported, the existing save path actively destroys it.

This is already a live bug without any feature work. When an app changes its own label —
Twitter to X is the obvious case — reopening that page shows it unticked and saving silently
drops it from the page.

### The storage format cannot hold arbitrary text

`DataManager.kt:15-18` parses with nothing escaped anywhere:

```kotlin
data.split("###").map { list ->
    list.split("++").map { pair ->
        val s = pair.split("|")
        Pair(s[0], s[1])
    }
}
```

A name containing `|` silently truncates the name and turns the remainder into the package. A
name containing `++` is worse: "C++" splits the entry in two, the fragment without a `|` hits
`s[1]`, and `loadData` throws `IndexOutOfBoundsException` on **every** subsequent load — the
app and the widget both stop working, and the user cannot get back in to undo it. `###`
splits pages the same way.

Today the label comes from the system, so this is rare. The moment a text field is pointed at
it, `|` and `++` become things a person can reasonably type.

(The comment at `DataManager.kt:5` documents the format as `package|label`; the code writes
`label|package`. Worth correcting while we are here.)

### Interaction with ADR 0002

The point of this feature is shorter names, and the configuration screen crashes on
`substring(0, 20)` precisely when a page's joined labels are *shorter* than twenty characters.
Shipping this before that fix would make an existing crash strictly more likely.

## Decision

Identity is the package name and nothing else.

Custom names are stored once per package, not once per entry, so a rename applies everywhere
the app appears. That includes pages it is added to *later*, which per-entry storage cannot do
without copying a name forward at creation time.

Storing names per package normalises the document. A page becomes an ordered list of package
names, and names and cached labels become maps keyed by package:

```json
{
  "version": 2,
  "names":  { "com.google.android.apps.nbu.files": "Files" },
  "labels": { "com.google.android.apps.nbu.files": "Files by Google" },
  "pages":  [ ["com.google.android.apps.nbu.files", "com.android.chrome"] ]
}
```

The displayed name is `names[package] ?: resolve(package) ?: labels[package]`. Resolve in
`provideGlance` rather than inside the composable, since it already has a `Context` and runs
off the main thread, and refresh `labels` whenever resolution succeeds.

Persist as JSON under a new preferences key, using `org.json` from the platform. On first read,
migrate the legacy delimited string into the new key and leave the old key in place, untouched,
as a one-time backup.

The shape of `pages` above is provisional. Pages are currently referenced by position, which is
broken today and is being decided separately — see open questions.

Put the renaming UI in a second step after app selection: "Save" moves from the checkbox list
to a short screen listing only the chosen apps, each with an editable name field prefilled with
the current display name. An empty field means "use the system label", which is also how a
custom name is reset.

## Alternatives considered

**Just let the user edit the label already stored.** The smallest possible diff, and tempting
because the field exists. Rejected because it collapses "the name the user chose" and "the
name the system reported when you ticked the box" into one string, which makes it impossible
to ever refresh a stale label or to offer a reset. It also still requires the identity fix, so
it does not actually save the work it appears to save.

**Store the custom name on each entry rather than once per package.** This is where the data
already lives, needs no second structure, and allows a page-specific name. Rejected once
renaming was settled as applying everywhere: per-entry storage can propagate an edit across
existing entries, but an app added to a new page afterwards would silently revert to its system
label, because nothing carries the name forward. Fixing that means looking up the name by
package at creation time, which is the package-keyed map with extra steps.

**Escape the delimiters and keep the existing format.** Cheaper than a format change and keeps
the data human-readable in `adb shell dumpsys`. Rejected because hand-rolled escaping is
exactly the kind of code that is subtly wrong for years, and because we would still have no
clean way to add the third field.

**`kotlinx.serialization` instead of `org.json`.** Nicer types and less boilerplate, at the
cost of a new Gradle plugin and runtime dependency for what is about fifty lines of parsing in
an app with nine source files. Not worth it. Room or DataStore are further past the point.

**Rename inline in the app-selection list.** One screen instead of two. Rejected because that
list is already a hundred-plus rows of checkbox and label, an edit affordance on every row
would dominate it, and — the deciding argument — that is the exact screen whose black-on-black
rendering we cannot yet explain (ADR 0001). Adding complexity to a screen we do not currently
understand is a bad trade.

**Rename by tapping a name on the main screen's page card.** Fewest screens, and the name is
edited where it is displayed. Rejected on discoverability: nothing indicates the text is
editable, and the cards are already small.

## Consequences

Fixing identity to compare on package alone is a prerequisite, and it fixes the live
disappearing-app bug described above as a side effect. That fix is worth landing on its own
even if this feature is dropped.

Names become self-healing. An app that renames itself is picked up on the next widget update
instead of showing a label frozen at selection time, and a user who wants the old name can
pin it as a custom name.

The legacy key stops being updated after migration, so a downgrade to the current release
would show data frozen at the migration point rather than nothing. That seems the right
trade against dual-writing two formats and letting them drift.

The widget gains a `PackageManager` lookup per app on each update. For ten entries on a page
this is not a concern, but it does mean widget rendering now depends on package state.

## Open questions

**How pages are identified and ordered.** A separate request landed while this was in draft:
named groups of apps *inside* a page, with the groups reorderable and the apps reorderable
within them. That is a third level in the model — page, group, app — and it changes what the
widget renders, since group titles become headers between blocks of app names. It needs its own
ADR.

It also collides with the fact that pages are referenced by position:
`PreferencesManager` stores a page index per widget id, and `DataManager.deleteEntry` removes
by position, so deleting a page already repoints every widget after it. Reordering would do the
same. Pages almost certainly need stable identifiers, and that belongs in the same migration as
this one rather than a second one later. It needs its own ADR once "group" is pinned down.

**Where ordering lives in the UI.** The rename step is the obvious home for reordering too —
it already shows exactly the chosen apps in a short list. Worth confirming before either is
built, since it means the second step is doing two jobs.

**What should an uninstalled app show?** The fallback chain ends at `cachedLabel`, so it keeps
its name and does nothing when tapped, which matches today's behaviour. Showing it as missing,
or filtering it out, are both defensible and neither is obviously better.

**Should the name field show the system label as a placeholder or as prefilled text?** A
placeholder makes "empty means system label" obvious; prefilled text makes editing a long name
into a short one easier, which is the actual use case.
