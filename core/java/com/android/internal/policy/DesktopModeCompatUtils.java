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

    /** Keeps a chatty app from flooding logcat with lookups. */
    private static final int MAX_STATUS_BAR_HEIGHT_LOOKUP_LOGS = 20;
    private static int sStatusBarHeightLookupLogCount;

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
     * Runtime switch for {@link #shouldZeroStatusBarHeight(int, Configuration)}, so the behaviour
     * can be A/B tested without rebuilding:
     *
     * <pre>
     * adb shell setprop persist.wm.debug.desktop_zero_status_bar_height false
     * </pre>
     */
    public static final String PROP_ZERO_STATUS_BAR_HEIGHT_IN_FREEFORM =
            "persist.wm.debug.desktop_zero_status_bar_height";

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
        if (!SystemProperties.getBoolean(PROP_ZERO_STATUS_BAR_HEIGHT_IN_FREEFORM, true)) {
            return false;
        }
        final WindowConfiguration winConfig = config.windowConfiguration;
        if (winConfig.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return false;
        }
        // Only when the window does not reach the top of the display, so the real status bar cannot
        // overlap it. Desktop windows are laid out below the status bar.
        return winConfig.getBounds().top > 0;
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
        sStatusBarHeightLookupLogCount++;
        final WindowConfiguration winConfig = config.windowConfiguration;
        Slog.d(TAG_STATUS_BAR_HEIGHT, "process=" + ActivityThread.currentProcessName()
                + " uid=" + Process.myUid()
                + " res=0x" + Integer.toHexString(resId)
                + " mode=" + winConfig.getWindowingMode()
                + " bounds=" + winConfig.getBounds().toShortString()
                + " zero=" + result
                + (sStatusBarHeightLookupLogCount == MAX_STATUS_BAR_HEIGHT_LOOKUP_LOGS
                        ? " (further lookups not logged)" : ""));
    }

    private static boolean isConfigurationDecoupled(@NonNull ActivityInfo info,
            boolean optOutEdgeToEdge) {
        return info.isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED) && !optOutEdgeToEdge;
    }
}
