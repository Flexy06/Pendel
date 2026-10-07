package de.flexy.pendel.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import de.flexy.pendel.PendelApp
import de.flexy.pendel.data.db.ActivityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Barometric altitude. Pressure is far more precise for *relative* height changes than GNSS
 * altitude. Uses sensor batching (10 s report latency) so the CPU is not woken for every sample.
 */
class BarometerSource(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /**
     * v2: ring buffer of (sensor timestamp, pressure). v1 attached the *latest* value to every fix
     * of a location batch (stale, identical values within a batch, jumps between batches). Now each
     * fix gets the median pressure measured around its own timestamp.
     */
    private val times = LongArray(BUFFER)
    private val pressures = FloatArray(BUFFER)
    private var count = 0
    private var head = 0

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000)
    }

    fun stop() {
        sm?.unregisterListener(this)
        synchronized(this) { count = 0; head = 0 }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val p = event.values.firstOrNull() ?: return
        if (p < 300f || p > 1100f) return // physically impossible at ground level → glitch
        synchronized(this) {
            times[head] = event.timestamp // elapsedRealtimeNanos clock
            pressures[head] = p
            head = (head + 1) % BUFFER
            if (count < BUFFER) count++
        }
    }

    /**
     * Barometric altitude (standard atmosphere) for a fix taken at [elapsedRealtimeNanos]:
     * median of the pressure samples within ±2 s, or null if there are none.
     */
    fun altitudeAt(elapsedRealtimeNanos: Long): Double? {
        val window = ArrayList<Float>()
        synchronized(this) {
            for (i in 0 until count) {
                if (kotlin.math.abs(times[i] - elapsedRealtimeNanos) <= 2_000_000_000L) window += pressures[i]
            }
        }
        if (window.isEmpty()) return null
        window.sort()
        val p = window[window.size / 2]
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p).toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val BUFFER = 512 // ~100 s at SENSOR_DELAY_NORMAL
    }
}

/**
 * Activity Transition API: the activity classifier runs on the low-power sensor hub, so detecting
 * "started cycling" costs practically nothing. Transition broadcasts are explicitly allowed to start
 * a location foreground service from the background.
 */
object ActivityRecognitionManager {
    private const val TAG = "ActivityRecognition"
    private const val REQUEST_CODE = 4711

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION)
        // must be mutable: Play services adds the result as extras
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (!hasPermission(context)) return
        val transitions = listOf(
            DetectedActivity.ON_BICYCLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.ON_BICYCLE to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
            DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        ).map { (type, transition) ->
            ActivityTransition.Builder().setActivityType(type).setActivityTransition(transition).build()
        }
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
            .addOnSuccessListener { Log.i(TAG, "transitions registered") }
            .addOnFailureListener { Log.w(TAG, "registration failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        if (!hasPermission(context)) return
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val pending = goAsync()
        val app = context.applicationContext as PendelApp
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // raw sensor data: kept for future mode/segmentation algorithms
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtimeNanos()
                app.container.trips.recordActivityEvents(result.transitionEvents.map {
                    ActivityEventEntity(
                        time = nowWall - (nowElapsed - it.elapsedRealTimeNanos) / 1_000_000,
                        activityType = it.activityType,
                        transition = it.transitionType,
                    )
                })
                if (!app.container.settings.current().autoDetect) return@launch
                for (e in result.transitionEvents) {
                    Log.i("ActivityTransition", "type=${e.activityType} transition=${e.transitionType}")
                    val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    when {
                        e.activityType == DetectedActivity.ON_BICYCLE && enter ->
                            if (TrackingState.live.value == null) TrackingService.startAuto(context)
                        e.activityType == DetectedActivity.ON_BICYCLE && !enter ->
                            TrackingService.notifyActivityEnded(context)
                        e.activityType == DetectedActivity.STILL && enter ->
                            TrackingService.notifyActivityEnded(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "de.flexy.pendel.ACTIVITY_TRANSITION"
    }
}

/** Activity transition registrations do not survive a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as PendelApp
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (app.container.settings.current().autoDetect) ActivityRecognitionManager.register(context)
            } finally {
                pending.finish()
            }
        }
    }
}
