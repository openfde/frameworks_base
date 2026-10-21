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

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GenieMinimizeAnimatorTest {
    private val startBounds = Rect(100, 100, 600, 500)
    private val targetBounds = Rect(900, 800, 964, 864)
    private val renderBounds = Rect(0, 0, 1200, 1200)
    private val vertices = FloatArray((COLS + 1) * (ROWS + 1) * 2)

    @Test
    fun computeVertices_atStart_isIdentity() {
        GenieMinimizeAnimator.computeVertices(
            startBounds,
            targetBounds,
            renderBounds,
            0f,
            vertices,
        )

        assertVertex(0, startBounds.left, startBounds.top)
        assertVertex(lastIndex(), startBounds.right, startBounds.bottom)
    }

    @Test
    fun computeVertices_atEnd_convergesOnIconCenter() {
        GenieMinimizeAnimator.computeVertices(
            startBounds,
            targetBounds,
            renderBounds,
            1f,
            vertices,
        )

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in vertices.indices step 2) {
            minX = minOf(minX, vertices[i])
            minY = minOf(minY, vertices[i + 1])
            maxX = maxOf(maxX, vertices[i])
            maxY = maxOf(maxY, vertices[i + 1])
        }
        val rect = Rect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
        assertThat(Math.abs(rect.centerX() - targetBounds.centerX())).isLessThan(3)
        assertThat(Math.abs(rect.centerY() - targetBounds.centerY())).isLessThan(3)
        assertThat(rect.width()).isLessThan(targetBounds.width() / 2 + 1)
        assertThat(rect.height()).isLessThan(targetBounds.height() / 2 + 1)
    }

    @Test
    fun computeVertices_midway_bottomLeadsAndNarrows() {
        GenieMinimizeAnimator.computeVertices(
            startBounds,
            targetBounds,
            renderBounds,
            0.5f,
            vertices,
        )

        val topLeft = vertex(0, 0)
        val topRight = vertex(0, COLS)
        val bottomLeft = vertex(ROWS, 0)
        val bottomRight = vertex(ROWS, COLS)

        // Bottom is narrower than the top: this is the funnel.
        assertThat(bottomRight.first - bottomLeft.first)
            .isLessThan(topRight.first - topLeft.first)
        // Bottom has travelled further down than the top.
        assertThat(bottomLeft.second).isGreaterThan(topLeft.second)
        // The bottom should also be most of the way to the dock by the halfway point, rather than
        // shrinking first and only moving at the end.
        val bottomTargetY =
            targetBounds.exactCenterY() + targetBounds.height() * 0.20f * 0.5f
        val bottomTravel =
            (bottomLeft.second + renderBounds.top - startBounds.bottom) /
                (bottomTargetY - startBounds.bottom)
        assertThat(bottomTravel).isGreaterThan(0.85f)
        // Bottom leads towards the icon on the right.
        assertThat((bottomLeft.first + bottomRight.first) / 2f)
            .isGreaterThan((topLeft.first + topRight.first) / 2f)
    }

    @Test
    fun computeVertices_late_topIsStillCollapsing() {
        GenieMinimizeAnimator.computeVertices(
            startBounds,
            targetBounds,
            renderBounds,
            0.7f,
            vertices,
        )

        val topLeft = vertex(0, 0)
        val topRight = vertex(0, COLS)
        val bottomLeft = vertex(ROWS, 0)
        val bottomRight = vertex(ROWS, COLS)
        val topWidth = topRight.first - topLeft.first
        val bottomWidth = bottomRight.first - bottomLeft.first

        // By 70% the top must have collapsed too, not only the bottom tip.
        assertThat(topWidth).isLessThan(startBounds.width() * 0.25f)
        assertThat(bottomWidth).isLessThan(topWidth)
    }

    private fun assertVertex(index: Int, x: Int, y: Int) {
        val (vx, vy) = vertex(index / (COLS + 1), (index % (COLS + 1)))
        assertThat(vx).isWithin(0.01f).of((x - renderBounds.left).toFloat())
        assertThat(vy).isWithin(0.01f).of((y - renderBounds.top).toFloat())
    }

    private fun vertex(row: Int, col: Int): Pair<Float, Float> {
        val index = (row * (COLS + 1) + col) * 2
        return vertices[index] to vertices[index + 1]
    }

    private fun lastIndex(): Int = (COLS + 1) * (ROWS + 1) - 1

    private companion object {
        const val COLS = 20
        const val ROWS = 24
    }
}
