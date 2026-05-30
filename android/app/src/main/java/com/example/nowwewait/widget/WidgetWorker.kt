package com.example.nowwewait.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.*
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.remote.StopDto
import com.example.nowwewait.location.LocationTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.TimeUnit

class WidgetWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetWorkerEntryPoint {
        fun repository(): DataRepository
        fun locationTracker(): LocationTracker
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        
        // 1. Resolve Hilt dependencies dynamically via EntryPoint
        val entryPoint = EntryPointAccessors.fromApplication(
            context,
            WidgetWorkerEntryPoint::class.java
        )
        val repository = entryPoint.repository()
        val locationTracker = entryPoint.locationTracker()

        try {
            // 2. Resolve which stop to show (Pinned Stop ID -> First Favorite -> Nearest GPS Stop)
            var targetStopId = repository.widgetPinnedStopId.firstOrNull()
            var stopName = ""
            var arrivalsStr = ""

            val favoritesList = repository.getFavoritesList()

            if (targetStopId.isNullOrBlank()) {
                // Pick first favorite
                val firstFav = favoritesList.firstOrNull()
                if (firstFav != null) {
                    targetStopId = firstFav.id
                    stopName = firstFav.name
                }
            }

            if (targetStopId.isNullOrBlank()) {
                // Auto Mode: Try to find nearest stop using GPS location
                val hasCoarse = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasFine = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasCoarse || hasFine) {
                    val coords = locationTracker.getCurrentLocation()
                    if (coords != null) {
                        val nearbyStops = repository.getNearbyStops(coords.first, coords.second, limit = 1)
                        val closest = nearbyStops.firstOrNull()
                        if (closest != null) {
                            targetStopId = closest.id
                            stopName = closest.name
                        }
                    }
                }
            }

            // 3. Update Glance widget
            if (targetStopId.isNullOrBlank()) {
                updateAllWidgetsError(context, "Añada un favorito o active ubicación")
            } else {
                // Fetch stop details if we don't have stop name yet
                if (stopName.isBlank()) {
                    try {
                        val detail = repository.getStopDetail(targetStopId)
                        stopName = detail.name
                    } catch (e: Exception) {
                        stopName = "Parada: $targetStopId"
                    }
                }

                // Query arrivals (falls back to offline Room cache automatically)
                try {
                    val (arrivals, _) = repository.getStopArrivals(targetStopId)
                    arrivalsStr = arrivals.take(3).joinToString(";") { arr ->
                        val minStr = when {
                            arr.minutes <= 0 -> "Llegando"
                            arr.minutes == 1 -> "1 min"
                            else -> "${arr.minutes} min"
                        }
                        "${arr.line}|${arr.destination}|$minStr"
                    }
                    updateAllWidgetsSuccess(context, targetStopId, stopName, arrivalsStr)
                } catch (e: Exception) {
                    updateAllWidgetsError(context, "Error de red. Intente más tarde.")
                }
            }

            // 4. Schedule next background refresh in 5 minutes
            enqueueNextLoop(context)

            return Result.success()
        } catch (e: Exception) {
            // Schedule retry loop
            enqueueNextLoop(context)
            return Result.failure()
        }
    }

    private suspend fun updateAllWidgetsSuccess(
        context: Context,
        stopId: String,
        stopName: String,
        arrivalsStr: String
    ) {
        val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(NowWeWaitWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[NowWeWaitWidget.KEY_STOP_ID] = stopId
                    this[NowWeWaitWidget.KEY_STOP_NAME] = stopName
                    this[NowWeWaitWidget.KEY_ARRIVALS_STR] = arrivalsStr
                    this[NowWeWaitWidget.KEY_LAST_UPDATE] = System.currentTimeMillis()
                    this.remove(NowWeWaitWidget.KEY_ERROR)
                }
            }
            NowWeWaitWidget().update(context, glanceId)
        }
    }

    private suspend fun updateAllWidgetsError(context: Context, errorMsg: String) {
        val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(NowWeWaitWidget::class.java)
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[NowWeWaitWidget.KEY_ERROR] = errorMsg
                    this[NowWeWaitWidget.KEY_LAST_UPDATE] = System.currentTimeMillis()
                }
            }
            NowWeWaitWidget().update(context, glanceId)
        }
    }

    companion object {
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetWorker>()
                .addTag("widget_update_immediate")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widget_immediate",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueNextLoop(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .addTag("widget_update_loop")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widget_loop",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
