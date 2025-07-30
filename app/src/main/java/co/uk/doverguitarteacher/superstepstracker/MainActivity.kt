package co.uk.doverguitarteacher.superstepstracker

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.Keep
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

// ------------------------- DataStore -------------------------
private val Context.dataStore by preferencesDataStore(name = "steps_prefs")
private object PrefKeys {
    val TODAY_DATE = stringPreferencesKey("today_date")          // yyyy-MM-dd
    val TODAY_STEPS = intPreferencesKey("today_steps")           // Int
    // History string; supports both formats:
    //   v1: "yyyy-MM-dd:steps|..."
    //   v2: "yyyy-MM-dd:steps:distanceKm:calories|..."
    val HISTORY = stringPreferencesKey("history")
    // Baseline for hardware counter
    val COUNTER_BASE = floatPreferencesKey("counter_base")
    val COUNTER_BASE_DATE = stringPreferencesKey("counter_base_date") // yyyy-MM-dd
    // Primary metric ("steps" or "distance")
    val PRIMARY_METRIC = stringPreferencesKey("primary_metric")
}

// ------------------------- Models -------------------------
@Keep
data class DayStats(
    val dateKey: String,          // yyyy-MM-dd
    val label: String,            // "Mon"..."Sun" or "Today"
    val steps: Int,
    val distanceKm: Double,
    val caloriesKcal: Double
)

// ------------------------- Utils -------------------------
private fun todayKey(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date())
}
private fun dayLabelFromKey(key: String): String {
    return if (key == todayKey()) {
        "Today"
    } else {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        try {
            val d = sdf.parse(key)
            dayFmt.format(d!!)
        } catch (_: Throwable) {
            key
        }
    }
}
private fun formatDisplayDate(date: Date = Date()): String {
    val sdf = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    return sdf.format(date)
}
private fun distanceFromSteps(steps: Int): Double = steps * 0.762 / 1000.0
private fun caloriesFromSteps(steps: Int): Double = steps * 0.04

// Decode "HISTORY" to a mutable map of DayStats keyed by dateKey.
private fun decodeHistory(raw: String?): MutableMap<String, DayStats> {
    val out = mutableMapOf<String, DayStats>()
    if (raw.isNullOrBlank()) return out
    val parts = raw.split("|").filter { it.isNotBlank() }
    parts.forEach { token ->
        val cols = token.split(":")
        // v1: date:steps
        // v2: date:steps:distance:calories
        if (cols.size >= 2) {
            val date = cols[0].trim()
            val steps = cols[1].trim().toIntOrNull() ?: 0
            val dist = cols.getOrNull(2)?.trim()?.toDoubleOrNull() ?: distanceFromSteps(steps)
            val cal = cols.getOrNull(3)?.trim()?.toDoubleOrNull() ?: caloriesFromSteps(steps)
            if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                out[date] = DayStats(
                    dateKey = date,
                    label = dayLabelFromKey(date),
                    steps = max(0, steps),
                    distanceKm = dist,
                    caloriesKcal = cal
                )
            }
        }
    }
    return out
}

// Encode history map as v2 format (with distance & calories) using '.' decimals consistently.
private fun encodeHistory(map: Map<String, DayStats>): String {
    return map.entries
        .sortedBy { it.key }
        .joinToString("|") {
            val v = it.value
            val dist = String.format(Locale.US, "%.2f", v.distanceKm)
            val cal = String.format(Locale.US, "%.2f", v.caloriesKcal)
            "${it.key}:${v.steps}:$dist:$cal"
        }
}

// Generate the last 6 days + today as date keys (oldest -> today)
private fun last7Keys(): List<String> {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val keys = ArrayDeque<String>(7)
    for (i in 6 downTo 1) {
        val c = cal.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, -i)
        keys.addLast(sdf.format(c.time))
    }
    keys.addLast(sdf.format(cal.time))
    return keys.toList()
}

// ------------------------- ViewModel -------------------------
class StepCounterViewModel(app: Application) : AndroidViewModel(app) {

    // Live UI state
    var steps by mutableStateOf(0)
        private set
    var lastStepTime by mutableStateOf(0L)
        private set
    var currentAcceleration by mutableStateOf(0f)
        private set
    var isWalking by mutableStateOf(false)
        private set
    var walkingIntensity by mutableStateOf("Inactive")
        private set
    var primaryMetric by mutableStateOf("steps") // "steps" or "distance"
        private set

    var weeklyHistory by mutableStateOf<List<DayStats>>(emptyList())
        private set

    val dailyGoal = 10_000

    // Persistence
    private val ds get() = getApplication<Application>().dataStore
    private var cachedTodayKey: String = todayKey()

    // Hardware counter baseline (cumulative since boot)
    private var counterBase: Float? = null
    private var counterBaseDate: String? = null

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            bootstrapFromStorage()
            // keep an eye on midnight rollover
            launch {
                while (true) {
                    delay(30_000)
                    if (todayKey() != cachedTodayKey) {
                        ensureDateRollover()
                    }
                }
            }
        }
    }

    // Toggle primary metric between steps and distance
    fun togglePrimaryMetric() {
        primaryMetric = if (primaryMetric == "steps") "distance" else "steps"
        viewModelScope.launch {
            ds.edit { e ->
                e[PrefKeys.PRIMARY_METRIC] = primaryMetric
            }
        }
    }

    // Get primary value based on current metric
    fun getPrimaryValue(steps: Int): String {
        return if (primaryMetric == "steps") {
            "%,d".format(steps)
        } else {
            "%.2f".format(distanceFromSteps(steps))
        }
    }

    // Get primary unit label
    fun getPrimaryLabel(): String {
        return if (primaryMetric == "steps") "Steps" else "km"
    }

    // Get goal in primary metric
    fun getPrimaryGoal(): String {
        return if (primaryMetric == "steps") {
            "%,d steps".format(dailyGoal)
        } else {
            "%.2f km".format(distanceFromSteps(dailyGoal))
        }
    }

    // ---------- Persistence bootstrap ----------
    private suspend fun bootstrapFromStorage() {
        val prefs = ds.data.first()
        val storedDate = prefs[PrefKeys.TODAY_DATE]
        val storedSteps = prefs[PrefKeys.TODAY_STEPS] ?: 0
        val hist = decodeHistory(prefs[PrefKeys.HISTORY])

        counterBase = prefs[PrefKeys.COUNTER_BASE]
        counterBaseDate = prefs[PrefKeys.COUNTER_BASE_DATE]
        primaryMetric = prefs[PrefKeys.PRIMARY_METRIC] ?: "steps"

        val today = todayKey()

        if (storedDate == null) {
            steps = 0
            cachedTodayKey = today
            weeklyHistory = computeSevenDayList(hist, today, 0)
            persist(today, 0, hist, null, null)
            return
        }

        if (storedDate != today) {
            // Move yesterday into history with distance & calories
            hist[storedDate] = DayStats(
                dateKey = storedDate,
                label = dayLabelFromKey(storedDate),
                steps = max(hist[storedDate]?.steps ?: 0, storedSteps),
                distanceKm = distanceFromSteps(max(hist[storedDate]?.steps ?: 0, storedSteps)),
                caloriesKcal = caloriesFromSteps(max(hist[storedDate]?.steps ?: 0, storedSteps))
            )
            val trimmed = trimToLast6(hist, today)
            steps = 0
            cachedTodayKey = today
            weeklyHistory = computeSevenDayList(trimmed, today, 0)
            // reset baseline for new day; will be set on first hardware event
            counterBase = null
            counterBaseDate = null
            persist(today, 0, trimmed, null, null)
        } else {
            // same day
            if (counterBaseDate != today) {
                counterBase = null
                counterBaseDate = null
            }
            steps = storedSteps
            cachedTodayKey = today
            val trimmed = trimToLast6(hist, today)
            weeklyHistory = computeSevenDayList(trimmed, today, storedSteps)
        }
    }

    private fun computeSevenDayList(
        hist: Map<String, DayStats>,
        todayKey: String,
        todaySteps: Int
    ): List<DayStats> {
        val keys = last7Keys()
        return keys.map { k ->
            if (k == todayKey) {
                DayStats(
                    dateKey = k,
                    label = "Today",
                    steps = max(0, todaySteps),
                    distanceKm = distanceFromSteps(todaySteps),
                    caloriesKcal = caloriesFromSteps(todaySteps)
                )
            } else {
                hist[k] ?: DayStats(
                    dateKey = k,
                    label = dayLabelFromKey(k),
                    steps = 0,
                    distanceKm = 0.0,
                    caloriesKcal = 0.0
                )
            }
        }
    }

    private fun trimToLast6(hist: Map<String, DayStats>, anchor: String): MutableMap<String, DayStats> {
        val keep = last7Keys().filter { it != anchor }.toSet()
        return hist.filterKeys { it in keep }.toMutableMap()
    }

    private suspend fun persist(
        today: String,
        todaySteps: Int,
        hist: Map<String, DayStats>,
        base: Float?,
        baseDate: String?
    ) {
        getApplication<Application>().dataStore.edit { e ->
            e[PrefKeys.TODAY_DATE] = today
            e[PrefKeys.TODAY_STEPS] = todaySteps
            e[PrefKeys.HISTORY] = encodeHistory(hist)
            e[PrefKeys.PRIMARY_METRIC] = primaryMetric
            if (base != null && baseDate != null) {
                e[PrefKeys.COUNTER_BASE] = base
                e[PrefKeys.COUNTER_BASE_DATE] = baseDate
            } else {
                e.remove(PrefKeys.COUNTER_BASE)
                e.remove(PrefKeys.COUNTER_BASE_DATE)
            }
        }
    }

    private suspend fun ensureDateRollover() {
        val current = todayKey()
        if (current != cachedTodayKey) {
            val prefs = ds.data.first()
            val hist = decodeHistory(prefs[PrefKeys.HISTORY])
            // push previous day's final stats
            hist[cachedTodayKey] = DayStats(
                dateKey = cachedTodayKey,
                label = dayLabelFromKey(cachedTodayKey),
                steps = steps,
                distanceKm = distanceFromSteps(steps),
                caloriesKcal = caloriesFromSteps(steps)
            )
            val trimmed = trimToLast6(hist, current)
            cachedTodayKey = current
            steps = 0
            weeklyHistory = computeSevenDayList(trimmed, current, 0)
            counterBase = null
            counterBaseDate = null
            persist(current, 0, trimmed, null, null)
        }
    }

    // ---------- Hardware counter handling ----------
    fun onHardwareCounter(totalSinceBoot: Float) {
        viewModelScope.launch {
            val currentKey = todayKey()
            if (currentKey != cachedTodayKey) {
                ensureDateRollover()
                val newSteps = 1
                counterBase = totalSinceBoot - newSteps.toFloat()
                counterBaseDate = currentKey
                updateSteps(newSteps)
                val prefs = ds.data.first()
                val hist = decodeHistory(prefs[PrefKeys.HISTORY])
                persist(cachedTodayKey, steps, hist, counterBase, counterBaseDate)
            } else {
                if (counterBase == null || counterBaseDate != cachedTodayKey) {
                    counterBase = totalSinceBoot - steps.toFloat()
                    counterBaseDate = cachedTodayKey
                    val prefs = ds.data.first()
                    val hist = decodeHistory(prefs[PrefKeys.HISTORY])
                    persist(cachedTodayKey, steps, hist, counterBase, counterBaseDate)
                }
                val calc = (totalSinceBoot - (counterBase ?: 0f)).toInt()
                if (calc < steps) {
                    counterBase = totalSinceBoot - steps.toFloat()
                    counterBaseDate = cachedTodayKey
                    val prefs = ds.data.first()
                    val hist = decodeHistory(prefs[PrefKeys.HISTORY])
                    persist(cachedTodayKey, steps, hist, counterBase, counterBaseDate)
                } else {
                    updateSteps(calc)
                }
            }
        }
    }

    // ---------- State updates ----------
    fun updateSteps(count: Int) {
        steps = max(0, count)
        checkWalkingStatus()
        scheduleSave()
        updateWeeklyToday()
    }

    fun updateLastStepTime(time: Long) {
        lastStepTime = time
        checkWalkingStatus()
    }

    fun updateMovementData(acceleration: Float, @Suppress("UNUSED_PARAMETER") time: Long) {
        currentAcceleration = acceleration
        walkingIntensity = when {
            acceleration > 15f -> "Running"
            acceleration > 12f -> "Fast Walk"
            acceleration > 10f -> "Walking"
            acceleration > 9f -> "Slow Walk"
            else -> "Inactive"
        }
        checkWalkingStatus()
    }

    private fun updateWeeklyToday() {
        val current = cachedTodayKey
        weeklyHistory = weeklyHistory.map { d ->
            if (d.dateKey == current) {
                val s = steps
                d.copy(
                    steps = s,
                    distanceKm = distanceFromSteps(s),
                    caloriesKcal = caloriesFromSteps(s)
                )
            } else d
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000) // debounce
            val prefs = ds.data.first()
            val hist = decodeHistory(prefs[PrefKeys.HISTORY])
            persist(cachedTodayKey, steps, hist, counterBase, counterBaseDate)
        }
    }

    private fun checkWalkingStatus() {
        val now = System.currentTimeMillis()
        val diff = now - lastStepTime
        isWalking = diff < 3_000 || currentAcceleration > 9.5f
    }

    fun getTimeSinceLastStep(): String {
        if (lastStepTime == 0L) return "No steps detected"
        val diff = System.currentTimeMillis() - lastStepTime
        return when {
            diff < 1_000 -> "Just now"
            diff < 60_000 -> "${diff / 1_000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            else -> "${diff / 3_600_000}h ago"
        }
    }

    fun getActivityStatus(): String {
        val diff = System.currentTimeMillis() - lastStepTime
        return when {
            diff < 1_000 -> "Stepping"
            currentAcceleration > 12f -> "Moving"
            isWalking -> walkingIntensity
            else -> "Inactive"
        }
    }

    // ---------- Stats aggregates ----------
    val weeklyTotal: Int
        get() = weeklyHistory.sumOf { it.steps }

    val weeklyAverage: Int
        get() = if (weeklyHistory.isEmpty()) 0 else weeklyTotal / weeklyHistory.size

    val weeklyDistance: Double
        get() = weeklyHistory.sumOf { it.distanceKm }

    val weeklyCalories: Double
        get() = weeklyHistory.sumOf { it.caloriesKcal }

    val bestDay: DayStats?
        get() = weeklyHistory.maxByOrNull { it.steps }
}

// ------------------------- Activity & sensors + GPS fusion -------------------------

// Fusion state provided to UI via CompositionLocal (no signature changes to your composables)
data class FusedMetrics(
    val gpsAccuracyM: Float = Float.NaN,
    val lastFixAgeMs: Long = Long.MAX_VALUE,
    val gpsSpeedMps: Double = 0.0,
    val stepSpeedMps: Double = 0.0,
    val fusedSpeedMps: Double = 0.0,
    val gpsDistanceKm: Double = 0.0,
    val stepDistanceKm: Double = 0.0,
    val fusedDistanceKm: Double = 0.0
)
private val LocalFusedMetrics = staticCompositionLocalOf { FusedMetrics() }

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // Hardware step-counter liveness tracking (prevents the "stops at 10" issue)
    private var lastHardwareCounterEventTime = 0L
    private var lastHardwareCounterValue: Float? = null
    private var hardwareCounterCandidate = false
    private val isHardwareActive: Boolean
        get() = hardwareCounterCandidate && (System.currentTimeMillis() - lastHardwareCounterEventTime) < 5_000

    private val inManualMode: Boolean
        get() = !isHardwareActive

    // Manual (fallback) detection state
    private var lastStepTime = 0L
    private var stepThreshold = 12.0f
    private var manualStepCount = 0

    private val stepCounterViewModel: StepCounterViewModel by viewModels()

    companion object {
        private const val TAG = "StepCounter"
        private const val PERMISSION_REQUEST_CODE = 1
        private const val PERMISSION_REQUEST_LOCATION = 2
    }

    // ---- GPS / FusedLocation ----
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var lastFixTimeMs = 0L
    private var lastGoodLocation: Location? = null
    private var gpsDistanceMeters = 0.0
    private var fusedDistanceMeters = 0.0

    // Step-derived stride (fixed for this "non-invasive" option)
    private var strideMeters = 0.762

    // Cadence window
    private val stepTimestamps = ArrayDeque<Long>()
    private fun pushStepTs(ts: Long) {
        stepTimestamps.addLast(ts)
        val horizon = ts - 8_000L
        while (stepTimestamps.isNotEmpty() && stepTimestamps.first() < horizon) {
            stepTimestamps.removeFirst()
        }
    }
    private fun cadenceSpm(now: Long): Double {
        if (stepTimestamps.size < 2) return 0.0
        val dt = (stepTimestamps.last() - stepTimestamps.first()).coerceAtLeast(1L).toDouble()
        val steps = (stepTimestamps.size - 1).toDouble()
        return steps * 60_000.0 / dt
    }
    private fun stepSpeedMps(now: Long): Double {
        val spm = cadenceSpm(now)
        if (spm <= 0.0) return 0.0
        return (spm / 60.0) * strideMeters
    }
    private fun gpsWeight(acc: Float, ageMs: Long): Double {
        val base = when {
            acc <= 5f  -> 0.85
            acc <= 10f -> 0.65
            acc <= 20f -> 0.40
            else       -> 0.15
        }
        val agePenalty = when {
            ageMs <= 2_000L  -> 1.0
            ageMs <= 5_000L  -> 0.9
            ageMs <= 10_000L -> 0.8
            else             -> 0.6
        }
        return (base * agePenalty).coerceIn(0.0, 0.95)
    }

    // State for UI
    private val fusedState = mutableStateOf(FusedMetrics())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permissions (activity recognition already requested below; add location too)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                PERMISSION_REQUEST_CODE
            )
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                PERMISSION_REQUEST_LOCATION
            )
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        Log.d(TAG, "Step Counter available: ${stepCounterSensor != null}")
        Log.d(TAG, "Step Detector available: ${stepDetectorSensor != null}")
        Log.d(TAG, "Accelerometer available: ${accelerometerSensor != null}")

        setContent {
            CompositionLocalProvider(LocalFusedMetrics provides fusedState.value) {
                MaterialTheme {
                    HealthyStepsCounterApp(stepCounterViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Registering sensor listeners")
        accelerometerSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        stepCounterSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        stepDetectorSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Reset hardware liveness and align fallback counter with persisted steps
        hardwareCounterCandidate = false
        lastHardwareCounterEventTime = 0L
        lastHardwareCounterValue = null
        manualStepCount = stepCounterViewModel.steps

        startLocationUpdates()
    }

    override fun onPause() {
        Log.d(TAG, "Unregistering sensor listeners")
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val now = System.currentTimeMillis()
            when (it.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSinceBoot = it.values[0]
                    val lastVal = lastHardwareCounterValue
                    hardwareCounterCandidate = true
                    lastHardwareCounterEventTime = now
                    lastHardwareCounterValue = totalSinceBoot
                    if (lastVal == null || totalSinceBoot >= lastVal) {
                        stepCounterViewModel.onHardwareCounter(totalSinceBoot)
                    }
                    // keep fused "step-speed" responsive using cadence window whenever detector not firing
                    // (cadence window updated mainly by detector or fallback below)
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    if (inManualMode) {
                        manualStepCount = max(manualStepCount, stepCounterViewModel.steps) + 1
                        stepCounterViewModel.updateSteps(manualStepCount)
                        stepCounterViewModel.updateLastStepTime(now)
                    } else {
                        stepCounterViewModel.updateLastStepTime(now)
                    }
                    pushStepTs(now)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = it.values[0]; val y = it.values[1]; val z = it.values[2]
                    val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    if (inManualMode) detectStepFromAccelerometer(magnitude, now)
                    stepCounterViewModel.updateMovementData(magnitude, now)
                }
            }
        }
    }

    private fun detectStepFromAccelerometer(magnitude: Float, currentTime: Long) {
        val timeSinceLastStep = currentTime - lastStepTime
        if (magnitude > stepThreshold && timeSinceLastStep > 300) {
            manualStepCount = max(manualStepCount, stepCounterViewModel.steps) + 1
            lastStepTime = currentTime
            stepCounterViewModel.updateSteps(manualStepCount)
            stepCounterViewModel.updateLastStepTime(currentTime)
            pushStepTs(currentTime)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }

    // ---------- GPS ----------
    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (locationCallback == null) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val now = System.currentTimeMillis()
                    val acc = loc.accuracy
                    val ageMs = 0L // callback is fresh

                    val gpsSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

                    // GPS segment distance with simple gates to avoid jumps
                    lastGoodLocation?.let { prev ->
                        val seg = prev.distanceTo(loc).toDouble()
                        if (seg.isFinite() && seg >= 0.0 && acc <= 50f && seg <= 100.0) {
                            gpsDistanceMeters += seg
                        }
                    }
                    lastGoodLocation = loc

                    // Step-derived speed from cadence
                    val stepSpeed = stepSpeedMps(now)

                    // Weighted fusion
                    val w = gpsWeight(acc, ageMs)
                    val fusedSpeed = w * gpsSpeed + (1 - w) * stepSpeed

                    val dtSec = if (lastFixTimeMs == 0L) 1.0
                    else ((now - lastFixTimeMs) / 1000.0).coerceIn(0.2, 2.5)
                    fusedDistanceMeters += fusedSpeed * dtSec
                    lastFixTimeMs = now

                    // Step distance (fixed stride for this mode)
                    val stepDist = stepCounterViewModel.steps * strideMeters

                    fusedState.value = fusedState.value.copy(
                        gpsAccuracyM = acc,
                        lastFixAgeMs = ageMs,
                        gpsSpeedMps = gpsSpeed,
                        stepSpeedMps = stepSpeed,
                        fusedSpeedMps = fusedSpeed,
                        gpsDistanceKm = gpsDistanceMeters / 1000.0,
                        stepDistanceKm = stepDist / 1000.0,
                        fusedDistanceKm = fusedDistanceMeters / 1000.0
                    )
                }
            }
        }

        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1_000L
        )
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(req, locationCallback as LocationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
    }
}

// ------------------------- UI -------------------------
@Composable
fun HealthyStepsCounterApp(viewModel: StepCounterViewModel) {
    var selected by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                selectedIndex = selected,
                onSelected = { selected = it }
            )
        }
    ) { padding ->
        when (selected) {
            0 -> HomeScreen(viewModel, padding, onViewAll = { selected = 1 })
            1 -> StatsScreen(viewModel, padding)
            2 -> PlaceholderScreen("Challenges", padding)
            3 -> PlaceholderScreen("Profile", padding)
        }
    }
}

@Composable
fun HomeScreen(viewModel: StepCounterViewModel, padding: PaddingValues, onViewAll: () -> Unit) {
    val fused = LocalFusedMetrics.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF0F4F8))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Header() }
        item { Spacer(Modifier.height(16.dp)) }
        item { StatusIndicator(viewModel) }
        item { Spacer(Modifier.height(16.dp)) }

        // ---- NEW: Fusion card (GPS + accelerometer/steps) ----
        item { FusionCard(fused) }

        item { Spacer(Modifier.height(16.dp)) }
        item { MainCard(viewModel) }
        item { Spacer(Modifier.height(32.dp)) }
        item { HistorySection(viewModel, onViewAll = onViewAll) }
    }
}

@Composable
private fun FusionCard(fused: FusedMetrics) {
    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sensor Fusion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val acc = fused.gpsAccuracyM
                Text(
                    if (acc.isNaN()) "GPS acc: —" else "GPS acc: %.1f m".format(acc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.height(8.dp))

            StatRow(label = "Speed (GPS)", value = "%.2f km/h".format(fused.gpsSpeedMps * 3.6))
            StatRow(label = "Speed (Steps)", value = "%.2f km/h".format(fused.stepSpeedMps * 3.6))
            StatRow(label = "Speed (Fused)", value = "%.2f km/h".format(fused.fusedSpeedMps * 3.6))
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            StatRow(label = "Distance (GPS)", value = "%.3f km".format(fused.gpsDistanceKm))
            StatRow(label = "Distance (Steps)", value = "%.3f km".format(fused.stepDistanceKm))
            StatRow(label = "Distance (Fused)", value = "%.3f km".format(fused.fusedDistanceKm))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatsScreen(viewModel: StepCounterViewModel, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF0F4F8))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Weekly Stats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Last 7 days including today", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
            Icon(Icons.Default.BarChart, contentDescription = null, tint = Color(0xFF4CAF50))
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                title = "Total",
                value = "%,d steps".format(viewModel.weeklyTotal),
                subtitle = "%.2f km • %.0f kcal".format(
                    viewModel.weeklyDistance,
                    viewModel.weeklyCalories
                ),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Average",
                value = "%,d/day".format(viewModel.weeklyAverage),
                subtitle = "%.2f km • %.0f kcal".format(
                    viewModel.weeklyDistance / 7.0,
                    viewModel.weeklyCalories / 7.0
                ),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))

        viewModel.bestDay?.let { best ->
            StatCard(
                title = "Best Day",
                value = "${best.label} • %,d".format(best.steps),
                subtitle = "%.2f km • %.0f kcal".format(best.distanceKm, best.caloriesKcal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Weekly Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                viewModel.weeklyHistory.forEach { d ->
                    WeeklyProgressBar(
                        day = d.label,
                        steps = d.steps,
                        goal = viewModel.dailyGoal,
                        isToday = d.label == "Today",
                        distanceKm = d.distanceKm,
                        calories = d.caloriesKcal,
                        primaryMetric = viewModel.primaryMetric
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
        }
    }
}

@Composable
fun StatusIndicator(viewModel: StepCounterViewModel) {
    var ticker by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            ticker = System.currentTimeMillis()
        }
    }
    val activityStatus = viewModel.getActivityStatus()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (activityStatus) {
                "Running" -> Color(0xFFE53935)
                "Fast Walk", "Moving" -> Color(0xFFFF9800)
                "Walking", "Stepping" -> Color(0xFF4CAF50)
                "Slow Walk" -> Color(0xFF8BC34A)
                else -> Color(0xFF9E9E9E)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (activityStatus) {
                        "Running" -> Icons.Default.DirectionsRun
                        "Fast Walk", "Moving", "Walking", "Stepping" -> Icons.Default.DirectionsWalk
                        "Slow Walk" -> Icons.Default.FitnessCenter
                        else -> Icons.Default.AccessTime
                    },
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(activityStatus, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Acceleration: ${String.format("%.1f", viewModel.currentAcceleration)} m/s²",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                viewModel.getTimeSinceLastStep(),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun Header() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Healthy Steps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Real-time step tracking", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { /* settings */ }, Modifier.clip(CircleShape).background(Color.White)) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.Gray)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, "User", tint = Color.White)
            }
        }
    }
}

@Composable
fun MainCard(viewModel: StepCounterViewModel) {
    // Calculate progress based on primary metric
    val progress = if (viewModel.primaryMetric == "steps") {
        (viewModel.steps / viewModel.dailyGoal.toFloat()).coerceIn(0f, 1f)
    } else {
        val goalDist = distanceFromSteps(viewModel.dailyGoal)
        val currentDist = distanceFromSteps(viewModel.steps)
        (currentDist / goalDist).toFloat().coerceIn(0f, 1f)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DateAndGoal(
                    goalText = viewModel.getPrimaryGoal(),
                    metric = viewModel.primaryMetric,
                    onToggle = { viewModel.togglePrimaryMetric() }
                )
                Spacer(Modifier.height(24.dp))
                CircularProgress(
                    progress = progress,
                    centerText = viewModel.getPrimaryValue(viewModel.steps),
                    centerLabel = viewModel.getPrimaryLabel(),
                    subtitle = "${(progress * 100).toInt()}% of goal"
                )
                Spacer(Modifier.height(24.dp))
                StatsInline(viewModel.steps, viewModel.primaryMetric)
            }
            FloatingActionButton(
                onClick = { /* sync placeholder */ },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = Color(0xFF4CAF50),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Sync, "Sync", tint = Color.White)
            }
        }
    }
}

@Composable
fun DateAndGoal(goalText: String, metric: String, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Today", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(formatDisplayDate(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            // Toggle button
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0F7FA)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    if (metric == "steps") "Show Distance" else "Show Steps",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text("Daily Goal", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                goalText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
fun CircularProgress(progress: Float, centerText: String, centerLabel: String, subtitle: String) {
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(Color(0xFFE0E0E0), -90f, 360f, useCenter = false, style = Stroke(stroke))
            drawArc(
                Color(0xFF4CAF50),
                -90f,
                360f * progress,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(centerText, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun StatsInline(steps: Int, primaryMetric: String) {
    val distance = distanceFromSteps(steps)
    val calories = caloriesFromSteps(steps)
    val activeMin = steps / 100
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        StatItem(
            "Distance",
            "%.2f km".format(distance),
            isHighlighted = primaryMetric == "distance"
        )
        StatItem(
            "Calories",
            "%.0f kcal".format(calories)
        )
        StatItem(
            "Active Min",
            "$activeMin min"
        )
    }
}

@Composable
fun StatItem(label: String, value: String, isHighlighted: Boolean = false) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
        )
    ) {
        Column(Modifier.padding(8.dp, 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isHighlighted) Color(0xFF4CAF50) else Color.Gray
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (isHighlighted) Color(0xFF2E7D32) else Color.Unspecified
            )
        }
    }
}

@Composable
fun HistorySection(viewModel: StepCounterViewModel, onViewAll: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weekly Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onViewAll) {
                    Text("View All", color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(16.dp))
            viewModel.weeklyHistory.forEach { day ->
                WeeklyProgressBar(
                    day = day.label,
                    steps = day.steps,
                    goal = viewModel.dailyGoal,
                    isToday = day.label == "Today",
                    distanceKm = day.distanceKm,
                    calories = day.caloriesKcal,
                    primaryMetric = viewModel.primaryMetric
                )
            }
        }
    }
}

@Composable
fun WeeklyProgressBar(
    day: String,
    steps: Int,
    goal: Int,
    isToday: Boolean = false,
    distanceKm: Double,
    calories: Double,
    primaryMetric: String
) {
    val prog = if (primaryMetric == "steps") {
        (steps / goal.toFloat()).coerceIn(0f, 1f)
    } else {
        val goalDist = distanceFromSteps(goal)
        (distanceKm / goalDist).toFloat().coerceIn(0f, 1f)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(day, Modifier.width(60.dp), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E0E0))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(prog)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(if (isToday) Color(0xFF4CAF50) else Color(0xFF81C784))
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "%.2f km • %.0f kcal".format(distanceKm, calories),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(90.dp)) {
            Text(
                if (primaryMetric == "steps") "%,d".format(steps) else "%.2f".format(distanceKm),
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun BottomNavigationBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    val items = listOf("Home", "Stats", "Challenges", "Profile")
    val icons = listOf(Icons.Default.Home, Icons.Default.BarChart, Icons.Default.EmojiEvents, Icons.Default.Person)
    NavigationBar(containerColor = Color.White) {
        items.forEachIndexed { i, label ->
            NavigationBarItem(
                icon = { Icon(icons[i], label) },
                label = { Text(label) },
                selected = selectedIndex == i,
                onClick = { onSelected(i) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4CAF50),
                    selectedTextColor = Color(0xFF4CAF50),
                    indicatorColor = Color(0xFFE8F5E9),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray
                )
            )
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF0F4F8)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Build, contentDescription = null, tint = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text("$title coming soon", color = Color.Gray)
    }
}
