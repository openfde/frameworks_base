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
package com.android.wm.shell.windowdecor.extension

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityManager.TaskDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for [fdeCustomTitle]. */
class TaskInfoExtensionTest {
    @Test
    fun fdeCustomTitle_returnsTextAfterMarker() {
        val task = runningTaskWithLabel("prefix FDE::TITLE::My Title")

        assertEquals("My Title", task.fdeCustomTitle.toString())
    }

    @Test
    fun fdeCustomTitle_returnsNullWithoutMarker() {
        val task = runningTaskWithLabel("My Title")

        assertNull(task.fdeCustomTitle)
    }

    @Test
    fun fdeCustomTitle_returnsNullWhenMarkerHasNoTitle() {
        val task = runningTaskWithLabel("FDE::TITLE::")

        assertNull(task.fdeCustomTitle)
    }

    private fun runningTaskWithLabel(taskLabel: String): RunningTaskInfo =
        RunningTaskInfo().apply {
            taskDescription = TaskDescription().apply { label = taskLabel }
        }
}
