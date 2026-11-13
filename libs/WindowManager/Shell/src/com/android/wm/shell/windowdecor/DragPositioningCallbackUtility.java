/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.windowdecor;

import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.TOOL_TYPE_ERASER;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;
import static android.view.MotionEvent.TOOL_TYPE_MOUSE;
import static android.view.MotionEvent.TOOL_TYPE_PALM;
import static android.view.MotionEvent.TOOL_TYPE_STYLUS;
import static android.view.MotionEvent.TOOL_TYPE_UNKNOWN;

import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_LEFT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_UNDEFINED;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_MOUSE;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_STYLUS;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_TOUCH;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_TOUCHPAD;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.window.DesktopModeFlags;

import com.android.internal.util.CompatibleConfig;
import com.android.window.flags.Flags;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger;
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController;

/**
 * Utility class that contains logic common to classes implementing {@link DragPositioningCallback}
 * Specifically, this class contains logic for determining changed bounds from a drag input
 * and applying that change to the task bounds when applicable.
 */
public class DragPositioningCallbackUtility {
    private static final String TAG = "DragPositioningCallbackUtility";

    // OEM start: disable window drag (move) and drag-resize for launch splash / advertisement
    // activities, ported from Android 14 CaptionWindowDecoration. The filtered activities are
    // configured in the compatible config (key code "forcedPortraitMode") instead of being
    // hard-coded. Matching logic (same as Android 14):
    //   1. package-level config: forcedPortraitMode = "true" for the package, or the package
    //      matches the "com.fde.record.package" system property;
    //   2. otherwise activity-level config: forcedPortraitMode = "true" for the package and
    //      the short activity class name (the part after the last '.').
    // The result is cached per top activity and refreshed whenever the top activity changes,
    // same as the mTopActivity/mDragResizeable cache in the Android 14 implementation.
    private static final String KEY_FORCED_PORTRAIT_MODE = "forcedPortraitMode";
    private static Context sCompatibleConfigContext;
    @Nullable
    private static ComponentName sCachedTopActivity;
    private static boolean sCachedDisabled;

    /** Initializes the context used to query the compatible config. Called by the decor VMs. */
    public static void initCompatibleConfig(Context context) {
        if (sCompatibleConfigContext == null && context != null) {
            sCompatibleConfigContext = context.getApplicationContext();
        }
    }

    /** Whether window drag (move) and drag-resize must be ignored for the top activity. */
    public static boolean isDragDisabledForTopActivity(@NonNull RunningTaskInfo taskInfo) {
        final ComponentName component = getTopActivityComponent(taskInfo);
        if (component == null) {
            return false;
        }
        if (component.equals(sCachedTopActivity)) {
            return sCachedDisabled;
        }
        final boolean disabled = queryDragDisabled(component);
        sCachedTopActivity = component;
        sCachedDisabled = disabled;
        if (disabled) {
            Log.d(TAG, "drag disabled for " + component.flattenToShortString());
        }
        return disabled;
    }

    private static ComponentName getTopActivityComponent(@NonNull RunningTaskInfo taskInfo) {
        final ActivityInfo topActivityInfo = taskInfo.topActivityInfo;
        if (topActivityInfo != null && topActivityInfo.getComponentName() != null) {
            return topActivityInfo.getComponentName();
        }
        return taskInfo.topActivity;
    }

    private static boolean queryDragDisabled(@NonNull ComponentName component) {
        if (sCompatibleConfigContext == null) {
            return false;
        }
        final String packageName = component.getPackageName();
        final String recordPackageName = SystemProperties.get("com.fde.record.package", "null");
        // Package-level config (empty activity name), same as Android 14.
        if (TextUtils.equals(recordPackageName, packageName)
                || TextUtils.equals(CompatibleConfig.queryStringValueData(sCompatibleConfigContext,
                        KEY_FORCED_PORTRAIT_MODE, packageName, ""), "true")) {
            return true;
        }
        // Activity-level config uses the short class name, same as Android 14.
        final String activityName = extractActivityName(component.getClassName());
        return TextUtils.equals(CompatibleConfig.queryStringValueData(sCompatibleConfigContext,
                KEY_FORCED_PORTRAIT_MODE, packageName, activityName), "true");
    }

    /** Returns the part of the class name after the last '.', same as Android 14. */
    private static String extractActivityName(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) {
            return "";
        }
        final int lastDotIndex = fullClassName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fullClassName;
        }
        return fullClassName.substring(lastDotIndex + 1);
    }
    // OEM end

    /**
     * Determine the delta between input's current point and the input start point.
     *
     * @param inputX               current input x coordinate
     * @param inputY               current input y coordinate
     * @param repositionStartPoint initial input coordinate
     * @return delta between these two points
     */
    static PointF calculateDelta(float inputX, float inputY, PointF repositionStartPoint) {
        final float deltaX = inputX - repositionStartPoint.x;
        final float deltaY = inputY - repositionStartPoint.y;
        return new PointF(deltaX, deltaY);
    }

    /**
     * Based on type of resize and delta provided, calculate the new bounds to display for this
     * task.
     *
     * @param ctrlType              type of drag being performed
     * @param repositionTaskBounds  the bounds the task is being repositioned to
     * @param taskBoundsAtDragStart the bounds of the task on the first drag input event
     * @param stableBounds          bounds that represent the resize limit of this task
     * @param delta                 difference between start input and current input in x/y
     *                              coordinates
     * @param windowDecoration      window decoration of the task being dragged
     * @param result                the result of the change bounds calculation, including whether
     *                              this method changed repositionTaskBounds, and whether the bounds
     *                              is violating the size constraints
     */
    static void changeBounds(int ctrlType, Rect repositionTaskBounds, Rect taskBoundsAtDragStart,
            Rect stableBounds, PointF delta, DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode,
            ChangeBoundsResult result) {
        result.reset();
        // If task is being dragged rather than resized, return since this method only handles
        // with resizing
        if (ctrlType == CTRL_TYPE_UNDEFINED) {
            return;
        }

        // OEM: resize gestures on the filtered launch splash / advertisement activities are
        // no-ops. The bounds stay untouched and result.boundsChanged stays false.
        if (isDragDisabledForTopActivity(windowDecoration.getTaskInfo())) {
            repositionTaskBounds.set(taskBoundsAtDragStart);
            return;
        }

        final int oldLeft = repositionTaskBounds.left;
        final int oldTop = repositionTaskBounds.top;
        final int oldRight = repositionTaskBounds.right;
        final int oldBottom = repositionTaskBounds.bottom;

        repositionTaskBounds.set(taskBoundsAtDragStart);

        boolean isAspectRatioMaintained = true;
        // Make sure the new resizing destination in any direction falls within the stable bounds.
        if ((ctrlType & CTRL_TYPE_LEFT) != 0) {
            repositionTaskBounds.left = Math.max(repositionTaskBounds.left + (int) delta.x,
                    stableBounds.left);
            if (repositionTaskBounds.left == stableBounds.left
                    && repositionTaskBounds.left + (int) delta.x != stableBounds.left) {
                // If the task edge have been set to the stable bounds and not due to the users
                // drag, the aspect ratio of the task will not be maintained.
                isAspectRatioMaintained = false;
            }
        }
        if ((ctrlType & CTRL_TYPE_RIGHT) != 0) {
            repositionTaskBounds.right = Math.min(repositionTaskBounds.right + (int) delta.x,
                    stableBounds.right);
            if (repositionTaskBounds.right == stableBounds.right
                    && repositionTaskBounds.right + (int) delta.x != stableBounds.right) {
                // If the task edge have been set to the stable bounds and not due to the users
                // drag, the aspect ratio of the task will not be maintained.
                isAspectRatioMaintained = false;
            }
        }
        if ((ctrlType & CTRL_TYPE_TOP) != 0) {
            repositionTaskBounds.top = Math.max(repositionTaskBounds.top + (int) delta.y,
                    stableBounds.top);
            if (repositionTaskBounds.top == stableBounds.top
                    && repositionTaskBounds.top + (int) delta.y != stableBounds.top) {
                // If the task edge have been set to the stable bounds and not due to the users
                // drag, the aspect ratio of the task will not be maintained.
                isAspectRatioMaintained = false;
            }
        }
        if ((ctrlType & CTRL_TYPE_BOTTOM) != 0) {
            repositionTaskBounds.bottom = Math.min(repositionTaskBounds.bottom + (int) delta.y,
                    stableBounds.bottom);
            if (repositionTaskBounds.bottom == stableBounds.bottom
                    && repositionTaskBounds.bottom + (int) delta.y != stableBounds.bottom) {
                // If the task edge have been set to the stable bounds and not due to the users
                // drag, the aspect ratio of the task will not be maintained.
                isAspectRatioMaintained = false;
            }
        }

        // If width or height are negative or violating the width or height constraints, revert the
        // respective bounds to use previous bound dimensions.
        if (isViolatingWidthConstraints(repositionTaskBounds.width(),
                /* startingWidth= */ oldRight - oldLeft, stableBounds, displayController,
                windowDecoration, canEnterDesktopMode)) {
            repositionTaskBounds.right = oldRight;
            repositionTaskBounds.left = oldLeft;
            isAspectRatioMaintained = false;
            result.violatingSizeConstraints = Flags.enableNoResizeCursor();
        }
        if (isViolatingHeightConstraints(repositionTaskBounds.height(),
                /* startingHeight= */oldBottom - oldTop, stableBounds, displayController,
                windowDecoration, canEnterDesktopMode)) {
            repositionTaskBounds.top = oldTop;
            repositionTaskBounds.bottom = oldBottom;
            isAspectRatioMaintained = false;
            result.violatingSizeConstraints = Flags.enableNoResizeCursor();
        }

        int taskId = windowDecoration.getTaskInfo().taskId;
        PinnedLayerController pinnedLayerController = windowDecoration.getPinnedLayerController();
        if (pinnedLayerController != null && pinnedLayerController.isPinned(taskId)) {
            if (isExceedingPinnedLayerConstraint(repositionTaskBounds.width(),
                    stableBounds.width())) {
                repositionTaskBounds.left = oldLeft;
                repositionTaskBounds.right = oldRight;
                isAspectRatioMaintained = false;
            }
            if (isExceedingPinnedLayerConstraint(repositionTaskBounds.height(),
                    stableBounds.height())) {
                repositionTaskBounds.top = oldTop;
                repositionTaskBounds.bottom = oldBottom;
                isAspectRatioMaintained = false;
            }
        }

        // If the application is unresizeable and any bounds have been set back to their old
        // location or to a stable bound edge, reset all the bounds to maintain the applications
        // aspect ratio.
        if (!isAspectRatioMaintained && !windowDecoration.getTaskInfo().isResizeable) {
            repositionTaskBounds.top = oldTop;
            repositionTaskBounds.bottom = oldBottom;
            repositionTaskBounds.right = oldRight;
            repositionTaskBounds.left = oldLeft;
        }

        // If there are no changes to the bounds after checking new bounds against minimum and
        // maximum width and height, do not set bounds and set result.boundsChanged to false
        result.boundsChanged = oldLeft != repositionTaskBounds.left
                || oldTop != repositionTaskBounds.top
                || oldRight != repositionTaskBounds.right
                || oldBottom != repositionTaskBounds.bottom;
    }

    private static boolean isExceedingPinnedLayerConstraint(int taskDimension,
            int screenDimension) {
        double maxDimension = screenDimension * 0.7;
        return (double) taskDimension > maxDimension;
    }

    /**
     * Set bounds using a {@link SurfaceControl.Transaction}.
     */
    static void setPositionOnDrag(WindowDecorationWrapper decoration, Rect repositionTaskBounds,
            Rect taskBoundsAtDragStart, PointF repositionStartPoint, SurfaceControl.Transaction t,
            float x, float y) {
        updateTaskBounds(repositionTaskBounds, taskBoundsAtDragStart, repositionStartPoint, x, y);
        t.setPosition(decoration.getTaskSurface(), repositionTaskBounds.left,
                repositionTaskBounds.top);
    }

    static void updateTaskBounds(Rect repositionTaskBounds, Rect taskBoundsAtDragStart,
            PointF repositionStartPoint, float x, float y) {
        final float deltaX = x - repositionStartPoint.x;
        final float deltaY = y - repositionStartPoint.y;
        repositionTaskBounds.set(taskBoundsAtDragStart);
        repositionTaskBounds.offset((int) deltaX, (int) deltaY);
    }

    /**
     * If task bounds are outside of provided drag area, snap the bounds to be just inside the
     * drag area.
     *
     * @param repositionTaskBounds bounds determined by task positioner
     * @param validDragArea        the area that task must be positioned inside
     * @return whether bounds were modified
     */
    public static boolean snapTaskBoundsIfNecessary(Rect repositionTaskBounds, Rect validDragArea) {
        // If we were never supplied a valid drag area, do not restrict movement.
        // Otherwise, we restrict deltas to keep task position inside the Rect.
        if (validDragArea.width() == 0) return false;
        boolean result = false;
        if (repositionTaskBounds.left < validDragArea.left) {
            repositionTaskBounds.offset(validDragArea.left - repositionTaskBounds.left, 0);
            result = true;
        } else if (repositionTaskBounds.left > validDragArea.right) {
            repositionTaskBounds.offset(validDragArea.right - repositionTaskBounds.left, 0);
            result = true;
        }
        if (repositionTaskBounds.top < validDragArea.top) {
            repositionTaskBounds.offset(0, validDragArea.top - repositionTaskBounds.top);
            result = true;
        } else if (repositionTaskBounds.top > validDragArea.bottom) {
            repositionTaskBounds.offset(0, validDragArea.bottom - repositionTaskBounds.top);
            result = true;
        }
        return result;
    }

    /**
     * Checks whether the new task bounds violate the allowed width.
     *
     * @param repositionedWidth task width after repositioning.
     * @param startingWidth task width before repositioning.
     * @param maxResizeBounds stable bounds for display.
     * @param displayController display controller for the task being checked.
     * @param windowDecoration contains decor info and helpers for the task.
     * @return whether the task is violating any of the width constraints, minimum or maximum.
     */
    public static boolean isViolatingWidthConstraints(int repositionedWidth, int startingWidth,
            Rect maxResizeBounds, DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        boolean isSizeIncreasing = (repositionedWidth - startingWidth) > 0;
        // Check if width is less than the minimum width constraint.
        if (repositionedWidth < getMinWidth(displayController, windowDecoration,
                canEnterDesktopMode)) {
            // Only allow width to be increased if it is already below minimum.
            return !isSizeIncreasing;
        }
        // Check if width is more than the maximum resize bounds on desktop windowing mode.
        // Only allow width to be decreased if it already exceeds maximum.
        return isSizeConstraintForDesktopModeEnabled(canEnterDesktopMode)
                && repositionedWidth > maxResizeBounds.width() && isSizeIncreasing;
    }

    /**
     * Checks whether the new task bounds violate the allowed height.
     *
     * @param repositionedHeight task's height after repositioning.
     * @param startingHeight task's height before repositioning.
     * @param maxResizeBounds stable bounds for display.
     * @param displayController display controller for the task being checked.
     * @param windowDecoration contains decor info and helpers for the task.
     * @return whether the task is violating any of the height constraints, minimum or maximum.
     */
    public static boolean isViolatingHeightConstraints(int repositionedHeight, int startingHeight,
            Rect maxResizeBounds, DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        boolean isSizeIncreasing = (repositionedHeight - startingHeight) > 0;
        // Check if height is less than the minimum height constraint.
        if (repositionedHeight < getMinHeight(displayController, windowDecoration,
                canEnterDesktopMode)) {
            // Only allow height to be increased if it is already below minimum.
            return !isSizeIncreasing;
        }
        // Check if height is more than the maximum resize bounds on desktop windowing mode.
        // Only allow height to be decreased if it already exceeds maximum.
        return isSizeConstraintForDesktopModeEnabled(canEnterDesktopMode)
                && repositionedHeight > maxResizeBounds.height() && isSizeIncreasing;
    }

    /**
     * Returns the corresponding input method type used such as a finger or stylus, if known.
     */
    @DragPositioningCallback.InputMethodType
    public static int getInputMethodFromMotionEvent(@Nullable MotionEvent e) {
        if (e == null) return INPUT_METHOD_TYPE_UNKNOWN;

        final int toolType = e.getToolType(e.findPointerIndex(e.getPointerId(0)));
        return switch (toolType) {
            case TOOL_TYPE_STYLUS -> INPUT_METHOD_TYPE_STYLUS;
            case TOOL_TYPE_MOUSE -> INPUT_METHOD_TYPE_MOUSE;
            case TOOL_TYPE_FINGER -> {
                final int source = e.getSource();
                if (source == SOURCE_MOUSE) {
                    yield INPUT_METHOD_TYPE_TOUCHPAD;
                } else if (source == SOURCE_TOUCHSCREEN) {
                    yield INPUT_METHOD_TYPE_TOUCH;
                }
                yield INPUT_METHOD_TYPE_UNKNOWN;
            }
            case TOOL_TYPE_ERASER, TOOL_TYPE_PALM, TOOL_TYPE_UNKNOWN -> INPUT_METHOD_TYPE_UNKNOWN;
            default -> INPUT_METHOD_TYPE_UNKNOWN;
        };
    }

    private static float getMinWidth(DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        if (!Flags.respectSystemDefaultMinSize()) {
            return windowDecoration.getTaskInfo().minWidth < 0
                    ? getDefaultMinWidth(displayController, windowDecoration, canEnterDesktopMode)
                    : windowDecoration.getTaskInfo().minWidth;
        }

        return Math.max(
                windowDecoration.getTaskInfo().minWidth,
                getDefaultMinWidth(displayController, windowDecoration, canEnterDesktopMode)
        );
    }

    private static float getMinHeight(DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        if (!Flags.respectSystemDefaultMinSize()) {
            return windowDecoration.getTaskInfo().minHeight < 0
                    ? getDefaultMinHeight(displayController, windowDecoration, canEnterDesktopMode)
                    : windowDecoration.getTaskInfo().minHeight;
        }

        return Math.max(
                windowDecoration.getTaskInfo().minHeight,
                getDefaultMinHeight(displayController, windowDecoration, canEnterDesktopMode)
        );
    }

    private static float getDefaultMinWidth(DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        if (isSizeConstraintForDesktopModeEnabled(canEnterDesktopMode)) {
            return WindowDecoration.loadDimensionPixelSize(
                    windowDecoration.getDecorWindowContext().getResources(),
                    R.dimen.desktop_mode_minimum_window_width);
        }
        return getDefaultMinSize(displayController, windowDecoration);
    }

    private static float getDefaultMinHeight(DisplayController displayController,
            WindowDecorationWrapper windowDecoration, boolean canEnterDesktopMode) {
        if (isSizeConstraintForDesktopModeEnabled(canEnterDesktopMode)) {
            return WindowDecoration.loadDimensionPixelSize(
                    windowDecoration.getDecorWindowContext().getResources(),
                    R.dimen.desktop_mode_minimum_window_height);
        }
        return getDefaultMinSize(displayController, windowDecoration);
    }

    private static float getDefaultMinSize(DisplayController displayController,
            WindowDecorationWrapper windowDecoration) {
        float density = displayController.getDisplayLayout(windowDecoration.getTaskInfo().displayId)
                .densityDpi() * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        return windowDecoration.getTaskInfo().defaultMinSize * density;
    }

    private static boolean isSizeConstraintForDesktopModeEnabled(boolean canEnterDesktopMode) {
        return canEnterDesktopMode
                && DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_SIZE_CONSTRAINTS.isTrue();
    }

    public interface DragEventListener {
        /**
         * Inform the implementing class that a drag move has started.
         *
         * @param taskId id of this positioner's {@link WindowDecoration}
         */
        void onDragMove(int taskId);

        /**
         * Inform the implementing class that a drag resize has started.
         */
        default void onDragResizeStarted(int taskId, @NonNull ResizeTrigger resizeTrigger,
                @NonNull InputMethod inputMethod, @NonNull Rect startTaskBounds) {}

        /**
         * Inform the implementing class that a drag resize has ended.
         */
        default void onDragResizeEnded(int taskId, @NonNull ResizeTrigger resizeTrigger,
                @NonNull InputMethod inputMethod, @NonNull Rect endTaskBounds) {}
    }

    /**
     * The result of a change bounds calculation.
     */
    public static class ChangeBoundsResult {
        /**
         * Whether the bounds have changed as a result of the calculation.
         */
        public boolean boundsChanged;

        /**
         * Whether the new bounds violate any size constraints (e.g. maximum or minimum
         * width/height).
         */
        public boolean violatingSizeConstraints;

        public void reset() {
            boundsChanged = false;
            violatingSizeConstraints = false;
        }
    }
}
