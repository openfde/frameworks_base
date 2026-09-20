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

package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.window.TaskConstants
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import java.util.function.Supplier
import kotlin.math.min

/**
 * One-time hint shown the first time a task is split into the parallel world.
 *
 * The card explains how the feature is used (drag the divider, the menu of the window can turn it
 * off). It is a non-touchable overlay: it disappears on its own after a few seconds or as soon as
 * the user starts dragging the divider, so it never blocks the window.
 */
class ParallelWorldGuide(
    private val context: Context,
    private val displayController: DisplayController,
    private val transactionSupplier: Supplier<SurfaceControl.Transaction>,
    initialTaskInfo: RunningTaskInfo,
    private val parentLeash: SurfaceControl,
    private val backgroundColor: Int,
    private val textColor: Int,
) {
    private val taskId: Int = initialTaskInfo.taskId
    private var taskInfo: RunningTaskInfo = initialTaskInfo
    private val leash: SurfaceControl
    private val viewHost: SurfaceControlViewHost
    private val card: LinearLayout

    private val cardWidth: Int = dp(CARD_WIDTH_DP)
    private val cardHeight: Int = dp(CARD_HEIGHT_DP)

    private var isShown = false

    private val autoHide = Runnable { hide() }

    init {
        leash =
            SurfaceControl.Builder()
                .setName("ParallelWorldGuide")
                .setContainerLayer()
                .setParent(parentLeash)
                .build()
        val windowManager = WindowlessWindowManager(taskInfo.configuration, leash, null)
        viewHost =
            SurfaceControlViewHost(
                context,
                displayController.getDisplay(taskInfo.displayId),
                windowManager,
                "ParallelWorldGuide",
            )
        card = buildCard()
        viewHost.setView(card, buildLayoutParams())
    }

    /**
     * Shows the hint next to the divider.
     *
     * @param info the current task info, used to keep the card inside the task bounds
     * @param captionHeight height of the caption, the card is placed below it
     * @param boundaryX x position of the divider, in task coordinates
     */
    fun show(info: RunningTaskInfo, captionHeight: Int, boundaryX: Float) {
        taskInfo = info
        if (isShown) {
            return
        }
        isShown = true
        val taskWidth = info.configuration.windowConfiguration.bounds.width()
        val x = min(boundaryX + dp(MARGIN_DP), (taskWidth - cardWidth - dp(MARGIN_DP)).toFloat())
        val y = (captionHeight + dp(MARGIN_DP)).toFloat()
        transactionSupplier
            .get()
            .reparent(leash, parentLeash)
            .setLayer(leash, TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS)
            .setWindowCrop(leash, cardWidth, cardHeight)
            .setPosition(leash, maxOf(x, 0f), y)
            .show(leash)
            .apply()
        card.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    /** Hides the hint, e.g. because the user started dragging the divider. */
    fun hide() {
        if (!isShown) {
            return
        }
        isShown = false
        card.removeCallbacks(autoHide)
        transactionSupplier.get().setVisibility(leash, false).apply()
    }

    /** Releases the hint and its surface. */
    fun dispose() {
        isShown = false
        viewHost.release()
        transactionSupplier.get().remove(leash).apply()
    }

    private fun buildCard(): LinearLayout {
        val title =
            TextView(context).apply {
                setText(R.string.parallel_world_guide_title)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
                setTypeface(typeface, Typeface.BOLD)
            }
        val dragHint =
            TextView(context).apply {
                setText(R.string.parallel_world_guide_drag)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            }
        val menuHint =
            TextView(context).apply {
                setText(R.string.parallel_world_guide_menu)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            }
        val padding = dp(PADDING_DP)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                    setColor(backgroundColor)
                }
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(dragHint)
            addView(menuHint)
        }
    }

    /**
     * The hint is a non-touchable overlay on top of the app content: it must not create an input
     * channel so that it cannot block the window, and it has to be a trusted overlay to be allowed
     * above the app.
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val lp =
            WindowManager.LayoutParams(
                cardWidth,
                cardHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT,
            )
        lp.title = "Parallel World Guide for task=$taskId"
        lp.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL
        lp.setTrustedOverlay()
        return lp
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val CARD_WIDTH_DP = 260
        const val CARD_HEIGHT_DP = 104
        const val CORNER_RADIUS_DP = 8
        const val PADDING_DP = 12
        const val MARGIN_DP = 20
        const val TITLE_TEXT_SIZE_SP = 13f
        const val TEXT_SIZE_SP = 11f
        const val AUTO_HIDE_MS = 6000L
    }
}
