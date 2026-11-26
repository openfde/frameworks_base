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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.pm.ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.view.Gravity;
import android.window.DesktopModeFlags;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * Utility functions for app compat in desktop windowing used by both window manager and System UI.
 * @hide
 */
public final class DesktopModeCompatUtils {

    /**
     * Debug aid: logs every lookup of the status bar height resources, to tell which apps read
     * them at runtime (covered by the zeroing below) from the ones that get the value through
     * another path (XML references, values cached at process start, ...).
     *
     * <pre>
     * adb shell setprop log.tag.ZeroSbh DEBUG
     * adb logcat -s ZeroSbh
     * </pre>
     */
    private static final String TAG_STATUS_BAR_HEIGHT = "ZeroSbh";
    /** Debug aid: prints the desktop inset switch values once per process. */
    private static final String TAG_SWITCHES = "DesktopInsetSwitch";
    private static boolean sLoggedSwitches;

    /** Keeps a chatty app from flooding logcat with lookups. */
    private static final int MAX_STATUS_BAR_HEIGHT_LOOKUP_LOGS = 40;
    /** Upper bound of distinct (resource, windowing mode, result) combinations being logged. */
    private static final int MAX_LOOKUP_SIGNATURES = 40;
    private static int sStatusBarHeightLookupLogCount;
    /** Already logged combinations, so each one is reported once instead of the first N lookups. */
    private static final ArrayList<String> sLoggedLookupSignatures = new ArrayList<>();

    /**
     * Whether the caption insets should be excluded from configuration for system to handle.
     * The caller should ensure the activity is in or entering desktop view.
     *
     * <p> The treatment is enabled when one of the following is true:
     * <li> The per-app override
     * {@link ActivityInfo#OVERRIDE_EXCLUDE_CAPTION_INSETS_FROM_APP_BOUNDS} is enabled and
     * app has not opted out.
     * <li> The task is not resizable and the top activity has configuration coupled with insets.
     */
    public static boolean shouldExcludeCaptionFromAppBounds(@NonNull ActivityInfo info,
            boolean isResizeable, boolean optOutEdgeToEdge,
            boolean isExcludeCaptionInsetsOverrideEnabled) {
        if (isExcludeCaptionInsetsOverrideEnabled) {
            return true;
        }

        if (isConfigurationDecoupled(info, optOutEdgeToEdge)) {
            return false;
        }

        return !isResizeable;
    }

    /**
     * Applies a vertical and horizontal gravity on the inOutBounds in relation to the stableBounds.
     */
    public static void applyLayoutGravity(int verticalGravity, int horizontalGravity,
            @NonNull Rect inOutBounds, @NonNull Rect stableBounds) {
        final int width = inOutBounds.width();
        final int height = inOutBounds.height();

        final float fractionOfHorizontalOffset = switch (horizontalGravity) {
            case Gravity.LEFT -> 0f;
            case Gravity.RIGHT -> 1f;
            default -> 0.5f;
        };

        final float fractionOfVerticalOffset = switch (verticalGravity) {
            case Gravity.TOP -> 0f;
            case Gravity.BOTTOM -> 1f;
            default -> 0.5f;
        };

        inOutBounds.offsetTo(stableBounds.left, stableBounds.top);
        final int xOffset = (int) (fractionOfHorizontalOffset * (stableBounds.width() - width));
        final int yOffset = (int) (fractionOfVerticalOffset * (stableBounds.height() - height));
        inOutBounds.offset(xOffset, yOffset);
    }

    /**
     * Returns the orientation of the given {@code rect}.
     */
    public static @Configuration.Orientation int computeConfigOrientation(@NonNull Rect rect) {
        return rect.height() >= rect.width()
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
    }

    /**
     * Switches for the desktop inset compatibility fixes.
     *
     * <p>They are read once per process, so a change requires the affected app to be restarted
     * (for the framework process itself, a reboot). This keeps the hot paths - window insets
     * dispatching and resource lookups - free of property reads:
     *
     * <pre>
     * adb shell setprop persist.wm.debug.desktop_caption_inset_target false
     * adb shell setprop persist.wm.debug.desktop_caption_inset_listener false
     * adb shell setprop persist.wm.debug.desktop_zero_status_bar_height false
     * adb shell setprop persist.wm.debug.desktop_caption_inset_framework_only false
     * </pre>
     */

    /** Apply the consumed insets to the view that actually hosts the content root. */
    public static final String PROP_CAPTION_INSET_TARGET =
            "persist.wm.debug.desktop_caption_inset_target";

    /** Keep the caption consumption working when the app sets a decor insets listener. */
    public static final String PROP_CAPTION_INSET_LISTENER =
            "persist.wm.debug.desktop_caption_inset_listener";

    /** Report a zero status bar height to apps in freeform windows. */
    public static final String PROP_ZERO_STATUS_BAR_HEIGHT =
            "persist.wm.debug.desktop_zero_status_bar_height";

    /**
     * Value, in px, that the status bar height resources resolve to in freeform windows when
     * {@link #PROP_ZERO_STATUS_BAR_HEIGHT} is enabled.
     *
     * <p>1px by default: the placeholder that apps written for the phone full screen model reserve
     * inside their own content is effectively gone, but a handful of apps treat a height of exactly
     * 0 as "there is no status bar at all" and switch to a completely different layout, so the
     * height is not reported as 0. Apps that need the plain zero can set the property to 0.
     */
    public static final String PROP_STATUS_BAR_HEIGHT_REPLACEMENT =
            "persist.wm.debug.desktop_status_bar_height_replacement";

    /** Default value of {@link #PROP_STATUS_BAR_HEIGHT_REPLACEMENT}. */
    private static final int DEFAULT_STATUS_BAR_HEIGHT_REPLACEMENT = 1;

    /**
     * Also replace the status bar height when the window configuration is not a desktop (freeform)
     * one, which is enabled by default.
     *
     * <p>An app can read the status bar height before its own window exists - while its process
     * starts during boot, or when the desktop restores the previous session - and cache the value.
     * At that point its configuration is still the display configuration, so the window based
     * checks below cannot be used and the app would keep the real status bar height, which then
     * shows up as a gap between the window decoration caption bar and the app's own header once the
     * window appears.
     *
     * <p>The replacement is skipped for windows that stay at the top of the display while being in
     * the desktop windowing mode, e.g. the desktop itself. Windows that are genuinely fullscreen
     * can be excluded by turning this off.
     */
    public static final String PROP_STATUS_BAR_HEIGHT_UNCONDITIONAL =
            "persist.wm.debug.desktop_status_bar_height_unconditional";

    /** Report the caption bar inset to the framework only, not to apps. */
    public static final String PROP_CAPTION_INSET_FRAMEWORK_ONLY =
            "persist.wm.debug.desktop_caption_inset_framework_only";

    // The switches are read on every use instead of being cached in a static field: framework
    // classes like this one can be initialized before an app process even starts, in which case a
    // cached value would be baked in for the lifetime of the system. All call sites are low
    // frequency (an insets dispatch, a window metrics request, or a lookup of one of the status
    // bar height resources), so the property read is cheap.

    /**
     * Whether the consumed insets should be applied to the outermost view hosting the content root
     * instead of the content root itself. Needed for apps that re-parent the content root into a
     * container which ignores {@link android.view.ViewGroup.MarginLayoutParams} on layout.
     */
    public static boolean isCaptionInsetTargetEnabled() {
        return SystemProperties.getBoolean(PROP_CAPTION_INSET_TARGET, true);
    }

    /**
     * Whether the caption bar inset should still be consumed when the app installed an
     * {@code OnApplyWindowInsetsListener} on its decor view, which would otherwise bypass
     * {@code DecorView#onApplyWindowInsets} completely.
     */
    public static boolean isCaptionInsetListenerEnabled() {
        return SystemProperties.getBoolean(PROP_CAPTION_INSET_LISTENER, true);
    }

    /**
     * Whether the caption bar inset of a desktop window is for the framework only: the framework
     * turns it into padding of the window content, so apps get an empty caption inset and cannot
     * reserve the same space a second time.
     */
    public static boolean isCaptionInsetFrameworkOnly() {
        final boolean appProcess = Process.myUid() >= Process.FIRST_APPLICATION_UID;
        if (appProcess) {
            logSwitches();
        }
        return appProcess
                && SystemProperties.getBoolean(PROP_CAPTION_INSET_FRAMEWORK_ONLY, true);
    }

    /**
     * Logs the desktop inset switches once per process, to tell whether an app process really sees
     * the property values the device was configured with. Enable with {@code log.tag.DebugTool
     * DEBUG}.
     */
    private static void logSwitches() {
        if (sLoggedSwitches || !Log.isLoggable(TAG_SWITCHES, Log.DEBUG)) {
            return;
        }
        sLoggedSwitches = true;
        Slog.d(TAG_SWITCHES, "process=" + ActivityThread.currentProcessName()
                + " uid=" + Process.myUid()
                + " target=" + isCaptionInsetTargetEnabled()
                + " listener=" + isCaptionInsetListenerEnabled()
                + " zeroStatusBarHeight=" + SystemProperties.getBoolean(
                        PROP_ZERO_STATUS_BAR_HEIGHT, true)
                + " captionInsetFrameworkOnly=" + SystemProperties.getBoolean(
                        PROP_CAPTION_INSET_FRAMEWORK_ONLY, true));
    }

    /**
     * Whether the given resource should resolve to 0 for the given configuration.
     *
     * <p>Desktop (freeform) windows are not covered by the system status bar - the only thing on
     * top of them is the window decoration caption bar, which is reported to apps as the
     * {@code captionBar} inset. Apps written for the phone full screen model still reserve
     * {@code @android:dimen/status_bar_height} inside their own content, and that placeholder then
     * shows up as a gap between the caption bar and the app's own header. Making the resource
     * resolve to 0 for those windows removes the placeholder at its source, i.e. without having to
     * maintain a list of apps.
     *
     * <p>Windows that still reach the top of the display (maximized or immersive windows that are
     * covered by the real status bar) keep the real value.
     */
    /**
     * Value, in px, that the status bar height resources should resolve to for the given window.
     */
    public static int getStatusBarHeightReplacement() {
        return SystemProperties.getInt(PROP_STATUS_BAR_HEIGHT_REPLACEMENT,
                DEFAULT_STATUS_BAR_HEIGHT_REPLACEMENT);
    }

    public static boolean shouldZeroStatusBarHeight(int resId, @NonNull Configuration config) {
        // Cheap id check first: this runs for every resource lookup that resolves a value.
        if (resId != R.dimen.status_bar_height
                && resId != R.dimen.status_bar_height_portrait
                && resId != R.dimen.status_bar_height_landscape
                && resId != R.dimen.status_bar_height_default) {
            return false;
        }
        final boolean result = shouldZeroStatusBarHeightForConfig(config);
        if (Log.isLoggable(TAG_STATUS_BAR_HEIGHT, Log.DEBUG)) {
            logStatusBarHeightLookup(resId, config, result);
        }
        return result;
    }

    private static boolean shouldZeroStatusBarHeightForConfig(@NonNull Configuration config) {
        // Only app processes: system components (SystemUI, WMS) legitimately ask for the real
        // status bar height while laying out a per-window context.
        if (Process.myUid() < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (!SystemProperties.getBoolean(PROP_ZERO_STATUS_BAR_HEIGHT, true)) {
            return false;
        }
        final WindowConfiguration winConfig = config.windowConfiguration;
        if (winConfig.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            // Desktop window: only when it does not reach the top of the display, so the real status
            // bar cannot overlap it. Desktop windows are laid out below the status bar, while the
            // desktop itself reports a freeform window at the display bounds and keeps the real
            // value.
            return winConfig.getBounds().top > 0;
        }
        // Not a desktop window: either the app is still starting - it reads the status bar height
        // before its window exists and caches it, with the display configuration - or the window is
        // genuinely fullscreen. Replace the value in both cases unless that is turned off.
        return SystemProperties.getBoolean(PROP_STATUS_BAR_HEIGHT_UNCONDITIONAL, true);
    }

    /**
     * Logs a lookup of the status bar height resources, so it can be told apart which apps read
     * them at runtime (and are therefore covered by the zeroing) from the ones that get the value
     * through another path (XML references, values cached at process start, ...).
     */
    private static void logStatusBarHeightLookup(int resId, @NonNull Configuration config,
            boolean result) {
        if (sStatusBarHeightLookupLogCount >= MAX_STATUS_BAR_HEIGHT_LOOKUP_LOGS) {
            return;
        }
        final WindowConfiguration winConfig = config.windowConfiguration;
        final int windowingMode = winConfig.getWindowingMode();
        // Log the first lookup of every distinct (res, windowing mode, result) combination instead
        // of the first N lookups: an app typically reads the value once per screen, so a plain cap
        // would hide the interesting reads behind the ones of the first screen.
        final String signature = resId + "/" + windowingMode + "/" + result;
        if (sLoggedLookupSignatures.contains(signature)) {
            return;
        }
        if (sLoggedLookupSignatures.size() >= MAX_LOOKUP_SIGNATURES) {
            return;
        }
        sLoggedLookupSignatures.add(signature);
        sStatusBarHeightLookupLogCount++;
        Slog.d(TAG_STATUS_BAR_HEIGHT, "process=" + ActivityThread.currentProcessName()
                + " uid=" + Process.myUid()
                + " res=0x" + Integer.toHexString(resId)
                + " mode=" + windowingMode
                + " bounds=" + winConfig.getBounds().toShortString()
                + " value=" + getStatusBarHeightReplacement()
                + " zero=" + result
                + (sStatusBarHeightLookupLogCount >= MAX_STATUS_BAR_HEIGHT_LOOKUP_LOGS
                        ? " (no further lookups will be logged)" : ""));
    }

    private static boolean isConfigurationDecoupled(@NonNull ActivityInfo info,
            boolean optOutEdgeToEdge) {
        return info.isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED) && !optOutEdgeToEdge;
    }
}
