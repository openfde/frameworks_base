/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.policy;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserManager.isHeadlessSystemUserMode;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppCompatTaskInfo;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.window.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class DesktopModeCompatPolicy {
    private static final String TAG = "DesktopModeCompatPolicy";

    @NonNull
    private final Context mContext;
    @NonNull
    private final String mSystemUiPackage;
    @NonNull
    private final List<String> mConfigExemptPackages;
    @NonNull
    private final List<String> mConfigTransparentExemptionIgnoreList;
    private final Map<String, Boolean> mPackageInfoCache = new HashMap<>();
    private PackageManager mPackageManager = null;
    @NonNull private final List<String> mConfigHomeFreeformActivities;
    @NonNull private final List<String> mConfigLaunchInFullscreenPackages;
    @NonNull private final List<String> mConfigCaptionInsetCompensationPackages;
    private final int mStatusBarHeightPx;

    public Supplier<String> mDefaultHomePackageSupplier;

    public DesktopModeCompatPolicy(@NonNull Context context) {
        mContext = context;
        mSystemUiPackage = context.getResources().getString(R.string.config_systemUi);
        mConfigExemptPackages = Arrays.asList(context.getResources().getStringArray(
                R.array.config_desktopExemptPackages));
        mConfigTransparentExemptionIgnoreList = Arrays.asList(context.getResources().getStringArray(
                R.array.config_desktopTransparentExemptionIgnoreList));
        mConfigHomeFreeformActivities =
                Arrays.asList(
                        context.getResources()
                                .getStringArray(
                                        R.array.config_desktopHomePackageFreeformActivities));
        mConfigLaunchInFullscreenPackages =
                Arrays.asList(context.getResources().getStringArray(
                        R.array.config_desktopLaunchInFullscreenPackages));
        mConfigCaptionInsetCompensationPackages =
                Arrays.asList(context.getResources().getStringArray(
                        R.array.config_desktopCaptionInsetCompensationPackages));
        // This is the value an app gets when it looks up "status_bar_height" itself, which is what
        // the apps listed above use to size their own placeholder.
        final Resources res = context.getResources();
        final int statusBarHeightId = res.getIdentifier("status_bar_height", "dimen", "android");
        mStatusBarHeightPx = statusBarHeightId > 0
                ? res.getDimensionPixelSize(statusBarHeightId)
                : res.getDimensionPixelSize(R.dimen.status_bar_height_default);
    }

    public void setDefaultHomePackageSupplier(
            @NonNull Supplier<String> defaultHomePackageSupplier) {
        mDefaultHomePackageSupplier = defaultHomePackageSupplier;
    }

    @NonNull
    private PackageManager getPackageManager() {
        if (mPackageManager != null) {
            return mPackageManager;
        }

        mPackageManager = mContext.getPackageManager();
        return mPackageManager;
    }

    @Nullable
    public String getDefaultHomePackage(int userId) {
        if (mDefaultHomePackageSupplier != null) {
            return mDefaultHomePackageSupplier.get();
        }
        final ResolveInfo homeActivityInfo = getPackageManager().resolveActivityAsUser(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0, userId);
        if (homeActivityInfo == null) return null;
        return homeActivityInfo.activityInfo.packageName;
    }

    /**
     * @see #isTopActivityExemptFromDesktopWindowing(ComponentName, boolean, boolean, int, int,
     * ActivityInfo, int)
     */
    public boolean isTopActivityExemptFromDesktopWindowing(@NonNull TaskInfo task) {
        return isTopActivityExemptFromDesktopWindowing(task.baseActivity,
                task.isTopActivityNoDisplay, task.isActivityStackTransparent, task.numActivities,
                task.userId, task.topActivityInfo, task.topActivityType);
    }

    /**
     * If the top activity should be exempt from desktop windowing and forced back to fullscreen.
     * Currently includes all system ui, default home and transparent stack activities with the
     * relevant permission or signature. However if the top activity is not being displayed,
     * regardless of its configuration, we will not exempt it as to remain in the desktop windowing
     * environment.
     */
    public boolean isTopActivityExemptFromDesktopWindowing(@Nullable ComponentName baseActivity,
            boolean isTopActivityNoDisplay, boolean isActivityStackTransparent, int numActivities,
            int userId, ActivityInfo info, @WindowConfiguration.ActivityType int topActivityType) {
        if (isHeadlessSystemUserMode() && userId == USER_SYSTEM) {
            // An activity for system user in HSUM should not activate desktop windowing.
            return true;
        }
        final String packageName = baseActivity != null ? baseActivity.getPackageName() : null;
        final String className = baseActivity != null ? baseActivity.getClassName() : null;
        if (packageName == null) {
            return false;
        }
        // If activity is not being displayed, window mode change has no visual affect so leave
        // unchanged.
        if (isTopActivityNoDisplay) {
            return false;
        }
        // Dream activities should be fullscreen and thus should be forced out of desktop.
        if (topActivityType == ACTIVITY_TYPE_DREAM) {
            return true;
        }
        // If activity belongs to package exempt via device config, force out of desktop.
        if (isPackageExemptViaConfig(packageName) && !isActivityStackTransparent) {
            return true;
        }
        // If activity belongs to system ui package, safe to force out of desktop.
        if (isSystemUiTask(packageName)) {
            return true;
        }
        // If activity belongs to default home package, safe to force out of desktop.
        if (isPartOfDefaultHomePackageOrNoHomeAvailable(packageName, userId, className)) {
            return true;
        }
        // If all activities in task stack are transparent AND package has the relevant
        // fullscreen transparent permission OR is signed with platform key, safe to force out
        // of desktop.
        // TODO: b/458693972 - Replace with permission and manifest check.
        return isTransparentTask(isActivityStackTransparent, numActivities)
                && (hasFullscreenTransparentPermission(packageName, userId, info)
                    || hasPlatformSignature(info)
                    || (Flags.enablePrivilegedAppTransparentWindowingExemptions()
                        && isPrivilegedApp(info)))
                && !mConfigTransparentExemptionIgnoreList.contains(packageName);
    }

    /**
     * @see #shouldDisableDesktopEntryPoints(String, String, int, boolean, boolean, int, int)
     */
    public boolean shouldDisableDesktopEntryPoints(@NonNull TaskInfo task) {
        final String packageName = task.baseActivity != null ? task.baseActivity.getPackageName() :
                null;
        final String className =
                task.baseActivity != null ? task.baseActivity.getClassName() : null;
        return shouldDisableDesktopEntryPoints(
                packageName,
                className,
                task.numActivities,
                task.isTopActivityNoDisplay,
                task.isActivityStackTransparent,
                task.topActivityType,
                task.userId);
    }

    /**
     * Whether all desktop entry points should be disabled for a given activity. Currently includes
     * all system ui, default home, transparent stack and no display activities.
     */
    public boolean shouldDisableDesktopEntryPoints(
            @Nullable String packageName,
            @Nullable String className,
            int numActivities,
            boolean isTopActivityNoDisplay,
            boolean isActivityStackTransparent,
            @WindowConfiguration.ActivityType int topActivityType,
            int userId) {
        if (packageName == null) {
            return true;
        }

        // Activity will not be displayed, no need to show desktop entry point.
        if (isTopActivityNoDisplay) {
            return true;
        }
        // Dream activities should be fullscreen and thus not allowed to enter desktop.
        if (topActivityType == ACTIVITY_TYPE_DREAM) {
            return true;
        }
        // If activity belongs to system ui package, hide desktop entry point.
        if (isSystemUiTask(packageName)) {
            return true;
        }
        // If activity belongs to default home package, safe to force out of desktop.
        if (isPartOfDefaultHomePackageOrNoHomeAvailable(packageName, userId, className)) {
            return true;
        }
        // If activity belongs to package exempt via device config, hide desktop entry point.
        if (isPackageExemptViaConfig(packageName)) {
            return true;
        }
        // If all activities in task stack are transparent AND package has the relevant fullscreen
        // transparent permission, safe to force out of desktop.
        return isTransparentTask(isActivityStackTransparent, numActivities);
    }


    /** @see DesktopModeCompatUtils#shouldExcludeCaptionFromAppBounds */
    public boolean shouldExcludeCaptionFromAppBounds(@NonNull TaskInfo taskInfo) {
        final AppCompatTaskInfo appCompatInfo = taskInfo.appCompatTaskInfo;
        if (Flags.refactorCaptionSandboxingToCore()) {
            return appCompatInfo != null && appCompatInfo.hasIsExcludeCaptionInsets();
        }
        if (taskInfo.topActivityInfo != null) {
            return DesktopModeCompatUtils.shouldExcludeCaptionFromAppBounds(
                    taskInfo.topActivityInfo,
                    taskInfo.isResizeable,
                    appCompatInfo != null && appCompatInfo.hasOptOutEdgeToEdge(),
                    appCompatInfo != null && appCompatInfo.hasIsExcludeCaptionInsets()
            );
        }
        return false;
    }

    /**
     * Returns true if all activities in a tasks stack are transparent. If there are no activities
     * will return false.
     */
    public boolean isTransparentTask(@NonNull TaskInfo task) {
        return isTransparentTask(task.isActivityStackTransparent, task.numActivities);
    }

    /**
     * System property that controls, at runtime, which apps get the caption inset compensation
     * described in {@link #getCaptionInsetCompensationPx(TaskInfo)}.
     *
     * <p>Accepted values (entries are "package" or "package/.Class", separated by comma, colon,
     * semicolon or whitespace):
     * <ul>
     *   <li>empty / unset: only the apps listed in
     *       {@code config_desktopCaptionInsetCompensationPackages}</li>
     *   <li>{@code none} or {@code 0}: disable the compensation completely (kill switch)</li>
     *   <li>{@code default} or {@code config}: back to the config list only (same as unset); use
     *       this instead of clearing the property, {@code adb shell} drops empty arguments on some
     *       platforms</li>
     *   <li>{@code all}: compensate every app. Debug only: content of apps that do not reserve the
     *       status bar height by themselves slides under the caption bar.</li>
     *   <li>otherwise: entries are added on top of the config list; a leading '-' removes an app
     *       (e.g. {@code -com.jingdong.app.mall})</li>
     * </ul>
     *
     * <p>Example:
     * <pre>
     * adb shell setprop persist.wm.debug.desktop_caption_inset_compensation \
     *     com.jingdong.app.mall,com.tencent.android.qqdownloader
     * </pre>
     *
     * <p>The new value is picked up by the next window decoration relayout (focus or size change),
     * so after changing it, refocus the window or resize it once.
     */
    public static final String PROP_CAPTION_INSET_COMPENSATION =
            "persist.wm.debug.desktop_caption_inset_compensation";

    /**
     * Master switch for the caption inset compensation implemented below.
     *
     * <p>Currently disabled: the behaviour is being replaced by making the status bar height that
     * apps read resolve to 0 for freeform windows. The whole implementation - config array,
     * runtime property and the shell side hook in CaptionController - is kept, so flipping this to
     * {@code true} brings the previous behaviour back without any other change.
     */
    private static final boolean ENABLE_CAPTION_INSET_COMPENSATION = false;

    private static final String PROP_VALUE_ALL = "all";
    private static final String PROP_VALUE_NONE = "none";
    private static final String PROP_VALUE_OFF = "0";
    private static final String PROP_VALUE_DEFAULT = "default";
    private static final String PROP_VALUE_CONFIG = "config";

    @Nullable private String mPropValueCache;
    @NonNull private List<String> mPropEntries = Collections.emptyList();
    @NonNull private List<String> mPropExcludedEntries = Collections.emptyList();
    private boolean mPropCompensateAll;
    private boolean mPropDisabled;

    /**
     * Returns the number of pixels that the caption bar inset should be reduced by for the given
     * task, so that an app's own status-bar-height placeholder ends up hidden behind the window
     * decoration caption bar instead of showing up as a gap between the caption and the app's
     * content.
     *
     * <p>The set of apps that get a non-zero value is
     * {@code config_desktopCaptionInsetCompensationPackages} plus whatever
     * {@link #PROP_CAPTION_INSET_COMPENSATION} adds at runtime. For any other app reducing the
     * inset would let content slide under the caption bar.
     */
    public int getCaptionInsetCompensationPx(@NonNull TaskInfo taskInfo) {
        if (!ENABLE_CAPTION_INSET_COMPENSATION || mStatusBarHeightPx <= 0) {
            return 0;
        }
        // Only windows of the desktop (freeform) windowing mode are covered by the caption bar
        // overlay; fullscreen/split windows keep the regular system bar behavior.
        if (taskInfo.getWindowingMode() != WindowConfiguration.WINDOWING_MODE_FREEFORM) {
            return 0;
        }
        refreshRuntimeEntriesIfNeeded();
        if (mPropDisabled) {
            return 0;
        }
        final ComponentName component = getTopComponent(taskInfo);
        if (component == null) {
            return 0;
        }
        if (matchesAny(mPropExcludedEntries, component)) {
            return 0;
        }
        if (!mPropCompensateAll
                && !matchesAny(mConfigCaptionInsetCompensationPackages, component)
                && !matchesAny(mPropEntries, component)) {
            return 0;
        }
        Slog.d(TAG, "Reducing the caption inset by " + mStatusBarHeightPx + "px for task "
                + taskInfo.taskId + " (" + component + ")");
        return mStatusBarHeightPx;
    }

    /**
     * Returns the component of the top activity of the task.
     *
     * <p>Note: the {@link TaskInfo}s delivered to the shell do not always carry
     * {@link TaskInfo#topActivityInfo}, so the component names have to be used as a fallback (the
     * shell itself relies on {@link TaskInfo#baseActivity}).
     */
    @Nullable
    private static ComponentName getTopComponent(@NonNull TaskInfo taskInfo) {
        final ActivityInfo info = taskInfo.topActivityInfo;
        if (info != null && info.packageName != null && info.name != null) {
            return new ComponentName(info.packageName, info.name);
        }
        if (taskInfo.topActivity != null) {
            return taskInfo.topActivity;
        }
        return taskInfo.baseActivity;
    }

    /** Reads the runtime entries from {@link #PROP_CAPTION_INSET_COMPENSATION} when it changed. */
    private void refreshRuntimeEntriesIfNeeded() {
        final String value;
        try {
            value = SystemProperties.get(PROP_CAPTION_INSET_COMPENSATION, "");
        } catch (RuntimeException e) {
            return;
        }
        if (value.equals(mPropValueCache)) {
            return;
        }
        mPropValueCache = value;
        mPropEntries = Collections.emptyList();
        mPropExcludedEntries = Collections.emptyList();
        mPropCompensateAll = false;
        mPropDisabled = false;

        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            Slog.d(TAG, "Caption inset compensation: config list only");
            return;
        }
        if (trimmed.equalsIgnoreCase(PROP_VALUE_NONE) || trimmed.equals(PROP_VALUE_OFF)) {
            mPropDisabled = true;
            Slog.d(TAG, "Caption inset compensation: disabled by "
                    + PROP_CAPTION_INSET_COMPENSATION);
            return;
        }
        if (trimmed.equalsIgnoreCase(PROP_VALUE_DEFAULT)
                || trimmed.equalsIgnoreCase(PROP_VALUE_CONFIG)) {
            // Same as unset: only the config list applies. Lets the property be reset without
            // having to clear it (adb shell drops empty arguments on some platforms).
            Slog.d(TAG, "Caption inset compensation: config list only (via "
                    + PROP_CAPTION_INSET_COMPENSATION + ")");
            return;
        }
        if (trimmed.equalsIgnoreCase(PROP_VALUE_ALL)) {
            mPropCompensateAll = true;
            Slog.d(TAG, "Caption inset compensation: ALL apps via "
                    + PROP_CAPTION_INSET_COMPENSATION);
            return;
        }
        final String[] parts = trimmed.split("[,:;\\s]+");
        final ArrayList<String> entries = new ArrayList<>(parts.length);
        final ArrayList<String> excluded = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            final boolean excludedEntry = part.charAt(0) == '-';
            final String entry = excludedEntry ? part.substring(1) : part;
            if (entry.isEmpty()) {
                continue;
            }
            final List<String> target = excludedEntry ? excluded : entries;
            if (!target.contains(entry)) {
                target.add(entry);
            }
        }
        mPropEntries = entries;
        mPropExcludedEntries = excluded;
        Slog.d(TAG, "Caption inset compensation via " + PROP_CAPTION_INSET_COMPENSATION
                + ": +" + entries + " -" + excluded);
    }

    private static boolean matchesAny(@NonNull List<String> entries,
            @NonNull ComponentName component) {
        final String packageName = component.getPackageName();
        if (packageName == null) {
            return false;
        }
        final String className = component.getClassName();
        for (int i = 0; i < entries.size(); i++) {
            if (matchesComponent(entries.get(i), packageName, className)) {
                return true;
            }
        }
        return false;
    }

    /** Matches a config entry against a component; entry is "pkg" or "pkg/.Class". */
    private static boolean matchesComponent(@NonNull String entry, @NonNull String packageName,
            @Nullable String className) {
        final String trimmed = entry.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        final int slash = trimmed.indexOf('/');
        if (slash < 0) {
            // Package level entry.
            return trimmed.equals(packageName);
        }
        if (!trimmed.substring(0, slash).equals(packageName)) {
            return false;
        }
        if (className == null) {
            return false;
        }
        String entryClass = trimmed.substring(slash + 1);
        if (entryClass.startsWith(".")) {
            entryClass = packageName + entryClass;
        }
        return entryClass.equals(className);
    }

    /** Returns true if the package should be launched in fullscreen by default when in desktop. */
    public boolean isPackageLaunchInFullscreen(@Nullable ComponentName baseActivity) {
        if (!Flags.launchGamesInFullscreenByAllowlist()) return false;
        if (baseActivity == null) return false;
        final String packageName = baseActivity.getPackageName();
        if (packageName == null) return false;
        return mConfigLaunchInFullscreenPackages.contains(packageName);
    }

    private boolean isTransparentTask(boolean isActivityStackTransparent, int numActivities) {
        return isActivityStackTransparent && numActivities > 0;
    }

    private boolean isSystemUiTask(@Nullable String packageName) {
        return Objects.equals(packageName, mSystemUiPackage);
    }

    private boolean isPackageExemptViaConfig(@NonNull String packageName) {
        return mConfigExemptPackages.contains(packageName);
    }

    // Checks if the app for the given package has the SYSTEM_ALERT_WINDOW permission.
    private boolean hasFullscreenTransparentPermission(
            @NonNull String packageName, int userId, ActivityInfo info) {
        if (info != null && info.applicationInfo != null) {
            int uid = info.applicationInfo.uid;
            Boolean appOpState = Settings.isCallingPackageAllowedToDrawOverlays(
                    mContext, uid, packageName, false);
            if (appOpState) return true;
        }

        final String cacheKey = userId + "@" + packageName;
        if (mPackageInfoCache.containsKey(cacheKey)) {
            return mPackageInfoCache.get(cacheKey);
        }
        boolean hasPermission = false;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfoAsUser(
                    packageName,
                    PackageManager.GET_PERMISSIONS,
                    userId
            );
            if (packageInfo != null && packageInfo.requestedPermissions != null) {
                for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                    if (Objects.equals(
                            packageInfo.requestedPermissions[i],
                            Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                        if ((packageInfo.requestedPermissionsFlags[i]
                                & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                            hasPermission = true;
                        }
                        break;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Package not found, so no permission
        }
        mPackageInfoCache.put(cacheKey, hasPermission);
        return hasPermission;
    }

    // Checks if the app is signed with the platform signature.
    private boolean hasPlatformSignature(@Nullable ActivityInfo info) {
        return info != null
                && info.applicationInfo != null
                && info.applicationInfo.isSignedWithPlatformKey();
    }

    // Checks if the app is a privileged application.
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    private boolean isPrivilegedApp(@Nullable ActivityInfo info) {
        return info != null
                && info.applicationInfo != null
                && info.applicationInfo.isPrivilegedApp();
    }

    /**
     * Returns true if the tasks base activity is part of the default home package, or there is
     * currently no default home package available.
     */
    public boolean isPartOfDefaultHomePackageOrNoHomeAvailable(
            @NonNull String packageName, int userId, @Nullable String className) {
        final String defaultHomePackage = getDefaultHomePackage(userId);
        return (defaultHomePackage == null || packageName.equals(defaultHomePackage))
                && (className == null || !mConfigHomeFreeformActivities.contains(className));
    }

    /**
    * Returns true if the task is transparent and full screen.
    * Examples are Gemini and circle to search that overlay on top of other apps.
    */
    public boolean isTransparentOverlay(@NonNull TaskInfo task) {
        return isTransparentOverlay(task.isActivityStackTransparent, task.numActivities,
                task.getWindowingMode());
    }

   /**
    * Returns true if the task is transparent and full screen.
    * Examples are Gemini and circle to search that overlay on top of other apps.
    */
    public boolean isTransparentOverlay(boolean isActivityStackTransparent,
            int numActivities, int windowingMode) {
        return isTransparentTask(isActivityStackTransparent, numActivities)
                && windowingMode == WINDOWING_MODE_FULLSCREEN;
    }
}