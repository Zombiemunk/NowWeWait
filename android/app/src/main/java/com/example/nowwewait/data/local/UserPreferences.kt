package com.example.nowwewait.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferences(private val context: Context) {
    companion object {
        val KEY_LAST_LAT = doublePreferencesKey("last_lat")
        val KEY_LAST_LNG = doublePreferencesKey("last_lng")
        val KEY_WIDGET_PINNED_STOP = stringPreferencesKey("widget_pinned_stop")
    }

    val lastLocation: Flow<Pair<Double, Double>?> = context.dataStore.data.map { preferences ->
        val lat = preferences[KEY_LAST_LAT]
        val lng = preferences[KEY_LAST_LNG]
        if (lat != null && lng != null) lat to lng else null
    }

    suspend fun saveLastLocation(lat: Double, lng: Double) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_LAT] = lat
            preferences[KEY_LAST_LNG] = lng
        }
    }

    val widgetPinnedStopId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_WIDGET_PINNED_STOP]
    }

    suspend fun saveWidgetPinnedStopId(stopId: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WIDGET_PINNED_STOP] = stopId
        }
    }
}
