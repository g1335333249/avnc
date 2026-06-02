/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.multi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.isVisible
import com.gaurav.avnc.session.InputBroadcaster
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.ui.vnc.gl.FrameRenderTarget
import com.gaurav.avnc.ui.vnc.gl.Renderer
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.VncClient

class MultiFrameView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val frameState = FrameState()
    private var client: VncClient? = null
    private var sessionId = 0L
    private var broadcaster: InputBroadcaster? = null

    private val target = object : FrameRenderTarget {
        override val client get() = this@MultiFrameView.client
        override val frameState get() = this@MultiFrameView.frameState
        override val drawRemoteCursor = true
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(Renderer(target))
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun bind(sessionId: Long, client: VncClient?, broadcaster: InputBroadcaster?) {
        this.sessionId = sessionId
        this.client = client
        this.broadcaster = broadcaster
        isVisible = client != null
        requestRender()
    }

    fun setFramebufferSize(width: Int, height: Int) {
        frameState.setFramebufferSize(width.toFloat(), height.toFloat())
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameState.setWindowSize(w.toFloat(), h.toFloat())
        frameState.setViewportSize(w.toFloat(), h.toFloat())
        frameState.setSafeArea(RectF(0f, 0f, w.toFloat(), h.toFloat()))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fb = frameState.toFb(PointF(event.x, event.y)) ?: return true
        val fbWidth = frameState.fbWidth.coerceAtLeast(1f)
        val fbHeight = frameState.fbHeight.coerceAtLeast(1f)
        val xRatio = fb.x / fbWidth
        val yRatio = fb.y / fbHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                broadcaster?.sendNormalizedPointerButtonDown(PointerButton.Left, xRatio, yRatio)
            }
            MotionEvent.ACTION_MOVE -> {
                broadcaster?.sendNormalizedPointerButtonDown(PointerButton.None, xRatio, yRatio)
            }
            MotionEvent.ACTION_UP -> {
                broadcaster?.sendNormalizedPointerButtonUp(PointerButton.Left, xRatio, yRatio)
            }
            MotionEvent.ACTION_CANCEL -> {
                broadcaster?.sendNormalizedPointerButtonRelease(xRatio, yRatio)
            }
        }
        return true
    }
}
