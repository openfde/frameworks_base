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
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import java.util.List;
import java.util.Map;

/**
 * SystemTaskFragmentOrganizer implements the FDE parallel world (magic window) inside a single
 * task.
 *
 * <p>Core idea: the task is dynamically expanded by the configured ratio and two TaskFragments
 * are created inside it:
 * <ul>
 *     <li>left: the main activity, keeps its original width;</li>
 *     <li>right: the additional activity, lives in the newly expanded area.</li>
 * </ul>
 *
 * <p>Differences from the initial implementation:
 * <ul>
 *     <li>All per-task state is keyed by task id, so multiple parallel world tasks can coexist
 *     (the previous global {@code mConfiguration}/{@code mIsExpandedMode} fields were shared
 *     between tasks);</li>
 *     <li>the split ratio is clamped to a sane range so a bad config cannot create an
 *     off-screen task;</li>
 *     <li>empty transactions are not applied and transaction errors are logged;</li>
 *     <li>the task is contracted using the last known task bounds of that task.</li>
 * </ul>
 */
public class SystemTaskFragmentOrganizer extends TaskFragmentOrganizer {

    private static final String TAG = "SystemTaskFragmentOrganizer";

    /** Time to wait for the task expansion to be applied before giving up on it. */
    private static final long EXPAND_TRANSITION_TIMEOUT_MS = 3000;
    /** Delay before pausing the main activity, to let the additional window settle down. */
    private static final long PAUSE_LEFT_DELAY_MS = 1000;
    /** Keep the ratio in a sane range: neither pane may be smaller than 20% of the task. */
    private static final float MIN_SPLIT_RATIO = 0.2f;
    private static final float MAX_SPLIT_RATIO = 0.8f;
    private static final float DEFAULT_SPLIT_RATIO = 0.5f;

    /** Apps that need special lifecycle handling. */
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String WECHAT_LOGIN_SELECT_UI = "LoginSelectUI";

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
     * @param task           the task that hosts both windows
     * @param primary        the main activity, always placed in the left fragment
     * @param secondary      the additional activity, may be {@code null} when it has to be
     *                       started by the organizer
     * @param secondaryIntent the intent used to start the additional activity when
     *                        {@code secondary} is {@code null}
     * @param ratio          fraction of the task width that belongs to the right fragment
     */
    void startSplit(Task task, ActivityRecord primary, ActivityRecord secondary,
            Intent secondaryIntent, float ratio) {
        if (task == null || primary == null) {
            return;
        }
        if (isWeChatLoginSelectUi(secondary)) {
            // WeChat login page must cover the whole task, never open it in the parallel window.
            Slog.d(TAG, "startSplit: skip WeChat login page");
            return;
        }
        final int taskId = task.mTaskId;
        if (secondary != null && mSplittingActivities.get(taskId) == secondary) {
            Slog.w(TAG, "startSplit: already splitting " + secondary);
            return;
        }

        final float splitRatio = clampRatio(ratio);
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
                Slog.d(TAG, "startSplit: " + secondary + " already in a task fragment");
                return false;
            }
            wct.reparentActivityToTaskFragment(rightToken, secondary.token);
        } else if (secondaryIntent != null) {
            wct.startActivityInTaskFragment(rightToken, primary.token, secondaryIntent, null);
        }
        final Rect bounds = mTaskBounds.get(taskId);
        markExpandTransition(taskId, bounds != null ? bounds.width() : -1);
        if (!wct.isEmpty()) {
            applyTransaction(wct, 0, false);
        }
        return true;
    }

    /** Expands the task and creates the left/right fragments. */
    private void createSplit(int taskId, Task task, ActivityRecord primary,
            ActivityRecord secondary, Intent secondaryIntent, float ratio) {
        mSplitRatios.put(taskId, ratio);
        final Rect taskBounds = new Rect(task.getBounds());
        mTaskBounds.put(taskId, taskBounds);
        final int originalWidth = taskBounds.width();
        final int height = taskBounds.height();
        final int expandedWidth = Math.round(originalWidth / (1 - ratio));
        // Expand the task first so that the fragments are created with their final bounds.
        final Rect newTaskBounds = new Rect(taskBounds.left, taskBounds.top,
                taskBounds.left + expandedWidth, taskBounds.bottom);
        mAtmService.resizeTask(taskId, newTaskBounds, 0);
        markExpandTransition(taskId, expandedWidth);
        mAtmService.mH.post(() -> {
            final IBinder primaryTfToken = new Binder();
            final IBinder secondaryTfToken = new Binder();
            final Rect leftBounds = new Rect(0, 0, originalWidth, height);
            final Rect rightBounds = new Rect(originalWidth, 0, expandedWidth, height);
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            final TaskFragmentCreationParams primaryParams =
                    new TaskFragmentCreationParams.Builder(getOrganizerToken(), primaryTfToken,
                            primary.token)
                            .setInitialRelativeBounds(leftBounds)
                            .build();
            wct.createTaskFragment(primaryParams);
            wct.reparentActivityToTaskFragment(primaryTfToken, primary.token);

            final TaskFragmentCreationParams secondaryParams =
                    new TaskFragmentCreationParams.Builder(getOrganizerToken(), secondaryTfToken,
                            primary.token)
                            .setInitialRelativeBounds(rightBounds)
                            .setPairedPrimaryFragmentToken(primaryTfToken)
                            .build();
            wct.createTaskFragment(secondaryParams);
            if (secondary != null) {
                wct.reparentActivityToTaskFragment(secondaryTfToken, secondary.token);
            } else if (secondaryIntent != null) {
                wct.startActivityInTaskFragment(secondaryTfToken, primary.token, secondaryIntent,
                        null);
            }
            wct.setAdjacentTaskFragments(primaryTfToken, secondaryTfToken,
                    new WindowContainerTransaction.TaskFragmentAdjacentParams());
            wct.setCompanionTaskFragment(primaryTfToken, secondaryTfToken, null /* toBeFinished */);

            mLeftFragments.put(taskId, primaryTfToken);
            mRightFragments.put(taskId, secondaryTfToken);
            if (secondary != null) {
                mSplittingActivities.put(taskId, secondary);
            }
            applyTransaction(wct, 0, false);
        });
    }

    /** Keeps the left/right fragments in sync with the current task bounds. */
    void updateContainersInTask(WindowContainerTransaction wct, int taskId, Rect taskBounds,
            Configuration configuration) {
        final IBinder rightToken = mRightFragments.get(taskId);
        if (rightToken == null) {
            // Only one activity left: let the left fragment fill the whole task.
            Slog.d(TAG, "updateContainersInTask: single window task " + taskId
                    + " bounds=" + taskBounds);
            resizeTaskFragment(wct, mLeftFragments.get(taskId), null);
            return;
        }
        Slog.d(TAG, "updateContainersInTask: task " + taskId + " bounds=" + taskBounds);
        final float ratio = mSplitRatios.get(taskId, DEFAULT_SPLIT_RATIO);
        final int totalWidth = taskBounds.width();
        final int leftWidth = Math.round(totalWidth * (1 - ratio));
        final int rightWidth = totalWidth - leftWidth;
        resizeTaskFragment(wct, mLeftFragments.get(taskId),
                new Rect(0, 0, leftWidth, taskBounds.height()));
        resizeTaskFragment(wct, rightToken,
                new Rect(leftWidth, 0, leftWidth + rightWidth, taskBounds.height()));
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
            Slog.w(TAG, "updateWindowingMode: fragment not ready, token=" + fragmentToken);
            return;
        }
        wct.setWindowingMode(info.getToken(), windowingMode);
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @Nullable IBinder fragmentToken,
            @Nullable Rect relativeBounds) {
        final TaskFragmentInfo info = fragmentToken == null ? null : mFragmentInfos.get(fragmentToken);
        if (info == null) {
            Slog.w(TAG, "resizeTaskFragment: fragment not ready, token=" + fragmentToken);
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
        Slog.e(TAG, "task fragment operation failed, op=" + opType + " info=" + info
                + (throwable != null ? ": " + throwable : ""));
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
        final ActivityRecord topActivity =
                ActivityRecord.forTokenLocked(activities.get(activities.size() - 1));
        pauseLeftIfNeed(topActivity);
    }

    /**
     * WeChat needs the main window to go through a pause/resume cycle before the additional
     * window gets the focus, otherwise the app keeps routing input to the main window.
     */
    void pauseLeftIfNeed(@Nullable ActivityRecord topActivity) {
        if (topActivity != null && WECHAT_PACKAGE.equals(topActivity.packageName)) {
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
            mAtmService.resizeTask(taskId, newTaskBounds, 0);
            task.type = Task.NOT_MAGIC_WINDOW;
            // Keep the left fragment registered: if the task is resized later, the fragment has
            // to be resized to fill the task (see updateContainersInTask).
            mRightFragments.remove(taskId);
            mSplitRatios.remove(taskId);
            mExpectedExpandedWidths.remove(taskId);
            mSplittingActivities.remove(taskId);
        });
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
    }

    private void markExpandTransition(int taskId, int expectedWidth) {
        if (expectedWidth <= 0) {
            return;
        }
        mExpectedExpandedWidths.put(taskId, expectedWidth);
        mAtmService.mH.postDelayed(() -> {
            final Integer expected = mExpectedExpandedWidths.get(taskId);
            if (expected != null && expected == expectedWidth) {
                Slog.w(TAG, "expand transition timeout for taskId=" + taskId);
                mExpectedExpandedWidths.remove(taskId);
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
                mExpectedExpandedWidths.remove(taskId);
            } else {
                Slog.d(TAG, "skip updateContainersInTask during expand transition, taskId=" + taskId
                        + " expectedWidth=" + expected + " actualWidth=" + taskBounds.width());
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

    private static float clampRatio(float ratio) {
        if (ratio <= 0f || ratio >= 1f || Float.isNaN(ratio)) {
            return DEFAULT_SPLIT_RATIO;
        }
        return Math.max(MIN_SPLIT_RATIO, Math.min(MAX_SPLIT_RATIO, ratio));
    }

    private static boolean isWeChatLoginSelectUi(@Nullable ActivityRecord activity) {
        if (activity == null || activity.intent == null
                || activity.intent.getComponent() == null) {
            return false;
        }
        final String className = activity.intent.getComponent().getClassName();
        return className != null && className.contains(WECHAT_LOGIN_SELECT_UI);
    }
}
