package com.guardianmesh.relay.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.guardianmesh.relay.diag.AppLog
import kotlin.math.sqrt

/**
 * "Shake the phone hard" trigger.
 *
 * A single jolt is not enough — a phone gets jolted in a bag all day. It takes
 * [REQUIRED_JOLTS] separate jolts inside [WINDOW_MS], each above the g-force threshold
 * for the chosen sensitivity, which is the shape of a deliberate shake and not of a
 * bus ride.
 *
 * Runs on the sensor thread via [SensorManager]. The accelerometer is delivered at
 * SENSOR_DELAY_UI (~60 ms) rather than FASTEST: enough to catch a shake, much easier
 * on the battery.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 1 = hardest to trigger, 3 = easiest. Settable while running. */
    @Volatile var sensitivity: Int = 2

    private val joltTimes = ArrayDeque<Long>()
    private var lastJoltAt = 0L
    private var running = false

    val isAvailable: Boolean get() = accelerometer != null

    fun start(): Boolean {
        if (running) return true
        val sm = sensorManager ?: return false
        val sensor = accelerometer ?: run {
            AppLog.w(TAG, "No accelerometer on this device — shake trigger unavailable")
            return false
        }
        running = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (running) AppLog.i(TAG, "Shake trigger armed (sensitivity $sensitivity → ${threshold()}g)")
        return running
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        joltTimes.clear()
        AppLog.i(TAG, "Shake trigger disarmed")
    }

    private fun threshold(): Float = when (sensitivity) {
        1 -> 2.7f   // needs a real wallop
        3 -> 1.8f   // a brisk flick is enough
        else -> 2.2f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < threshold()) return

        val now = System.currentTimeMillis()
        // One jolt is one direction change; ignore the ringing right after it.
        if (now - lastJoltAt < MIN_GAP_MS) return
        lastJoltAt = now

        joltTimes.addLast(now)
        while (joltTimes.isNotEmpty() && now - joltTimes.first() > WINDOW_MS) joltTimes.removeFirst()

        if (joltTimes.size >= REQUIRED_JOLTS) {
            joltTimes.clear()
            AppLog.w(TAG, "Shake detected (${"%.1f".format(g)}g ×$REQUIRED_JOLTS)")
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "SHAKE"
        const val REQUIRED_JOLTS = 3
        const val WINDOW_MS = 1_500L
        const val MIN_GAP_MS = 180L
    }
}
