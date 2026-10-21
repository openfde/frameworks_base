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

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.window.TransitionInfo.Change

/**
 * Keeps track of where the desktop dock icons are so that the minimize animation can suck a
 * freeform window into the icon instead of fading it out in place.
 *
 * The dock lives in a different process (Launcher/TaskPlugin). It pushes the icon bounds to shell
 * whenever its layout changes. A one-shot target can also be supplied together with a
 * minimize request, which is used when the minimize is initiated from the dock itself.
 *
 * All coordinates are in display space.
 */
object MinimizeAnimationTargets {
    /**
     * Broadcasts used by the taskbar/dock plugin to report icon bounds. A broadcast is used
     * instead of adding methods to IDesktopMode because Launcher bundles its own copy of that
     * interface and would otherwise have to be rebuilt as well.
     */
    const val ACTION_UPDATE_DOCK_ICON_BOUNDS =
        "com.android.wm.shell.action.UPDATE_DOCK_ICON_BOUNDS"
    const val ACTION_SET_MINIMIZE_TARGET =
        "com.android.wm.shell.action.SET_MINIMIZE_TARGET"
    const val EXTRA_DISPLAY_ID = "display_id"
    const val EXTRA_PACKAGE_NAMES = "package_names"
    const val EXTRA_PACKAGE_BOUNDS = "package_bounds"
    const val EXTRA_TASK_IDS = "task_ids"
    const val EXTRA_TASK_BOUNDS = "task_bounds"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TARGET_BOUNDS = "target_bounds"

    /** Targets set right before a minimize request; they take precedence over the cached map. */
    private val pendingTargets = HashMap<Int, PendingTarget>()

    /** Cached dock icon bounds, keyed by display id and package name. */
    private val iconBoundsByPackage = HashMap<Int, HashMap<String, Rect>>()

    /**
     * Cached dock icon bounds keyed by display id and task id. This is the accurate lookup for
     * apps that share a package (for example X11 windows) and for multi-window apps.
     */
    private val iconBoundsByTaskId = HashMap<Int, HashMap<Int, Rect>>()

    private data class PendingTarget(val bounds: Rect, val time: Long)

    /**
     * Replaces the dock icon bounds for [displayId].
     *
     * [boundsByPackage] keys are package names and [boundsByTaskId] keys are task ids (as
     * strings), both mapping to icon bounds.
     */
    @JvmStatic
    @Synchronized
    fun updateDockIconBounds(
        displayId: Int,
        boundsByPackage: Bundle,
        boundsByTaskId: Bundle,
    ) {
        val packageBounds = HashMap<String, Rect>()
        for (key in boundsByPackage.keySet()) {
            @Suppress("DEPRECATION")
            val rect = boundsByPackage.getParcelable(key) as? Rect ?: continue
            if (!rect.isEmpty) {
                packageBounds[key] = Rect(rect)
            }
        }
        if (packageBounds.isEmpty()) {
            iconBoundsByPackage.remove(displayId)
        } else {
            iconBoundsByPackage[displayId] = packageBounds
        }

        val taskBounds = HashMap<Int, Rect>()
        for (key in boundsByTaskId.keySet()) {
            val taskId = key.toIntOrNull() ?: continue
            @Suppress("DEPRECATION")
            val rect = boundsByTaskId.getParcelable(key) as? Rect ?: continue
            if (!rect.isEmpty) {
                taskBounds[taskId] = Rect(rect)
            }
        }
        if (taskBounds.isEmpty()) {
            iconBoundsByTaskId.remove(displayId)
        } else {
            iconBoundsByTaskId[displayId] = taskBounds
        }
    }

    /** Sets a target for one specific task; used when minimizing by tapping a dock icon. */
    @JvmStatic
    @Synchronized
    fun setPendingTarget(taskId: Int, bounds: Rect) {
        if (bounds.isEmpty) {
            pendingTargets.remove(taskId)
        } else {
            pendingTargets[taskId] = PendingTarget(Rect(bounds), SystemClock.uptimeMillis())
        }
    }

    /**
     * Returns the bounds of the dock icon that [change] should be minimized into, or null when the
     * dock location is unknown (in which case the caller should use the regular animation).
     */
    @JvmStatic
    @Synchronized
    fun findTarget(change: Change): Rect? {
        val taskInfo: RunningTaskInfo = change.taskInfo ?: return null
        return findTarget(taskInfo)
    }

    /**
     * Returns whether a dock icon target is known for [change], without consuming a pending
     * one-shot target.
     */
    @JvmStatic
    @Synchronized
    fun hasTarget(change: Change): Boolean {
        val taskInfo: RunningTaskInfo = change.taskInfo ?: return false
        val now = SystemClock.uptimeMillis()
        val pending = pendingTargets[taskInfo.taskId]
        if (pending != null) {
            if (now - pending.time <= PENDING_TARGET_TIMEOUT_MS) {
                return true
            }
            pendingTargets.remove(taskInfo.taskId)
        }
        if (iconBoundsByTaskId[taskInfo.displayId]?.containsKey(taskInfo.taskId) == true) {
            return true
        }
        if (iconBoundsByTaskId.values.any { it.containsKey(taskInfo.taskId) }) {
            return true
        }
        val packageName =
            taskInfo.baseActivity?.packageName ?: taskInfo.topActivity?.packageName ?: return false
        return iconBoundsByPackage[taskInfo.displayId]?.containsKey(packageName) == true
    }

    /** Returns the bounds of the dock icon for [taskInfo], or null when it is unknown. */
    @JvmStatic
    @Synchronized
    fun findTarget(taskInfo: RunningTaskInfo): Rect? {
        val now = SystemClock.uptimeMillis()
        val pending = pendingTargets[taskInfo.taskId]
        if (pending != null) {
            pendingTargets.remove(taskInfo.taskId)
            if (now - pending.time <= PENDING_TARGET_TIMEOUT_MS) {
                return Rect(pending.bounds)
            }
        }
        iconBoundsByTaskId[taskInfo.displayId]?.get(taskInfo.taskId)?.let { return Rect(it) }
        // Task ids are unique across displays. Fall back to searching other displays in case the
        // task info carries an invalid display id during the transition.
        for (displayBounds in iconBoundsByTaskId.values) {
            displayBounds[taskInfo.taskId]?.let { return Rect(it) }
        }
        val packageName =
            taskInfo.baseActivity?.packageName ?: taskInfo.topActivity?.packageName ?: return null
        return iconBoundsByPackage[taskInfo.displayId]?.get(packageName)?.let { Rect(it) }
    }

    /** Visible for tests. */
    @JvmStatic
    @Synchronized
    fun clear() {
        pendingTargets.clear()
        iconBoundsByPackage.clear()
        iconBoundsByTaskId.clear()
    }

    private const val PENDING_TARGET_TIMEOUT_MS = 2_000L
}
