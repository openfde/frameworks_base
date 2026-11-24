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

import android.app.ActivityManager.RunningTaskInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.SystemProperties
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.android.wm.shell.R
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.dagger.DynamicOverride
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.EnterReason
import com.android.wm.shell.desktopmode.data.DesktopRepository
import com.android.wm.shell.desktopmode.multidesks.DesksController
import com.android.wm.shell.desktopmode.multidesks.DesksOrganizer
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopState
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.OverviewVisibilityChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.UserChangeListener
import javax.inject.Inject

/**
 * OpenFDE: per-display virtual desktop strip.
 *
 * Renders one [DeskStripView] per display (only the primary display for now) as a system overlay
 * above the FDE dock. The strip shows every desk of the display with miniature rectangles for its
 * windows, lets the user switch desks with a click and create a new desk with "+".
 *
 * It also provides the drop target for the "drag a window onto a desk thumbnail" interaction:
 * [DesktopTasksController] forwards drag-move/drag-end events here, the strip highlights the desk
 * under the pointer and, on drop, the window is moved to that desk without switching the active
 * desk.
 */
@WMSingleton
class DeskStripController @Inject constructor(
    private val context: Context,
    private val displayController: DisplayController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val shellController: ShellController,
    @param:DynamicOverride private val userRepositories: DesktopUserRepositories,
    private val desksController: DesksController,
    @param:DynamicOverride private val desksOrganizer: DesksOrganizer,
    private val keyguardManager: KeyguardManager,
    private val desktopState: DesktopState,
    @param:ShellMainThread private val mainExecutor: ShellExecutor,
    @param:ShellMainThread private val handler: Handler,
) : OverviewVisibilityChangeListener, KeyguardChangeListener, UserChangeListener {

    private var desktopTasksController: DesktopTasksController? = null
    private val displayId = Display.DEFAULT_DISPLAY
    private var stripWindow: StripWindow? = null
    private var registeredRepository: DesktopRepository? = null
    private var toast: Toast? = null
    private var started = false
    private var refreshScheduled = false
    private var windowDragInProgress = false

    /**
     * Safety switches, so the strip and the drop interaction can be disabled on a running device
     * without rebuilding SystemUI:
     *   adb shell setprop persist.fde.desk_strip.enable 0
     *   adb shell setprop persist.fde.desk_strip.drag_to_desk 0
     * Both are read once when the shell starts; toggle requires restarting SystemUI.
     */
    private val enabled = SystemProperties.getBoolean(PROP_ENABLED, true)
    private val dragToDeskEnabled = SystemProperties.getBoolean(PROP_DRAG_TO_DESK, true)

    private val stripBottomMarginPx: Int by lazy {
        val displayContext = displayController.getDisplayContext(displayId) ?: context
        displayContext.resources.getDimensionPixelSize(R.dimen.desk_strip_bottom_margin)
    }

    private val density: Float
        get() = (displayController.getDisplayContext(displayId) ?: context)
            .resources.displayMetrics.density

    private val hideToastRunnable =
        Runnable {
            toast?.cancel()
            toast = null
        }

    private val deskChangeListener =
        object : DesktopRepository.DeskChangeListener {
            override fun onDeskAdded(displayId: Int, deskId: Int) {
                if (displayId == this@DeskStripController.displayId) scheduleRefresh()
            }

            override fun onDeskRemoved(displayId: Int, deskId: Int) {
                if (displayId == this@DeskStripController.displayId) scheduleRefresh()
            }

            override fun onActiveDeskChanged(
                displayId: Int,
                newActiveDeskId: Int,
                oldActiveDeskId: Int,
            ) {
                if (displayId == this@DeskStripController.displayId) scheduleRefresh()
            }

            override fun onCanCreateDesksChanged(canCreateDesks: Boolean) {
                scheduleRefresh()
            }

            override fun onTaskAppearingInDesk(taskId: Int, displayId: Int, deskId: Int) {
                if (displayId == this@DeskStripController.displayId) scheduleRefresh()
            }
        }

    private val taskInfoChangedListener: (RunningTaskInfo) -> Unit = { taskInfo ->
        if (taskInfo.displayId == displayId) scheduleRefresh()
    }

    private val taskVanishedListener: (RunningTaskInfo) -> Unit = { taskInfo ->
        if (taskInfo.displayId == displayId) scheduleRefresh()
    }

    private val displayListener =
        object : DisplayController.OnDisplaysChangedListener {
            override fun onDisplayConfigurationChanged(displayId: Int, newConfig: Configuration) {
                if (displayId == this@DeskStripController.displayId) scheduleRefresh()
            }
        }

    private val refreshRunnable =
        Runnable {
            refreshScheduled = false
            refresh()
        }

    /** Called once by [DesktopTasksController] after the shell has been initialized. */
    fun onInit(controller: DesktopTasksController) {
        if (started || !enabled) return
        desktopTasksController = controller
        started = true
        shellController.addOverviewVisibilityChangeListener(this)
        shellController.addKeyguardChangeListener(this)
        shellController.addUserChangeListener(this)
        registerRepositoryListeners()
        desksOrganizer.addOnDesktopTaskInfoChangedListener(taskInfoChangedListener)
        desksOrganizer.addOnDesktopTaskVanishedListener(taskVanishedListener)
        displayController.addDisplayWindowListener(displayListener)
        handler.post { refresh() }
    }

    override fun onOverviewShown(displayId: Int) {
        if (displayId == this.displayId) scheduleRefresh()
    }

    override fun onOverviewHidden(displayId: Int) {
        if (displayId == this.displayId) scheduleRefresh()
    }

    override fun onKeyguardVisibilityChanged(
        visible: Boolean,
        occluded: Boolean,
        animatingDismiss: Boolean,
    ) {
        scheduleRefresh()
    }

    override fun onUserChanged(newUserId: Int, userContext: Context) {
        registerRepositoryListeners()
        scheduleRefresh()
    }

    private fun registerRepositoryListeners() {
        val repository = userRepositories.current
        if (registeredRepository === repository) return
        registeredRepository?.removeDeskChangeListener(deskChangeListener)
        registeredRepository = repository
        repository.addDeskChangeListener(deskChangeListener, mainExecutor)
    }

    private fun scheduleRefresh() {
        if (!started || refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refreshRunnable, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        if (!started) return
        val displayContext = displayController.getDisplayContext(displayId) ?: context
        val repository = userRepositories.current
        val summaries = repository.getDeskSummaries(displayId)
        val displayLayout = displayController.getDisplayLayout(displayId)
        val displayWidth = displayLayout?.width() ?: 0
        val displayHeight = displayLayout?.height() ?: 0

        val items =
            summaries.map { summary ->
                val windows =
                    summary.taskIds.mapNotNull { taskId ->
                        val taskInfo = shellTaskOrganizer.getRunningTaskInfo(taskId)
                            ?: return@mapNotNull null
                        val bounds = taskInfo.configuration.windowConfiguration.bounds
                        DeskStripView.DeskWindow(
                            bounds =
                                RectF(
                                    bounds.left.toFloat(),
                                    bounds.top.toFloat(),
                                    bounds.right.toFloat(),
                                    bounds.bottom.toFloat(),
                                ),
                            minimized = summary.minimizedTaskIds.contains(taskId),
                        )
                    }
                DeskStripView.DeskItem(
                    deskId = summary.deskId,
                    label =
                        displayContext.getString(
                            R.string.desk_strip_item_name,
                            summary.position + 1,
                        ),
                    isActive = summary.isActive,
                    isMain = summary.position == 0,
                    windows = windows,
                )
            }

        ensureWindow()
        val window = stripWindow ?: return
        window.view.setDesks(
            items = items,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            mainBadgeLabel = displayContext.getString(R.string.desk_strip_main_badge),
        )
        window.view.contentDescription =
            displayContext.getString(R.string.desk_strip_accessibility)

        val visible =
            items.any { it.isActive } &&
                !shellController.isOverviewVisible(displayId) &&
                !keyguardManager.isKeyguardLocked &&
                desktopState.isDesktopModeSupportedOnDisplay(displayId)
        window.view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun ensureWindow() {
        if (stripWindow != null) return
        val displayContext = displayController.getDisplayContext(displayId) ?: context
        val windowContext =
            displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null /* options */,
            )
        val windowManager = windowContext.getSystemService(WindowManager::class.java)
        if (windowManager == null) {
            Log.w(TAG, "No WindowManager for display $displayId")
            return
        }
        val view =
            DeskStripView(windowContext).apply {
                onDeskClickListener = { deskId -> onDeskClicked(deskId) }
                onAddClickListener = { onAddDeskClicked() }
                visibility = View.GONE
            }
        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
                .apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = -stripBottomMarginPx
                    setTitle(WINDOW_TITLE)
                    setFitInsetsTypes(0)
                    setFitInsetsSides(0)
                    setFitInsetsIgnoringVisibility(true)
                }
        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to add the desk strip window", e)
            return
        }
        stripWindow = StripWindow(view)
        Log.d(TAG, "Desk strip window added on display $displayId")
    }

    private fun onDeskClicked(deskId: Int) {
        val repository = userRepositories.current
        val activeDeskId = repository.getActiveDeskId(displayId)
        Log.d(TAG, "onDeskClicked desk=$deskId active=$activeDeskId")
        if (activeDeskId == deskId) return
        val controller = desktopTasksController ?: return
        val userId = shellController.currentUserId
        if (activeDeskId == null) {
            controller.activateDesk(
                deskId = deskId,
                userId = userId,
                enterReason = EnterReason.TASKBAR_ICON,
                forceDefaultTransition = true,
            )
        } else {
            controller.switchDeskWithAnimation(
                displayId = displayId,
                userId = userId,
                activeDeskId = activeDeskId,
                deskId = deskId,
                enterReason = EnterReason.TASKBAR_ICON,
            )
        }
    }

    private fun onAddDeskClicked() {
        val userId = shellController.currentUserId
        if (!desksController.canCreateDesks(userId)) return
        Log.d(TAG, "onAddDeskClicked user=$userId")
        desksController.createDesk(
            displayId = displayId,
            userId = userId,
            enforceDeskLimit = true,
            activateDesk = true,
            enterReason = EnterReason.TASKBAR_ICON,
            onResult = { handler.post { scheduleRefresh() } },
        )
    }

    /**
     * Called by [DesktopTasksController] while a window is dragged. Highlights the desk thumbnail
     * under the pointer, if any.
     */
    fun onWindowDragMove(displayId: Int, x: Float, y: Float) {
        if (!enabled || !dragToDeskEnabled) return
        // onDragPositioningEnd() is also called for a plain caption click (a gesture that ends
        // without any move). Remember that a move actually happened so that a click on a caption
        // that happens to overlap the strip is not treated as a drop.
        windowDragInProgress = true
        if (displayId != this.displayId) {
            stripWindow?.view?.setDragHoveredDesk(null)
            return
        }
        stripWindow?.view?.setDragHoveredDesk(findDeskAt(x, y))
    }

    /**
     * Called by [DesktopTasksController] when a window drag ends. Returns the desk id of the
     * thumbnail under the pointer, or null if the window was not dropped on a thumbnail.
     */
    fun onWindowDragEnd(displayId: Int, x: Float, y: Float): Int? {
        val wasDragging = windowDragInProgress
        windowDragInProgress = false
        stripWindow?.view?.setDragHoveredDesk(null)
        if (!enabled || !dragToDeskEnabled || !wasDragging) return null
        return if (displayId == this.displayId) findDeskAt(x, y) else null
    }

    /** Called when a window drag is cancelled; clears any pending drop state. */
    fun onWindowDragCancel() {
        windowDragInProgress = false
        stripWindow?.view?.setDragHoveredDesk(null)
    }

    /** Shows the "moved to desktop N" feedback after a window was dropped on a thumbnail. */
    fun onTaskMovedToDesk(deskId: Int) {
        val repository = userRepositories.current
        if (deskId !in repository.getAllDeskIds()) return
        val position = repository.getDeskPosition(deskId) ?: return
        val displayContext = displayController.getDisplayContext(displayId) ?: context
        showToast(displayContext.getString(R.string.desk_moved_toast, position + 1))
    }

    private fun findDeskAt(displayX: Float, displayY: Float): Int? {
        val window = stripWindow ?: return null
        if (window.view.visibility != View.VISIBLE) return null
        val location = IntArray(2)
        window.view.getLocationOnScreen(location)
        val deskId =
            window.view.findDeskAt(displayX - location[0], displayY - location[1])
        return if (deskId == -1) null else deskId
    }

    private fun showToast(text: String) {
        val displayContext = displayController.getDisplayContext(displayId) ?: context
        handler.removeCallbacks(hideToastRunnable)
        toast?.cancel()
        val newToast = Toast.makeText(displayContext, text, Toast.LENGTH_SHORT)
        newToast.setGravity(
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            stripBottomMarginPx + dp(TOAST_ABOVE_STRIP_DP),
        )
        toast = newToast
        newToast.show()
        handler.postDelayed(hideToastRunnable, TOAST_DURATION_MS)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private class StripWindow(val view: DeskStripView)

    companion object {
        private const val TAG = "DeskStripController"
        private const val WINDOW_TITLE = "DeskStrip"
        private const val REFRESH_DELAY_MS = 80L
        private const val TOAST_DURATION_MS = 1200L
        private const val TOAST_ABOVE_STRIP_DP = 100
        private const val PROP_ENABLED = "persist.fde.desk_strip.enable"
        private const val PROP_DRAG_TO_DESK = "persist.fde.desk_strip.drag_to_desk"
    }
}
