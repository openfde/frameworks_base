/*
 * Copyright (C) 2026 The OpenFDE Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_OP_TYPE;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_THROWABLE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SystemTaskFragmentOrganizer implements the FDE parallel world (magic window) inside a single
 * task.
 *
 * <p>Core idea: the task is dynamically expanded and two TaskFragments are created inside it:
 * <ul>
 *     <li>left: the main activity, keeps its original width;</li>
 *     <li>right: the additional activity, lives in the newly expanded area.</li>
 * </ul>
 *
 * <p>The organizer is registered as a system organizer because it creates TaskFragments for
 * tasks that belong to other apps.
 *
 * <p>Robustness: the split is rolled back when the activities are finishing, when the minimum
 * window dimensions cannot be satisfied or when the TaskFragment transaction fails, so a task
 * never stays stuck in the {@link Task#IN_PARALLEL_WINDOW} state.
 */
public class SystemTaskFragmentOrganizer extends TaskFragmentOrganizer {

    private static final String TAG = "SystemTaskFragmentOrganizer";

    /** Time to wait for the task expansion to be applied before giving up on it. */
    private static final long EXPAND_TRANSITION_TIMEOUT_MS = 3000;
    /** Delay before pausing the main activity, to let the additional window settle down. */
    private static final long PAUSE_LEFT_DELAY_MS = 1000;
    private static final float DEFAULT_SPLIT_RATIO = 0.5f;
    /** Reason used when pausing the main window through the TaskFragment. */
    private static final String PAUSE_REASON = "parallel-world";

    private final ActivityTaskManagerService mAtmService;

    /** taskId -> left (main) fragment token. */
    private final SparseArray<IBinder> mLeftFragments = new SparseArray<>();
    /** taskId -> right (additional) fragment token. */
    private final SparseArray<IBinder> mRightFragments = new SparseArray<>();
    /** fragment token -> latest fragment info. */
    private final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();
    /** taskId -> ratio of the task width that belongs to the right fragment. */
    private final SparseArray<Float> mSplitRatios = new SparseArray<>();
    /** taskId -> last known bounds of the task. */
    private final SparseArray<Rect> mTaskBounds = new SparseArray<>();
    /** taskId -> last known parent configuration. */
    private final SparseArray<Configuration> mParentConfigs = new SparseArray<>();
    /** taskId -> display id of the task. */
    private final SparseArray<Integer> mDisplayIds = new SparseArray<>();
    /** taskId -> expected width while the expand transition is still running. */
    private final SparseArray<Integer> mExpectedExpandedWidths = new SparseArray<>();
    /** taskId -> activity that is being reparented into the right fragment. */
    private final SparseArray<ActivityRecord> mSplittingActivities = new SparseArray<>();
    /** error callback token -> taskId of the pending split. */
    private final ArrayMap<IBinder, Integer> mErrorCallbackTokens = new ArrayMap<>();

    public SystemTaskFragmentOrganizer(ActivityTaskManagerService atmService) {
        super(atmService.mH::post);
        mAtmService = atmService;
    }

    public void register() {
        // Register as a system organizer: the organizer runs in system_server and creates
        // TaskFragments for tasks that belong to other apps (the parallel world split).
        super.registerOrganizer(true /* isSystemOrganizer */);
    }

    /**
     * Entry point of the parallel world split.
     *
     * <p>If the task is not split yet, it is expanded and two TaskFragments are created. If it is
     * already split, the existing right fragment is reused.
     *
     * @param task            the task that hosts both windows
     * @param primary         the main activity, always placed in the left fragment
     * @param secondary       the additional activity, may be {@code null} when it has to be
     *                        started by the organizer
     * @param secondaryIntent the intent used to start the additional activity when
     *                        {@code secondary} is {@code null}
     * @param ratio           fraction of the task width that belongs to the right fragment
     */
    void startSplit(Task task, ActivityRecord primary, ActivityRecord secondary,
            Intent secondaryIntent, float ratio) {
        if (task == null || primary == null || primary.finishing) {
            return;
        }
        if (secondary != null && secondary.finishing) {
            Slog.w(TAG, "startSplit: secondary is finishing, skip " + secondary);
            return;
        }
        final ParallelWorldConfig config = ParallelWorldConfig.get();
        // Defensive checks: only the main window may open an additional window, and the
        // additional window must not be an excluded page.
        if (config.getMagicWindowType(primary.packageName, primary.info.name)
                != Task.MAGIC_MAIN_WINDOW) {
            Slog.w(TAG, "startSplit: " + primary + " is not a main window, skip");
            return;
        }
        if (secondary != null
                && !config.isAdditionalWindow(secondary.packageName, secondary.info.name)) {
            Slog.w(TAG, "startSplit: " + secondary + " is not an additional window, skip");
            return;
        }
        final int taskId = task.mTaskId;
        if (secondary != null && mSplittingActivities.get(taskId) == secondary) {
            Slog.w(TAG, "startSplit: already splitting " + secondary);
            return;
        }

        final float splitRatio = ParallelWorldConfig.clampRatio(ratio);
        final long origId = Binder.clearCallingIdentity();
        try {
            // Mark the task before resizing it, so that the expanded bounds are never persisted
            // as the launch params of the app.
            task.type = Task.IN_PARALLEL_WINDOW;
            final IBinder existingRight = mRightFragments.get(taskId);
            final boolean alreadySplit = existingRight != null
                    && mFragmentInfos.get(existingRight) != null;
            if (alreadySplit) {
                if (!reuseExistingSplit(taskId, existingRight, primary, secondary,
                        secondaryIntent)) {
                    return;
                }
            } else {
                createSplit(taskId, task, primary, secondary, secondaryIntent, splitRatio);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Reuses the existing right fragment.
     *
     * @return {@code false} when nothing has to be applied (the activity already lives in the
     *         target fragment).
     */
    private boolean reuseExistingSplit(int taskId, IBinder rightToken, ActivityRecord primary,
            ActivityRecord secondary, Intent secondaryIntent) {
        Slog.d(TAG, "startSplit: already split, reuse right fragment for task " + taskId);
        mAtmService.mH.postDelayed(() -> pauseLeftIfNeed(primary), PAUSE_LEFT_DELAY_MS);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (secondary != null) {
            if (secondary.getTaskFragment() != null) {
                // The new activity is already placed in the right fragment by the generic
                // TaskFragment launch logic.
                return false;
            }
            wct.reparentActivityToTaskFragment(rightToken, secondary.token);
        } else if (secondaryIntent != null) {
            wct.startActivityInTaskFragment(rightToken, primary.token, secondaryIntent, null);
        }
        if (wct.isEmpty()) {
            return true;
        }
        wct.setErrorCallbackToken(registerErrorCallback(taskId));
        applyTransaction(wct, TASK_FRAGMENT_TRANSIT_OPEN, false);
        final Rect bounds = mTaskBounds.get(taskId);
        markExpandTransition(taskId, bounds != null ? bounds.width() : -1);
        return true;
    }

    /** Expands the task and creates the left/right fragments. */
    private void createSplit(int taskId, Task task, ActivityRecord primary,
            ActivityRecord secondary, Intent secondaryIntent, float ratio) {
        final List<ActivityRecord> leftActivities = new ArrayList<>(1);
        leftActivities.add(primary);
        final List<ActivityRecord> rightActivities = new ArrayList<>(1);
        if (secondary != null) {
            rightActivities.add(secondary);
        }
        createSplit(taskId, task, leftActivities, rightActivities, secondaryIntent, ratio);
    }

    /**
     * Expands the task and creates the left/right fragments with the given activities.
     *
     * @param leftActivities  activities of the main (left) window, bottom to top, never empty
     * @param rightActivities activities of the additional (right) window, bottom to top
     * @param secondaryIntent intent used to start the first activity of the right window when
     *                        {@code rightActivities} is empty and the split comes from a launch
     */
    private void createSplit(int taskId, Task task, List<ActivityRecord> leftActivities,
            List<ActivityRecord> rightActivities, Intent secondaryIntent, float ratio) {
        final ActivityRecord primary = leftActivities.get(0);
        final Rect taskBounds = new Rect(task.getBounds());
        final int originalWidth = taskBounds.width();
        final int height = taskBounds.height();
        final int minPrimaryWidth = getMinWidth(primary, task);
        int minSecondaryWidth = 0;
        for (ActivityRecord activity : rightActivities) {
            minSecondaryWidth = Math.max(minSecondaryWidth, getMinWidth(activity, task));
        }

        int leftWidth = Math.max(originalWidth, minPrimaryWidth);
        int rightWidth = Math.max(Math.round(leftWidth * ratio / (1 - ratio)), minSecondaryWidth);
        final int maxExpandedWidth = getMaxExpandedWidth(task);
        if (leftWidth + rightWidth > maxExpandedWidth) {
            rightWidth = maxExpandedWidth - leftWidth;
            if (rightWidth < minSecondaryWidth) {
                // The additional window cannot satisfy its minimum dimensions, give up the split
                // instead of reparenting the activity to the whole task.
                Slog.w(TAG, "createSplit: cannot satisfy the minimum dimensions (task=" + taskId
                        + " left=" + leftWidth + " right=" + rightWidth
                        + " minSecondary=" + minSecondaryWidth + " max=" + maxExpandedWidth + ")");
                rollbackSplit(task);
                return;
            }
        }
        final int expandedWidth = leftWidth + rightWidth;
        final float effectiveRatio = (float) rightWidth / expandedWidth;
        Slog.d(TAG, "createSplit: task=" + taskId + " bounds=" + taskBounds
                + " left=" + leftWidth + " right=" + rightWidth
                + " ratio=" + effectiveRatio
                + " leftActivities=" + leftActivities.size()
                + " rightActivities=" + rightActivities.size());

        mSplitRatios.put(taskId, effectiveRatio);
        mTaskBounds.put(taskId, taskBounds);
        // When a main window fragment is still registered (the split was merged before), pin it to
        // the main window width before expanding the task: its relative bounds fill the task, so
        // otherwise the main window would stretch over the whole expanded task and the expansion
        // would be visible twice.
        final IBinder existingLeftToken = mLeftFragments.get(taskId);
        if (existingLeftToken != null && mFragmentInfos.get(existingLeftToken) != null) {
            final WindowContainerTransaction pinWct = new WindowContainerTransaction();
            resizeTaskFragment(pinWct, existingLeftToken, new Rect(0, 0, leftWidth, height));
            if (!pinWct.isEmpty()) {
                Slog.d(TAG, "createSplit: task=" + taskId + " pin the existing main fragment to "
                        + leftWidth);
                applyTransaction(pinWct, 0, false);
            }
        }
        // Expand the task first so that the fragments are created with their final bounds.
        final Rect newTaskBounds = new Rect(taskBounds.left, taskBounds.top,
                taskBounds.left + expandedWidth, taskBounds.bottom);
        mAtmService.resizeTask(taskId, newTaskBounds, 0);
        markExpandTransition(taskId, expandedWidth);

        final int left = leftWidth;
        final int right = rightWidth;
        mAtmService.mH.post(() -> {
            final IBinder primaryTfToken = new Binder();
            final IBinder secondaryTfToken = new Binder();
            final Rect leftBounds = new Rect(0, 0, left, height);
            final Rect rightBounds = new Rect(left, 0, left + right, height);
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            final TaskFragmentCreationParams primaryParams =
                    new TaskFragmentCreationParams.Builder(getOrganizerToken(), primaryTfToken,
                            primary.token)
                            .setInitialRelativeBounds(leftBounds)
                            .build();
            wct.createTaskFragment(primaryParams);
            for (ActivityRecord activity : leftActivities) {
                wct.reparentActivityToTaskFragment(primaryTfToken, activity.token);
            }

            final TaskFragmentCreationParams secondaryParams =
                    new TaskFragmentCreationParams.Builder(getOrganizerToken(), secondaryTfToken,
                            primary.token)
                            .setInitialRelativeBounds(rightBounds)
                            .setPairedPrimaryFragmentToken(primaryTfToken)
                            .build();
            wct.createTaskFragment(secondaryParams);
            for (ActivityRecord activity : rightActivities) {
                wct.reparentActivityToTaskFragment(secondaryTfToken, activity.token);
            }
            if (rightActivities.isEmpty() && secondaryIntent != null) {
                wct.startActivityInTaskFragment(secondaryTfToken, primary.token, secondaryIntent,
                        null);
            }
            wct.setAdjacentTaskFragments(primaryTfToken, secondaryTfToken,
                    new WindowContainerTransaction.TaskFragmentAdjacentParams());
            wct.setCompanionTaskFragment(primaryTfToken, secondaryTfToken, null /* toBeFinished */);
            wct.setErrorCallbackToken(registerErrorCallback(taskId));

            mLeftFragments.put(taskId, primaryTfToken);
            mRightFragments.put(taskId, secondaryTfToken);
            if (!rightActivities.isEmpty()) {
                mSplittingActivities.put(taskId, rightActivities.get(rightActivities.size() - 1));
            }
            applyTransaction(wct, TASK_FRAGMENT_TRANSIT_OPEN, false);
        });
    }

    /**
     * Merges the parallel world back into a single window: the activities of the additional
     * window are moved into the main window fragment, the (now empty) additional fragment is
     * removed by the framework and the task shrinks back to the main window width.
     *
     * <p>A TaskFragment must never be deleted while it still has activities: the framework
     * finishes them in that case, so the activities are reparented first.
     */
    void exitSplit(int taskId) {
        final IBinder leftToken = mLeftFragments.get(taskId);
        final IBinder rightToken = mRightFragments.get(taskId);
        final TaskFragmentInfo rightInfo = mFragmentInfos.get(rightToken);
        if (leftToken == null || rightToken == null || rightInfo == null) {
            // Already a single window.
            Slog.d(TAG, "exitSplit: task=" + taskId + " is not split");
            return;
        }
        final List<IBinder> activities = rightInfo.getActivities();
        if (activities == null || activities.isEmpty()) {
            // The additional window is already empty: delete it here, a TaskFragment created by an
            // organizer is not removed when it loses its last activity (see
            // TaskFragment#shouldRemoveSelfOnLastChildRemoval). Removing it makes the framework
            // call onTaskFragmentVanished, which contracts the task.
            Slog.d(TAG, "exitSplit: task=" + taskId + " additional window is empty");
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            deleteTaskFragment(wct, rightInfo);
            applyTransaction(wct, 0, false);
            return;
        }
        Slog.d(TAG, "exitSplit: task=" + taskId + " merge " + activities.size()
                + " activities into the main window");
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        for (IBinder activityToken : activities) {
            wct.reparentActivityToTaskFragment(leftToken, activityToken);
        }
        // Delete the additional window in the same transaction: a TaskFragment created by an
        // organizer is not removed when its last activity is reparented away, it would stay as an
        // empty, blank pane where the additional window was. Removing it makes the framework call
        // onTaskFragmentVanished, which contracts the task.
        deleteTaskFragment(wct, rightInfo);
        // No transition: the additional window should just disappear and the task contract
        // immediately, without a merge animation.
        applyTransaction(wct, 0, false);
    }

    /**
     * Enters the parallel world of the task on user request: the activities of the task are moved
     * into the main (left) and the additional (right) window according to the config, and the task
     * is expanded exactly like the automatic split does.
     *
     * <p>Activities that are not additional windows (excluded pages, pages of other applications)
     * stay in the main window. When the task has no additional window page yet, nothing is split:
     * the request is remembered by the caller and the automatic split takes over as soon as an
     * additional page is opened.
     *
     * @return whether the request was accepted.
     */
    boolean enterSplit(int taskId) {
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        if (task == null) {
            return false;
        }
        if (task.type == Task.IN_PARALLEL_WINDOW || mRightFragments.get(taskId) != null) {
            // Already in the parallel world, nothing to do.
            return true;
        }
        if (task.inPinnedWindowingMode()) {
            Slog.w(TAG, "enterSplit: the task is in picture-in-picture");
            return false;
        }
        final ParallelWorldConfig config = ParallelWorldConfig.get();
        if (!config.isEnabled()) {
            Slog.w(TAG, "enterSplit: the feature is disabled");
            return false;
        }
        final List<ActivityRecord> leftActivities = new ArrayList<>();
        final List<ActivityRecord> rightActivities = new ArrayList<>();
        task.forAllActivities(activity -> {
            if (activity.finishing || activity.info == null) {
                return;
            }
            if (config.isAdditionalWindow(activity.packageName, activity.info.name)) {
                rightActivities.add(activity);
            } else {
                leftActivities.add(activity);
            }
        }, false /* traverseTopToBottom */);
        if (leftActivities.isEmpty()) {
            Slog.w(TAG, "enterSplit: no main window activity in task " + taskId);
            return false;
        }
        final String packageName = leftActivities.get(0).packageName;
        final float ratio = config.getSplitRatio(mAtmService.mContext, packageName);
        if (ratio <= 0f) {
            Slog.w(TAG, "enterSplit: " + packageName + " is not a parallel world package");
            return false;
        }
        if (rightActivities.isEmpty()) {
            // There is no additional window page yet: keep the task in a single window and let
            // the automatic split take over as soon as an additional page is opened.
            Slog.d(TAG, "enterSplit: task=" + taskId + " package=" + packageName
                    + " has no additional window yet, armed");
            return true;
        }
        Slog.d(TAG, "enterSplit: task=" + taskId + " package=" + packageName
                + " left=" + leftActivities.size() + " right=" + rightActivities.size());
        final long origId = Binder.clearCallingIdentity();
        try {
            // Mark the task before resizing it, so that the expanded bounds are never persisted
            // as the launch params of the app.
            task.type = Task.IN_PARALLEL_WINDOW;
            createSplit(taskId, task, leftActivities, rightActivities, null /* secondaryIntent */,
                    ratio);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
        return true;
    }

    /**
     * Enters or exits the parallel world of the task on user request and remembers the choice for
     * the package of the task, so that a package the user switched on always starts split and a
     * package the user switched off never splits automatically.
     *
     * @return whether the request was accepted.
     */
    boolean setEnabled(int taskId, boolean enabled) {
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        if (task == null) {
            return false;
        }
        final String packageName = getTaskPackage(task);
        if (enabled) {
            if (!enterSplit(taskId)) {
                return false;
            }
            if (packageName != null) {
                ParallelWorldConfig.get().setUserMode(mAtmService.mContext, packageName,
                        ParallelWorldConfig.MODE_ON);
            }
        } else {
            exitSplit(taskId);
            if (packageName != null) {
                ParallelWorldConfig.get().setUserMode(mAtmService.mContext, packageName,
                        ParallelWorldConfig.MODE_OFF);
            }
        }
        // The mode is not part of the split state, refresh the task info so that the shell can
        // update the parallel world toggle (the task may still be a single window).
        task.dispatchTaskInfoChangedIfNeeded(true /* force */);
        return true;
    }

    /**
     * Configures the top activity of the task as the main window of the parallel world and enables
     * the feature for its package. Used when the user enables the parallel world from the window
     * menu of an application that is not part of the device config: the page that is open when the
     * user enables it becomes the main (left) window.
     *
     * @return whether the request was accepted.
     */
    boolean configureMain(int taskId) {
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        if (task == null) {
            return false;
        }
        final ActivityRecord top = task.getTopNonFinishingActivity();
        if (top == null || top.info == null) {
            return false;
        }
        final String packageName = top.packageName;
        final String activityName = top.info.name;
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(activityName)) {
            return false;
        }
        Slog.d(TAG, "configureMain: task=" + taskId + " package=" + packageName
                + " activity=" + activityName);
        ParallelWorldConfig.get().setUserMainActivity(mAtmService.mContext, packageName,
                ParallelWorldConfig.toSimpleClassName(activityName));
        // Enables the feature for the package (and splits right away when the task already has an
        // additional window page).
        return setEnabled(taskId, true);
    }

    /** Moves the split point of an existing split; remembers the user preference when persisted. */
    void setSplitRatio(int taskId, float ratio, boolean persist) {
        final IBinder leftToken = mLeftFragments.get(taskId);
        final IBinder rightToken = mRightFragments.get(taskId);
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        if (task == null || leftToken == null || rightToken == null) {
            return;
        }
        final Rect taskBounds = mTaskBounds.get(taskId);
        if (taskBounds == null) {
            return;
        }
        final float clampedRatio = ParallelWorldConfig.clampRatio(ratio);
        mSplitRatios.put(taskId, clampedRatio);
        if (persist) {
            final String packageName = getTaskPackage(task);
            if (packageName != null) {
                ParallelWorldConfig.get().setUserRatio(mAtmService.mContext, packageName,
                        clampedRatio);
            }
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        updateContainersInTask(wct, taskId, taskBounds, task.getConfiguration());
        if (!wct.isEmpty()) {
            applyTransaction(wct, 0, false);
        }
        if (persist) {
            // Resizing the fragments does not change the task bounds, so no task info change is
            // sent for it. Refresh it once the ratio is persisted so that the shell (which caches
            // the task info) picks up the new ratio.
            task.dispatchTaskInfoChangedIfNeeded(true /* force */);
        }
    }

    /** The current split ratio of the task, or {@code 0} when it is not split. */
    public float getSplitRatio(int taskId) {
        final Float ratio = mSplitRatios.get(taskId);
        return ratio != null ? ratio : 0f;
    }

    /**
     * Whether the split of the task is fully set up: both fragments exist and the task has been
     * expanded to the expected width. Until then the Shell must not show the divider, otherwise
     * it would be positioned with the not yet expanded task bounds.
     */
    public boolean isSplitReady(int taskId) {
        final IBinder leftToken = mLeftFragments.get(taskId);
        final IBinder rightToken = mRightFragments.get(taskId);
        if (leftToken == null || rightToken == null
                || mFragmentInfos.get(leftToken) == null || mFragmentInfos.get(rightToken) == null) {
            return false;
        }
        final Integer expectedWidth = mExpectedExpandedWidths.get(taskId);
        if (expectedWidth == null) {
            return true;
        }
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        return task != null && task.getBounds().width() == expectedWidth;
    }

    /** Notifies the task organizer listeners that the task info changed. */
    private void dispatchTaskInfoChanged(int taskId) {
        final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        if (task != null) {
            task.dispatchTaskInfoChangedIfNeeded(false /* force */);
        }
    }

    @Nullable
    private static String getTaskPackage(@NonNull Task task) {
        if (task.realActivity != null) {
            return task.realActivity.getPackageName();
        }
        final ActivityRecord top = task.getTopNonFinishingActivity();
        return top != null ? top.packageName : null;
    }

    /** Keeps the left/right fragments in sync with the current task bounds. */
    void updateContainersInTask(WindowContainerTransaction wct, int taskId, Rect taskBounds,
            Configuration configuration) {
        final IBinder rightToken = mRightFragments.get(taskId);
        if (rightToken == null) {
            // Only one activity left: let the left fragment fill the whole task.
            resizeTaskFragment(wct, mLeftFragments.get(taskId), null);
            return;
        }
        final float ratio = mSplitRatios.get(taskId, DEFAULT_SPLIT_RATIO);
        final int totalWidth = taskBounds.width();
        final int leftWidth = Math.round(totalWidth * (1 - ratio));
        resizeTaskFragment(wct, mLeftFragments.get(taskId),
                new Rect(0, 0, leftWidth, taskBounds.height()));
        resizeTaskFragment(wct, rightToken,
                new Rect(leftWidth, 0, totalWidth, taskBounds.height()));
        final int windowingMode = configuration.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            updateWindowingMode(wct, mLeftFragments.get(taskId), WINDOWING_MODE_MULTI_WINDOW);
            updateWindowingMode(wct, rightToken, WINDOWING_MODE_MULTI_WINDOW);
        } else if (windowingMode == WINDOWING_MODE_FREEFORM) {
            updateWindowingMode(wct, mLeftFragments.get(taskId), WINDOWING_MODE_FREEFORM);
            updateWindowingMode(wct, rightToken, WINDOWING_MODE_FREEFORM);
        }
    }

    void updateWindowingMode(@NonNull WindowContainerTransaction wct,
            @Nullable IBinder fragmentToken, int windowingMode) {
        final TaskFragmentInfo info = fragmentToken == null ? null : mFragmentInfos.get(fragmentToken);
        if (info == null) {
            return;
        }
        wct.setWindowingMode(info.getToken(), windowingMode);
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @Nullable IBinder fragmentToken,
            @Nullable Rect relativeBounds) {
        final TaskFragmentInfo info = fragmentToken == null ? null : mFragmentInfos.get(fragmentToken);
        if (info == null) {
            return;
        }
        wct.setRelativeBounds(info.getToken(), relativeBounds != null ? relativeBounds : new Rect());
    }

    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        super.onTransactionReady(transaction);
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        for (TaskFragmentTransaction.Change change : changes) {
            final int taskId = change.getTaskId();
            final TaskFragmentInfo info = change.getTaskFragmentInfo();
            switch (change.getType()) {
                case TYPE_TASK_FRAGMENT_APPEARED:
                    updateTaskFragmentInfo(info);
                    break;
                case TYPE_TASK_FRAGMENT_INFO_CHANGED:
                    updateTaskFragmentInfo(info);
                    onTaskFragmentInfoChanged(wct, info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_VANISHED:
                    onTaskFragmentVanished(wct, info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED:
                    onTaskFragmentParentInfoChanged(wct, taskId,
                            change.getTaskFragmentParentInfo());
                    break;
                case TYPE_TASK_FRAGMENT_ERROR:
                    logTaskFragmentError(change);
                    break;
                case TYPE_ACTIVITY_REPARENTED_TO_TASK:
                    break;
                default:
                    break;
            }
        }
        if (!wct.isEmpty()) {
            applyTransaction(wct, 0, false);
        }
    }

    private void logTaskFragmentError(TaskFragmentTransaction.Change change) {
        final Bundle errorBundle = change.getErrorBundle();
        final Throwable throwable = errorBundle == null ? null
                : (Throwable) errorBundle.getSerializable(KEY_ERROR_CALLBACK_THROWABLE);
        final TaskFragmentInfo info = errorBundle == null ? null
                : errorBundle.getParcelable(KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO,
                        TaskFragmentInfo.class);
        final int opType = errorBundle == null ? -1
                : errorBundle.getInt(KEY_ERROR_CALLBACK_OP_TYPE, -1);
        final IBinder errorToken = change.getErrorCallbackToken();
        final Integer taskId = errorToken != null
                ? mErrorCallbackTokens.remove(errorToken) : null;
        Slog.e(TAG, "task fragment operation failed, task=" + taskId + " op=" + opType
                + " info=" + info + (throwable != null ? ": " + throwable : ""));
        if (taskId != null && !isSplitMaterialized(taskId)) {
            // The split never materialized, reset the task so that its launch params are
            // persisted again and a later attempt can retry.
            final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
            if (task != null) {
                task.type = Task.NOT_MAGIC_WINDOW;
            }
            clearTaskState(taskId);
        }
    }

    void updateTaskFragmentInfo(@Nullable TaskFragmentInfo taskFragmentInfo) {
        if (taskFragmentInfo == null) {
            return;
        }
        mFragmentInfos.put(taskFragmentInfo.getFragmentToken(), taskFragmentInfo);
    }

    void onTaskFragmentInfoChanged(WindowContainerTransaction wct,
            @Nullable TaskFragmentInfo taskFragmentInfo, int taskId) {
        if (taskFragmentInfo == null) {
            return;
        }
        if (!taskFragmentInfo.hasRunningActivity()) {
            Slog.d(TAG, "onTaskFragmentInfoChanged: fragment has no activity, delete it. task="
                    + taskId + " token=" + taskFragmentInfo.getFragmentToken());
            deleteTaskFragment(wct, taskFragmentInfo);
            mFragmentInfos.remove(taskFragmentInfo.getFragmentToken());
            return;
        }
        pauseLeftIfNeed(taskFragmentInfo, taskId);
    }

    void pauseLeftIfNeed(IBinder leftToken) {
        final TaskFragmentInfo leftInfo = mFragmentInfos.get(leftToken);
        if (leftInfo == null) {
            return;
        }
        final List<IBinder> activities = leftInfo.getActivities();
        if (activities == null || activities.isEmpty()) {
            return;
        }
        pauseLeftIfNeed(ActivityRecord.forTokenLocked(activities.get(activities.size() - 1)));
    }

    /**
     * Pauses the main window when the app needs it (see the {@code pauseLeft} config). The pause
     * strategy is controlled by {@link ParallelWorldConfig#PROP_PAUSE_MODE}.
     */
    void pauseLeftIfNeed(@Nullable ActivityRecord topActivity) {
        if (topActivity == null) {
            return;
        }
        final ParallelWorldConfig config = ParallelWorldConfig.get();
        if (!config.isPauseLeftEnabled(topActivity.packageName)) {
            return;
        }
        if (config.isTaskFragmentPauseMode()) {
            final TaskFragment taskFragment = topActivity.getTaskFragment();
            if (taskFragment != null && taskFragment.getResumedActivity() != null) {
                Slog.d(TAG, "pauseLeftIfNeed: pause " + topActivity + " through TaskFragment");
                taskFragment.startPausing(false /* userLeaving */, false /* uiSleeping */,
                        null /* resuming */, PAUSE_REASON);
            }
        } else {
            Slog.d(TAG, "pauseLeftIfNeed: pause " + topActivity);
            topActivity.pauseActivityLockedOnly();
        }
    }

    void pauseLeftIfNeed(TaskFragmentInfo taskFragmentInfo, int taskId) {
        final IBinder rightToken = mRightFragments.get(taskId);
        if (rightToken != null && rightToken.equals(taskFragmentInfo.getFragmentToken())) {
            pauseLeftIfNeed(mLeftFragments.get(taskId));
        }
    }

    void deleteTaskFragment(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo) {
        if (taskFragmentInfo == null || taskFragmentInfo.getFragmentToken() == null
                || mFragmentInfos.get(taskFragmentInfo.getFragmentToken()) == null) {
            return;
        }
        wct.deleteTaskFragment(taskFragmentInfo.getFragmentToken());
    }

    void onTaskFragmentVanished(WindowContainerTransaction wct,
            @Nullable TaskFragmentInfo taskFragmentInfo, int taskId) {
        if (taskFragmentInfo == null) {
            return;
        }
        final IBinder token = taskFragmentInfo.getFragmentToken();
        Slog.d(TAG, "onTaskFragmentVanished: task=" + taskId + " token=" + token);
        if (token.equals(mRightFragments.get(taskId))) {
            contractTask(taskId);
        } else if (token.equals(mLeftFragments.get(taskId))) {
            // The main window is gone, finish the additional window as well.
            final TaskFragmentInfo rightInfo = mFragmentInfos.get(mRightFragments.get(taskId));
            if (rightInfo != null && rightInfo.getActivities() != null) {
                for (IBinder activityToken : rightInfo.getActivities()) {
                    wct.finishActivity(activityToken);
                }
            } else {
                final ActivityRecord secondary = mSplittingActivities.get(taskId);
                if (secondary != null) {
                    wct.finishActivity(secondary.token);
                }
            }
            clearTaskState(taskId);
        }
        mFragmentInfos.remove(token);
    }

    /** Shrinks the task back to the main window width and clears the split state. */
    private void contractTask(int taskId) {
        final Rect taskBounds = mTaskBounds.get(taskId);
        final Float ratio = mSplitRatios.get(taskId);
        mAtmService.mH.post(() -> {
            final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
            if (task == null) {
                clearTaskState(taskId);
                return;
            }
            final Rect bounds = taskBounds != null ? taskBounds : new Rect(task.getBounds());
            final float splitRatio = ratio != null ? ratio : DEFAULT_SPLIT_RATIO;
            final Rect newTaskBounds = new Rect(bounds.left, bounds.top,
                    bounds.left + Math.round(bounds.width() * (1 - splitRatio)), bounds.bottom);
            Slog.d(TAG, "contractTask: task=" + taskId + " bounds=" + newTaskBounds);
            // Reset the type before resizing: the task info update sent below is used by the
            // window decoration to remove the divider, it must not see the task as split with the
            // already contracted bounds.
            task.type = Task.NOT_MAGIC_WINDOW;
            // Resize the task directly instead of ActivityTaskManagerService#resizeTask: that one
            // wraps the resize into a TRANSIT_CHANGE transition, which animates the contraction.
            // The additional window has to disappear and the task has to shrink immediately.
            task.resize(newTaskBounds, 0 /* resizeMode */, false /* preserveWindow */);
            // The resize above does not go through a transition, so no task info update is sent
            // for it: the shell would keep the window decoration (caption, shadow, divider) at the
            // old, wider bounds and leave an empty area where the additional window was. Refresh
            // the task info so that the shell relayouts the decoration right away.
            task.dispatchTaskInfoChangedIfNeeded(true /* force */);
            // Keep the left fragment registered: if the task is resized later, the fragment has
            // to be resized to fill the task (see updateContainersInTask).
            mRightFragments.remove(taskId);
            mSplitRatios.remove(taskId);
            mExpectedExpandedWidths.remove(taskId);
            mSplittingActivities.remove(taskId);
            removeErrorCallback(taskId);
        });
    }

    /** Resets the task after a failed split. */
    private void rollbackSplit(@Nullable Task task) {
        if (task == null) {
            return;
        }
        Slog.w(TAG, "rollbackSplit: task=" + task.mTaskId);
        task.type = Task.NOT_MAGIC_WINDOW;
        clearTaskState(task.mTaskId);
    }

    private void clearTaskState(int taskId) {
        mLeftFragments.remove(taskId);
        mRightFragments.remove(taskId);
        mSplitRatios.remove(taskId);
        mTaskBounds.remove(taskId);
        mParentConfigs.remove(taskId);
        mDisplayIds.remove(taskId);
        mExpectedExpandedWidths.remove(taskId);
        mSplittingActivities.remove(taskId);
        removeErrorCallback(taskId);
    }

    private IBinder registerErrorCallback(int taskId) {
        final IBinder token = new Binder();
        mErrorCallbackTokens.put(token, taskId);
        return token;
    }

    private void removeErrorCallback(int taskId) {
        for (int i = mErrorCallbackTokens.size() - 1; i >= 0; --i) {
            if (mErrorCallbackTokens.valueAt(i) == taskId) {
                mErrorCallbackTokens.removeAt(i);
            }
        }
    }

    private boolean isSplitMaterialized(int taskId) {
        final IBinder leftToken = mLeftFragments.get(taskId);
        return leftToken != null && mFragmentInfos.get(leftToken) != null;
    }

    private void markExpandTransition(int taskId, int expectedWidth) {
        if (expectedWidth <= 0) {
            return;
        }
        mExpectedExpandedWidths.put(taskId, expectedWidth);
        mAtmService.mH.postDelayed(() -> {
            final Integer expected = mExpectedExpandedWidths.get(taskId);
            if (expected == null || expected != expectedWidth) {
                return;
            }
            Slog.w(TAG, "expand transition timeout for taskId=" + taskId);
            mExpectedExpandedWidths.remove(taskId);
            if (!isSplitMaterialized(taskId)) {
                // Self-heal: the fragments were never created, reset the task.
                final Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
                if (task != null) {
                    task.type = Task.NOT_MAGIC_WINDOW;
                }
                clearTaskState(taskId);
            } else {
                // The fragments exist but the expected width was never confirmed; let the Shell
                // show the divider with the current bounds.
                dispatchTaskInfoChanged(taskId);
            }
        }, EXPAND_TRANSITION_TIMEOUT_MS);
    }

    void onTaskFragmentParentInfoChanged(WindowContainerTransaction wct, int taskId,
            TaskFragmentParentInfo parentInfo) {
        if (parentInfo == null) {
            return;
        }
        final Rect taskBounds = parentInfo.getConfiguration().windowConfiguration.getBounds();
        mTaskBounds.put(taskId, new Rect(taskBounds));
        if (shouldUpdateContainer(taskId, parentInfo)) {
            final Integer expected = mExpectedExpandedWidths.get(taskId);
            if (expected == null || taskBounds.width() == expected) {
                updateContainersInTask(wct, taskId, taskBounds, parentInfo.getConfiguration());
                if (expected != null) {
                    mExpectedExpandedWidths.remove(taskId);
                    // The task has been expanded, notify the Shell so that it can show the
                    // divider at the final position.
                    dispatchTaskInfoChanged(taskId);
                }
            }
        }
        mParentConfigs.put(taskId, new Configuration(parentInfo.getConfiguration()));
        mDisplayIds.put(taskId, parentInfo.getDisplayId());
    }

    boolean shouldUpdateContainer(int taskId, @NonNull TaskFragmentParentInfo info) {
        final Configuration configuration = info.getConfiguration();
        final Configuration lastConfiguration = mParentConfigs.get(taskId);
        return info.isVisible()
                && !isInPictureInPicture(configuration)
                && (lastConfiguration == null
                        || lastConfiguration.diffPublicOnly(configuration) != 0
                        || mDisplayIds.get(taskId, -1) != info.getDisplayId());
    }

    private static boolean isInPictureInPicture(@NonNull Configuration configuration) {
        return configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    /** Minimum width of the activity, or {@code 0} when it does not declare one. */
    private static int getMinWidth(@NonNull ActivityRecord activity, @NonNull Task task) {
        final Point minDimensions = activity.getMinDimensions(task.getDisplayContent());
        return minDimensions != null ? Math.max(0, minDimensions.x) : 0;
    }

    /** Upper bound of the expanded task: never wider than the display. */
    private static int getMaxExpandedWidth(@NonNull Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(task.getBounds().width(), displayContent.getBounds().width());
    }

    /** Dumps the state of the organizer. */
    public void dump(PrintWriter pw) {
        pw.println("  registered=" + (getOrganizerToken() != null));
        if (mLeftFragments.size() == 0 && mRightFragments.size() == 0) {
            pw.println("  (no parallel world task)");
            return;
        }
        final int size = mLeftFragments.size();
        for (int i = 0; i < size; i++) {
            final int taskId = mLeftFragments.keyAt(i);
            final IBinder left = mLeftFragments.valueAt(i);
            final IBinder right = mRightFragments.get(taskId);
            final Float ratio = mSplitRatios.get(taskId);
            final Rect bounds = mTaskBounds.get(taskId);
            final ActivityRecord secondary = mSplittingActivities.get(taskId);
            pw.println("  task=" + taskId
                    + " left=" + left
                    + " right=" + right
                    + " ratio=" + ratio
                    + " bounds=" + bounds
                    + " expectedWidth=" + mExpectedExpandedWidths.get(taskId)
                    + " secondary=" + secondary
                    + " materialized=" + isSplitMaterialized(taskId));
        }
    }
}
