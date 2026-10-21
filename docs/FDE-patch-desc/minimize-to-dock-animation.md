# Minimize-to-dock animation

Freeform windows minimize into their dock icon with a mesh-warped "genie"
animation. The animation is used both when the title bar minimize button is clicked
and when the dock icon of a running window is clicked.

## Behaviour

- The task window is captured once at the start of the minimize transition.
- The snapshot is drawn into a temporary SurfaceControl layer using
  `Canvas.drawBitmapMesh`.
- The bottom rows of the mesh lead towards the icon while the top rows lag, so the
  window bends into a funnel instead of uniformly shrinking.
- The window converges to a small region at the dock's top edge, above the icon
  center, so the final part of the tail remains visible.
- The snapshot stays opaque until the very end and then fades out quickly.
- If the snapshot or the temporary layer cannot be created (secure content, capture
  failure, missing dock bounds, fullscreen task), the regular minimize animation is
  used instead.
- Minimizing the last visible freeform task also uses the mesh animation when its
  dock target is known; otherwise the existing desktop exit animation is kept.

## Framework side (`frameworks/base`)

- `MinimizeAnimationTargets` (`WindowManager/Shell/shared`) caches dock icon bounds
  per display, package and task id. A one-shot target can also be supplied for a
  specific task.
- `DesktopTasksController` receives the bounds through a permission-protected
  broadcast from the taskbar plugin:
  - `com.android.wm.shell.action.UPDATE_DOCK_ICON_BOUNDS`
  - `com.android.wm.shell.action.SET_MINIMIZE_TARGET`
- `GenieMinimizeAnimator` (`WindowManager/Shell/desktopmode`) captures the task
  surface, creates a render layer parented to the task display area, draws the mesh
  every frame, and cleans up the layer and snapshot when the animation finishes.
- `DesktopMinimizationTransitionHandler` selects the configured animation only
  for `TRANSIT_MINIMIZE` / task-limit minimize of freeform tasks. Close and
  back-navigation transitions keep the regular animation.
- `MinimizeAnimator` provides the affine dock animation (mode `1`) and remains
  the fallback when the mesh snapshot is unavailable.

## Dock side (`packages/apps/TaskPlugin`)

- `DockAppsLayout.reportDockIconBounds()` reports all visible running-app icon
  rectangles after layout/scroll changes. It reports both package names and task ids;
  the task-id map is what makes multiple windows of the same package (including X11
  apps) map to the right icon.
- `DockAppAdapter` sends a precise one-shot target for the clicked task right before
  minimizing, so a hover/press scale on the icon does not offset the animation.
- `Utils.getScaledBoundsOnScreen()` maps the icon corners through
  `transformMatrixToGlobal()`, so press scaling, pivots and any parent/taskbar
  scaling are all included in the reported bounds.

The bounds are sent as a broadcast rather than through a new `IDesktopMode` AIDL
method, because Launcher bundles its own copy of that interface and an older Launcher
APK would otherwise need to be rebuilt.

## Requirements and limitations

- The task must be capturable. Windows containing secure layers fall back to the
  regular animation.
- The snapshot freezes the window content for the duration of the animation.
- The render layer is parented to the task display area so the dock/taskbar can stay
  above it. Layer ordering must be checked on the target device.
- The animation renders one window-sized snapshot for roughly 0.5 seconds. It is
  intended to use the cached snapshot when possible and a bounded snapshot size for
  high-resolution displays.

## Runtime switch

The animation can be selected without rebuilding:

```bash
# 0: disabled (stock minimize animation)
# 1: affine animation
# 2: snapshot + mesh genie animation (default)
adb shell setprop persist.wm.debug.minimize_to_dock.mode 2
```

The property is read for every minimize transition, so changing it does not require a
SystemUI restart.

## Tuning

The mesh animation is defined in `GenieMinimizeAnimator`:

- `DURATION_MS` (default `480`)
- `MESH_COLS` / `MESH_ROWS` (default `20` / `24`)
- `TOP_Y_LAG`, `TOP_X_LAG`, `TOP_WIDTH_LAG` (default `0.12`, `0.18`, `0.15`)
- `TARGET_WIDTH_FACTOR` / `TARGET_HEIGHT_FACTOR` (default `0.12`)
- `ALPHA_START` (default `0.90`)
- `MAX_SNAPSHOT_SIZE` (default `1920` px on the longest side)

The separate Y/X/width lags let every row keep collapsing while the top still
trails the bottom, preserving the funnel. Width uses an `easeOutQuart` curve so
the whole shape, not only the bottom, keeps shrinking through the animation.

The dock is normally 70dp tall. By default the merge point is placed 35dp above
the icon center, at the dock's top edge, so the narrow end of the funnel is not
hidden behind the taskbar. This can be tuned without rebuilding:

```bash
adb shell setprop persist.wm.debug.minimize_to_dock.speed 0.25
adb shell setprop persist.wm.debug.minimize_to_dock.merge_y_offset_dp 35
```

`speed` is a duration multiplier (`0.25` means four times slower); it affects
both the genie and affine animations. `merge_y_offset_dp` applies to the genie
mesh. Both properties are read for every animation.
