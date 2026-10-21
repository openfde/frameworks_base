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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.animation.LinearInterpolator
import android.window.ScreenCapture.ScreenCaptureParams
import android.window.ScreenCaptureInternal
import android.window.TransitionInfo.Change
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
import com.android.internal.jank.InteractionJankMonitor
import java.time.Duration
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Snapshot-based mesh-warp minimize animation.
 *
 * The task surface is captured once at the start of the transition, then drawn into a temporary
 * SurfaceControl using [Canvas.drawBitmapMesh]. Unlike the normal minimize animation, which is
 * limited to affine transforms, this can bend the window into the dock icon like the macOS genie
 * effect.
 *
 * The original task leash is hidden and replaced by [mRenderLayer] for the duration of the
 * animation.
 */
object GenieMinimizeAnimator {
    private const val TAG = "GenieMinimizeAnimator"

    private const val DURATION_MS = 480L
    private const val MESH_COLS = 20
    private const val MESH_ROWS = 24
    private const val TOP_LAG = 0.45f
    private const val TARGET_WIDTH_FACTOR = 0.20f
    private const val TARGET_HEIGHT_FACTOR = 0.20f
    private const val MAX_SNAPSHOT_SIZE = 1920f
    private const val ALPHA_START = 0.86f
    private const val RENDER_MARGIN_FRACTION = 0.35f

    /**
     * Creates a mesh-warp minimize animation for [change], or returns null when the snapshot or
     * the temporary surface cannot be created. In that case the caller should use the regular
     * minimize animation.
     *
     * [startTransaction] is applied by the transition after all animations have been created, so
     * the original task is hidden and the render layer is shown only once the first snapshot frame
     * has been drawn.
     */
    @JvmStatic
    fun create(
        context: Context,
        change: Change,
        targetBounds: Rect,
        displayAreaLeash: SurfaceControl,
        startTransaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
        interactionJankMonitor: InteractionJankMonitor,
        animationHandler: Handler,
        startAnimDelay: Duration,
    ): Animator? {
        val startBounds = change.startAbsBounds
        if (startBounds.isEmpty || targetBounds.isEmpty) return null

        val metrics = context.resources.displayMetrics
        val displayWidth = metrics.widthPixels
        val displayHeight = metrics.heightPixels
        if (displayWidth <= 0 || displayHeight <= 0) return null

        val snapshot = captureSnapshot(change.leash, startBounds) ?: return null
        val bitmap = snapshot.bitmap
        Log.d(
            TAG,
            "snapshot ${bitmap.width}x${bitmap.height} start=$startBounds target=$targetBounds",
        )
        val renderBounds =
            calculateRenderBounds(startBounds, targetBounds, displayWidth, displayHeight)
        if (renderBounds.isEmpty) {
            snapshot.release()
            return null
        }

        val renderLayer = try {
            SurfaceControl.Builder()
                .setName("GenieMinimize")
                .setCallsite(TAG)
                .setParent(displayAreaLeash)
                .setBufferSize(renderBounds.width(), renderBounds.height())
                .setFormat(PixelFormat.TRANSLUCENT)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create render layer", e)
            snapshot.release()
            return null
        }

        val surface = try {
            Surface(renderLayer)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create render surface", e)
            renderLayer.release()
            snapshot.release()
            return null
        }
        val vertices = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
        val paint =
            Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }

        try {
            computeVertices(startBounds, targetBounds, renderBounds, 1f, vertices)
            drawFrame(surface, bitmap, vertices, paint)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to draw initial snapshot frame", e)
            cleanup(renderLayer, surface, bitmap, snapshot)
            return null
        }

        try {
            startTransaction
                .setLayer(renderLayer, Integer.MAX_VALUE)
                .setPosition(renderLayer, renderBounds.left.toFloat(), renderBounds.top.toFloat())
                .setAlpha(renderLayer, 1f)
                .show(renderLayer)
                .hide(change.leash)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show genie render layer", e)
            cleanup(renderLayer, surface, bitmap, snapshot)
            return null
        }

        var cleanedUp = false
        val doCleanup = {
            if (!cleanedUp) {
                cleanedUp = true
                cleanup(renderLayer, surface, bitmap, snapshot)
            }
        }

        val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION_MS
                interpolator = LinearInterpolator()
                this.startDelay = startAnimDelay.toMillis()
                addUpdateListener { animation ->
                    val fraction = animation.animatedFraction
                    computeVertices(startBounds, targetBounds, renderBounds, fraction, vertices)
                    try {
                        drawFrame(surface, bitmap, vertices, paint)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to draw genie frame", e)
                    }
                    val alpha =
                        if (fraction <= ALPHA_START) {
                            1f
                        } else {
                            val fade = (fraction - ALPHA_START) / (1f - ALPHA_START)
                            1f - fade * fade
                        }
                    Transaction()
                        .setAlpha(renderLayer, alpha)
                        .setFrameTimeline(Choreographer.getInstance().vsyncId)
                        .apply()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            interactionJankMonitor.begin(
                                renderLayer,
                                context,
                                animationHandler,
                                CUJ_DESKTOP_MODE_MINIMIZE_WINDOW,
                            )
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            interactionJankMonitor.cancel(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            interactionJankMonitor.end(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                            doCleanup()
                            onAnimFinish(animation)
                        }
                    }
                )
            }
        return animator
    }

    private data class Snapshot(
        val bitmap: Bitmap,
        val hardwareBuffer: android.hardware.HardwareBuffer?,
    ) {
        fun release() {
            if (!bitmap.isRecycled) bitmap.recycle()
            try {
                hardwareBuffer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to close snapshot buffer", e)
            }
        }
    }

    private fun captureSnapshot(leash: SurfaceControl, startBounds: Rect): Snapshot? {
        val maxDimension = max(startBounds.width(), startBounds.height()).toFloat()
        val scale = if (maxDimension > MAX_SNAPSHOT_SIZE) {
            MAX_SNAPSHOT_SIZE / maxDimension
        } else {
            1f
        }
        val buffer = try {
            ScreenCaptureInternal.captureLayers(
                ScreenCaptureInternal.LayerCaptureArgs.Builder(leash)
                    .setSourceCrop(Rect(0, 0, startBounds.width(), startBounds.height()))
                    .setFrameScale(scale)
                    .setSecureContentPolicy(ScreenCaptureParams.SECURE_CONTENT_POLICY_CAPTURE)
                    .setProtectedContentPolicy(
                        ScreenCaptureParams.PROTECTED_CONTENT_POLICY_CAPTURE
                    )
                    .setPreserveDisplayColors(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to capture task snapshot", e)
            return null
        }
        val hardwareBuffer = buffer?.hardwareBuffer
        if (buffer == null || hardwareBuffer == null || buffer.containsSecureLayers()) {
            Log.w(TAG, "No usable task snapshot")
            hardwareBuffer?.close()
            return null
        }
        val raw = try {
            buffer.asBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to wrap task snapshot", e)
            null
        }
        if (raw == null) {
            hardwareBuffer.close()
            return null
        }

        val software =
            if (raw.config == Bitmap.Config.HARDWARE) {
                try {
                    raw.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to copy hardware snapshot", e)
                    null
                }
            } else {
                raw
            }
        if (software == null) {
            raw.recycle()
            hardwareBuffer.close()
            return null
        }
        if (software !== raw) {
            raw.recycle()
        }
        return Snapshot(software, hardwareBuffer)
    }

    private fun calculateRenderBounds(
        startBounds: Rect,
        targetBounds: Rect,
        displayWidth: Int,
        displayHeight: Int,
    ): Rect {
        val bounds = Rect(startBounds)
        bounds.union(targetBounds)
        val margin =
            (max(startBounds.width(), startBounds.height()) * RENDER_MARGIN_FRACTION).toInt()
        bounds.inset(-margin, -margin)
        bounds.intersect(0, 0, displayWidth, displayHeight)
        return bounds
    }

    /**
     * Computes a grid in display coordinates. The bottom rows lead towards the dock while the top
     * rows lag, which bends the window into a funnel. The destination is converted to
     * [renderBounds] local coordinates for the render surface.
     */
    fun computeVertices(
        startBounds: Rect,
        targetBounds: Rect,
        renderBounds: Rect,
        fraction: Float,
        outVertices: FloatArray,
    ) {
        val t = fraction.coerceIn(0f, 1f)
        val startWidth = startBounds.width().toFloat()
        val startHeight = startBounds.height().toFloat()
        val startCenterX = startBounds.exactCenterX()
        val targetCenterX = targetBounds.exactCenterX()
        val targetCenterY = targetBounds.exactCenterY()
        val targetWidth = targetBounds.width() * TARGET_WIDTH_FACTOR
        val targetHeight = targetBounds.height() * TARGET_HEIGHT_FACTOR
        val originX = renderBounds.left.toFloat()
        val originY = renderBounds.top.toFloat()
        val controlX = startCenterX + (targetCenterX - startCenterX) * 0.18f

        var index = 0
        for (row in 0..MESH_ROWS) {
            val v = row.toFloat() / MESH_ROWS
            // Bottom rows start immediately; top rows wait and then catch up.
            val delay = (1f - v) * TOP_LAG
            val rowT = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)

            val xProgress = rowT.pow(0.70f)
            val yProgress = rowT.pow(0.80f)
            val widthProgress = easeOutCubic(rowT)
            val centerX = quadratic(startCenterX, controlX, targetCenterX, xProgress)
            val startRowY = startBounds.top + v * startHeight
            val targetRowY = targetCenterY + (v - 0.5f) * targetHeight
            val centerY = lerp(startRowY, targetRowY, yProgress)
            val rowWidth = lerp(startWidth, targetWidth, widthProgress)

            for (col in 0..MESH_COLS) {
                val u = col.toFloat() / MESH_COLS
                val arc =
                    (1f - rowT) * (2f * u - 1f).pow(2f) * startHeight * 0.025f *
                        sin(Math.PI.toFloat() * t)
                outVertices[index++] = centerX + (u - 0.5f) * rowWidth - originX
                outVertices[index++] = centerY + arc - originY
            }
        }
    }

    private fun drawFrame(
        surface: Surface,
        bitmap: Bitmap,
        vertices: FloatArray,
        paint: Paint,
    ) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmapMesh(
                bitmap,
                MESH_COLS,
                MESH_ROWS,
                vertices,
                0,
                null,
                0,
                paint,
            )
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun cleanup(
        renderLayer: SurfaceControl,
        surface: Surface,
        bitmap: Bitmap,
        snapshot: Snapshot,
    ) {
        try {
            Transaction().remove(renderLayer).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove render layer", e)
        }
        try {
            surface.release()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release render surface", e)
        }
        try {
            renderLayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release render layer", e)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        // The bitmap may be backed by the same buffer; close it last.
        try {
            snapshot.hardwareBuffer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to close snapshot buffer", e)
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun quadratic(start: Float, control: Float, end: Float, fraction: Float): Float {
        val inverse = 1f - fraction
        return inverse * inverse * start + 2f * inverse * fraction * control +
            fraction * fraction * end
    }

    private fun easeOutCubic(fraction: Float): Float {
        val inverse = 1f - fraction
        return 1f - inverse * inverse * inverse
    }
}
