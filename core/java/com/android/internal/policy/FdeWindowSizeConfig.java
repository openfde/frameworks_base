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

package com.android.internal.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;

import java.util.HashMap;

/**
 * FDE: per-app window size configuration for desktop (freeform) windows.
 *
 * <p>The values are defined in {@code core/res/res/values/config.xml} under
 * {@code config_fdeAppWindowSizeSettings}. Each entry has the format
 * {@code packageName,startWidth,startHeight,minWidth,minHeight,maxWidth,maxHeight}:
 * <pre>
 * &lt;string-array name="config_fdeAppWindowSizeSettings"&gt;
 *     &lt;item&gt;com.example.app,480,640,320,400,960,1200&lt;/item&gt;
 * &lt;/string-array&gt;
 * </pre>
 * All sizes are expressed in density-independent pixels (dp); {@code 0} means "not configured"
 * for that particular field.
 * <ul>
 *   <li>{@code startWidth/startHeight}: initial size used when the app is launched as an FDE
 *       desktop window. When unset the platform default desktop size is used.</li>
 *   <li>{@code minWidth/minHeight}: smallest size allowed when the user drags/resizes the window.
 *       {@code 0} keeps the platform default minimum.</li>
 *   <li>{@code maxWidth/maxHeight}: largest size allowed. Both resizing by dragging and
 *       maximizing (fullscreen) are clamped so the window never exceeds these values.
 *       {@code 0} means no limit.</li>
 * </ul>
 */
public final class FdeWindowSizeConfig {

    private static final String TAG = "FdeWindowSizeConfig";

    private static volatile FdeWindowSizeConfig sInstance;

    /** Application context, only used to resolve resources once. */
    private final Context mContext;

    private final HashMap<String, Entry> mEntries = new HashMap<>();

    private FdeWindowSizeConfig(@NonNull Context context) {
        mContext = context;
        reload();
    }

    /**
     * Returns the shared instance, loading the settings from resources on first use.
     *
     * @param context a context used to resolve the resource array; may be {@code null} when only
     *                system resources are needed (e.g. shell process).
     */
    @NonNull
    public static FdeWindowSizeConfig getInstance(@Nullable Context context) {
        FdeWindowSizeConfig config = sInstance;
        if (config == null) {
            synchronized (FdeWindowSizeConfig.class) {
                config = sInstance;
                if (config == null) {
                    config = new FdeWindowSizeConfig(context != null
                            ? context.getApplicationContext() : null);
                    sInstance = config;
                }
            }
        }
        return config;
    }

    /** (Re)loads the settings from resources. Kept public so overlays can be refreshed. */
    public void reload() {
        final Resources resources = mContext != null ? mContext.getResources()
                : Resources.getSystem();
        synchronized (mEntries) {
            mEntries.clear();
            try {
                final String[] rawEntries = resources.getStringArray(
                        R.array.config_fdeAppWindowSizeSettings);
                for (String raw : rawEntries) {
                    final Entry entry = parseEntry(raw);
                    if (entry != null && !mEntries.containsKey(entry.packageName)) {
                        mEntries.put(entry.packageName, entry);
                    }
                }
            } catch (Resources.NotFoundException e) {
                Slog.w(TAG, "config_fdeAppWindowSizeSettings not defined in config.xml", e);
            }
        }
    }

    @Nullable
    private static Entry parseEntry(@NonNull String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        final String[] parts = TextUtils.split(raw.trim(), ",");
        if (parts.length < 7) {
            Slog.w(TAG, "Skipping malformed app window size entry: " + raw);
            return null;
        }
        try {
            return new Entry(parts[0].trim(),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()),
                    Integer.parseInt(parts[5].trim()),
                    Integer.parseInt(parts[6].trim()));
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Skipping app window size entry with invalid numbers: " + raw, e);
            return null;
        }
    }

    /**
     * Returns the settings of the given package, or {@code null} if the package is not configured.
     */
    @Nullable
    public Entry getEntry(@Nullable String packageName) {
        if (packageName == null) {
            return null;
        }
        synchronized (mEntries) {
            return mEntries.get(packageName);
        }
    }

    /** Converts a dp value to pixels using the given display density. */
    public static int dpToPx(int dp, float density) {
        return Math.round(dp * density);
    }

    /** Size settings of a configured package. All fields are in dp; 0 means "not configured". */
    public static final class Entry {
        public final String packageName;
        public final int startWidth;
        public final int startHeight;
        public final int minWidth;
        public final int minHeight;
        public final int maxWidth;
        public final int maxHeight;

        /** Whether a startup size (both width and height) is configured. */
        public final boolean hasStartSize;
        /** Whether a minimum size (both width and height) is configured. */
        public final boolean hasMinSize;
        /** Whether a maximum size (both width and height) is configured. */
        public final boolean hasMaxSize;

        Entry(String packageName, int startWidth, int startHeight, int minWidth, int minHeight,
                int maxWidth, int maxHeight) {
            this.packageName = packageName;
            this.startWidth = startWidth;
            this.startHeight = startHeight;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            hasStartSize = startWidth > 0 && startHeight > 0;
            hasMinSize = minWidth > 0 && minHeight > 0;
            hasMaxSize = maxWidth > 0 && maxHeight > 0;
        }

        /**
         * Clamps {@code bounds} in place to the configured min/max sizes.
         *
         * <p>When a max limit is hit, the edge that wasn't moved (the anchor) is kept, mirroring how
         * {@code minWidth} limits are applied by {@code Task.adjustForMinimalTaskDimensions}. When a
         * window that previously filled its parent viewport is clamped, the clamped window is
         * centered in the viewport.
         *
         * @param bounds the bounds to clamp (in px), modified in place
         * @param previous previous bounds (in px), used to determine the anchored edge
         * @param parent viewport the window is displayed in (in px), used to center clamped
         *               maximize requests
         * @param density the display density of the display the window is on
         */
        public void clampBounds(@NonNull Rect bounds, @NonNull Rect previous,
                @Nullable Rect parent, float density) {
            if (hasMinSize) {
                clampAxis(bounds, previous,
                        dpToPx(minWidth, density), dpToPx(minHeight, density),
                        true /* enforceMin */);
            }
            if (hasMaxSize) {
                final int maxW = dpToPx(maxWidth, density);
                final int maxH = dpToPx(maxHeight, density);
                if (bounds.width() > maxW || bounds.height() > maxH) {
                    // Only a request that matches the parent viewport (i.e. a maximize/fullscreen
                    // request) gets recentered after clamping; an over-display drag keeps its
                    // anchored edge like a normal resize.
                    final boolean fillsParent = parent != null && !parent.isEmpty()
                            && Math.abs(bounds.width() - parent.width()) <= 1
                            && Math.abs(bounds.height() - parent.height()) <= 1;
                    clampAxis(bounds, previous, maxW, maxH, false /* enforceMin */);
                    if (fillsParent) {
                        // A maximize/fullscreen-like request was clamped: keep the window centered
                        // in the available viewport so it still looks maximized.
                        centerInParent(bounds, parent);
                    }
                }
            }
        }

        private static void clampAxis(@NonNull Rect bounds, @NonNull Rect previous,
                int limitW, int limitH, boolean enforceMin) {
            if (limitW > 0 && (enforceMin ? bounds.width() < limitW : bounds.width() > limitW)) {
                if (!previous.isEmpty() && bounds.right == previous.right) {
                    bounds.left = bounds.right - limitW;
                } else {
                    bounds.right = bounds.left + limitW;
                }
            }
            if (limitH > 0 && (enforceMin ? bounds.height() < limitH
                    : bounds.height() > limitH)) {
                if (!previous.isEmpty() && bounds.bottom == previous.bottom) {
                    bounds.top = bounds.bottom - limitH;
                } else {
                    bounds.bottom = bounds.top + limitH;
                }
            }
        }

        static void centerInParent(@NonNull Rect bounds, @NonNull Rect parent) {
            bounds.offset((parent.width() - bounds.width()) / 2 + parent.left - bounds.left,
                    (parent.height() - bounds.height()) / 2 + parent.top - bounds.top);
        }

        @Override
        public String toString() {
            return "Entry{" + packageName + ", start=" + startWidth + "x" + startHeight
                    + ", min=" + minWidth + "x" + minHeight + ", max=" + maxWidth + "x"
                    + maxHeight + '}';
        }
    }
}
