package co.uk.doverguitarteacher.superstepstracker

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class Challenge(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val progress: Float,
    val goal: Int,
    val current: Int
)

class ChallengesViewModel(app: Application) : AndroidViewModel(app) {
    private val ds = app.dataStore

    val challenges: StateFlow<List<Challenge>> = ds.data.map { preferences ->
        val history = decodeHistory(preferences[PrefKeys.HISTORY])
        val dailyGoal = preferences[PrefKeys.DAILY_GOAL] ?: 10000

        listOf(
            calculateDailyStreakChallenge(history, dailyGoal),
            Challenge("Weekend Warrior", "Get 25,000 steps over a weekend.", Icons.Default.Star, 0.0f, 25000, 0),
            Challenge("Mountain Climber", "A tough challenge for the most dedicated.", Icons.Default.Terrain, 0.0f, 50000, 0)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun calculateDailyStreakChallenge(history: Map<String, DayStats>, dailyGoal: Int): Challenge {
        val keys = last7Keys().reversed()
        var streak = 0
        for (key in keys) {
            val dayStats = history[key]
            if (dayStats != null && dayStats.steps >= dailyGoal) {
                streak++
            } else {
                break
            }
        }
        return Challenge(
            title = "Daily Streak",
            description = "Hit your daily goal 7 days in a row.",
            icon = Icons.Default.Whatshot,
            progress = (streak / 7.0f).coerceIn(0f, 1f),
            goal = 7,
            current = streak
        )
    }
}
