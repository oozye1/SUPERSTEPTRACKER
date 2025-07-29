package co.uk.doverguitarteacher.superstepstracker

import androidx.activity.viewModels
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var initialStepCount: Int? = null

    // Accelerometer-based step detection
    private var lastAccelValues = FloatArray(3)
    private var lastStepTime = 0L
    private var stepThreshold = 12.0f
    private var manualStepCount = 0

    private val stepCounterViewModel: StepCounterViewModel by viewModels()

    companion object {
        private const val TAG = "StepCounter"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                PERMISSION_REQUEST_CODE
            )
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        Log.d(TAG, "Step Counter available: ${stepCounterSensor != null}")
        Log.d(TAG, "Step Detector available: ${stepDetectorSensor != null}")
        Log.d(TAG, "Accelerometer available: ${accelerometerSensor != null}")

        setContent {
            MaterialTheme {
                HealthyStepsCounterApp(stepCounterViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Registering sensor listeners")

        // Always register accelerometer for continuous monitoring
        accelerometerSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Accelerometer registered")
        }

        // Try to use hardware step sensors as secondary source
        stepCounterSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        stepDetectorSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        Log.d(TAG, "Unregistering sensor listeners")
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTime = System.currentTimeMillis()

            when (it.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = it.values[0].toInt()
                    Log.d(TAG, "Hardware Step Counter: $totalSteps")

                    if (initialStepCount == null) {
                        initialStepCount = totalSteps
                    }

                    val hardwareSteps = totalSteps - (initialStepCount ?: totalSteps)
                    // Use the higher of hardware or manual count
                    val finalCount = maxOf(hardwareSteps, manualStepCount)
                    stepCounterViewModel.updateSteps(finalCount)
                }

                Sensor.TYPE_STEP_DETECTOR -> {
                    Log.d(TAG, "Hardware step detected")
                    stepCounterViewModel.updateLastStepTime(currentTime)
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    // Continuous accelerometer monitoring for step detection
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]

                    // Calculate magnitude of acceleration
                    val magnitude = sqrt((x*x + y*y + z*z).toDouble()).toFloat()

                    // Detect steps using accelerometer
                    detectStepFromAccelerometer(magnitude, currentTime)

                    // Update movement status
                    stepCounterViewModel.updateMovementData(magnitude, currentTime)
                }
            }
        }
    }

    private fun detectStepFromAccelerometer(magnitude: Float, currentTime: Long) {
        // Simple step detection algorithm
        val timeSinceLastStep = currentTime - lastStepTime

        // Detect significant acceleration changes that indicate steps
        if (magnitude > stepThreshold && timeSinceLastStep > 300) { // Min 300ms between steps
            manualStepCount++
            lastStepTime = currentTime

            Log.d(TAG, "Accelerometer step detected! Count: $manualStepCount, Magnitude: $magnitude")

            stepCounterViewModel.updateSteps(manualStepCount)
            stepCounterViewModel.updateLastStepTime(currentTime)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }
}

class StepCounterViewModel : ViewModel() {
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

    val dailyGoal = 10000

    fun updateSteps(count: Int) {
        steps = count
        checkWalkingStatus()
    }

    fun updateLastStepTime(time: Long) {
        lastStepTime = time
        checkWalkingStatus()
    }

    fun updateMovementData(acceleration: Float, time: Long) {
        currentAcceleration = acceleration

        // Determine walking intensity based on acceleration
        walkingIntensity = when {
            acceleration > 15f -> "Running"
            acceleration > 12f -> "Fast Walk"
            acceleration > 10f -> "Walking"
            acceleration > 9f -> "Slow Walk"
            else -> "Inactive"
        }

        checkWalkingStatus()
    }

    private fun checkWalkingStatus() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastStep = currentTime - lastStepTime

        // Consider walking if recent step OR current acceleration indicates movement
        isWalking = timeSinceLastStep < 3000 || currentAcceleration > 9.5f
    }

    fun getTimeSinceLastStep(): String {
        if (lastStepTime == 0L) return "No steps detected"

        val timeDiff = System.currentTimeMillis() - lastStepTime
        return when {
            timeDiff < 1000 -> "Just now"
            timeDiff < 60000 -> "${timeDiff / 1000}s ago"
            timeDiff < 3600000 -> "${timeDiff / 60000}m ago"
            else -> "${timeDiff / 3600000}h ago"
        }
    }

    fun getActivityStatus(): String {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastStep = currentTime - lastStepTime

        return when {
            timeSinceLastStep < 1000 -> "Stepping"
            currentAcceleration > 12f -> "Moving"
            isWalking -> walkingIntensity
            else -> "Inactive"
        }
    }
}

@Composable
fun HealthyStepsCounterApp(viewModel: StepCounterViewModel = viewModel()) {
    Scaffold(bottomBar = { BottomNavigationBar() }) { padding ->
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
            item { MainCard(viewModel) }
            item { Spacer(Modifier.height(32.dp)) }
            item { HistorySection(viewModel.steps, viewModel.dailyGoal) }
        }
    }
}

@Composable
fun StatusIndicator(viewModel: StepCounterViewModel) {
    // Force recomposition every 100ms for real-time updates
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100) // Update 10 times per second
            currentTime = System.currentTimeMillis()
        }
    }

    val activityStatus = viewModel.getActivityStatus()
    val isActive = activityStatus != "Inactive"

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
                    Text(
                        activityStatus,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
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
    val progress = (viewModel.steps / viewModel.dailyGoal.toFloat()).coerceIn(0f,1f)
    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DateAndGoal(viewModel.dailyGoal)
                Spacer(Modifier.height(24.dp))
                CircularProgress(progress, viewModel.steps)
                Spacer(Modifier.height(24.dp))
                Stats(viewModel.steps)
            }
            FloatingActionButton(
                onClick = { /* sync */ },
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
fun DateAndGoal(dailyGoal: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Today", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date()),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Daily Goal", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("%,d steps".format(dailyGoal),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50))
        }
    }
}

@Composable
fun CircularProgress(progress: Float, steps: Int) {
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(Color(0xFFE0E0E0), -90f, 360f, useCenter = false, style = Stroke(stroke))
            drawArc(Color(0xFF4CAF50), -90f, 360f * progress, useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Steps", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("%,d".format(steps), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("${(progress*100).toInt()}% of goal",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun Stats(steps: Int) {
    val distance = steps * 0.762 / 1000    // km
    val calories = steps * 0.04            // kcal
    val activeMin = steps / 100
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        StatItem("Distance", "%.2f km".format(distance))
        StatItem("Calories", "%.0f kcal".format(calories))
        StatItem("Active Min", "$activeMin min")
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Card(shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
        Column(Modifier.padding(8.dp,12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HistorySection(today: Int, goal: Int) {
    Card(shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Weekly Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { /* view all */ }) {
                    Text("View All", color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(16.dp))
            WeeklyProgressBar("Mon", 6502, goal)
            WeeklyProgressBar("Tue", 8012, goal)
            WeeklyProgressBar("Wed", 4532, goal)
            WeeklyProgressBar("Thu", 9021, goal)
            WeeklyProgressBar("Today", today, goal, isToday = true)
        }
    }
}

@Composable
fun WeeklyProgressBar(day: String, steps: Int, goal: Int, isToday: Boolean = false) {
    val prog = (steps / goal.toFloat()).coerceIn(0f,1f)
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(day, Modifier.width(50.dp), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier.weight(1f).height(16.dp).clip(CircleShape).background(Color(0xFFE0E0E0))
        ) {
            Box(
                Modifier.fillMaxWidth(prog).height(16.dp).clip(CircleShape)
                    .background(if (isToday) Color(0xFF4CAF50) else Color(0xFF81C784))
            )
        }
        Text("%,d".format(steps), Modifier.width(60.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BottomNavigationBar() {
    var selected by remember { mutableStateOf(0) }
    val items = listOf("Home","Stats","Challenges","Profile")
    val icons = listOf(Icons.Default.Home, Icons.Default.BarChart, Icons.Default.EmojiEvents, Icons.Default.Person)
    NavigationBar(containerColor = Color.White) {
        items.forEachIndexed { i, label ->
            NavigationBarItem(
                icon = { Icon(icons[i], label) },
                label = { Text(label) },
                selected = selected==i,
                onClick = { selected = i },
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
