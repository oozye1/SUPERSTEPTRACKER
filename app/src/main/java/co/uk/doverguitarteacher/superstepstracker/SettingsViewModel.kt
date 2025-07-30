package co.uk.doverguitarteacher.superstepstracker

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val ds = app.dataStore

    val dailyGoal = ds.data.map { preferences ->
        preferences[PrefKeys.DAILY_GOAL] ?: 10000
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10000)

    val themeOption = ds.data.map { preferences ->
        try {
            ThemeOption.valueOf(preferences[PrefKeys.THEME] ?: ThemeOption.System.name)
        } catch (e: IllegalArgumentException) {
            ThemeOption.System
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeOption.System)

    fun setDailyGoal(goal: Int) {
        viewModelScope.launch {
            ds.edit { settings ->
                settings[PrefKeys.DAILY_GOAL] = goal
            }
        }
    }

    fun setThemeOption(option: ThemeOption) {
        viewModelScope.launch {
            ds.edit { settings ->
                settings[PrefKeys.THEME] = option.name
            }
        }
    }
}
