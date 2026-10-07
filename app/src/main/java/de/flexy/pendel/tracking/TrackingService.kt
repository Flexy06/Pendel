package de.flexy.pendel.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.flexy.pendel.MainActivity
import de.flexy.pendel.PendelApp
import de.flexy.pendel.R
import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.model.TrackPoint
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.data.db.TrackPointEntity
import de.flexy.pendel.data.rawPoint
import de.flexy.pendel.data.db.TripTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that exists only while a trip is recorded.
 *
 * Energy strategy (see docs/ARCHITECTURE.md §8):
 *  - GNSS only between trip start and end
 *  - 2 s interval while moving, 5 s after 60 s standing still
 *  - batched delivery (15 s) whenever no live screen is visible → CPU sleeps between batches
 *  - DB writes in chunks of 20 points
 *  - auto stop after prolonged standstill
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var barometer: BarometerSource
    private val container get() = (application as PendelApp).container

    private val mutex = Mutex()
    private val buffer = ArrayList<TrackPointEntity>()
    private var tripId: Long? = null
    private var auto = false
    private var activityEnded = false
    private var lastPoint: TrackPoint? = null
    /** Last position with a real displacement (v3: stillness is judged by position, not by speed). */
    private var anchor: TrackPoint? = null
    private var sampling = SamplingMode.MOVING
    private var batched = true
    private var uiJob: Job? = null
    private var finishing = false
    private var knownPlaces: List<de.flexy.pendel.data.db.PlaceEntity> = emptyList()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locs = result.locations
            scope.launch { onLocations(locs) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        barometer = BarometerSource(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MANUAL -> begin(auto = false)
            ACTION_START_AUTO -> begin(auto = true)
            ACTION_STOP -> scope.launch { finish("manual stop") }
            ACTION_ACTIVITY_ENDED -> activityEnded = true
            null -> {
                // restarted by the system after process death: we cannot resume GNSS reliably
                // without user intent → finalize what was recorded and stop.
                scope.launch {
                    container.trips.finalizeOrphanedRecordings(before = System.currentTimeMillis())
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(auto: Boolean) {
        if (tripId != null || finishing) return
        this.auto = auto
        val startTime = System.currentTimeMillis()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(0.0, startTime),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / missing background permission
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission – cannot record")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // the id is assigned synchronously-enough: points arriving before are buffered
        runBlocking {
            val id = container.trips.startTrip(
                if (auto) TripTrigger.AUTO else TripTrigger.MANUAL,
                if (auto) TransportMode.BICYCLE else null,
            )
            tripId = id
            knownPlaces = runCatching { container.db.placeDao().all() }.getOrDefault(emptyList())
            TrackingState.set(LiveTrip(tripId = id, startTime = startTime, auto = auto))
        }
        if (barometer.available) barometer.start()
        batched = !TrackingState.uiVisible.value
        requestUpdates()
        uiJob = scope.launch {
            TrackingState.uiVisible.drop(1).collect { visible ->
                batched = !visible
                requestUpdates()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, sampling.intervalMs)
            .setMinUpdateIntervalMillis(sampling.intervalMs / 2)
            .setMaxUpdateDelayMillis(if (batched) BATCH_DELAY_MS else 0L)
            .setWaitForAccurateLocation(false)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        TrackingState.update { it.copy(sampling = sampling, batched = batched) }
        Log.d(TAG, "location request: ${sampling.label}, batched=$batched")
    }

    private suspend fun onLocations(locs: List<Location>) {
        mutex.withLock { handleLocations(locs) }
    }

    /** Must be called with [mutex] held. */
    private suspend fun handleLocations(locs: List<Location>) {
        val id = tripId ?: return
        if (finishing) return
        var distance = TrackingState.live.value?.distanceM ?: 0.0
        for (l in locs) {
            val p = TrackPoint(
                t = l.time,
                lat = l.latitude,
                lon = l.longitude,
                accuracy = if (l.hasAccuracy()) l.accuracy else 99f,
                speed = if (l.hasSpeed()) l.speed else null,
                bearing = if (l.hasBearing()) l.bearing else null,
                altitude = if (l.hasAltitude()) l.altitude else null,
            )
            val prev = lastPoint
            if (prev != null) {
                if (p.t <= prev.t) continue
                val d = GeoMath.distance(prev.lat, prev.lon, p.lat, p.lon)
                // live distance ignores jitter while standing; exact value comes from post-processing
                if ((p.speed ?: 1f) > 0.7f && d < 200 && p.accuracy < 35f) distance += d
            }
            lastPoint = p
            // indoor GNSS reports phantom speeds (1–14 m/s) – only an accurate fix that is really
            // somewhere else counts as movement
            val a = anchor
            if (a == null || (p.accuracy <= ANCHOR_ACCURACY_M && GeoMath.distance(a.lat, a.lon, p.lat, p.lon) > ANCHOR_RADIUS_M)) anchor = p
            // raw storage keeps GNSS and barometric altitude apart, plus accuracy values
            buffer += rawPoint(
                tripId = id, t = p.t, lat = p.lat, lon = p.lon, accuracyM = p.accuracy,
                speedMs = p.speed, bearingDeg = p.bearing, gpsAltitudeM = p.altitude,
                speedAccuracyMs = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                verticalAccuracyM = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
                baroAltitudeM = barometer.altitudeAt(l.elapsedRealtimeNanos),
            )
        }
        val last = lastPoint ?: return
        val now = last.t
        val live = TrackingState.live.value
        // still = no accurate fix more than ANCHOR_RADIUS_M away from the anchor for a while
        val anchorT = anchor?.t ?: now
        val stationarySince = if (now - anchorT < 15_000) null else anchorT
        TrackingState.update {
            it.copy(
                distanceM = distance, speedMs = (last.speed ?: 0f).toDouble(), points = it.points + locs.size,
                accuracyM = last.accuracy, lastFixTime = last.t, stationarySince = stationarySince,
            )
        }
        if (buffer.size >= FLUSH_EVERY) flush(id)

        // adaptive sampling
        val stillFor = stationarySince?.let { (now - it) / 1000 } ?: 0
        val wanted = if (stillFor >= 60) SamplingMode.STATIONARY else SamplingMode.MOVING
        if (wanted != sampling) {
            sampling = wanted
            scope.launch(Dispatchers.Main) { requestUpdates() }
        }
        updateNotification(distance, live?.startTime ?: now)

        // auto stop – v2: standing still inside a known place (Uni, Zuhause, Mensa …) means we
        // arrived; stop after 45 s instead of waiting minutes and recording indoor GPS noise
        // (only after having ridden a bit – otherwise unlocking the bike at home would end the trip)
        val atKnownPlace = distance > 300 &&
            knownPlaces.any { GeoMath.distance(it.lat, it.lon, last.lat, last.lon) <= it.radiusM }
        val limit = when {
            auto && atKnownPlace -> 45
            auto && activityEnded -> 120
            auto -> 240
            atKnownPlace -> 5 * 60
            else -> 20 * 60
        }
        if (stillFor >= limit) {
            scope.launch { finish("standing still for ${stillFor}s") }
        }
    }

    private suspend fun flush(id: Long) {
        if (buffer.isEmpty()) return
        val chunk = ArrayList(buffer)
        buffer.clear()
        container.trips.appendPoints(chunk)
    }

    private suspend fun finish(reason: String) {
        val id = mutex.withLock {
            if (finishing) return
            finishing = true
            fused.removeLocationUpdates(callback)
            barometer.stop()
            uiJob?.cancel()
            val id = tripId
            if (id != null) flush(id)
            id
        }
        Log.i(TAG, "finish trip $id: $reason")
        if (id != null) container.trips.finishTrip(id)
        TrackingState.set(null)
        tripId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        barometer.stop()
        val id = tripId
        if (id != null && !finishing) {
            // destroyed unexpectedly: persist the buffer synchronously
            runBlocking {
                flush(id)
                container.trips.finishTrip(id)
            }
            TrackingState.set(null)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(distanceM: Double, startTime: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PendelApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (auto) "Fahrt erkannt – wird aufgezeichnet" else "Fahrt wird aufgezeichnet")
            .setContentText("%.2f km".format(distanceM / 1000))
            .setWhen(startTime)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Beenden", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(distanceM: Double, startTime: Long) {
        if (canNotify()) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(distanceM, startTime))
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ANCHOR_RADIUS_M = 75.0
        private const val ANCHOR_ACCURACY_M = 20f
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 42
        private const val FLUSH_EVERY = 20
        private const val BATCH_DELAY_MS = 15_000L
        const val ACTION_START_MANUAL = "de.flexy.pendel.START_MANUAL"
        const val ACTION_START_AUTO = "de.flexy.pendel.START_AUTO"
        const val ACTION_STOP = "de.flexy.pendel.STOP"
        const val ACTION_ACTIVITY_ENDED = "de.flexy.pendel.ACTIVITY_ENDED"

        fun startManual(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(ACTION_START_MANUAL))
        }

        fun startAuto(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(ACTION_START_AUTO))
        }

        fun stop(context: Context) {
            if (TrackingState.live.value == null) return
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun notifyActivityEnded(context: Context) {
            if (TrackingState.live.value?.auto != true) return
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_ACTIVITY_ENDED))
        }
    }
}
