# Upstream issue draft: Lawnchair

Paste into the bug report form at
https://github.com/LawnchairLauncher/lawnchair/issues/new/choose. Field headings below match
the form. Fill in the device and version fields from her phone before submitting.

---

## Describe the bug

Cancelling a widget's configuration activity leaves a dead widget on the home screen. The tile
stays where it was dropped, bound to an app widget id that has already been freed, until the
next full model reload removes it. Anything that touches that id in the meantime fails with
`java.lang.IllegalArgumentException: Bad widget id`.

This affects any widget with a configuration activity, including simply pressing back at the
setup screen. It is easiest to hit with a widget whose configuration activity crashes, because
then the user never gets a chance to complete it.

The cause looks like a gap between two halves of the
`FLAG_ENABLE_ADD_APP_WIDGET_VIA_CONFIG_ACTIVITY_V2` flow.

`Launcher.addAppWidgetImpl` no longer returns early when a configuration activity starts. It
calls `completeAddAppWidget(..., showPendingWidget = true, ...)`, which writes the item to the
database with `restoreStatus = FLAG_UI_NOT_READY` and adds a `PendingAppWidgetHostView` to the
workspace, so the widget appears immediately with a preview.

The `RESULT_CANCELED` branch of `completeTwoStageWidgetDrop` was not updated to match:

```java
} else if (resultCode == RESULT_CANCELED) {
    mAppWidgetHolder.deleteAppWidgetId(appWidgetId);
    animationType = Workspace.CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION;
}
```

It frees the id and nothing else. The view and the database row survive. `addAppWidgetImpl` has
also already called `getDragLayer().clearAnimatedView()`, so `mDragLayer.getAnimatedView()` is
null by this point and even the cancel animation's completion callback does not run.

The row is eventually removed: on the next full reload `WidgetInflater.inflateAppWidget` cannot
resolve the id, so it returns `TYPE_DELETE`. That is consistent with reports of widgets that
"disappear" some time after being placed.

This is inherited AOSP Launcher3 code rather than anything specific to Lawnchair. 14-dev is not
affected, because there `addAppWidgetImpl` returns early whenever a configuration activity
starts, so nothing is added until the configuration succeeds.

## Steps to reproduce

1. Long press the home screen and open the widget picker.
2. Drag any widget that has a configuration activity onto the home screen.
3. When the configuration screen opens, press back instead of completing it.
4. A tile for the widget remains on the home screen.
5. Long press it and choose the reconfigure (pencil) option — this fails with "Bad widget id".
6. Restart the launcher. The tile is gone.

## Expected behavior

Cancelling the configuration should leave the home screen exactly as it was before the widget
was dropped, which is what happened before the V2 flow.

## Additional context

This has been reported several times from the far end, as widgets that vanish or as "Bad widget
id" crashes, and fixed each time at the point of failure rather than the point of creation:

* #5124, closed by `aa8dd44`, which catches `IllegalArgumentException` in
  `LauncherWidgetHolder.startConfigActivity` and reallocates the id.
* `e4d8d3c` then had to cap that reallocate-and-retry loop at three attempts, closing #5765,
  #5764, #5534, #5505 and #4533.

Those workarounds are still worth keeping, since stale rows already exist on users' home
screens. But nothing currently removes the row at the moment the configuration is abandoned.

A patch is attached as a pull request.
