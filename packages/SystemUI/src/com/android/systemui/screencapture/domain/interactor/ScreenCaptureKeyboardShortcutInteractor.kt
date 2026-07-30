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

package com.android.systemui.screencapture.domain.interactor

import android.util.Log
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters.Record.LargeScreenCaptureUiParameters
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiSource
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.LargeScreenCaptureFeaturesInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.ScreenshotInteractor
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureRegion as LargeScreenCaptureRegion
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureType as LargeScreenCaptureType
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.statusbar.policy.domain.interactor.UserSetupInteractor
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Handles the resulting actions of screen capture related keyboard shortcuts. */
@SysUISingleton
class ScreenCaptureKeyboardShortcutInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val userRepository: UserRepository,
    private val hsum: HeadlessSystemUserMode,
    private val featuresInteractor: LargeScreenCaptureFeaturesInteractor,
    private val screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
    private val screenshotInteractor: ScreenshotInteractor,
    private val userSetupInteractor: UserSetupInteractor,
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
) {
    fun attemptPartialRegionScreenshot() {
        backgroundScope.launch {
            launchPreCaptureUi(
                captureType = LargeScreenCaptureType.SCREENSHOT,
                captureRegion = LargeScreenCaptureRegion.PARTIAL,
            )
        }
    }

    fun attemptAppWindowScreenshot() {
        if (!featuresInteractor.appWindowRegionSupported) {
            return
        }
        backgroundScope.launch {
            launchPreCaptureUi(
                captureType = LargeScreenCaptureType.SCREENSHOT,
                captureRegion = LargeScreenCaptureRegion.APP_WINDOW,
            )
        }
    }

    /** Starts a fullscreen screen recording directly, without opening any UI. */
    fun attemptRecording() {
        backgroundScope.launch {
            val params = ScreenRecordingParameters(
                captureTarget = null,
                audioSource = ScreenRecordingAudioSource.NONE,
                displayId = Display.DEFAULT_DISPLAY,
                shouldShowTaps = false,
            )
            Log.i(TAG, "Starting recording via keyboard shortcut")
            screenRecordingServiceInteractor.startRecordingDelayed(params)
        }
    }

    private suspend fun launchPreCaptureUi(
        captureType: LargeScreenCaptureType,
        captureRegion: LargeScreenCaptureRegion,
    ) {
        // If large-screen capture UI is not supported, default to taking a fullscreen screenshot.
        if (!screenCaptureRecordFeaturesInteractor.isLargeScreenScreencaptureEnabled) {
            screenshotInteractor.requestFullscreenScreenshot()
            return
        }

        if (screenCaptureUiInteractor.isVisible(ScreenCaptureType.RECORD)) {
            Log.i(TAG, "Screen capture UI is already visible.")
            return
        }

        if (keyguardInteractor.isKeyguardCurrentlyShowing()) {
            Log.i(TAG, "Screen capture UI is disabled when keyguard is showing.")
            return
        }

        if (!userSetupInteractor.isUserSetUp.first()) {
            Log.i(TAG, "Screen capture UI is disabled during OOBE.")
            return
        }

        if (hsum.isHeadlessSystemUser(userRepository.getSelectedUserInfo().id)) {
            Log.i(TAG, "Screen capture UI is disabled for headless system user.")
            return
        }

        screenCaptureUiInteractor.show(
            ScreenCaptureUiParameters.Record(
                largeScreenParameters = LargeScreenCaptureUiParameters(captureType, captureRegion)
            ),
            source = getUiSource(captureRegion),
        )
    }

    private fun getUiSource(captureRegion: LargeScreenCaptureRegion): ScreenCaptureUiSource? {
        return when (captureRegion) {
            LargeScreenCaptureRegion.PARTIAL -> {
                ScreenCaptureUiSource.PARTIAL_SCREENSHOT_KEYBOARD_SHORTCUT
            }
            LargeScreenCaptureRegion.APP_WINDOW -> {
                ScreenCaptureUiSource.APP_WINDOW_SCREENSHOT_KEYBOARD_SHORTCUT
            }
            else -> {
                null
            }
        }
    }

    private companion object {
        const val TAG = "ScreenCaptureKeyboardShortcutInteractor"
    }
}
