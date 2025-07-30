package co.uk.doverguitarteacher.superstepstracker

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore by preferencesDataStore(name = "steps_prefs")

object PrefKeys {
    val TODAY_DATE = stringPreferencesKey("today_date")
    val TODAY_STEPS = intPreferencesKey("today_steps")
    val HISTORY = stringPreferencesKey("history")
    val COUNTER_BASE = floatPreferencesKey("counter_base")
    val COUNTER_BASE_DATE = stringPreferencesKey("counter_base_date")
    val DAILY_GOAL = intPreferencesKey("daily_goal")
    val THEME = stringPreferencesKey("theme_option")
}
