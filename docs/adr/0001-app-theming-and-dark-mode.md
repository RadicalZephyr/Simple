# ADR 0001: App theming and dark mode

**Status:** Draft

## Context

Simple declares a single platform theme in `app/src/main/res/values/themes.xml`:

```xml
<style name="Theme.Simple" parent="android:Theme.Material.Light.NoActionBar" />
```

That is a light-only theme with no `values-night` variant, so in system dark mode the
platform still treats every Simple activity as a light app. Meanwhile the Compose layer
decides light or dark for itself: `SimpleTheme` calls `isSystemInDarkTheme()` and applies
`dynamicDarkColorScheme` or `dynamicLightColorScheme` accordingly. The two halves of the app
therefore disagree about what mode the app is in.

Two consequences follow. The window background stays light while Compose paints dark, which
is visible at least during activity transitions. More importantly, an app that declares a
light theme and does not opt out is eligible for the system's force-dark pass. Compose
excludes its own view from that pass (`AndroidComposeView` calls `Api29Impl.disallowForceDark`
on itself), so on stock Android the Compose content should survive intact, but several OEM
skins implement their own darkening that is less careful.

This ADR exists because of a reported bug: the app-selection editor renders as black text on
a black background on a Pixel in dark mode. I have not been able to derive that from the
source. `Scaffold` always supplies a matched `background`/`onBackground` pair, and I checked
the real androidx sources rather than trusting memory: `ColorScheme.contentColorFor` maps
every surface role including the `surfaceContainer*` variants, so there is no
`Color.Unspecified` fallback turning text black. The mechanism is still unknown, so the
change below is hardening rather than a confirmed fix.

There is also a second, unrelated theming smell. `TextAppsWidgetConfigActivity` wraps its
content in a bare `MaterialTheme { }` and then `ConfigurationScreen` wraps its own content in
`SimpleTheme { }`. The outer wrapper contributes nothing except a default light color scheme
that is immediately replaced.

## Decision

Make the platform theme follow the system night mode explicitly, and opt out of force-dark
since Compose owns the app's colors:

```xml
<!-- values/themes.xml -->
<style name="Theme.Simple" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:forceDarkAllowed">false</item>
</style>

<!-- values-night/themes.xml -->
<style name="Theme.Simple" parent="android:Theme.Material.NoActionBar">
    <item name="android:forceDarkAllowed">false</item>
</style>
```

Alongside that, replace the hardcoded `Color.Gray` labels in `MainActivity` and
`SelectAppActivity` with `MaterialTheme.colorScheme.onSurfaceVariant`, and drop the redundant
outer `MaterialTheme { }` in `TextAppsWidgetConfigActivity`.

Keep dynamic color on. There is no evidence that Material You is the problem, and turning it
off would change the app's appearance for everyone to fix a bug we have not yet located.

## Alternatives considered

**Use `android:Theme.DeviceDefault.DayNight.NoActionBar` instead of a `values-night`
resource.** Fewer files, but `DayNight` is OEM-skinned and I am not confident the
`.NoActionBar` variant is available as a public platform style on every device. An explicit
`values-night` resource compiles predictably and expresses the same intent.

**Set `forceDarkAllowed=false` and leave the light-only parent.** This addresses force-dark
but leaves the window background light in dark mode, so transitions still flash. Half a fix.

**Drop dynamic color and ship the static `LightColorScheme`/`DarkColorScheme` already defined
in `ui/theme/Theme.kt`.** This would make the palette identical on every device and would
rule out Material You as a cause in one step. Rejected for now as a diagnostic disguised as a
product change; if the open question below resolves against dynamic color, revisit.

## Consequences

In dark mode the app becomes dark end to end rather than dark Compose content inside a light
window, which is the correct behavior regardless of whether it fixes the reported bug.
Force-dark can no longer touch any part of the app on any device.

The reported black-on-black bug may survive this change. That is an accepted risk: the change
is worth making on its own merits, and it removes several candidate causes so that whatever
remains is easier to isolate.

## Open questions

The following observations would identify the mechanism, and the answers should be recorded
here when we have them:

1. On the main screen, are the app names inside each page card readable, or only the grey
   "Page N" labels? If the app names are invisible but the grey labels are not, the problem is
   content-color resolution rather than the background.
2. In the editor, are the checkboxes and the "Save N apps" button visible while the labels are
   not? Controls draw from `primary`, labels from `LocalContentColor`; if the controls are fine
   the two are being resolved from different schemes.
3. Does turning off system dark mode make the screen readable?
4. Does a screenshot show text that is absent, or text that is present at very low contrast?
   "Extra dim" and similar accessibility overlays produce the second.
