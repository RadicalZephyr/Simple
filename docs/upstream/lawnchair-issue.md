# Upstream issue draft: Lawnchair

Open at https://github.com/LawnchairLauncher/lawnchair/issues/new/choose and pick the bug
report form. Headings below match its fields.

Before posting, reproduce it once. Drag any widget with a configuration activity onto the home
screen, press back at the setup screen, and check whether a tile is left behind. The code
analysis below is verified against AOSP and two forks, but nobody has watched the plain
back-button case happen, and that is a thirty-second check that makes the report unarguable.

---

## Title

```
Cancelled widget configuration leaves an orphaned widget ("Bad widget id")
```

Alternative, if a plainer one reads better:

```
Widget stays on the home screen after its configuration screen is cancelled
```

---

## Describe the bug

Cancelling a widget's configuration activity leaves a dead widget on the home screen — a tile
bound to an app widget id that has already been freed. It survives until the next full model
reload, and anything that touches that id in the meantime fails with
`java.lang.IllegalArgumentException: Bad widget id`.

This is not an exotic path. It happens with any widget that has a configuration activity, and
pressing back at the setup screen is enough. It is easiest to hit with a widget whose
configuration activity crashes, because then the user never gets the chance to complete it.

The cause is a gap between the two halves of the
`FLAG_ENABLE_ADD_APP_WIDGET_VIA_CONFIG_ACTIVITY_V2` flow.

`Launcher.addAppWidgetImpl` no longer returns early when a configuration activity starts. It
calls `completeAddAppWidget(..., showPendingWidget = true, ...)`, which writes the item to the
database with `restoreStatus = FLAG_UI_NOT_READY` and adds a `PendingAppWidgetHostView` to the
workspace, so the widget shows immediately with a preview.

The `RESULT_CANCELED` branch of `completeTwoStageWidgetDrop` was not updated to match:

```java
} else if (resultCode == RESULT_CANCELED) {
    mAppWidgetHolder.deleteAppWidgetId(appWidgetId);
    animationType = Workspace.CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION;
}
```

It frees the id and stops. The view and the database row both survive. `addAppWidgetImpl` has
also already called `getDragLayer().clearAnimatedView()` by this point, so
`mDragLayer.getAnimatedView()` is null and the cancel animation's completion callback never runs
either.

The row does eventually go: on the next full reload `WidgetInflater.inflateAppWidget` cannot
resolve the id and returns `TYPE_DELETE`. That matches the reports of widgets that vanish some
time after being placed.

`14-dev` is not affected — there `addAppWidgetImpl` returns early whenever a configuration
activity starts, so nothing is added until configuration succeeds. `15-dev` and `16-dev` both
carry the new flow with the flag defaulted to `true`.

## Steps to reproduce

1. Long press the home screen and open the widget picker.
2. Drag any widget that has a configuration activity onto the home screen.
3. Press back at the configuration screen instead of completing it.
4. A tile for the widget is left on the home screen.
5. Long press it and choose reconfigure — this fails with "Bad widget id".
6. Restart the launcher. The tile is gone.

## Expected behavior

Cancelling configuration leaves the home screen exactly as it was before the widget was
dropped, which is what happened before the V2 flow.

## Device information

Not device-specific — it is in the add-widget flow rather than in anything a device does
differently. *(Fill in your device and Android version anyway, since the form asks.)*

## App version

*(Your Lawnchair version.)*

## Additional context

This has been reported several times already, but always from the far end — as widgets that
vanish, or as "Bad widget id" crashes — and fixed each time at the point of failure rather than
the point of creation:

* #5124, closed by `aa8dd44`, which catches `IllegalArgumentException` in
  `LauncherWidgetHolder.startConfigActivity` and reallocates the id.
* `e4d8d3c` then had to cap that reallocate-and-retry loop at three attempts, closing #5765,
  #5764, #5534, #5505 and #4533.

Both are worth keeping — stale rows already exist on people's home screens and still need
handling. But nothing currently removes the row at the moment configuration is abandoned, which
is why they keep appearing.

This is not a Lawnchair regression. It is AOSP's, and it is unfixed everywhere I could look.
I checked `Launcher.java` in AOSP Launcher3 directly:

| Branch | V2 flag | add-before-config | `RESULT_CANCELED` branch |
| --- | --- | --- | --- |
| `main` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-release` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-qpr1-release` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-qpr2-release` | **gone** | **unconditional** | `deleteAppWidgetId` only |

Android 15 is affected too — LineageOS's Trebuchet at `lineage-22.2` carries the same code.

The `android16-qpr2-release` row is the one worth pausing on. The flag has been finalised and
removed, but the code it gated has not: the comment in `addAppWidgetImpl` still names
`FLAG_ENABLE_ADD_APP_WIDGET_VIA_CONFIG_ACTIVITY_V2`, `showPendingWidget` is still there, and the
early return has simply been deleted, so `completeAddAppWidget(..., needsConfigure(), ...)` now
runs unconditionally. Adding the widget before configuration completes is permanent on that
branch, there is no longer a flag to turn it off, and the cancel path is still the same two
lines.

So waiting for AOSP is not a plan here. In `completeTwoStageWidgetDrop`, no branch I checked
contains `deleteWidgetInfo` or `removeWorkspaceItem` at all.

A patch is attached as a pull request.
