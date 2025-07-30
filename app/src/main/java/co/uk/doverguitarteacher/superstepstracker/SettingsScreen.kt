package co.uk.doverguitarteacher.superstepstracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    viewModel: SettingsViewModel,
    onNavigateUp: () -> Unit
) {
    val dailyGoal by viewModel.dailyGoal.collectAsState()
    val themeOption by viewModel.themeOption.collectAsState()

    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GoalSlider(
                currentValue = dailyGoal,
                onValueChange = { viewModel.setDailyGoal(it) }
            )
            ThemeSelector(
                currentOption = themeOption,
                onOptionSelected = { viewModel.setThemeOption(it) }
            )
        }
    }
}

@Composable
private fun GoalSlider(currentValue: Int, onValueChange: (Int) -> Unit) {
    var sliderPosition by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Daily Step Goal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Current: ${sliderPosition.roundToInt()} steps", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                valueRange = 1000f..20000f,
                steps = 18,
                onValueChangeFinished = {
                    onValueChange(sliderPosition.roundToInt())
                }
            )
        }
    }
}

@Composable
private fun ThemeSelector(currentOption: ThemeOption, onOptionSelected: (ThemeOption) -> Unit) {
    val options = ThemeOption.values()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            options.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (option == currentOption),
                            onClick = { onOptionSelected(option) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (option == currentOption),
                        onClick = { onOptionSelected(option) }
                    )
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
