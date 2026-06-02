/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.vnc.VncClient

interface FrameRenderTarget {
    val client: VncClient?
    val frameState: FrameState
    val drawRemoteCursor: Boolean
}
