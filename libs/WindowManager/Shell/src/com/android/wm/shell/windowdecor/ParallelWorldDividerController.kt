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
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.IWindowSession
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowInputChannelParams
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.view.WindowlessWindowManager
import android.widget.FrameLayout
import android.window.InputTransferToken
import android.window.TaskConstants
import androidx.compose.ui.graphics.toArgb
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.function.Supplier

/** Callback invoked while the user drags the divider. */
fun interface OnRatioChangedListener {
    /**
     * @param ratio fraction of the task width that belongs to the right pane.
     * @param persist whether the ratio should be remembered as the user preference.
     */
    fun onRatioChanged(taskId: Int, ratio: Float, persist: Boolean)
}

private const val TAG = "ParallelWorldDivider"

/**
 * Draggable divider between the two panes of a parallel world (FDE magic window) task.
 *
 * The divider is rendered by a small [SurfaceControlViewHost] overlay and receives its input
 * through an [InputEventReceiver] on the task leash, the same input mechanism used by the window
 * resize handles ([DragResizeInputListener]). The view host itself is created without an input
 * channel so that only one input window is used.
 */
class ParallelWorldDividerController
@JvmOverloads
constructor(
    private val context: Context,
    private val displayController: DisplayController,
    private val transactionSupplier: Supplier<SurfaceControl.Transaction>,
    initialTaskInfo: RunningTaskInfo,
    parentLeash: SurfaceControl,
    private val onRatioChanged: OnRatioChangedListener,
    /** Used to pick the divider colors from the theme of the task; optional. */
    private val decorThemeUtil: DecorThemeUtil? = null,
    /**
     * Covers the two panes while the user drags the divider, so that the drag follows the pointer
     * even when the activities cannot be resized live; optional.
     */
    private val dragVeil: ParallelWorldDragVeil? = null,
    /** Invoked when a drag starts, used to hide the hint; optional. */
    private val onDragStarted: (() -> Unit)? = null,
    /** Invoked when the pointer starts or stops hovering the divider; optional. */
    private val onHoverChanged: ((Boolean) -> Unit)? = null,
) {
    private val taskId: Int = initialTaskInfo.taskId
    private var taskInfo: RunningTaskInfo = initialTaskInfo
    private val leash: SurfaceControl
    private val viewHost: SurfaceControlViewHost
    private val rootView: FrameLayout
    private val line: View
    private val handle: View

    /**
     * Width of the divider surface, which is also the touchable width. It is wider than the
     * visual handle so that the divider is easy to grab with a mouse.
     */
    private val dividerSurfaceWidth: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_width)
    private val lineWidth: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_line_width)
    private val lineDragWidth: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_line_drag_width)
    private val handleWidth: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_handle_width)
    private val handleHeight: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_handle_height)
    private val handleDragHeight: Int =
        context.resources.getDimensionPixelSize(R.dimen.parallel_world_divider_handle_drag_height)

    /** Colors of the divider, refreshed from the task theme on every update. */
    private var lineColor: Int = DEFAULT_LINE_COLOR
    private var handleColor: Int = DEFAULT_HANDLE_COLOR
    private var highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR
    /** Whether the pointer is hovering the divider. */
    private var isHovered = false

    /** Input channel of the divider, granted on the task leash. */
    private val inputChannel: InputChannel?
    private var inputEventReceiver: InputEventReceiver? = null

    /** Ratio computed from the latest drag move, persisted when the drag finishes. */
    private var lastDraggedRatio = -1f
    /** Last ratio that was sent to the system server during the drag. */
    private var lastLiveRatio = -1f
    /** Uptime of the last live ratio update, used to throttle them. */
    private var lastLiveRatioTime = 0L
    /**
     * Boundary between the two panes, in task coordinates. It follows the actual divider
     * position, which is more reliable than the ratio from the task info: the task info is not
     * re-sent for every ratio change, so its ratio can be stale.
     */
    private var lastBoundary = -1f
    /** Boundary when the drag started, the drag is computed relative to it. */
    private var dragStartBoundary = -1f
    /** Raw x of the pointer when the drag started, used to follow the pointer. */
    private var dragStartRawX = 0f
    /** Top position of the divider, kept for the local move during a drag. */
    private var lastTop = 0f
    /**
     * Ratio that was sent when the drag finished and that the panes have to reach before the veils
     * are hidden, or {@code -1} when there is nothing pending.
     */
    private var pendingVeilHideRatio = -1f
    private val hideVeilRunnable = Runnable {
        pendingVeilHideRatio = -1f
        dragVeil?.hide()
    }
    /** Size the hosted view was last measured with, to avoid unnecessary setView calls. */
    private var lastViewWidth = -1
    private var lastViewHeight = -1

    init {
        leash = SurfaceControl.Builder()
            .setName("ParallelWorldDivider")
            .setContainerLayer()
            .setParent(parentLeash)
            .build()
        val windowManager = WindowlessWindowManager(taskInfo.configuration, leash, null)
        viewHost = SurfaceControlViewHost(
            context,
            displayController.getDisplay(taskInfo.displayId),
            windowManager,
            "ParallelWorldDivider",
        )

        line = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = lineWidth / 2f
                setColor(lineColor)
            }
        }
        handle = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = handleWidth / 2f
                setColor(handleColor)
            }
        }
        rootView = FrameLayout(context).apply {
            addView(
                line,
                FrameLayout.LayoutParams(lineWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                },
            )
            addView(
                handle,
                FrameLayout.LayoutParams(handleWidth, handleHeight).apply {
                    gravity = android.view.Gravity.CENTER
                },
            )
        }
        applyVisualState()

        inputChannel = createInputChannel()
        if (inputChannel != null) {
            inputEventReceiver = object : InputEventReceiver(inputChannel,
                Looper.myLooper() ?: Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    val handled = event is MotionEvent && handleMotionEvent(event)
                    finishInputEvent(event, handled)
                }
            }
        } else {
            Log.e(TAG, "divider has no input channel, the divider will not be draggable")
        }
        // The first update() from the window decoration attaches the divider to the task leash.
    }

    /**
     * Updates the divider position and size from the latest task info.
     *
     * @param parentLeash the task leash the divider is attached to; the divider follows the task
     *                    visibility, crop and z-order.
     */
    fun update(info: RunningTaskInfo, captionHeight: Int, parentLeash: SurfaceControl) {
        taskInfo = info
        updateDividerColors(info)
        // The panes reached their final size once the task reports the ratio that was applied when
        // the drag finished. The applications still need a few frames to redraw behind the veils,
        // so they are kept a little longer: when they disappear, the content is final and does not
        // have to settle afterwards.
        if (pendingVeilHideRatio > 0f
            && abs(info.magicWindowRatio - pendingVeilHideRatio) < RATIO_EPSILON) {
            pendingVeilHideRatio = -1f
            rootView.removeCallbacks(hideVeilRunnable)
            rootView.postDelayed(hideVeilRunnable, VEIL_HIDE_DELAY_MS)
        }
        val bounds = info.configuration.windowConfiguration.bounds
        val ratio = currentRatio(info)
        // While dragging, the divider follows the pointer instead of the ratio from the task
        // info, which is not refreshed for every ratio change.
        val boundary =
            if (dragStartBoundary >= 0f && lastBoundary >= 0f) lastBoundary
            else bounds.width() * (1 - ratio)
        lastBoundary = boundary
        // Coordinates are relative to the task leash, the divider surface is centered on the
        // boundary between the two panes.
        val x = boundary - dividerSurfaceWidth / 2f
        val y = captionHeight.toFloat()
        val height = max(0, bounds.height() - captionHeight)
        if (height <= 0) {
            hide()
            return
        }
        lastTop = y
        if (dividerSurfaceWidth != lastViewWidth || height != lastViewHeight) {
            viewHost.setView(rootView, buildLayoutParams(dividerSurfaceWidth, height))
            updateInputRegion(dividerSurfaceWidth, height)
            lastViewWidth = dividerSurfaceWidth
            lastViewHeight = height
        }
        transactionSupplier.get()
            // Reparent on every update: the task leash may be recreated by the window decoration.
            .reparent(leash, parentLeash)
            .setLayer(leash, TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS)
            // The crop gives the container layer a size, which is required for the input window
            // to have a non-empty frame and touchable region.
            .setWindowCrop(leash, dividerSurfaceWidth, height)
            .setPosition(leash, x, y)
            .show(leash)
            .apply()
    }

    private fun hide() {
        transactionSupplier.get().setVisibility(leash, false).apply()
    }

    /** Refreshes the divider colors from the theme of the task. */
    private fun updateDividerColors(info: RunningTaskInfo) {
        val colorScheme = decorThemeUtil?.getColorScheme(info) ?: return
        lineColor = colorScheme.outlineVariant.toArgb()
        handleColor = colorScheme.onSurface.toArgb()
        highlightColor = colorScheme.primary.toArgb()
        applyVisualState()
    }

    /**
     * Applies the current visual state: the line and the handle are highlighted while the pointer
     * hovers the divider or the divider is dragged, and both grow while dragging.
     */
    private fun applyVisualState() {
        val dragging = dragStartBoundary >= 0f
        val active = dragging || isHovered
        (line.background as? GradientDrawable)?.setColor(if (active) highlightColor else lineColor)
        (handle.background as? GradientDrawable)?.setColor(
            if (active) highlightColor else handleColor)
        line.alpha = if (active) 1f else LINE_ALPHA
        handle.alpha = if (active) 1f else HANDLE_ALPHA
        updateViewSize(line, if (dragging) lineDragWidth else lineWidth,
            FrameLayout.LayoutParams.MATCH_PARENT)
        updateViewSize(handle, handleWidth, if (dragging) handleDragHeight else handleHeight)
    }

    private fun updateViewSize(view: View, width: Int, height: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (lp.width == width && lp.height == height) {
            return
        }
        lp.width = width
        lp.height = height
        view.layoutParams = lp
    }

    /**
     * The divider is an overlay on top of the app content, so the view host window has to be a
     * non-focusable trusted overlay and must not create its own input channel; the input is
     * handled by [inputEventReceiver] on the task leash.
     */
    private fun buildLayoutParams(width: Int, height: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT,
        )
        lp.title = "Parallel World Divider for task=$taskId"
        // The input is handled by our own channel on the task leash, the view host must not
        // create another one for the divider view.
        lp.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL
        lp.setTrustedOverlay()
        return lp
    }

    private fun createInputChannel(): InputChannel? {
        val session = getWindowSession() ?: return null
        return try {
            val params = WindowInputChannelParams()
            params.displayId = taskInfo.displayId
            params.surface = leash
            params.clientToken = Binder()
            params.inputTransferToken = InputTransferToken()
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            params.inputHandleName = "ParallelWorldDivider"
            session.grantInputChannel(params)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to grant the divider input channel", e)
            null
        }
    }

    private fun updateInputRegion(width: Int, height: Int) {
        val session = getWindowSession() ?: return
        // The channel must be looked up from the receiver: creating the receiver moves the
        // native state of the InputChannel, which then can no longer be used to get the token.
        val channelToken = inputEventReceiver?.token ?: return
        try {
            val params = WindowInputChannelParams()
            params.displayId = taskInfo.displayId
            params.channelToken = channelToken
            params.surface = leash
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            params.region = Region(0, 0, width, height)
            session.updateInputChannel(params)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to update the divider input region", e)
        }
    }

    private fun getWindowSession(): IWindowSession? =
        try {
            WindowManagerGlobal.getWindowSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get the window session", e)
            null
        }

    private fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = taskInfo.configuration.windowConfiguration.bounds
                dragStartBoundary = if (lastBoundary >= 0f) lastBoundary
                else bounds.width() * (1 - currentRatio(taskInfo))
                dragStartRawX = event.rawX
                lastDraggedRatio = -1f
                lastLiveRatio = -1f
                lastLiveRatioTime = 0L
                // A new drag starts: a pending hide from the previous drag must not fire while the
                // veils are visible again.
                rootView.removeCallbacks(hideVeilRunnable)
                pendingVeilHideRatio = -1f
                // Cover the panes: the divider (and the veils) follow the pointer while the panes
                // are resized behind the veils (see the move handling).
                onDragStarted?.invoke()
                updateDragVeil()
                applyVisualState()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_OUTSIDE -> {
                if (dragStartBoundary < 0f) {
                    return true
                }
                val bounds = taskInfo.configuration.windowConfiguration.bounds
                if (bounds.width() <= 0) {
                    return true
                }
                // Follow the pointer relative to the drag start. Raw coordinates are used
                // because the divider surface moves with the drag, which would otherwise
                // cancel out the pointer delta reported in window coordinates.
                val boundaryX = dragStartBoundary + (event.rawX - dragStartRawX)
                val ratio = 1f - boundaryX / bounds.width()
                val clamped = min(MAX_RATIO, max(MIN_RATIO, ratio))
                lastDraggedRatio = clamped
                // Move the divider and the veils right away for a responsive drag.
                lastBoundary = bounds.width() * (1 - clamped)
                val x = lastBoundary - dividerSurfaceWidth / 2f
                transactionSupplier.get().setPosition(leash, x, lastTop).apply()
                updateDragVeil()
                // Resize the panes while dragging too (throttled), like the window resize of the
                // desktop mode does: the applications relayout behind the veils, so when the drag
                // finishes the content already has its final size and does not have to scale
                // afterwards. Only the final ratio is persisted.
                val now = SystemClock.uptimeMillis()
                if (abs(clamped - lastLiveRatio) >= RATIO_EPSILON
                    && now - lastLiveRatioTime >= LIVE_RATIO_UPDATE_MS) {
                    lastLiveRatio = clamped
                    lastLiveRatioTime = now
                    onRatioChanged.onRatioChanged(taskId, clamped, false /* persist */)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag()
                return true
            }
            MotionEvent.ACTION_HOVER_ENTER -> {
                isHovered = true
                applyVisualState()
                onHoverChanged?.invoke(true)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isHovered = false
                applyVisualState()
                onHoverChanged?.invoke(false)
                return true
            }
        }
        return false
    }

    private fun endDrag() {
        if (lastDraggedRatio > 0f) {
            // The final ratio is sent here once more with persist: the live updates during the drag
            // are throttled, so the last one can be slightly behind the position of the pointer.
            onRatioChanged.onRatioChanged(taskId, lastDraggedRatio, true /* persist */)
            // Keep the veils until the panes have their final size, so that the user sees the
            // final layout as soon as the veils are gone (see update). A timeout makes sure the
            // veils are hidden even if the update never comes.
            pendingVeilHideRatio = lastDraggedRatio
            rootView.removeCallbacks(hideVeilRunnable)
            rootView.postDelayed(hideVeilRunnable, VEIL_HIDE_TIMEOUT_MS)
        } else {
            dragVeil?.hide()
        }
        dragStartBoundary = -1f
        applyVisualState()
    }

    /** Shows or moves the veils that cover the two panes while dragging. */
    private fun updateDragVeil() {
        val veil = dragVeil ?: return
        val bounds = taskInfo.configuration.windowConfiguration.bounds
        if (bounds.width() <= 0 || lastBoundary <= 0f) {
            return
        }
        val boundary = lastBoundary.roundToInt()
        val top = lastTop.roundToInt()
        val leftBounds = Rect(0, top, boundary, bounds.height())
        val rightBounds = Rect(boundary, top, bounds.width(), bounds.height())
        veil.show(leftBounds, rightBounds, taskInfo)
    }

    private fun currentRatio(info: RunningTaskInfo): Float =
        if (info.magicWindowRatio > 0f) info.magicWindowRatio else DEFAULT_RATIO

    /** Releases the divider overlay and its input. */
    fun close() {
        rootView.removeCallbacks(hideVeilRunnable)
        pendingVeilHideRatio = -1f
        dragVeil?.dispose()
        // The receiver owns the input channel, disposing it also disposes the channel.
        inputEventReceiver?.dispose()
        inputEventReceiver = null
        viewHost.release()
        transactionSupplier.get().remove(leash).apply()
    }

    private companion object {
        /** Fallback colors used until the task theme has been applied. */
        const val DEFAULT_LINE_COLOR = 0x66000000
        const val DEFAULT_HANDLE_COLOR = -0x67000000 // 0x99000000
        val DEFAULT_HIGHLIGHT_COLOR = 0xFF1A73E8.toInt()
        /** Alpha of the line and the handle when the divider is neither hovered nor dragged. */
        const val LINE_ALPHA = 0.5f
        const val HANDLE_ALPHA = 0.7f
        const val DEFAULT_RATIO = 5f / 9f
        const val MIN_RATIO = 0.2f
        const val MAX_RATIO = 0.8f
        /** Difference below which two ratios are considered the same. */
        const val RATIO_EPSILON = 0.001f
        /** Minimum time between two live ratio updates while dragging. */
        const val LIVE_RATIO_UPDATE_MS = 32L
        /**
         * Time the veils are kept after the final ratio was applied, to give the applications a few
         * frames to redraw behind them.
         */
        const val VEIL_HIDE_DELAY_MS = 200L
        /** Latest time the veils are kept after a drag before they are hidden anyway. */
        const val VEIL_HIDE_TIMEOUT_MS = 800L
    }
}
