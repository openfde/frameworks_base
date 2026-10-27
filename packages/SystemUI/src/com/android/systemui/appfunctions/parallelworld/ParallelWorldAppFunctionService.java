/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.appfunctions.parallelworld;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskInfo;
import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionService;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appsearch.GenericDocument;
import android.content.pm.SigningInfo;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exposes the FDE parallel world (magic window) switch of an application window as an app
 * function.
 *
 * <p>The function is hosted by SystemUI because switching the parallel world requires the {@link
 * android.Manifest.permission#MANAGE_ACTIVITY_TASKS} permission. The target window is selected by
 * package name (choosing the focused/visible task when the application has multiple windows) or
 * directly by task id.
 */
public class ParallelWorldAppFunctionService extends AppFunctionService {
    private static final String TAG = "ParallelWorldAppFn";

    /** Opens or closes the parallel world of the target window. */
    public static final String FUNCTION_SET_PARALLEL_WORLD_ENABLED =
            "setParallelWorldEnabled";

    /** Package name of the application whose window is controlled. */
    public static final String PARAM_PACKAGE_NAME = "packageName";
    /** Whether the parallel world should be opened ({@code true}) or closed ({@code false}). */
    public static final String PARAM_ENABLED = "enabled";
    /** Optional id of the target task, used to disambiguate windows of the same application. */
    public static final String PARAM_TASK_ID = "taskId";

    /** Result schema type returned by this service. */
    public static final String RESULT_SCHEMA_TYPE = "ParallelWorldResult";
    public static final String RESULT_PACKAGE_NAME = "packageName";
    public static final String RESULT_TASK_ID = "taskId";
    public static final String RESULT_ENABLED = "enabled";
    public static final String RESULT_SUCCESS = "success";

    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        final Thread thread = new Thread(runnable, TAG);
                        thread.setDaemon(true);
                        return thread;
                    });

    @Override
    public void onExecuteFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull String callingPackage,
            @NonNull SigningInfo callingPackageSigningInfo,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback) {
        if (!FUNCTION_SET_PARALLEL_WORLD_ENABLED.equals(request.getFunctionIdentifier())) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_FUNCTION_NOT_FOUND,
                            "Unknown function: " + request.getFunctionIdentifier()));
            return;
        }

        final GenericDocument parameters = request.getParameters();
        final String packageName = parameters.getPropertyString(PARAM_PACKAGE_NAME);
        final Boolean enabled = parameters.getPropertyBoolean(PARAM_ENABLED);
        final Long taskId = parameters.getPropertyLong(PARAM_TASK_ID);
        if (enabled == null) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "Missing required parameter: " + PARAM_ENABLED));
            return;
        }
        if (TextUtils.isEmpty(packageName) && (taskId == null || taskId <= 0)) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "Missing required parameter: " + PARAM_PACKAGE_NAME));
            return;
        }

        mExecutor.execute(
                () -> {
                    if (cancellationSignal.isCanceled()) {
                        reportError(
                                callback,
                                new AppFunctionException(
                                        AppFunctionException.ERROR_CANCELLED,
                                        "The request was cancelled"));
                        return;
                    }
                    try {
                        executeSetParallelWorldEnabled(
                                packageName, enabled, taskId, callback);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to change the parallel world state", e);
                        reportError(
                                callback,
                                new AppFunctionException(
                                        AppFunctionException.ERROR_SYSTEM_ERROR,
                                        e.getMessage()));
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Unexpected failure", e);
                        reportError(
                                callback,
                                new AppFunctionException(
                                        AppFunctionException.ERROR_SYSTEM_ERROR,
                                        e.getMessage()));
                    }
                });
    }

    private void executeSetParallelWorldEnabled(
            @Nullable String packageName,
            boolean enabled,
            @Nullable Long requestedTaskId,
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback)
            throws RemoteException {
        final ActivityTaskManager activityTaskManager =
                getSystemService(ActivityTaskManager.class);
        if (activityTaskManager == null) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_SYSTEM_ERROR,
                            "ActivityTaskManager is unavailable"));
            return;
        }

        final TaskInfo task = findTask(activityTaskManager, packageName, requestedTaskId);
        if (task == null) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "No window found for " + describeTarget(packageName, requestedTaskId)));
            return;
        }
        if (!TextUtils.isEmpty(packageName) && !matchesPackage(task, packageName)) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "Task " + task.taskId + " does not belong to " + packageName));
            return;
        }

        final String resultPackageName =
                !TextUtils.isEmpty(packageName)
                        ? packageName
                        : getTaskPackageName(task);
        if (resultPackageName == null) {
            reportError(
                    callback,
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "Unable to resolve the package of task " + task.taskId));
            return;
        }
        final boolean accepted;
        if (enabled && (task.magicWindowEnabled
                || task.magicWindowType == TaskInfo.MAGIC_WINDOW_TYPE_IN_PARALLEL)) {
            // The window is already in the parallel world (or armed for it): do not reconfigure
            // the main window just because the function was called again.
            accepted = true;
        } else if (enabled) {
            // Applications that are not part of the device config have no main window yet, and a
            // package configured by the user may be opened from another page. In both cases the
            // currently visible page becomes the main window, which is the same behavior as
            // confirming the parallel world dialog in the window menu.
            if (task.magicWindowType == TaskInfo.MAGIC_WINDOW_TYPE_NONE
                    || task.magicWindowUserConfigured) {
                accepted = activityTaskManager.getService().configureParallelWorldMain(
                        task.taskId);
            } else {
                accepted = activityTaskManager.getService().setParallelWorldEnabled(
                        task.taskId, true /* enabled */);
            }
        } else {
            accepted = activityTaskManager.getService().setParallelWorldEnabled(
                    task.taskId, false /* enabled */);
        }
        Log.i(
                TAG,
                "setParallelWorldEnabled package="
                        + resultPackageName
                        + " task="
                        + task.taskId
                        + " enabled="
                        + enabled
                        + " accepted="
                        + accepted);

        @SuppressWarnings({"rawtypes", "unchecked"})
        final GenericDocument.Builder resultBuilder =
                new GenericDocument.Builder("", "parallelWorldResult", RESULT_SCHEMA_TYPE);
        resultBuilder.setPropertyString(RESULT_PACKAGE_NAME, resultPackageName);
        resultBuilder.setPropertyLong(RESULT_TASK_ID, task.taskId);
        resultBuilder.setPropertyBoolean(RESULT_ENABLED, enabled);
        resultBuilder.setPropertyBoolean(RESULT_SUCCESS, accepted);
        resultBuilder.setPropertyBoolean(
                ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE, accepted);
        reportResult(callback, new ExecuteAppFunctionResponse(resultBuilder.build()));
    }

    @Nullable
    private TaskInfo findTask(
            @NonNull ActivityTaskManager activityTaskManager,
            @Nullable String packageName,
            @Nullable Long requestedTaskId) {
        final int userId = getUserId();
        final List<ActivityManager.RunningTaskInfo> tasks =
                activityTaskManager.getTasks(Integer.MAX_VALUE);
        if (requestedTaskId != null && requestedTaskId > 0) {
            if (requestedTaskId > Integer.MAX_VALUE) {
                return null;
            }
            for (int i = 0; i < tasks.size(); i++) {
                final ActivityManager.RunningTaskInfo task = tasks.get(i);
                if (task.userId == userId && task.taskId == requestedTaskId.intValue()) {
                    return task;
                }
            }
            return null;
        }
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        TaskInfo visibleMatch = null;
        TaskInfo anyMatch = null;
        for (int i = 0; i < tasks.size(); i++) {
            final ActivityManager.RunningTaskInfo task = tasks.get(i);
            if (task.userId != userId || !matchesPackage(task, packageName)) {
                continue;
            }
            // getTasks() returns the most recent task first. Prefer the focused window over a
            // merely visible one, so that the action applies to the window the user is using.
            if (task.isFocused) {
                return task;
            }
            if (task.isVisible && visibleMatch == null) {
                visibleMatch = task;
            }
            if (anyMatch == null) {
                anyMatch = task;
            }
        }
        return visibleMatch != null ? visibleMatch : anyMatch;
    }

    private static boolean matchesPackage(@NonNull TaskInfo task, @NonNull String packageName) {
        return (task.baseActivity != null
                        && packageName.equals(task.baseActivity.getPackageName()))
                || (task.topActivity != null
                        && packageName.equals(task.topActivity.getPackageName()))
                || (task.realActivity != null
                        && packageName.equals(task.realActivity.getPackageName()));
    }

    @Nullable
    private static String getTaskPackageName(@NonNull TaskInfo task) {
        if (task.baseActivity != null) {
            return task.baseActivity.getPackageName();
        }
        if (task.topActivity != null) {
            return task.topActivity.getPackageName();
        }
        if (task.realActivity != null) {
            return task.realActivity.getPackageName();
        }
        return null;
    }

    private static String describeTarget(
            @Nullable String packageName, @Nullable Long requestedTaskId) {
        if (requestedTaskId != null && requestedTaskId > 0) {
            return "task " + requestedTaskId;
        }
        return "package " + packageName;
    }

    private void reportResult(
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback,
            @NonNull ExecuteAppFunctionResponse response) {
        getMainExecutor().execute(() -> callback.onResult(response));
    }

    private void reportError(
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback,
            @NonNull AppFunctionException exception) {
        getMainExecutor().execute(() -> callback.onError(exception));
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
