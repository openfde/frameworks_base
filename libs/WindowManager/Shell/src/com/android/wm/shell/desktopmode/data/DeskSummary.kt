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

package com.android.wm.shell.desktopmode.data

/**
 * Immutable snapshot of a single desk, used by the desk strip UI.
 *
 * The task bounds are intentionally not part of this class; callers resolve them from
 * [com.android.wm.shell.ShellTaskOrganizer] so that this repository stays independent from the
 * task organizer.
 *
 * @property deskId id of the desk, which is also the task id of its root task.
 * @property position zero-based position of the desk in the ordered list of its display.
 * @property isActive whether this desk is the currently active desk of its display.
 * @property taskIds ids of the freeform tasks of the desk, ordered from top to bottom.
 * @property minimizedTaskIds ids of the tasks of the desk that are currently minimized.
 */
data class DeskSummary(
    val deskId: Int,
    val position: Int,
    val isActive: Boolean,
    val taskIds: List<Int>,
    val minimizedTaskIds: Set<Int>,
)
