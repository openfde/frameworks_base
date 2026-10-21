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
import android.content.ComponentName
import android.graphics.Rect
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MinimizeAnimationTargetsTest {

    @Before
    fun setUp() {
        MinimizeAnimationTargets.clear()
    }

    @After
    fun tearDown() {
        MinimizeAnimationTargets.clear()
    }

    @Test
    fun findTarget_pendingTargetTakesPrecedenceAndIsConsumed() {
        val task = createTask()
        val pending = Rect(100, 200, 300, 400)
        val cached = Rect(1, 2, 3, 4)
        MinimizeAnimationTargets.updateDockIconBounds(
            0,
            bundleOf("test.pkg" to cached),
            bundleOf("42" to cached),
        )
        MinimizeAnimationTargets.setPendingTarget(42, pending)

        assertThat(MinimizeAnimationTargets.findTarget(task)).isEqualTo(pending)
        // The one-shot target must not be reused.
        assertThat(MinimizeAnimationTargets.findTarget(task)).isEqualTo(cached)
    }

    @Test
    fun findTarget_usesTaskBoundsBeforePackageBounds() {
        val task = createTask()
        val taskBounds = Rect(10, 20, 30, 40)
        val packageBounds = Rect(50, 60, 70, 80)
        MinimizeAnimationTargets.updateDockIconBounds(
            0,
            bundleOf("test.pkg" to packageBounds),
            bundleOf("42" to taskBounds),
        )

        assertThat(MinimizeAnimationTargets.findTarget(task)).isEqualTo(taskBounds)
    }

    @Test
    fun findTarget_fallsBackToPackageBounds() {
        val task = createTask()
        val packageBounds = Rect(50, 60, 70, 80)
        MinimizeAnimationTargets.updateDockIconBounds(
            0,
            bundleOf("test.pkg" to packageBounds),
            Bundle(),
        )

        assertThat(MinimizeAnimationTargets.findTarget(task)).isEqualTo(packageBounds)
    }

    @Test
    fun findTarget_returnsNullWhenDockIsUnknown() {
        assertThat(MinimizeAnimationTargets.findTarget(createTask())).isNull()
    }

    private fun createTask(): RunningTaskInfo =
        RunningTaskInfo().apply {
            taskId = 42
            displayId = 0
            baseActivity = ComponentName("test.pkg", "test.Activity")
        }

    private fun bundleOf(vararg entries: Pair<String, Rect>): Bundle =
        Bundle().apply {
            entries.forEach { (key, value) -> putParcelable(key, value) }
        }
}
