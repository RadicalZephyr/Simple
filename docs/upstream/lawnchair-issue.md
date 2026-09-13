# Upstream issue draft: Lawnchair

Open at https://github.com/LawnchairLauncher/lawnchair/issues/new/choose and pick the bug
report form. Headings below match its fields.

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

Reproduced on Lawnchair 15 Beta 3, Samsung Galaxy A13 5G, using the Google Calendar "full
calendar view" widget, which has a configuration activity.

1. Long press the home screen and open the widget picker.
2. Drag a widget that has a configuration activity onto the home screen.
3. At the configuration screen, press back — or press its own Cancel button. Both do the same
   thing.
4. A tile is left on the home screen: a gear icon in the top left, the app's icon in the
   middle, and the text "Tap to finish setup".
5. Tap it. Nothing happens.
6. Restart the launcher. The tile is gone.

Completing the configuration normally gives a working widget, so the failure is specific to
cancelling.

The tile in step 4 is a `PendingAppWidgetHostView` in `FLAG_UI_NOT_READY` — "Tap to finish
setup" is `gadget_complete_setup_text`, drawn at `PendingAppWidgetHostView.java:128`.

## Expected behavior

Cancelling configuration leaves the home screen exactly as it was before the widget was
dropped, which is what happened before the V2 flow.

### Why tapping it does nothing

Worth spelling out, because it explains why this is not being reported as a crash any more.

`ItemClickHandler.onClickPendingWidget` finds the tile ready for setup, sees that
`FLAG_ID_NOT_VALID` is not set, and calls
`addFlowHandler.startConfigActivity(launcher, info, REQUEST_RECONFIGURE_APPWIDGET)` with the
widget id that was already freed. That is the "Bad widget id" `IllegalArgumentException` — but
`LauncherWidgetHolder.startConfigActivity` now catches it, `handleInvalidWidgetId` frees the id
again and allocates a fresh one, and the retry runs against an id that was never bound to a
provider, so it throws again until the retry cap stops it.

So the workarounds are doing their job: the crash is gone. What is left in its place is a tile
that looks actionable, says "Tap to finish setup", and silently does nothing — which is a worse
bug to diagnose and an easier one to ignore.

### Valid pending widget vs orphaned pending widget

These look identical and behave completely differently, which is worth knowing before testing.

The Clock widget does offer a pencil edit action on long press. On a correctly configured widget
it works. On one of these orphans it does nothing — same inert path as tapping the tile, for the
same reason. (The Calendar widget offers no edit action at all, so it is a poor choice for
testing that part.)

For contrast: on the Pixel, Anki produced a tile that also said it needed setup, and there the
reconfigure flow worked and completed it properly. That tile had a widget id that was still
bound. The difference is not that a widget is pending — pending is a legitimate state — it is
that these orphans hold an id that has been freed underneath them.

### Two other observations

Deleting the tile by long press works normally, so nobody is stuck — which probably explains
why this gets shrugged off rather than reported.

Adding the same widget again does **not** recover the orphan, though it can look like it does.
With the Calendar widget the newly configured widget appeared where the broken tile had been and
seemed to replace it — it is large enough that two will not fit on a page, and being transparent,
the dead one is visible straight through the working one once you look for it. Repeating it with the Clock widget showed what is really happening: the new
tile is placed over the old one, and once configuration finishes the orphan slides out from
underneath and stays on the home screen. So you end up with a working widget and the dead tile
still there, just moved.

That is worth noting in its own right — the orphan does not hold its cell against a later drop.
Whatever bookkeeping the workspace does about occupancy, a row that exists and a view that is
present are not stopping something else being placed on top of them.

## Device information

Samsung Galaxy A13 5G, with Lawnchair as the default launcher. Not device-specific — the fault
is in the add-widget flow rather than in anything a device does differently. It does not
reproduce under Pixel Launcher on a Pixel 6a, Android 16, build `CP1A.260405.005`; see
Additional context.

## App version

Lawnchair 15 Beta 3.

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

### Why this happens in Lawnchair and not on a Pixel

The code is AOSP's, and it is unfixed on every branch I could read:

| Branch | V2 flag | add-before-config | `RESULT_CANCELED` branch |
| --- | --- | --- | --- |
| `main` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-release` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-qpr1-release` | present | behind the flag | `deleteAppWidgetId` only |
| `android16-qpr2-release` | **gone** | **unconditional** | `deleteAppWidgetId` only |

No branch has `deleteWidgetInfo` or `removeWorkspaceItem` anywhere in
`completeTwoStageWidgetDrop`. LineageOS's Trebuchet at `lineage-22.2` carries the same code, so
Android 15 is in the same position.

But it does not reproduce on stock. On a Pixel 6a running Android 16, build
`CP1A.260405.005`, backing out of a configuration activity leaves nothing behind — which is the pre-V2 behaviour, and the
most likely explanation is that Google ships
`enable_add_app_widget_via_config_activity_v2` disabled. The flag's value lives in release
configuration rather than in the Launcher3 tree, so I could not read it directly.

Lawnchair sets it to `true` in its own `FeatureFlagsImpl`. That is the difference: the fault is
in AOSP's code, but Lawnchair is the one enabling the path that reaches it.

Which means there is a cheaper fix than the attached patch, and it should be said plainly:
**setting the flag to `false` would make this go away today.** Two reasons I would not stop
there. It gives up the feature the flag exists for — the widget appearing immediately with a
preview instead of after configuration. And it has an expiry date: on `android16-qpr2-release`
the flag and its guard are already gone, the comment in `addAppWidgetImpl` still names a flag
that no longer exists, and `completeAddAppWidget(..., needsConfigure(), ...)` runs
unconditionally. Whenever Lawnchair merges that Launcher3, there will be no flag left to turn
off.

One thing I cannot square, and would rather state than paper over. That Pixel build is dated
April 2026, which ought to put it past the point where `android16-qpr2-release` removed the flag
— and it still does not reproduce. So either Pixel Launcher does not track that branch here, or
it carries something AOSP does not; its source is not something I can read. That does not change
what happens in Lawnchair, but it does mean I am not claiming to know what stock devices will do
after a future merge.

So the choice is between a stopgap that stops working at the next merge, and removing the row at
the moment configuration is abandoned. I would rather do the second, but the first is a
legitimate call and I would rather raise it than have you find it.

A patch is attached as a pull request.
