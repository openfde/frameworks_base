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
import android.graphics.Rect
import android.view.SurfaceControl
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.annotations.ShellMainThreadImmediate
import com.android.wm.shell.windowdecor.common.WindowDecorTaskResourceLoader
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Covers the two panes of a parallel world task while the user drags the divider.
 *
 * The panes are not resized live while dragging: every pointer move would have to resize the
 * activities and wait for them to re-lay out, which is slow and never follows the pointer. Instead
 * both panes are covered by a veil - a background color and the application icon, the same look
 * the desktop mode uses while a window is resized - and only the veils and the divider move while
 * dragging. The final ratio is applied once the drag finishes.
 *
 * The veils are created on the first drag and reused afterwards; each pane veil is hosted by its
 * own container surface so that [ResizeVeil] can move and crop the container without touching the
 * task leash.
 */
class ParallelWorldDragVeil(
    private val context: Context,
    private val displayController: DisplayController,
    private val taskResourceLoader: WindowDecorTaskResourceLoader,
    @ShellMainThread private val mainDispatcher: CoroutineDispatcher,
    @ShellMainThreadImmediate private val mainImmediateScope: CoroutineScope,
    private val parentLeash: SurfaceControl,
    private val transactionSupplier: Supplier<SurfaceControl.Transaction>,
) {
    private var leftVeil: ResizeVeil? = null
    private var rightVeil: ResizeVeil? = null
    private var leftContainer: SurfaceControl? = null
    private var rightContainer: SurfaceControl? = null

    /** Whether the veils are currently shown. */
    private var isVisible = false

    /**
     * Shows the veils over the two panes (creating them on the first call) or moves them when they
     * are already shown.
     *
     * @param leftBounds bounds of the main window pane, in task coordinates
     * @param rightBounds bounds of the additional window pane, in task coordinates
     */
    fun show(leftBounds: Rect, rightBounds: Rect, info: RunningTaskInfo) {
        if (isVisible) {
            update(leftBounds, rightBounds)
            return
        }
        val left = ensureVeil(left = true, info) ?: return
        val right = ensureVeil(left = false, info) ?: return
        val leftContainer = leftContainer ?: return
        val rightContainer = rightContainer ?: return
        val t = transactionSupplier.get()
        left.showVeil(t, leftContainer, leftBounds, info, false /* fadeIn */)
        right.showVeil(t, rightContainer, rightBounds, info, false /* fadeIn */)
        t.apply()
        isVisible = true
    }

    /** Moves the veils to the new pane bounds while dragging. */
    fun update(leftBounds: Rect, rightBounds: Rect) {
        if (!isVisible) {
            return
        }
        val t = transactionSupplier.get()
        leftVeil?.updateResizeVeil(t, leftBounds)
        rightVeil?.updateResizeVeil(t, rightBounds)
        t.apply()
    }

    /** Fades the veils out; the panes become visible again. */
    fun hide() {
        if (!isVisible) {
            return
        }
        isVisible = false
        leftVeil?.hideVeil()
        rightVeil?.hideVeil()
    }

    /** Releases the veils and their surfaces. */
    fun dispose() {
        isVisible = false
        leftVeil?.dispose()
        rightVeil?.dispose()
        leftVeil = null
        rightVeil = null
        val t = transactionSupplier.get()
        leftContainer?.let { t.remove(it) }
        rightContainer?.let { t.remove(it) }
        t.apply()
        leftContainer = null
        rightContainer = null
    }

    /** Returns the veil of the pane, creating the container and the veil on the first call. */
    private fun ensureVeil(left: Boolean, info: RunningTaskInfo): ResizeVeil? {
        val existing = if (left) leftVeil else rightVeil
        if (existing != null) {
            return existing
        }
        val name = if (left) "main" else "additional"
        val container =
            SurfaceControl.Builder()
                .setName("ParallelWorldDragVeil $name of Task=${info.taskId}")
                .setContainerLayer()
                .setParent(parentLeash)
                .setCallsite("ParallelWorldDragVeil#ensureVeil")
                .build()
        val veil =
            ResizeVeil(
                context = context,
                displayController = displayController,
                taskResourceLoader = taskResourceLoader,
                mainDispatcher = mainDispatcher,
                mainImmediateScope = mainImmediateScope,
                parentSurface = container,
                surfaceControlTransactionSupplier = transactionSupplier,
                taskInfo = info,
            )
        transactionSupplier
            .get()
            // Below the window decorations: the veils must not cover the caption and the divider.
            .setLayer(container, TASK_CHILD_LAYER_BELOW_DECORATIONS)
            .show(container)
            .apply()
        if (left) {
            leftContainer = container
            leftVeil = veil
        } else {
            rightContainer = container
            rightVeil = veil
        }
        return veil
    }

    private companion object {
        /** Below [android.window.TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS]. */
        val TASK_CHILD_LAYER_BELOW_DECORATIONS =
            android.window.TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS - 1000
    }
}
