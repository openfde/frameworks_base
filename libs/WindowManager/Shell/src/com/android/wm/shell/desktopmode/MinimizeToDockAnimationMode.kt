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

package com.android.wm.shell.desktopmode

import android.os.SystemProperties

/**
 * Runtime switch for the minimize-to-dock animation.
 *
 * `persist.wm.debug.minimize_to_dock.mode`:
 * - 0: disabled, use the stock minimize animation
 * - 1: affine mesh-less animation (the fde_17_minimize-style effect)
 * - 2: snapshot + mesh "genie" animation (default)
 */
object MinimizeToDockAnimationMode {
    const val OFF = 0
    const val AFFINE = 1
    const val MESH = 2

    private const val PROPERTY = "persist.wm.debug.minimize_to_dock.mode"

    @JvmStatic
    fun current(): Int =
        when (SystemProperties.getInt(PROPERTY, MESH)) {
            OFF -> OFF
            AFFINE -> AFFINE
            else -> MESH
        }
}
