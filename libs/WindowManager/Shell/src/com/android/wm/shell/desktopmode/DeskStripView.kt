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

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * OpenFDE: draws the virtual desktop strip at the bottom of the primary display.
 *
 * Each desk is rendered as a small thumbnail containing scaled-down rectangles for its windows,
 * its index (e.g. "Desktop 2"), an active highlight and a "main" badge for the first desk. A
 * trailing "+" button creates a new desk.
 *
 * The strip is drawn by a single [View] (instead of child views) because the number of desks and
 * their window counts change frequently while windows are being dragged around; a single view
 * keeps relayout/measure cheap and makes the drag hit-testing trivial.
 */
class DeskStripView(context: Context) : View(context) {

    /** A window of a desk, with bounds in display coordinates. */
    data class DeskWindow(val bounds: RectF, val minimized: Boolean)

    /** Everything needed to draw one desk thumbnail. */
    data class DeskItem(
        val deskId: Int,
        val label: String,
        val isActive: Boolean,
        val isMain: Boolean,
        val windows: List<DeskWindow>,
    )

    /** Invoked when the user taps a desk thumbnail. */
    var onDeskClickListener: ((deskId: Int) -> Unit)? = null

    /** Invoked when the user taps the "+" button. */
    var onAddClickListener: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val padding = dp(8f)
    private val thumbWidth = dp(96f)
    private val thumbHeight = dp(58f)
    private val thumbGap = dp(8f)
    private val addWidth = dp(40f)
    private val labelHeight = dp(18f)
    private val thumbCorner = dp(8f)
    private val stripCorner = dp(14f)

    private val stripBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE61A1E26.toInt()
        style = Paint.Style.FILL
    }
    private val stripBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6F2F4F7.toInt()
        style = Paint.Style.FILL
    }
    private val minimizedWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F2F4F7
        style = Paint.Style.FILL
    }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val hoverBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7EE08A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val activeLabelPaint = Paint(labelPaint).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val mainBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }
    private val mainBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val addBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        style = Paint.Style.FILL
    }
    private val addBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val addHoverBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7EE08A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val addSignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val deskColors = arrayOf(
        0xFF3A7BD5.toInt() to 0xFF3AC8A0.toInt(),
        0xFFE85D75.toInt() to 0xFF8B4AD0.toInt(),
        0xFFF0A35E.toInt() to 0xFFD9544F.toInt(),
        0xFF48C9B0.toInt() to 0xFF2A5D8F.toInt(),
        0xFF8E6CEF.toInt() to 0xFF4A3FA8.toInt(),
        0xFFE58A4E.toInt() to 0xFFB5452E.toInt(),
    )

    private val desks = mutableListOf<DeskItem>()
    private val thumbRects = SparseArray<RectF>()
    private val addRect = RectF()
    private var displayWidth = 1f
    private var displayHeight = 1f
    private var mainBadgeLabel: String = ""

    private var hoveredDeskId = -1
    private var dragHoveredDeskId = -1
    private var pressedDeskId = -1
    private var pressedAdd = false
    private var addHovered = false

    init {
        isClickable = true
        isFocusable = false
    }

    /** Sets the strip content. [displayWidth] / [displayHeight] are the display size in pixels. */
    fun setDesks(
        items: List<DeskItem>,
        displayWidth: Int,
        displayHeight: Int,
        mainBadgeLabel: String,
    ) {
        val countChanged = desks.size != items.size
        desks.clear()
        desks.addAll(items)
        this.displayWidth = displayWidth.coerceAtLeast(1).toFloat()
        this.displayHeight = displayHeight.coerceAtLeast(1).toFloat()
        this.mainBadgeLabel = mainBadgeLabel
        if (countChanged) {
            requestLayout()
        }
        invalidate()
    }

    /** Highlights [deskId] while a window is dragged over it, or clears it when null. */
    fun setDragHoveredDesk(deskId: Int?) {
        val newValue = deskId ?: -1
        if (dragHoveredDeskId != newValue) {
            dragHoveredDeskId = newValue
            invalidate()
        }
    }

    /** Returns the desk id under the given view-local coordinates, or -1. */
    fun findDeskAt(x: Float, y: Float): Int {
        for (i in 0 until thumbRects.size()) {
            if (thumbRects.valueAt(i).contains(x, y)) {
                return thumbRects.keyAt(i)
            }
        }
        return -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = desks.size.coerceAtLeast(1)
        val desiredWidth = padding * 2 + count * (thumbWidth + thumbGap) + addWidth
        val desiredHeight = padding * 2 + thumbHeight + labelHeight
        setMeasuredDimension(
            View.resolveSize(desiredWidth.toInt(), widthMeasureSpec),
            View.resolveSize(desiredHeight.toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(background, stripCorner, stripCorner, stripBackgroundPaint)
        canvas.drawRoundRect(background, stripCorner, stripCorner, stripBorderPaint)

        thumbRects.clear()
        var left = padding
        val top = padding
        desks.forEachIndexed { index, desk ->
            val rect = RectF(left, top, left + thumbWidth, top + thumbHeight)
            thumbRects.put(desk.deskId, rect)
            drawDesk(canvas, desk, index, rect)
            left += thumbWidth + thumbGap
        }
        addRect.set(left, top, left + addWidth, top + thumbHeight)
        drawAddButton(canvas)
    }

    private fun drawDesk(canvas: Canvas, desk: DeskItem, index: Int, rect: RectF) {
        val colors = deskColors[index % deskColors.size]
        thumbPaint.shader =
            LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                colors.first,
                colors.second,
                Shader.TileMode.CLAMP,
            )
        canvas.drawRoundRect(rect, thumbCorner, thumbCorner, thumbPaint)
        thumbPaint.shader = null

        val scaleX = rect.width() / displayWidth
        val scaleY = rect.height() / displayHeight
        desk.windows.forEach { window ->
            val windowRect =
                RectF(
                    rect.left + window.bounds.left * scaleX,
                    rect.top + window.bounds.top * scaleY,
                    rect.left + window.bounds.right * scaleX,
                    rect.top + window.bounds.bottom * scaleY,
                )
            windowRect.inset(dp(1f), dp(1f))
            if (windowRect.width() < dp(2f) || windowRect.height() < dp(2f)) {
                return@forEach
            }
            canvas.drawRoundRect(
                windowRect,
                dp(1.5f),
                dp(1.5f),
                if (window.minimized) minimizedWindowPaint else windowPaint,
            )
        }

        when {
            dragHoveredDeskId == desk.deskId -> {
                canvas.drawRoundRect(rect, thumbCorner, thumbCorner, hoverBorderPaint)
            }
            hoveredDeskId == desk.deskId && !desk.isActive -> {
                canvas.drawRoundRect(rect, thumbCorner, thumbCorner, hoverBorderPaint)
            }
            desk.isActive -> {
                canvas.drawRoundRect(rect, thumbCorner, thumbCorner, activeBorderPaint)
            }
            else -> {
                canvas.drawRoundRect(rect, thumbCorner, thumbCorner, thumbBorderPaint)
            }
        }

        if (desk.isMain && mainBadgeLabel.isNotEmpty()) {
            drawMainBadge(canvas, rect)
        }

        labelPaint.color = if (desk.isActive) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        activeLabelPaint.color = 0xFFFFFFFF.toInt()
        val labelPaintToUse = if (desk.isActive) activeLabelPaint else labelPaint
        val baseline = rect.bottom + labelHeight - dp(3f)
        canvas.drawText(desk.label, rect.centerX(), baseline, labelPaintToUse)
    }

    private fun drawMainBadge(canvas: Canvas, rect: RectF) {
        val textWidth = mainBadgeTextPaint.measureText(mainBadgeLabel)
        val badgeWidth = (textWidth + dp(8f)).coerceAtMost(rect.width() * 0.5f)
        val badgeHeight = dp(14f)
        val badge = RectF(
            rect.right - badgeWidth - dp(3f),
            rect.top + dp(3f),
            rect.right - dp(3f),
            rect.top + dp(3f) + badgeHeight,
        )
        canvas.drawRoundRect(badge, badgeHeight / 2f, badgeHeight / 2f, mainBadgePaint)
        canvas.drawText(
            mainBadgeLabel,
            badge.centerX(),
            badge.centerY() + mainBadgeTextPaint.textSize / 3f,
            mainBadgeTextPaint,
        )
    }

    private fun drawAddButton(canvas: Canvas) {
        canvas.drawRoundRect(addRect, thumbCorner, thumbCorner, addBackgroundPaint)
        canvas.drawRoundRect(
            addRect,
            thumbCorner,
            thumbCorner,
            if (pressedAdd || addHovered) addHoverBorderPaint else addBorderPaint,
        )
        val centerX = addRect.centerX()
        val centerY = addRect.centerY()
        val arm = min(addRect.width(), addRect.height()) * 0.16f
        canvas.drawLine(centerX - arm, centerY, centerX + arm, centerY, addSignPaint)
        canvas.drawLine(centerX, centerY - arm, centerX, centerY + arm, addSignPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedDeskId = findDeskAt(event.x, event.y)
                pressedAdd = addRect.contains(event.x, event.y)
                invalidate()
                return pressedDeskId != -1 || pressedAdd
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedDeskId != -1 && findDeskAt(event.x, event.y) != pressedDeskId) {
                    pressedDeskId = -1
                    invalidate()
                } else if (pressedAdd && !addRect.contains(event.x, event.y)) {
                    pressedAdd = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deskId = findDeskAt(event.x, event.y)
                if (pressedDeskId != -1 && deskId == pressedDeskId) {
                    onDeskClickListener?.invoke(deskId)
                } else if (pressedAdd && addRect.contains(event.x, event.y)) {
                    onAddClickListener?.invoke()
                }
                resetPressedState()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetPressedState()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val newHoveredDeskId = findDeskAt(event.x, event.y)
        val newAddHovered = addRect.contains(event.x, event.y)
        if (newHoveredDeskId != hoveredDeskId || newAddHovered != addHovered) {
            hoveredDeskId = newHoveredDeskId
            addHovered = newAddHovered
            invalidate()
        }
        return true
    }

    private fun resetPressedState() {
        pressedDeskId = -1
        pressedAdd = false
        invalidate()
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
}
