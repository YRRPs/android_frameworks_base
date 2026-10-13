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
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceSession
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.wm.shell.displayareahelper.DisplayAreaHelper
import java.util.Optional
import javax.inject.Inject

/**
 * The frozen, blurred cover picture shown on the cover's own logical display while both panels
 * are lit during a Duo open. [capture] runs while the cover is still the default display; [show]
 * runs once the cover's logical display exists; [release] drops everything. All calls run on the
 * @UnfoldBg handler thread. Open for tests.
 */
open class DuoCoverFrame
@Inject
constructor(
    private val imageCapture: ImageCapture,
    private val displayAreaHelper: Optional<DisplayAreaHelper>,
    @UnfoldBg private val bgHandler: Handler,
) {
    private var shot: Bitmap? = null
    private var container: SurfaceControl? = null
    private var picture: SurfaceControl? = null
    private var blur: SurfaceControl? = null
    // A release() or a newer show() invalidates an in-flight show.
    private var generation = 0

    open var displayId: Int = Display.INVALID_DISPLAY
        protected set

    open val hasPicture: Boolean
        get() = shot != null

    /** Captures [fromDisplayId]. Returns false if capture failed; the open then runs unfrozen. */
    open fun capture(fromDisplayId: Int): Boolean {
        ensureOnBg()
        shot?.recycle()
        shot = imageCapture.captureDisplay(fromDisplayId)
        return shot != null
    }

    /**
     * Attaches the picture and a blur layer sized [widthPx] x [heightPx] to [targetDisplayId].
     * [onShown] runs on the bg thread with true once attached, or false if there is no picture,
     * no DisplayAreaHelper, or a release() came first.
     */
    open fun show(targetDisplayId: Int, widthPx: Int, heightPx: Int, onShown: (Boolean) -> Unit) {
        ensureOnBg()
        val bitmap = shot
        val helper = displayAreaHelper.orElse(null)
        if (bitmap == null || helper == null) {
            onShown(false)
            return
        }
        removeLayers()
        val requested = ++generation
        val builder =
            SurfaceControl.Builder(SurfaceSession())
                .setContainerLayer()
                .setName("yrrp-duo-cover-frame")
        helper.attachToRootDisplayArea(targetDisplayId, builder) { b ->
            bgHandler.post {
                val shown =
                    requested == generation &&
                        buildLayers(b, bitmap, Rect(0, 0, widthPx, heightPx))
                if (shown) displayId = targetDisplayId
                onShown(shown)
            }
        }
    }

    /** Applies blur regions to the blur layer. Returns false before [show] completes. */
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

    /** Builds the container and its two children from the attached [containerBuilder]. */
    private fun buildLayers(
        containerBuilder: SurfaceControl.Builder,
        bitmap: Bitmap,
        bounds: Rect,
    ): Boolean {
        val buffer: HardwareBuffer = bitmap.hardwareBuffer ?: return false
        val root = containerBuilder.build()
        val pictureLayer =
            SurfaceControl.Builder(SurfaceSession())
                .setBLASTLayer()
                .setName("yrrp-duo-cover-picture")
                .setParent(root)
                .build()
        val blurLayer =
            SurfaceControl.Builder(SurfaceSession())
                .setEffectLayer()
                .setName("yrrp-duo-cover-blur")
                .setParent(root)
                .build()
        SurfaceControl.Transaction()
            .setLayer(root, OVERLAY_LAYER_Z_INDEX)
            .setLayer(pictureLayer, 0)
            .setLayer(blurLayer, 1)
            .setBuffer(pictureLayer, buffer)
            .setCrop(blurLayer, bounds)
            .show(root)
            .show(pictureLayer)
            .show(blurLayer)
            .apply()
        container = root
        picture = pictureLayer
        blur = blurLayer
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

    private fun ensureOnBg() =
        check(Looper.myLooper() == bgHandler.looper) {
            "DuoCoverFrame must be used on the @UnfoldBg handler"
        }

    private companion object {
        // One below the rotation-animation overlay, as FullscreenLightRevealAnimation uses.
        const val OVERLAY_LAYER_Z_INDEX = Integer.MAX_VALUE - 1
    }
}
