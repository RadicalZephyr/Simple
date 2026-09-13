# Upstream PR draft: Lawnchair

Open from `RadicalZephyr/lawnchair`, branch `claude/simple-widget-lawnchair-issues-1lo0n2`,
against `LawnchairLauncher/lawnchair` `16-dev`. The same patch applies to `15-dev`, which has
the same flow and the same cancel branch — worth offering if they want it there too.

Headings below match `.github/pull_request_template.md`.

---

### Description

`completeTwoStageWidgetDrop` now removes the pending widget when its configuration activity is
cancelled, instead of only freeing the app widget id. Without this, a cancelled configuration
leaves a dead tile on the home screen bound to an id that no longer exists.

Fixes #(issue)

### Reasoning

With `FLAG_ENABLE_ADD_APP_WIDGET_VIA_CONFIG_ACTIVITY_V2`, `addAppWidgetImpl` no longer returns
early when a configuration activity starts. It calls `completeAddAppWidget` with
`showPendingWidget = true`, which writes the item to the database with `FLAG_UI_NOT_READY` and
adds a `PendingAppWidgetHostView` to the workspace so the widget shows immediately with a
preview.

The `RESULT_CANCELED` path was not updated to match. It called
`mAppWidgetHolder.deleteAppWidgetId(appWidgetId)` and stopped, leaving the view and the row in
place. The tile survives until the next full reload, where `WidgetInflater` returns
`TYPE_DELETE` because the id no longer resolves, and until then anything touching that id fails
with "Bad widget id".

The fix uses `ModelWriter.deleteWidgetInfo`, which already exists for exactly this pairing — it
deletes the row and the widget id together and notifies the model callbacks. Paths that never
added a pending view fall through to the previous behaviour unchanged, which covers the pre-V2
flow, `REQUEST_BIND_APPWIDGET` cancellations where nothing was added yet, and the case where the
view has already gone.

The flow is AOSP's and unfixed upstream — `main`, `android16-release`,
`android16-qpr1-release` and `android16-qpr2-release` all carry the same two-line
`RESULT_CANCELED` branch, with no `deleteWidgetInfo` or `removeWorkspaceItem` anywhere in
`completeTwoStageWidgetDrop`. It does not reproduce on a Pixel 6a running Android 16, though,
which suggests Google ships the flag disabled; Lawnchair sets it `true` in `FeatureFlagsImpl`,
which is why this reaches Lawnchair users and not stock ones.

Setting the flag `false` would therefore also fix it today, and that is a fair thing to prefer.
It gives up the feature the flag exists for, and it expires: on `android16-qpr2-release` the
flag and its guard are already gone and the behaviour is unconditional, so at that merge there
will be nothing left to switch off.

This does not replace the existing workarounds in `LauncherWidgetHolder.startConfigActivity`
(`aa8dd44`, `e4d8d3c`). Those still need to handle stale rows already present on users' home
screens; this stops new ones being created.

### Testing

1. Long press the home screen, open the widget picker, and drag any widget with a configuration
   activity onto the home screen.
2. Press back at the configuration screen.
3. Before: a tile remains, and the reconfigure option on it fails with "Bad widget id".
   After: the home screen is unchanged, as it was before the V2 flow.
4. Repeat, completing the configuration normally, and confirm the widget is still added and
   resized correctly.
5. Repeat with a widget that has no configuration activity, to confirm the untouched path.
6. Worth also exercising the bind-permission path (`REQUEST_BIND_APPWIDGET` cancelled), which
   takes the fall-through branch.

### Type of change

:white_check_mark: **Bug fix** (A non-breaking change that fixes an issue)
