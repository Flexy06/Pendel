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

    @Volatile var altitudeM: Double? = null
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000)
    }

    fun stop() {
        sm?.unregisterListener(this)
        altitudeM = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val p = event.values.firstOrNull() ?: return
        // altitude relative to standard atmosphere – absolute offset irrelevant, deltas are precise
        altitudeM = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p).toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
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
