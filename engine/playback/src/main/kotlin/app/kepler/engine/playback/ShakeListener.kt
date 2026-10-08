package app.kepler.engine.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/** Listens to the accelerometer for a shake while the sleep timer is about to fire. */
class ShakeListener(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val detector = ShakeDetector()
    private var running = false

    fun start() {
        if (running) return
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (detector.onSample(v[0], v[1], v[2], SystemClock.elapsedRealtime())) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
