# Minimize-to-dock animation

Freeform windows now minimize into their dock icon instead of only scaling/fading in
place. The animation is used both when the title bar minimize button is clicked and
when the dock icon of a running window is clicked.

## Behaviour

- The window keeps its content visible while it accelerates towards the dock icon.
- The bottom-center of the window is the anchor, so the top of the window collapses
  downwards.
- The width shrinks faster than the height, which gives the window the stretched
  "being sucked into the dock" look.
- A small horizontal shear is applied during the sideways part of the motion: the
  bottom edge leads towards the icon and the top edge lags, which reads as a soft
  bend even with an affine surface transform.
- The window center converges on the dock icon center and shrinks below the icon size
  (40% by default), so it is absorbed by the icon instead of stopping on top of it.
- The path moves sideways late, producing a curved trajectory when the icon is not
  directly below the window.
- The window stays fully opaque until 90% of the animation and fades quickly at the
  very end, when it is already small and centered on the icon.
- When no dock icon bounds are known (for example the dock plugin is not running, or
  the window is fullscreen), the previous minimize animation is used unchanged.
- Minimizing the last visible freeform task also uses this animation when its dock
  target is known; otherwise the existing desktop exit animation is kept.

## Framework side (`frameworks/base`)

- `MinimizeAnimationTargets` (`WindowManager/Shell/shared`) caches the dock icon
  bounds per display, package and task id. A one-shot target can also be supplied for
  a specific task.
- `IDesktopMode.updateDockIconBounds()` and
  `IDesktopMode.setMinimizeAnimationTarget()` let the dock process push the icon
  locations into shell when the Launcher was built against the updated AIDL.
- A permission-protected broadcast fallback
  (`com.android.wm.shell.action.UPDATE_DOCK_ICON_BOUNDS` /
  `com.android.wm.shell.action.SET_MINIMIZE_TARGET`) is used when the Launcher APK
  bundles an older copy of `IDesktopMode` and therefore does not expose the new
  methods. This avoids requiring a Launcher3 rebuild just for the animation.
- `MinimizeAnimator` now accepts optional target bounds. When present it runs a
  single time-driven animation that controls position, separate X/Y scale and alpha.
- `DesktopMinimizationTransitionHandler` and `FreeformTaskTransitionHandler` look up
  the target only for `TRANSIT_MINIMIZE` / task-limit minimize of freeform tasks.
  Close and back-navigation transitions keep the regular animation.

## Dock side (`packages/apps/TaskPlugin`)

- `DockAppsLayout.reportDockIconBounds()` reports all visible running-app icon
  rectangles after layout/scroll changes. It reports both package names and task ids;
  the task-id map is what makes multiple windows of the same package (including X11
  apps) map to the right icon.
- `DockAppAdapter` sends a precise one-shot target for the clicked task right before
  minimizing, so a hover/press scale on the icon does not offset the animation.
- `Utils.getScaledBoundsOnScreen()` accounts for the icon's current scale.

## Limitation

`SurfaceControl.Transaction` only exposes affine transforms (position, separate X/Y
scale, 2D matrix). A true macOS-style mesh-warped "genie" funnel would need a mesh
warp in SurfaceFlinger/RenderEngine. This implementation is the closest effect that
can be produced entirely with the existing surface animation API.

## Tuning

The animation is defined in `MinimizeAnimator.createDockMinimizeAnimator()`:

- `MINIMIZE_TO_DOCK_DURATION_MS` (default `420`)
- `MINIMIZE_TO_DOCK_ALPHA_START` (default `0.90`)
- `FINAL_TARGET_SCALE` (default `0.40` of the dock icon)
- `SKEW_STRENGTH` / `MAX_SKEW` (default `0.90` / `0.55`)
- the horizontal/vertical progress curves and the separate X/Y scale curves.
