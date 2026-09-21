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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.window.TaskConstants
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.common.DisplayController
import java.util.function.Supplier
import kotlin.math.max

/**
 * Confirmation dialog shown when the user enables the parallel world on an application that is not
 * part of the device config.
 *
 * It makes clear that the page which is open right now becomes the main (left) window, because
 * that choice is not easy to undo. Confirming configures the activity and enables the feature.
 */
class ParallelWorldConfigureDialog(
    private val context: Context,
    private val displayController: DisplayController,
    private val transactionSupplier: Supplier<SurfaceControl.Transaction>,
    private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    private val taskInfo: RunningTaskInfo,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val accentColor: Int,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val leash: SurfaceControl
    private val viewHost: SurfaceControlViewHost
    private val rootView: LinearLayout

    private val dialogWidth: Int = dp(DIALOG_WIDTH_DP)
    /**
     * Background of the card: the colour from the theme made opaque, a dialog that lets the window
     * behind it shine through is hard to read.
     */
    private val cardBackgroundColor: Int = Color.argb(
        255,
        Color.red(backgroundColor),
        Color.green(backgroundColor),
        Color.blue(backgroundColor),
    )
    /** Border of the card, a translucent accent colour. */
    private val borderColor: Int = (accentColor and 0x00FFFFFF) or 0x40000000
    /**
     * Height of the dialog. It is measured from the content in the constructor: the card has a
     * fixed size, a content that is higher than the card would clip the buttons.
     */
    private var dialogHeight: Int = dp(DIALOG_MIN_HEIGHT_DP)
    private var isDismissed = false

    init {
        leash =
            SurfaceControl.Builder()
                .setName("ParallelWorldConfigureDialog")
                .setContainerLayer()
                .also { rootTdaOrganizer.attachToDisplayArea(taskInfo.displayId, it) }
                .build()
        val windowManager = WindowlessWindowManager(taskInfo.configuration, leash, null)
        viewHost =
            SurfaceControlViewHost(
                context,
                displayController.getDisplay(taskInfo.displayId),
                windowManager,
                "ParallelWorldConfigureDialog",
            )
        rootView = buildCard()
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        dialogHeight = max(rootView.measuredHeight, dp(DIALOG_MIN_HEIGHT_DP))
        viewHost.setView(rootView, buildLayoutParams())
    }

    /**
     * Shows the dialog in the middle of the task. The leash is attached to the display area, the
     * task bounds are in display coordinates.
     */
    fun show() {
        val bounds = taskInfo.configuration.windowConfiguration.bounds
        val x = bounds.left + (bounds.width() - dialogWidth) / 2
        val y = bounds.top + (bounds.height() - dialogHeight) / 2
        Log.d(TAG, "configure dialog show: task=${taskInfo.taskId} h=$dialogHeight")
        val cardRed = Color.red(cardBackgroundColor) / 255f
        val cardGreen = Color.green(cardBackgroundColor) / 255f
        val cardBlue = Color.blue(cardBackgroundColor) / 255f
        transactionSupplier
            .get()
            .setLayer(leash, TaskConstants.TASK_CHILD_LAYER_FLOATING_MENU)
            .setPosition(leash, x.toFloat(), y.toFloat())
            .setWindowCrop(leash, dialogWidth, dialogHeight)
            // The window background is transparent, so the card is what is visible. Paint the leash
            // with the same colour as the card and give it a shadow: SurfaceFlinger only draws a
            // shadow for a layer that has a colour, and the shadow makes the dialog stand out from
            // the window behind it.
            .setColor(leash, floatArrayOf(cardRed, cardGreen, cardBlue))
            .setShadowRadius(leash, dp(SHADOW_RADIUS_DP).toFloat())
            .show(leash)
            .apply()
    }

    /** Hides and releases the dialog. */
    fun dismiss() {
        if (isDismissed) {
            return
        }
        isDismissed = true
        Log.d(TAG, "configure dialog dismiss: task=${taskInfo.taskId}")
        viewHost.release()
        transactionSupplier.get().remove(leash).apply()
        onDismiss()
    }

    private fun buildCard(): LinearLayout {
        val title =
            TextView(context).apply {
                setText(R.string.parallel_world_configure_title)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
                setTypeface(typeface, Typeface.BOLD)
            }
        val message =
            TextView(context).apply {
                setText(R.string.parallel_world_configure_message)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            }
        val hint =
            TextView(context).apply {
                // The main window cannot be changed easily, so always remind the user that the
                // page which is open right now is the one that becomes the left window. Which page
                // is the "main" one cannot be detected reliably (many apps open an ad page first).
                setText(R.string.parallel_world_configure_hint)
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            }
        val buttons =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(
                    actionView(R.string.parallel_world_configure_cancel, accentColor) {
                        dismiss()
                    },
                    actionParams(0),
                )
                addView(
                    actionView(R.string.parallel_world_configure_enable, accentColor) {
                        onConfirm()
                        dismiss()
                    },
                    actionParams(ACTION_SPACING_DP),
                )
            }
        val padding = dp(PADDING_DP)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                    setColor(cardBackgroundColor)
                    // A border so that the card is visible even on a window of a similar colour.
                    setStroke(dp(STROKE_WIDTH_DP), borderColor)
                }
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(message, blockParams(BLOCK_SPACING_DP))
            addView(hint, blockParams(BLOCK_SPACING_DP))
            addView(buttons, blockParams(BUTTONS_SPACING_DP))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                }
                true
            }
        }
    }

    private fun blockParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
            .apply { topMargin = dp(topMarginDp) }

    private fun actionParams(startMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
            .apply { marginStart = dp(startMarginDp) }

    private fun actionView(textRes: Int, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            setText(textRes)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ACTION_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val padding = dp(ACTION_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val lp =
            WindowManager.LayoutParams(
                dialogWidth,
                dialogHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT,
            )
        lp.title = "Parallel World Configure Dialog for task=${taskInfo.taskId}"
        lp.setTrustedOverlay()
        return lp
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ParallelWorld"
        const val DIALOG_WIDTH_DP = 340
        const val DIALOG_MIN_HEIGHT_DP = 190
        const val CORNER_RADIUS_DP = 14
        const val PADDING_DP = 20
        const val STROKE_WIDTH_DP = 1
        const val SHADOW_RADIUS_DP = 12
        const val BLOCK_SPACING_DP = 10
        const val BUTTONS_SPACING_DP = 16
        const val ACTION_SPACING_DP = 12
        const val ACTION_PADDING_DP = 10
        const val TITLE_TEXT_SIZE_SP = 16f
        const val TEXT_SIZE_SP = 13f
        const val ACTION_TEXT_SIZE_SP = 14f
        const val LINE_SPACING_MULTIPLIER = 1.3f
    }
}
