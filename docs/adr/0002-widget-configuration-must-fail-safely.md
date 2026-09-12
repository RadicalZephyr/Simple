# ADR 0002: Widget configuration must fail safely

**Status:** Draft. Superseded in part by ADR 0004 — the Simple half no longer applies,
since we are not continuing on that codebase. The Lawnchair half stands.

## Context

Dropping the Simple widget onto a Lawnchair home screen opens the widget configuration
screen. Tapping the "App Display Number" field closes that screen immediately and leaves a
dead widget tile on the home screen. The same app worked under Nova.

Two separate bugs combine to produce this, one in each project.

### The crash is ours

`TextAppsWidgetConfigActivity.kt:200` builds each dropdown entry like this:

```kotlin
val text = d.joinToString { it.first }.substring(0, 20) + "..."
```

Tapping the field fires `onFocusEvent` (`:181`), which sets `expanded = true`, which composes
the `DropdownMenu` items. For any page whose joined app labels are shorter than twenty
characters, `substring(0, 20)` throws `StringIndexOutOfBoundsException` and the process dies.
`"Signal, Camera"` is fourteen characters. The dropdown arrow crashes identically.

This is entirely launcher-independent, and it is data-dependent, which is why the screen used
to work: it only crashes once at least one page's label string is short enough. The same
screen has two more failure modes of the same shape. `fontSizeText.toFloat()` at `:92` runs
inside `lifecycleScope.launch` and is guarded only by the *number* field's validation, so a
non-numeric font size crashes on save, and the surrounding `try` block only covers the widget
update that follows it. The dropdown's `Modifier.width(textFieldSize.width.dp)` at `:197`
treats a pixel measurement as dp.

### The wreckage left behind is Lawnchair's

Lawnchair 15 and 16 carry a Launcher3 flow that Lawnchair 14 and Nova do not. With
`enableAddAppWidgetViaConfigActivityV2()` — hardcoded `true` in Lawnchair's `FeatureFlagsImpl`
— `Launcher.addAppWidgetImpl` (`Launcher.java:1861`) writes the widget into the workspace
database as a `PendingAppWidgetHostView` *before* the configuration activity finishes
(`Launcher.java:1533`). The cancel path at `Launcher.java:996` then does only this:

```java
} else if (resultCode == RESULT_CANCELED) {
    mAppWidgetHolder.deleteAppWidgetId(appWidgetId);
    animationType = Workspace.CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION;
}
```

It frees the widget id but never removes the view or the database row, and because
`addAppWidgetImpl` already cleared the drag layer's animated view, the cancel animation's
completion callback never runs either. What remains is a tile bound to a widget id that no
longer exists. It survives until the next full model reload, at which point `WidgetInflater`
returns `TYPE_DELETE` because the id no longer resolves — which is why it can disappear on its
own after a launcher restart.

Lawnchair 14's `addAppWidgetImpl` returns early when a configuration activity starts, so
nothing is added and a crash leaves no trace. That is the whole of the Nova-versus-Lawnchair
difference. Nothing here is specific to Lawnchair's own code; it inherited a new upstream
flow that assumes configuration activities exit cleanly.

Note that this affects every widget whose configuration is cancelled, not just ours. Backing
out of any widget's configuration screen leaves the same dead tile.

### Prior art in Lawnchair

This has been reported repeatedly, but always from the far end of the failure, and it has
never been fixed at the source.

Issue #5124 (Dec 2024, Lawnchair 15 Nightly on a OnePlus Nord 3) is the clearest example:
"place a widget, widget disappears", and tapping the reconfigure pencil crashes with
`java.lang.IllegalArgumentException: Bad widget id 2367`. That is exactly what a workspace row
that has outlived its widget id produces. It was closed the next day by commit `aa8dd44`,
which did two things: delete the widget id in `completeAddAppWidget` when the provider info is
null, and catch `IllegalArgumentException` in `LauncherWidgetHolder.startConfigActivity`,
adding `handleInvalidWidgetId` to delete the stale id, allocate a new one, and restart
configuration.

That was not enough. Commit `e4d8d3c` (Sep 2025) added a retry counter capping the
reallocate-and-restart loop at three attempts, and closed five more issues doing it: #5765,
#5764, #5534, #5505 and #4533. A workaround that needs a loop limit is a workaround that is
firing often.

`handleInvalidWidgetId` is present in 15-dev and 16-dev but not in 14-dev, so it arrived
alongside the V2 flow rather than before it.

Two other repairs in this area are unrelated to ours and worth not confusing with it. PR #6408
(merged Feb 2026) fixed configuration activities failing to launch at all under Android 14+
background-activity-launch hardening. Issue #6208, still open, reports configurable widgets
failing to place on a Pixel 9a running Android 16 unless "Remove animations" is enabled.

So six or more issues have been closed by catching the symptom at `startConfigActivity` —
reallocate the id, retry, then cap the retries — and none by removing the row that goes stale
in the first place. I could not find any issue, open or closed, that describes the dead tile
itself. Users report what they see later: the widget vanished, or the launcher crashed with
"Bad widget id".

## Decision

Fix both halves.

In Simple, treat the configuration screen as untrusted input handling. Truncate safely rather
than with `substring`, parse with `toIntOrNull`/`toFloatOrNull`, validate the font size field
the same way the number field is already validated, and convert the dropdown width through
`LocalDensity`.

In Lawnchair, make the cancel path clean up what the V2 flow added. `ModelWriter` already has
the right method for this — `deleteWidgetInfo(info, holder, reason)` deletes the widget id and
the database row together and notifies the model callbacks:

```java
} else if (resultCode == RESULT_CANCELED) {
    LauncherAppWidgetHostView pendingView = enableAddAppWidgetViaConfigActivityV2()
            ? mWorkspace.getWidgetForAppWidgetId(appWidgetId) : null;
    if (pendingView != null && pendingView.getTag() instanceof LauncherAppWidgetInfo info) {
        mWorkspace.removeWorkspaceItem(pendingView);
        getModelWriter().deleteWidgetInfo(info, mAppWidgetHolder, "widget config cancelled");
    } else {
        mAppWidgetHolder.deleteAppWidgetId(appWidgetId);
    }
    animationType = Workspace.CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION;
}
```

The `else` branch preserves the existing behavior for every path that did not add a pending
view, including Lawnchair 14-style flows and the case where the view has already gone.

## Alternatives considered

**Fix only Simple.** The crash is the cause and the launcher bug is only the amplifier, so
fixing Simple alone does restore her widget. Rejected because the launcher bug is real,
affects every widget that cancels configuration, and is small enough to be worth carrying.

**Turn `enableAddAppWidgetViaConfigActivityV2` off in Lawnchair's `FeatureFlagsImpl`.** One
line, reverts to the 14 behavior, no ghost widgets. Rejected: it throws away a deliberate
upstream feature (the widget appears immediately with a preview instead of after
configuration) to work around a missing cleanup, and it would silently diverge from upstream
in a way that is easy to lose on the next merge.

**Make the model loader clean up eagerly instead of fixing the cancel path.** Rejected as
treating the symptom in the wrong layer. The loader already deletes these rows on the next
reload; the problem is the window before that, and only the cancel path knows the
configuration was abandoned.

## Consequences

Cancelling or crashing a widget configuration leaves no tile behind, which is the behavior
users had before the V2 flow landed.

The Lawnchair change is a divergence from upstream Launcher3 and will need to be carried
across merges until it is upstreamed. It belongs upstream — the bug is in inherited AOSP code,
not in Lawnchair's own — so the fix should be written so it can be sent there unchanged, and
its rationale kept in the commit message rather than in a doc file that upstream would not
want.

Anyone running a stock Lawnchair 15 or 16 build still needs the Simple fix. The Lawnchair
patch only stops a crashing configuration screen from leaving debris; it does not stop the
crash.

## Open questions

None outstanding on provenance. AOSP Launcher3 was checked directly: `main`,
`android16-release`, `android16-qpr1-release` and `android16-qpr2-release` all carry the same
unfixed `RESULT_CANCELED` branch, and Android 15 does too by way of LineageOS's Trebuchet at
`lineage-22.2`. On `android16-qpr2-release` the flag has been finalised and removed while the
code it gated remains, so add-before-config is unconditional there and cannot be switched off.
Porting an upstream fix is not an option because there is not one.
