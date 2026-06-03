/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import android.graphics.PointF
import com.gaurav.avnc.vnc.PointerButton

/**
 * Broadcasts user input to a selected set of connected VNC sessions.
 *
 * Pointer broadcasts can use raw framebuffer coordinates when all desktops share
 * the same resolution, or normalized coordinates when the UI wants proportional
 * control across desktops with different resolutions.
 */
class InputBroadcaster(private val sessionProvider: () -> Iterable<MultiRemoteSessionManager.SessionSnapshot>) {

    private val targetSessionIds = linkedSetOf<Long>()

    fun setTargets(sessionIds: Collection<Long>) {
        targetSessionIds.clear()
        targetSessionIds.addAll(sessionIds)
    }

    fun clearTargets() {
        targetSessionIds.clear()
    }

    fun targets(): Set<Long> = targetSessionIds.toSet()

    fun sendKey(keySym: Int, xtCode: Int, isDown: Boolean): Int {
        return targetMessengers().count { it.sendKey(keySym, xtCode, isDown) }
    }

    fun sendPointerButtonDown(button: PointerButton, point: PointF): Int {
        return targetMessengers().onEach { it.sendPointerButtonDown(button, point) }.count()
    }

    fun sendPointerButtonUp(button: PointerButton, point: PointF): Int {
        return targetMessengers().onEach { it.sendPointerButtonUp(button, point) }.count()
    }

    fun sendPointerButtonRelease(point: PointF): Int {
        return targetMessengers().onEach { it.sendPointerButtonRelease(point) }.count()
    }

    fun sendNormalizedPointerMove(xRatio: Float, yRatio: Float): Int {
        return targetSessions().onEach {
            it.messenger?.sendPointerMove(it.mapNormalizedPoint(xRatio, yRatio))
        }.count()
    }

    fun sendNormalizedPointerButtonDown(button: PointerButton, xRatio: Float, yRatio: Float): Int {
        return targetSessions().onEach {
            it.messenger?.sendPointerButtonDown(button, it.mapNormalizedPoint(xRatio, yRatio))
        }.count()
    }

    fun sendNormalizedPointerButtonUp(button: PointerButton, xRatio: Float, yRatio: Float): Int {
        return targetSessions().onEach {
            it.messenger?.sendPointerButtonUp(button, it.mapNormalizedPoint(xRatio, yRatio))
        }.count()
    }

    fun sendNormalizedPointerButtonRelease(xRatio: Float, yRatio: Float): Int {
        return targetSessions().onEach {
            it.messenger?.sendPointerButtonRelease(it.mapNormalizedPoint(xRatio, yRatio))
        }.count()
    }

    fun sendClipboardText(text: String): Int {
        return targetMessengers().onEach { it.sendClipboardText(text) }.count()
    }

    fun refreshFrameBuffers(): Int {
        return targetMessengers().onEach { it.refreshFrameBuffer() }.count()
    }

    private fun targetSessions(): List<MultiRemoteSessionManager.SessionSnapshot> {
        return sessionProvider()
                .filter { it.connected }
                .filter { targetSessionIds.isEmpty() || it.id in targetSessionIds }
                .toList()
    }

    private fun targetMessengers(): List<Messenger> {
        return targetSessions().mapNotNull { it.messenger }
    }

    private fun MultiRemoteSessionManager.SessionSnapshot.mapNormalizedPoint(xRatio: Float, yRatio: Float): PointF {
        val width = framebufferWidth.coerceAtLeast(1)
        val height = framebufferHeight.coerceAtLeast(1)
        val x = xRatio.coerceIn(0f, 1f) * (width - 1)
        val y = yRatio.coerceIn(0f, 1f) * (height - 1)
        return PointF(x, y)
    }
}
