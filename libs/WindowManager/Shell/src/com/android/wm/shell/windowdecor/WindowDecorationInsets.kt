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

package com.android.wm.shell.windowdecor

import android.graphics.Insets
import android.graphics.Rect
import android.os.Binder
import android.view.InsetsBoundingRect
import android.view.InsetsSource
import android.view.WindowInsets
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import com.android.window.flags.Flags

/** Adds, removes, and updates caption insets. */
data class WindowDecorationInsets
private constructor(
    private val token: WindowContainerToken,
    private val owner: Binder,
    private val frame: Frame,
    private val boundingRects: List<Rect> = emptyList(),
    private val insetsBoundingRects: List<InsetsBoundingRect> = emptyList(),
    @InsetsSource.Flags private val flags: Int = 0,
    private val shouldAddCaptionInset: Boolean = false,
    private val appBoundsExclusion: AppBoundsExclusion? = null,
    /**
     * Height of the caption bar inset actually reported to apps. A negative value means "the full
     * caption height" (i.e. [frame].height).
     *
     * It is smaller than the caption height only for apps that add their own status bar height
     * placeholder inside their content: the placeholder then ends up hidden behind the caption bar
     * instead of being pushed below it. See config_desktopCaptionInsetCompensationPackages.
     */
    private val captionInsetHeight: Int = -1,
) {
    private sealed class Frame {
        abstract val height: Int

        data class Absolute(val rect: Rect, override val height: Int = rect.height()) : Frame()

        data class Relative(override val height: Int) : Frame()
    }

    private data class AppBoundsExclusion(val taskFrame: Rect)

    /** The captionBar inset height that is published to apps. */
    private val reportedCaptionHeight: Int
        get() = if (captionInsetHeight >= 0) captionInsetHeight else frame.height

    /** The height of the gesture area of the caption; always the full caption height. */
    private val gestureHeight: Int
        get() = frame.height

    constructor(
        token: WindowContainerToken,
        owner: Binder,
        frame: Rect,
        taskFrame: Rect? = null,
        boundingRects: List<Rect> = emptyList(),
        insetsBoundingRects: List<InsetsBoundingRect> = emptyList(),
        @InsetsSource.Flags flags: Int = 0,
        shouldAddCaptionInset: Boolean = false,
        excludedFromAppBounds: Boolean = false,
        captionInsetHeight: Int = -1,
    ) : this(
        token,
        owner,
        Frame.Relative(frame.height()),
        boundingRects,
        insetsBoundingRects,
        flags,
        shouldAddCaptionInset,
        if (excludedFromAppBounds) AppBoundsExclusion(checkNotNull(taskFrame)) else null,
        captionInsetHeight,
    )

    /** Updates the caption insets. */
    fun update(wct: WindowContainerTransaction) {
        if (!shouldAddCaptionInset) return
        logD(
            TAG,
            "update insets for wc=%s with frame=%s, rects=%s, appBoundsExclusion=%s, "
                    + "captionInsetHeight=%d",
            token,
            frame,
            if (com.android.window.flags.Flags.improveFluidResizingPerformance()) {
                insetsBoundingRects
            } else {
                boundingRects
            },
            appBoundsExclusion,
            reportedCaptionHeight,
        )
        if (com.android.window.flags.Flags.improveFluidResizingPerformance()) {
            val rects: Array<InsetsBoundingRect>? =
                if (insetsBoundingRects.isEmpty()) null else insetsBoundingRects.toTypedArray()
            when (frame) {
                is Frame.Absolute -> {
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.captionBar(),
                        captionBarRect(frame.rect),
                        rects,
                        flags,
                    )
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.mandatorySystemGestures(),
                        frame.rect,
                        rects,
                        /* flags= */ 0,
                    )
                }
                is Frame.Relative -> {
                    val insets = Insets.of(0, reportedCaptionHeight, 0, 0)
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.captionBar(),
                        insets,
                        rects,
                        flags,
                    )
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.mandatorySystemGestures(),
                        Insets.of(0, gestureHeight, 0, 0),
                        rects,
                        /* flags= */ 0,
                    )
                }
            }
        } else {
            val rects: Array<Rect>? =
                if (boundingRects.isEmpty()) null else boundingRects.toTypedArray()
            when (frame) {
                is Frame.Absolute -> {
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.captionBar(),
                        captionBarRect(frame.rect),
                        rects,
                        flags,
                    )
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.mandatorySystemGestures(),
                        frame.rect,
                        rects,
                        /* flags= */ 0,
                    )
                }
                is Frame.Relative -> {
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.captionBar(),
                        Insets.of(0, reportedCaptionHeight, 0, 0),
                        rects,
                        flags,
                    )
                    wct.addInsetsSource(
                        token,
                        owner,
                        INDEX,
                        WindowInsets.Type.mandatorySystemGestures(),
                        Insets.of(0, gestureHeight, 0, 0),
                        rects,
                        /* flags= */ 0,
                    )
                }
            }
        }
        if (!Flags.refactorCaptionSandboxingToCore()) {
            appBoundsExclusion?.let { exclusion ->
                val appBounds = Rect(exclusion.taskFrame).apply { top += frame.height }
                wct.setAppBounds(token, appBounds)
            }
        }
    }

    /** The captionBar source rect, clamped to [reportedCaptionHeight] if needed. */
    private fun captionBarRect(rect: Rect): Rect =
        if (captionInsetHeight in 0 until rect.height()) {
            Rect(rect).apply { bottom = top + captionInsetHeight }
        } else {
            rect
        }

    /** Removes the caption insets. */
    fun remove(wct: WindowContainerTransaction) {
        wct.removeInsetsSource(token, owner, INDEX, WindowInsets.Type.captionBar())
        wct.removeInsetsSource(token, owner, INDEX, WindowInsets.Type.mandatorySystemGestures())
        if (!Flags.refactorCaptionSandboxingToCore()) {
            appBoundsExclusion?.let { wct.setAppBounds(token, Rect()) }
        }
    }

    companion object {
        private const val TAG = "WindowDecorationInsets"
        /** Index for caption insets source. */
        private const val INDEX = 0
    }
}
