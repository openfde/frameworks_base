/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.display;

import android.util.MathUtils;

/**
 * Utility methods for calculating the display brightness.
 *
 * openfde: the conversion between the slider space and the linear brightness is a plain linear
 * mapping (upstream implements a Hybrid Log Gamma curve). The container mirrors the brightness of
 * the host desktop, whose UI shows brightness as a linear percentage, so the slider percentage
 * must be the brightness percentage: 0% -> min, 100% -> max (1..255 at the LightsService
 * boundary). A slider at 50% therefore means 50% brightness.
 */
public class BrightnessUtils {

    /** Range of the value used by the brightness sliders. */
    public static final int GAMMA_SPACE_MIN = 0;
    public static final int GAMMA_SPACE_MAX = 65535;

    /**
     * Converts the slider value into the linear brightness value.
     *
     * The mapping is linear (see the class comment), so this is a plain interpolation between
     * {@code min} and {@code max} driven by the slider position.
     *
     * @param val The slider value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding setting value.
     */
    public static final int convertGammaToLinear(int val, int min, int max) {
        // openfde: plain linear mapping, no HLG curve. The slider position is the
        // brightness percentage, so 0..GAMMA_SPACE_MAX ends up as min..max and a
        // 50% slider means 50% brightness (1..255 at the LightsService boundary).
        final float normalizedVal =
                MathUtils.constrain(MathUtils.norm(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, val), 0f, 1f);
        return Math.round(MathUtils.lerp(min, max, normalizedVal));
    }

    /**
     * Version of {@link #convertGammaToLinear} that takes and returns float values.
     * TODO(flc): refactor Android Auto to use float version
     *
     * @param val The slider value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding setting value.
     */
    public static final float convertGammaToLinearFloat(int val, float min, float max) {
        // openfde: plain linear mapping, no HLG curve (see convertGammaToLinear).
        final float normalizedVal =
                MathUtils.constrain(MathUtils.norm(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, val), 0f, 1f);
        return MathUtils.lerp(min, max, normalizedVal);
    }

    /**
     * Converts the linear brightness value back into the slider value.
     *
     * Inverse of {@link #convertGammaToLinear}.
     *
     * @param val The brightness setting value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding slider value
     */
    public static final int convertLinearToGamma(int val, int min, int max) {
        return convertLinearToGammaFloat((float) val, (float) min, (float) max);
    }

    /**
     * Version of {@link #convertLinearToGamma} that takes float values.
     * TODO: brightnessfloat merge with above method(?)
     * @param val The brightness setting value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     * @return The corresponding slider value
     */
    public static final int convertLinearToGammaFloat(float val, float min, float max) {
        // openfde: inverse of the plain linear mapping above, so that the slider
        // position always shows the brightness percentage.
        final float normalizedVal =
                MathUtils.constrain(MathUtils.norm(min, max, val), 0f, 1f);
        return Math.round(MathUtils.lerp(GAMMA_SPACE_MIN, GAMMA_SPACE_MAX, normalizedVal));
    }
}
