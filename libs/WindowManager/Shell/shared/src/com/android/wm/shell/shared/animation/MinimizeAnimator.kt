/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl.Transaction
import android.view.animation.LinearInterpolator
import android.window.TransitionInfo.Change
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
import com.android.internal.jank.InteractionJankMonitor
import com.android.wm.shell.shared.animation.WindowAnimator.BoundsAnimationParams.AnimationBounds
import java.time.Duration
import kotlin.math.pow

/** Creates minimization animation */
object MinimizeAnimator {
    private const val TAG = "MinimizeAnimator"

    private const val MINIMIZE_ANIM_ALPHA_DURATION_MS = 100L

    /**
     * Duration of the "suck into the dock" animation. Slightly longer than the regular minimize
     * animation so the curved path and the non-uniform scaling are readable.
     */
    private const val MINIMIZE_TO_DOCK_DURATION_MS = 420L

    /** Fraction of the animation after which the window starts fading out. */
    private const val MINIMIZE_TO_DOCK_ALPHA_START = 0.90f

    /** Final size relative to the dock icon. The window is absorbed by the icon, not left on it. */
    private const val FINAL_TARGET_SCALE = 0.40f

    /** Maximum horizontal shear, in fractions of the window height, applied during the swoop. */
    private const val MAX_SKEW = 0.55f

    private const val SKEW_STRENGTH = 0.90f

    /** Keep the window stretched until this point, then collapse its height into the icon. */
    private const val SCALE_Y_BREAK_FRACTION = 0.70f

    private const val SCALE_Y_EARLY_POWER = 2.2f

    private const val SCALE_Y_LATE_POWER = 0.65f

    private val minimizeBoundsAnimationDef =
        WindowAnimator.BoundsAnimationParams(
            durationMs = 200,
            endOffsetYDp = 12f,
            endScale = 0.97f,
            interpolator = Interpolators.STANDARD_ACCELERATE,
            // In some cases, nav-back on the last desktop task may cause it to be
            // reparented into a fullscreen TDA before being minimized back into a desk by
            // [DesktopBackNavTransitionObserver]. The minimize animation would then occur
            // when the task is still fullscreen, which means it should use the start
            // bounds for the minimize animation.
            animBounds = AnimationBounds.START,
        )

    /**
     * Creates a minimize animator for given task [Change].
     *
     * @param onAnimFinish finish-callback for the animation, note that this is called on the same
     *   thread as the animation itself.
     * @param animationHandler the Handler that the animation is running on.
     * @param targetBounds when non-null, the window is animated into this rectangle (the bounds of
     *   the corresponding dock icon) instead of the regular fade/scale animation.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        change: Change,
        transaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
        interactionJankMonitor: InteractionJankMonitor,
        animationHandler: Handler,
        startAnimDelay: Duration = Duration.ZERO,
        targetBounds: Rect? = null,
    ): Animator {
        val dockAnimator =
            if (targetBounds != null && !targetBounds.isEmpty) {
                createDockMinimizeAnimator(change, transaction, targetBounds)
            } else {
                null
            }
        val boundsAnimator =
            if (dockAnimator == null) {
                WindowAnimator.createBoundsAnimator(
                    context.resources.displayMetrics,
                    minimizeBoundsAnimationDef,
                    change,
                    transaction,
                )
            } else {
                null
            }
        val alphaAnimator =
            if (dockAnimator == null) {
                ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = MINIMIZE_ANIM_ALPHA_DURATION_MS
                    interpolator = Interpolators.LINEAR
                    addUpdateListener { animation ->
                        transaction
                            .setAlpha(change.leash, animation.animatedValue as Float)
                            .setFrameTimeline(Choreographer.getInstance().vsyncId)
                            .apply()
                    }
                }
            } else {
                null
            }
        val listener =
            object : Animator.AnimatorListener {
                override fun onAnimationStart(animator: Animator) {
                    interactionJankMonitor.begin(
                        change.leash,
                        context,
                        animationHandler,
                        CUJ_DESKTOP_MODE_MINIMIZE_WINDOW,
                    )
                }

                override fun onAnimationCancel(animator: Animator) {
                    interactionJankMonitor.cancel(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                }

                override fun onAnimationRepeat(animator: Animator) = Unit

                override fun onAnimationEnd(animator: Animator) {
                    interactionJankMonitor.end(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                    onAnimFinish(animator)
                }
            }
        return AnimatorSet().apply {
            startDelay = startAnimDelay.toMillis()
            if (dockAnimator != null) {
                play(dockAnimator)
            } else {
                playTogether(checkNotNull(boundsAnimator), checkNotNull(alphaAnimator))
            }
            addListener(listener)
        }
    }

    /**
     * Animates the task leash from its current bounds into [targetBounds], which is the bounds of
     * the dock icon for this task.
     *
     * The platform only exposes affine transforms for a surface, so this is not a true mesh-warp
     * "genie" effect. Instead it reproduces the motion of the macOS minimize animation:
     * - the bottom edge leads towards the icon while the top lags behind;
     * - the width is narrowed much faster than the height, leaving a tall "genie tail";
     * - a strong horizontal shear bends the window towards the icon;
     * - the window center converges on the icon center and shrinks below the icon size;
     * - the window stays opaque until the very end, then fades.
     */
    private fun createDockMinimizeAnimator(
        change: Change,
        transaction: Transaction,
        targetBounds: Rect,
    ): ValueAnimator? {
        val start = change.startAbsBounds
        if (start.isEmpty || targetBounds.isEmpty) {
            return null
        }
        val startWidth = start.width().toFloat()
        val startHeight = start.height().toFloat()
        val startAnchorX = start.exactCenterX()
        val startAnchorY = start.bottom.toFloat()
        val endAnchorX = targetBounds.exactCenterX()
        // Never grow a window that is already smaller than its dock icon. Also shrink below the
        // icon size so the window is visibly absorbed by the icon instead of stopping on top of it.
        val endScaleX =
            (targetBounds.width() / startWidth * FINAL_TARGET_SCALE).coerceIn(0.01f, 1f)
        val endScaleY =
            (targetBounds.height() / startHeight * FINAL_TARGET_SCALE).coerceIn(0.01f, 1f)
        // The anchor is the bottom-center of the window. Put the window center on the icon center
        // at the end of the animation.
        val endAnchorY = targetBounds.exactCenterY() + startHeight * endScaleY / 2f
        val scaleYBase = SCALE_Y_BREAK_FRACTION.pow(SCALE_Y_EARLY_POWER)
        val leash = change.leash
        val durationMs = MinimizeAnimationTuning.scaleDuration(MINIMIZE_TO_DOCK_DURATION_MS)
        Log.d(
            TAG,
            "dock minimize speed=${MinimizeAnimationTuning.speed()} duration=${durationMs}ms",
        )

        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            // All the easing is done manually below so that translation, X/Y scale and alpha can
            // use different curves.
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction

                // The bottom edge leads the motion: it heads for the dock early while the top
                // lags, which is what gives the window its "pulled into the dock" shape.
                val horizontalProgress = 1f - (1f - fraction).pow(1.8f)
                val verticalProgress = 1f - (1f - fraction).pow(1.4f)
                val anchorX = startAnchorX + (endAnchorX - startAnchorX) * horizontalProgress
                val anchorY = startAnchorY + (endAnchorY - startAnchorY) * verticalProgress

                // Narrow the window much faster than it gets shorter. The resulting tall, thin
                // window reads as a genie tail instead of a uniform shrink. Once the bottom has
                // reached the dock, collapse the remaining height quickly so the window is small
                // by the time it fades out.
                val scaleXProgress = fraction.pow(0.8f)
                val scaleYProgress =
                    if (fraction < SCALE_Y_BREAK_FRACTION) {
                        fraction.pow(SCALE_Y_EARLY_POWER)
                    } else {
                        val tail =
                            ((fraction - SCALE_Y_BREAK_FRACTION) /
                                    (1f - SCALE_Y_BREAK_FRACTION))
                                .pow(SCALE_Y_LATE_POWER)
                        scaleYBase + (1f - scaleYBase) * tail
                    }
                val scaleX = 1f + (endScaleX - 1f) * scaleXProgress
                val scaleY = 1f + (endScaleY - 1f) * scaleYProgress

                // Strengthen the horizontal shear while there is still horizontal distance to
                // cover. The bottom edge leads towards the icon and the top edge trails behind.
                val remainingHorizontal = endAnchorX - anchorX
                val skewEnvelope = Math.sin(Math.PI * fraction).toFloat()
                val skew =
                    (remainingHorizontal / startHeight * SKEW_STRENGTH * skewEnvelope)
                        .coerceIn(-MAX_SKEW, MAX_SKEW)

                // Position is the top-left corner, the bottom-center of the window is the anchor.
                val transformedAnchorX = scaleX * startWidth / 2f + skew * startHeight
                val positionX = anchorX - transformedAnchorX
                val positionY = anchorY - startHeight * scaleY

                val alpha =
                    if (fraction <= MINIMIZE_TO_DOCK_ALPHA_START) {
                        1f
                    } else {
                        val fade =
                            (fraction - MINIMIZE_TO_DOCK_ALPHA_START) /
                                (1f - MINIMIZE_TO_DOCK_ALPHA_START)
                        // Keep most of the alpha until the window is very small, then fade
                        // quickly at the end.
                        1f - fade * fade
                    }

                transaction
                    .setPosition(leash, positionX, positionY)
                    // Matrix is [scaleX, skew; 0, scaleY].
                    .setMatrix(leash, scaleX, skew, 0f, scaleY)
                    .setAlpha(leash, alpha)
                    .setFrameTimeline(Choreographer.getInstance().vsyncId)
                    .apply()
            }
        }
    }
}
