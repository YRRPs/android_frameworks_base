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

package com.android.systemui.unfold.yrrp

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.DataSpace
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceSession
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.YrrpFoldAnimationLog
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.wm.shell.displayareahelper.DisplayAreaHelper
import java.util.Optional
import javax.inject.Inject

/**
 * The frozen cover picture with a blur layer above it, shown on the cover's own logical display
 * while both panels are lit during a Duo open. [capture] runs while the cover is still the
 * default display; [show] runs once the cover's logical display exists; [release] drops
 * everything. All calls run on the @UnfoldBg handler thread. Open for tests.
 */
open class DuoCoverFrame
@Inject
constructor(
    private val imageCapture: ImageCapture,
    private val displayAreaHelper: Optional<DisplayAreaHelper>,
    @UnfoldBg private val bgHandler: Handler,
    @YrrpFoldAnimationLog private val logBuffer: LogBuffer,
) {
    private var shot: Bitmap? = null
    private var container: SurfaceControl? = null
    private var picture: SurfaceControl? = null
    private var blur: SurfaceControl? = null
    // capture(), show() and release() bump this; it invalidates an in-flight show.
    private var generation = 0

    private class AttachRequest(
        val displayId: Int,
        val bounds: Rect,
        val onShown: (Boolean) -> Unit,
        val generation: Int,
        val startMs: Long,
    ) {
        var attempts = 0
    }

    open var displayId: Int = Display.INVALID_DISPLAY
        protected set

    open val hasPicture: Boolean
        get() = shot != null

    /**
     * Captures [fromDisplayId]. Replaces and recycles the previous shot and cancels an in-flight
     * [show]. Returns false if capture failed; the open then runs unfrozen.
     */
    open fun capture(fromDisplayId: Int): Boolean {
        ensureOnBg()
        generation++
        removeLayers()
        shot?.recycle()
        shot = null
        val captured =
            runCatching { imageCapture.captureDisplay(fromDisplayId) }
                .onFailure { log("cover frame capture failed exception=${it.javaClass.name}") }
                .getOrNull()
        if (captured == null) log("cover frame capture failed exception=none")
        shot = captured
        return captured != null
    }

    /**
     * Attaches the picture and a blur layer to [targetDisplayId]. [widthPx] x [heightPx] must
     * match the captured display (the same cover panel); the picture is not scaled. [onShown]
     * runs on the bg thread at most once, only for the latest request: a newer [show],
     * [capture] or [release] supersedes this one and it never reports. It reports true once
     * attached, and false when there is no picture, the bitmap is recycled or not a hardware
     * bitmap, or the display has no root display area within [ATTACH_TIMEOUT_MS]. With no
     * picture or no DisplayAreaHelper, false is reported synchronously, before this returns.
     */
    open fun show(targetDisplayId: Int, widthPx: Int, heightPx: Int, onShown: (Boolean) -> Unit) {
        ensureOnBg()
        removeLayers()
        val requested = ++generation
        if (shot == null || !displayAreaHelper.isPresent) {
            onShown(false)
            return
        }
        val request =
            AttachRequest(
                targetDisplayId,
                Rect(0, 0, widthPx, heightPx),
                onShown,
                requested,
                SystemClock.uptimeMillis(),
            )
        val builder =
            SurfaceControl.Builder(SurfaceSession())
                .setContainerLayer()
                .setName("yrrp-duo-cover-frame")
        tryAttach(builder, request)
    }

    /**
     * Applies blur regions to the blur layer. Returns false before [show] completes. The result
     * does not depend on cross-window blur availability; the caller checks that.
     */
    open fun setBlurRegions(regions: List<FloatArray>): Boolean {
        ensureOnBg()
        val layer = blur?.takeIf { it.isValid } ?: return false
        SurfaceControl.Transaction().setBlurRegions(layer, regions.toTypedArray()).apply()
        return true
    }

    /** Removes the layers and recycles the picture. Safe to call repeatedly. */
    open fun release() {
        ensureOnBg()
        generation++
        removeLayers()
        shot?.recycle()
        shot = null
    }

    private fun tryAttach(builder: SurfaceControl.Builder, request: AttachRequest) {
        if (request.generation != generation) return
        request.attempts++
        displayAreaHelper.get().tryAttachToRootDisplayArea(request.displayId, builder) { b, ok ->
            bgHandler.post { onAttachResult(b, ok, request) }
        }
    }

    private fun onAttachResult(
        builder: SurfaceControl.Builder,
        attached: Boolean,
        request: AttachRequest,
    ) {
        if (request.generation != generation) return
        val timedOut = SystemClock.uptimeMillis() - request.startMs >= ATTACH_TIMEOUT_MS
        if (!attached && !timedOut) {
            bgHandler.postDelayed({ tryAttach(builder, request) }, ATTACH_RETRY_MS)
            return
        }
        val shown = attached && buildLayers(builder, request.bounds)
        if (shown) displayId = request.displayId
        val (id, attempts) = request.displayId to request.attempts
        log("cover frame attach display=$id attempts=$attempts ok=$shown")
        request.onShown(shown)
    }

    /** Builds the container and its two children from the attached [containerBuilder]. */
    private fun buildLayers(containerBuilder: SurfaceControl.Builder, bounds: Rect): Boolean {
        val bitmap = shot ?: return false
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.HARDWARE) return false
        val root = containerBuilder.build()
        container = root
        val pictureLayer =
            SurfaceControl.Builder()
                .setParent(root)
                .setBLASTLayer()
                .setOpaque(true)
                .setCallsite("DuoCoverFrame")
                .setName("yrrp-duo-cover-picture")
                .build()
        picture = pictureLayer
        val blurLayer =
            SurfaceControl.Builder()
                .setParent(root)
                .setEffectLayer()
                .setCallsite("DuoCoverFrame")
                .setName("yrrp-duo-cover-blur")
                .build()
        blur = blurLayer
        bitmap.hardwareBuffer.use { buffer ->
            val transaction =
                SurfaceControl.Transaction()
                    .setLayer(root, OVERLAY_LAYER_Z_INDEX)
                    .setLayer(pictureLayer, 0)
                    .setLayer(blurLayer, 1)
                    .setBuffer(pictureLayer, buffer)
                    .setDataSpace(
                        pictureLayer,
                        bitmap.colorSpace?.dataSpace ?: DataSpace.DATASPACE_SRGB,
                    )
                    // The frozen picture is the only content on this display; dimming it as
                    // HDR content would make it differ from the live cover it replaces.
                    .setDimmingEnabled(pictureLayer, false)
                    .setCrop(blurLayer, bounds)
            transaction.show(root).show(pictureLayer).show(blurLayer).apply()
        }
        return true
    }

    private fun removeLayers() {
        val layers = listOfNotNull(blur, picture, container).filter { it.isValid }
        if (layers.isNotEmpty()) {
            val transaction = SurfaceControl.Transaction()
            layers.forEach { transaction.remove(it) }
            transaction.apply()
        }
        blur = null
        picture = null
        container = null
        displayId = Display.INVALID_DISPLAY
    }

    private fun log(message: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })

    private fun ensureOnBg() =
        check(Looper.myLooper() == bgHandler.looper) {
            "DuoCoverFrame must be used on the @UnfoldBg handler"
        }

    companion object {
        private const val TAG = "YrrpFoldAnimation"
        private const val ATTACH_RETRY_MS = 50L
        private const val ATTACH_TIMEOUT_MS = 500L
        // One below the rotation-animation overlay, as FullscreenLightRevealAnimation uses.
        private const val OVERLAY_LAYER_Z_INDEX = Integer.MAX_VALUE - 1
    }
}
