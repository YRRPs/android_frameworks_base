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

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.android.systemui.unfold.dagger.UnfoldBg
import javax.inject.Inject

/**
 * Reports the hinge angle while [start] is in effect. Registers TYPE_HINGE_ANGLE on the
 * @UnfoldBg handler, so all calls and callbacks run on the @UnfoldBg handler thread. Open for
 * tests.
 */
open class CoverHingeAngleSource
@Inject
constructor(
    private val sensorManager: SensorManager,
    @UnfoldBg private val bgHandler: Handler,
) {
    private var listener: SensorEventListener? = null

    open val isListening: Boolean
        get() = listener != null

    /**
     * Starts reporting angles to [onAngle]; a second call replaces the callback. Returns false
     * when the hinge sensor is missing or registration fails, in which case nothing is
     * listening.
     */
    open fun start(onAngle: (Float) -> Unit): Boolean {
        ensureOnBg()
        stop()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return false
        val newListener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // Ignore events queued before stop() or a replacing start().
                    if (listener === this) onAngle(event.values[0])
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        val registered =
            sensorManager.registerListener(
                newListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, bgHandler)
        if (!registered) return false
        listener = newListener
        return true
    }

    open fun stop() {
        ensureOnBg()
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
    }

    private fun ensureOnBg() =
        check(Looper.myLooper() == bgHandler.looper) {
            "CoverHingeAngleSource must be used on the @UnfoldBg handler"
        }
}
