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

package com.android.wm.shell.shared.animation

import android.os.SystemProperties

/**
 * Debug timing controls for the minimize-to-dock animations.
 *
 * `persist.wm.debug.minimize_to_dock.speed` is a multiplier applied to the animation duration:
 * - `1` (default): normal speed
 * - `0.5`: twice as slow
 * - `0.25`: four times slower
 * - `0.1`: ten times slower
 *
 * The property is read for every animation, so it can be changed with `setprop` without restarting
 * SystemUI. It applies to both the mesh (genie) and affine minimize-to-dock animations.
 *
 * `persist.wm.debug.minimize_to_dock.merge_y_offset_dp` controls how far above the dock icon
 * center the mesh converges. The dock is normally 70dp tall, so its top edge is 35dp above the
 * icon center. Raise this if the bottom of the funnel is hidden behind the dock.
 */
object MinimizeAnimationTuning {
    private const val SPEED_PROPERTY = "persist.wm.debug.minimize_to_dock.speed"
    private const val MERGE_Y_OFFSET_PROPERTY =
        "persist.wm.debug.minimize_to_dock.merge_y_offset_dp"
    private const val DEFAULT_SPEED = 1f
    private const val MIN_SPEED = 0.05f
    private const val MAX_SPEED = 4f
    private const val DEFAULT_MERGE_Y_OFFSET_DP = 35f
    private const val MAX_MERGE_Y_OFFSET_DP = 200f

    /** Returns the configured speed multiplier, falling back to 1 for invalid values. */
    @JvmStatic
    fun speed(): Float {
        val raw = SystemProperties.get(SPEED_PROPERTY, DEFAULT_SPEED.toString()).trim()
        val speed = raw.toFloatOrNull() ?: return DEFAULT_SPEED
        if (!speed.isFinite() || speed <= 0f) return DEFAULT_SPEED
        return speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /**
     * Returns how many dp above the icon center the mesh should converge. The default is half of
     * the normal 70dp dock height, which puts the final point at the dock's top edge.
     */
    @JvmStatic
    fun mergeYOffsetDp(): Float {
        val raw =
            SystemProperties.get(
                    MERGE_Y_OFFSET_PROPERTY,
                    DEFAULT_MERGE_Y_OFFSET_DP.toString(),
                )
                .trim()
        val offset = raw.toFloatOrNull() ?: return DEFAULT_MERGE_Y_OFFSET_DP
        if (!offset.isFinite()) return DEFAULT_MERGE_Y_OFFSET_DP
        return offset.coerceIn(0f, MAX_MERGE_Y_OFFSET_DP)
    }

    /** Scales [baseDurationMs] by the configured speed. */
    @JvmStatic
    fun scaleDuration(baseDurationMs: Long): Long {
        if (baseDurationMs <= 0L) return baseDurationMs
        return (baseDurationMs / speed()).toLong().coerceAtLeast(1L)
    }
}
