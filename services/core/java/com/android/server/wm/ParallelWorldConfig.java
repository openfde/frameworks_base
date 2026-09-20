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

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.CompatibleConfig;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Central configuration of the FDE parallel world (magic window) feature.
 *
 * <p>Configuration has two parts:
 * <ul>
 *     <li>{@code /system/magicwindow_config/magic_config.xml} declares, for each app, the main
 *     activity (the window that stays on the left), the activities that must never be opened in
 *     the parallel window and whether the main window needs a forced pause/resume cycle;</li>
 *     <li>the {@code configMagicWindow} key of the compatibility config framework declares the
 *     width ratio of the two panes, e.g. {@code {"ratio":"4:5"}}.</li>
 * </ul>
 *
 * <p>All the runtime decisions (whether a package is a parallel world package, the type of an
 * activity and the split ratio) are made in the system server, so application processes do not
 * have to be modified and cannot fake the config.
 */
public final class ParallelWorldConfig {

    private static final String TAG = "ParallelWorldConfig";

    /** System property that switches the whole feature on/off. */
    private static final String PROP_ENABLED = "persist.sys.fde.parallel_world";
    /** System property that selects how the main window is paused, see {@link #PAUSE_MODE_LEGACY}. */
    public static final String PROP_PAUSE_MODE = "persist.sys.fde.parallel_world.pause_mode";
    /** Pause the main window with the legacy pause/resume cycle. */
    public static final String PAUSE_MODE_LEGACY = "legacy";
    /** Pause the main window through {@link TaskFragment#startPausing}. */
    public static final String PAUSE_MODE_TASK_FRAGMENT = "tf";

    private static final String CONFIG_DIR = "/system/magicwindow_config";
    private static final String CONFIG_FILE = "magic_config.xml";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_PACKAGE = "packagename";
    private static final String ATTR_MAIN = "main";
    private static final String ATTR_EXCLUDE = "exclude";
    private static final String ATTR_PAUSE_LEFT = "pauseLeft";
    private static final String LIST_SEPARATOR = "/";

    /** Key of the ratio config in the compatibility config framework. */
    private static final String KEY_RATIO = "configMagicWindow";
    /** Settings.Global key prefix of the ratio adjusted by the user. */
    private static final String SETTING_RATIO_PREFIX = "parallel_world_ratio_";
    /** Settings.Global key prefix of the mode chosen by the user for the package. */
    private static final String SETTING_MODE_PREFIX = "parallel_world_mode_";
    /** Settings.Global key prefix of the main activity configured by the user. */
    private static final String SETTING_USER_MAIN_PREFIX = "parallel_world_user_main_";
    /** The user never switched the parallel world of the package: follow the config. */
    public static final int MODE_UNSET = 0;
    /** The user switched the parallel world on: the package always starts split. */
    public static final int MODE_ON = 1;
    /** The user switched the parallel world off: the package never splits. */
    public static final int MODE_OFF = 2;
    private static final float MIN_SPLIT_RATIO = 0.2f;
    private static final float MAX_SPLIT_RATIO = 0.8f;
    private static final float DEFAULT_SPLIT_RATIO = 0.5f;
    /** Default pane ratio (4:5) used when the compatibility config has no ratio entry. */
    private static final float DEFAULT_PANE_RATIO = 5f / 9f;

    private static final ParallelWorldConfig sInstance = new ParallelWorldConfig();

    private final HashMap<String, PackageConfig> mPackages = new HashMap<>();
    /**
     * Packages whose main activity was configured by the user, see {@link #getUserMainActivity}.
     * Kept in memory so that the dumpsys can report them; the value itself lives in Settings.
     */
    private final ArraySet<String> mUserConfiguredPackages = new ArraySet<>();
    /** Application context, set by {@link #load(Context)}, used to read the user configuration. */
    private Context mContext;

    private ParallelWorldConfig() {
    }

    public static ParallelWorldConfig get() {
        return sInstance;
    }

    /** Whether the feature is enabled globally. */
    public boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    /** Whether the main window should be paused through {@link TaskFragment#startPausing}. */
    public boolean isTaskFragmentPauseMode() {
        return PAUSE_MODE_TASK_FRAGMENT.equals(
                SystemProperties.get(PROP_PAUSE_MODE, PAUSE_MODE_LEGACY));
    }

    /**
     * Loads (or reloads) the parallel world config from
     * {@code /system/magicwindow_config/magic_config.xml}.
     */
    public void load(Context context) {
        mContext = context != null ? context.getApplicationContext() : null;
        final File configFile = new File(CONFIG_DIR, CONFIG_FILE);
        if (!configFile.exists()) {
            Slog.i(TAG, "Didn't find parallel world config file: " + configFile);
            return;
        }
        final HashMap<String, PackageConfig> packages = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG
                        || !TAG_PACKAGE.equals(parser.getName())) {
                    continue;
                }
                String packageName = null;
                String main = null;
                String exclude = null;
                boolean pauseLeft = false;
                for (int i = 0; i < parser.getAttributeCount(); ++i) {
                    final String attrName = parser.getAttributeName(i);
                    final String attrValue = parser.getAttributeValue(i);
                    switch (attrName) {
                        case ATTR_PACKAGE:
                            packageName = attrValue;
                            break;
                        case ATTR_MAIN:
                            main = attrValue;
                            break;
                        case ATTR_EXCLUDE:
                            exclude = attrValue;
                            break;
                        case ATTR_PAUSE_LEFT:
                            pauseLeft = Boolean.parseBoolean(attrValue);
                            break;
                        default:
                            break;
                    }
                }
                if (TextUtils.isEmpty(packageName)) {
                    continue;
                }
                final PackageConfig config = new PackageConfig();
                parseActivityList(main, config.mainActivities);
                parseActivityList(exclude, config.excludedActivities);
                config.pauseLeft = pauseLeft;
                if (!config.mainActivities.isEmpty()) {
                    packages.put(packageName, config);
                }
            }
            synchronized (mPackages) {
                mPackages.clear();
                mPackages.putAll(packages);
            }
            Slog.i(TAG, "Loaded parallel world config: " + mPackages);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to load parallel world config: " + configFile, e);
        }
    }

    private static void parseActivityList(String list, ArraySet<String> out) {
        if (TextUtils.isEmpty(list)) {
            return;
        }
        for (String name : list.split(LIST_SEPARATOR)) {
            name = toSimpleClassName(name.trim());
            if (!TextUtils.isEmpty(name)) {
                out.add(name);
            }
        }
    }

    /**
     * Returns the parallel world type of the given activity: {@link Task#MAGIC_MAIN_WINDOW} for a
     * main activity, {@link Task#MAGIC_ADDITIONAL_WINDOW} for any other activity of a configured
     * package and {@link Task#NOT_MAGIC_WINDOW} for everything else (unconfigured package, an
     * excluded activity or the feature being disabled).
     *
     * <p>The comparison is exact on the simple class name (inner class suffix stripped), so an
     * activity whose name merely contains a configured main activity name is not misclassified.
     * The caller may pass a fully qualified class name, a {@code package/Class} short string or
     * a simple class name.
     */
    public int getMagicWindowType(String packageName, String activity) {
        if (!isEnabled() || TextUtils.isEmpty(packageName) || TextUtils.isEmpty(activity)) {
            return Task.NOT_MAGIC_WINDOW;
        }
        final PackageConfig config;
        synchronized (mPackages) {
            config = mPackages.get(packageName);
        }
        if (config == null) {
            // Not part of the device config: the user may have configured the package from the
            // window menu, then the activity that was open when it was enabled is the main one.
            final String userMain = getUserMainActivity(mContext, packageName);
            if (userMain == null) {
                return Task.NOT_MAGIC_WINDOW;
            }
            return userMain.equals(toSimpleClassName(activity))
                    ? Task.MAGIC_MAIN_WINDOW : Task.MAGIC_ADDITIONAL_WINDOW;
        }
        final String simpleName = toSimpleClassName(activity);
        if (config.excludedActivities.contains(simpleName)) {
            return Task.NOT_MAGIC_WINDOW;
        }
        return config.mainActivities.contains(simpleName)
                ? Task.MAGIC_MAIN_WINDOW : Task.MAGIC_ADDITIONAL_WINDOW;
    }

    /** Whether the given activity should be opened in the parallel (right) window. */
    public boolean isAdditionalWindow(String packageName, String activity) {
        return getMagicWindowType(packageName, activity) == Task.MAGIC_ADDITIONAL_WINDOW;
    }

    /** Whether the main window of the given package needs a forced pause/resume cycle. */
    public boolean isPauseLeftEnabled(String packageName) {
        if (!isEnabled() || TextUtils.isEmpty(packageName)) {
            return false;
        }
        final PackageConfig config;
        synchronized (mPackages) {
            config = mPackages.get(packageName);
        }
        return config != null && config.pauseLeft;
    }

    /**
     * Returns the split ratio of the given package: the fraction of the task width that belongs
     * to the additional (right) window. Returns {@code 0} when the package is not configured as
     * a parallel world package.
     *
     * <p>Priority: the ratio adjusted by the user, then the {@code configMagicWindow} entry of
     * the compatibility config, then the default 4:5 pane ratio. A package listed in
     * {@code magic_config.xml} takes part in the feature even when the compatibility config does
     * not declare a ratio.
     */
    public float getSplitRatio(Context context, String packageName) {
        if (!isEnabled() || TextUtils.isEmpty(packageName)) {
            return 0f;
        }
        boolean configured;
        synchronized (mPackages) {
            configured = mPackages.containsKey(packageName);
        }
        if (!configured && getUserMainActivity(context, packageName) == null) {
            // Neither the device config nor the user configured this package.
            return 0f;
        }
        final float userRatio = getUserRatio(context, packageName);
        if (userRatio > 0f) {
            return userRatio;
        }
        final String json = CompatibleConfig.queryStringValueData(context, KEY_RATIO, packageName);
        if (TextUtils.isEmpty(json)) {
            return DEFAULT_PANE_RATIO;
        }
        return parseRatio(json);
    }

    /** The ratio adjusted by the user for the package, or {@code 0} when it was never adjusted. */
    public float getUserRatio(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return 0f;
        }
        return Settings.Global.getFloat(context.getContentResolver(),
                SETTING_RATIO_PREFIX + packageName, 0f);
    }

    /** Remembers the ratio adjusted by the user for the package. */
    public void setUserRatio(Context context, String packageName, float ratio) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        Settings.Global.putFloat(context.getContentResolver(),
                SETTING_RATIO_PREFIX + packageName, clampRatio(ratio));
    }

    /** The mode chosen by the user for the package, see {@link #MODE_UNSET} and friends. */
    public int getUserMode(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return MODE_UNSET;
        }
        return Settings.Global.getInt(context.getContentResolver(),
                SETTING_MODE_PREFIX + packageName, MODE_UNSET);
    }

    /** Remembers the mode chosen by the user for the package. */
    public void setUserMode(Context context, String packageName, int mode) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        Settings.Global.putInt(context.getContentResolver(),
                SETTING_MODE_PREFIX + packageName, mode);
    }

    /**
     * The main activity of the package configured by the user, or {@code null} when the user never
     * configured it. Applications that are not part of the device config can be configured from
     * the window menu: the activity that is open when the user enables the parallel world becomes
     * the main (left) window.
     */
    public String getUserMainActivity(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return null;
        }
        final String activity = Settings.Global.getString(context.getContentResolver(),
                SETTING_USER_MAIN_PREFIX + packageName);
        if (TextUtils.isEmpty(activity)) {
            return null;
        }
        synchronized (mUserConfiguredPackages) {
            mUserConfiguredPackages.add(packageName);
        }
        return activity;
    }

    /** Remembers the main activity configured by the user for the package. */
    public void setUserMainActivity(Context context, String packageName, String activity) {
        if (context == null || TextUtils.isEmpty(packageName) || TextUtils.isEmpty(activity)) {
            return;
        }
        Settings.Global.putString(context.getContentResolver(),
                SETTING_USER_MAIN_PREFIX + packageName, activity);
        synchronized (mUserConfiguredPackages) {
            mUserConfiguredPackages.add(packageName);
        }
    }

    /**
     * Whether the package takes part in the automatic split, i.e. whether opening an additional
     * window activity from the main window splits the task. The user can switch the automatic
     * split off with the parallel world entry of the window menu.
     */
    public boolean isAutoSplitEnabled(Context context, String packageName) {
        return isEnabled() && getUserMode(context, packageName) != MODE_OFF;
    }

    /** Parses {@code {"ratio":"4:5"}} into the fraction of the right window. */
    private static float parseRatio(String jsonString) {
        if (TextUtils.isEmpty(jsonString)) {
            return DEFAULT_SPLIT_RATIO;
        }
        try {
            final JSONObject obj = new JSONObject(jsonString);
            final String ratio = obj.optString("ratio");
            if (TextUtils.isEmpty(ratio)) {
                return DEFAULT_SPLIT_RATIO;
            }
            final String[] parts = ratio.split(":");
            if (parts.length != 2) {
                return DEFAULT_SPLIT_RATIO;
            }
            final int primary = Integer.parseInt(parts[0].trim());
            final int secondary = Integer.parseInt(parts[1].trim());
            if (primary <= 0 || secondary <= 0) {
                return DEFAULT_SPLIT_RATIO;
            }
            return clampRatio((float) secondary / (primary + secondary));
        } catch (JSONException | NumberFormatException e) {
            Slog.w(TAG, "Invalid parallel world ratio config: " + jsonString, e);
            return DEFAULT_SPLIT_RATIO;
        }
    }

    /** Clamps the ratio into a sane range so a bad config cannot create an off-screen task. */
    public static float clampRatio(float ratio) {
        if (ratio <= 0f || ratio >= 1f || Float.isNaN(ratio)) {
            return DEFAULT_SPLIT_RATIO;
        }
        return Math.max(MIN_SPLIT_RATIO, Math.min(MAX_SPLIT_RATIO, ratio));
    }

    /** Normalizes a class name to its simple class name, without the inner class suffix. */
    public static String toSimpleClassName(String name) {
        if (TextUtils.isEmpty(name)) {
            return name;
        }
        // ComponentName#flattenToShortString() uses "package/.Class" or "package/Class".
        final int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        final int dollar = name.indexOf('$');
        if (dollar >= 0) {
            name = name.substring(0, dollar);
        }
        final int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name;
    }

    /** Dumps the static config and the current ratio of each configured package. */
    public void dump(PrintWriter pw, Context context) {
        pw.println("Parallel world (FDE magic window):");
        pw.println("  enabled=" + isEnabled()
                + " pauseMode=" + SystemProperties.get(PROP_PAUSE_MODE, PAUSE_MODE_LEGACY));
        synchronized (mPackages) {
            if (mPackages.isEmpty()) {
                pw.println("  (no package configured in the device config)");
            } else {
                for (Map.Entry<String, PackageConfig> entry : mPackages.entrySet()) {
                    final PackageConfig config = entry.getValue();
                    final String userMain = getUserMainActivity(context, entry.getKey());
                    pw.println("  " + entry.getKey()
                            + " source=xml"
                            + " main=" + config.mainActivities
                            + " exclude=" + config.excludedActivities
                            + " pauseLeft=" + config.pauseLeft
                            + (userMain != null ? " userMain=" + userMain : "")
                            + " mode=" + getUserMode(context, entry.getKey())
                            + " ratio=" + getSplitRatio(context, entry.getKey()));
                }
            }
        }
        // Packages the user configured from the window menu, see getUserMainActivity.
        synchronized (mUserConfiguredPackages) {
            for (int i = 0; i < mUserConfiguredPackages.size(); i++) {
                final String packageName = mUserConfiguredPackages.valueAt(i);
                synchronized (mPackages) {
                    if (mPackages.containsKey(packageName)) {
                        continue;
                    }
                }
                pw.println("  " + packageName
                        + " source=user"
                        + " main=[" + getUserMainActivity(context, packageName) + "]"
                        + " mode=" + getUserMode(context, packageName)
                        + " ratio=" + getSplitRatio(context, packageName));
            }
        }
    }

    private static final class PackageConfig {
        final ArraySet<String> mainActivities = new ArraySet<>();
        final ArraySet<String> excludedActivities = new ArraySet<>();
        boolean pauseLeft;
    }
}
