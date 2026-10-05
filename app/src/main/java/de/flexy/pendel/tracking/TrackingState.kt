package de.flexy.pendel.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SamplingMode(val intervalMs: Long, val label: String) {
    MOVING(2_000, "2 s"),
    STATIONARY(5_000, "5 s"),
}

data class LiveTrip(
    val tripId: Long,
    val startTime: Long,
    val auto: Boolean,
    val distanceM: Double = 0.0,
    val speedMs: Double = 0.0,
    val points: Int = 0,
    val accuracyM: Float? = null,
    val lastFixTime: Long? = null,
    val sampling: SamplingMode = SamplingMode.MOVING,
    val batched: Boolean = true,
    val stationarySince: Long? = null,
)

/** Process-wide live recording state, observed by the UI. */
object TrackingState {
    private val _live = MutableStateFlow<LiveTrip?>(null)
    val live: StateFlow<LiveTrip?> = _live.asStateFlow()

    /** True while a screen showing live data is visible → deliver fixes without batching. */
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    internal fun set(value: LiveTrip?) { _live.value = value }
    internal fun update(f: (LiveTrip) -> LiveTrip) { _live.value = _live.value?.let(f) }
    fun setUiVisible(v: Boolean) { _uiVisible.value = v }
}
