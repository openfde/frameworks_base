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

package com.android.server.wm;

import android.os.SystemProperties;

/**
 * Feature switches for FDE warm host tasks.
 *
 * <p>To avoid the 100~300ms activity cold launch for every X window, FDE
 * pre-creates a few "warm host" activities (1x1, transparent, not focusable)
 * and claims them when an X window appears. For Android such a host is not a
 * normal task and needs a few per-subsystem exceptions. Each exception may
 * cause problems on its own, so they are split into independent switches for
 * easy bisection.
 *
 * <p>All switches are {@code persist.fde.warm.*} properties and take effect
 * immediately (no reboot needed):
 * <pre>
 *   adb shell setprop persist.fde.warm.enable  1|0   master switch (0 = no exceptions at all)
 *   adb shell setprop persist.fde.warm.recents 1|0   keep warm tasks out of recents
 *   adb shell setprop persist.fde.warm.tasks   1|0   keep warm tasks out of getTasks (dock)
 *   adb shell setprop persist.fde.warm.size    1|0   allow warm tasks below the freeform minimum (1dp)
 * </pre>
 *
 * <p>Note: focus is intentionally NOT handled here. Making warm hosts
 * non-focusable (by flag or by 1dp geometry) left them never resumed, so the
 * claim was handled as an activity restart which recreated the window and its
 * surface (screen flash, graphics buffer churn). The warm window already sets
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE with alpha=0 in the app, so input
 * focus never reaches it.
 */
final class FdeWarmHostConfig {

    private static final String PREFIX = "persist.fde.warm.";

    private FdeWarmHostConfig() {
    }

    /** Master switch: when off, warm tasks behave exactly like normal tasks. */
    static boolean master() {
        return SystemProperties.getBoolean(PREFIX + "enable", true);
    }

    /** Recents: warm tasks do not enter RecentTasks / are not persisted. */
    static boolean recents() {
        return master() && SystemProperties.getBoolean(PREFIX + "recents", true);
    }

    /** Task lists: filter warm tasks out of ActivityTaskManagerService.getTasks(). */
    static boolean tasks() {
        return master() && SystemProperties.getBoolean(PREFIX + "tasks", true);
    }

    /** Size: let warm tasks skip the freeform minimum size so 1x1 is possible. */
    static boolean size() {
        return master() && SystemProperties.getBoolean(PREFIX + "size", true);
    }
}
