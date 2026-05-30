package com.example.nowwewait.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

class NowWeWaitWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val stopName = prefs[KEY_STOP_NAME] ?: "Now We Wait"
        val stopId = prefs[KEY_STOP_ID] ?: ""
        val arrivalsStr = prefs[KEY_ARRIVALS_STR] ?: ""
        val lastUpdate = prefs[KEY_LAST_UPDATE] ?: 0L
        val error = prefs[KEY_ERROR]

        // Parse delimited arrivals "A3155|Bilbao|7 min;A3515|Bermeo|14 min"
        val arrivals = remember(arrivalsStr) {
            if (arrivalsStr.isBlank()) emptyList() else {
                arrivalsStr.split(";").mapNotNull {
                    val parts = it.split("|")
                    if (parts.size == 3) parts else null
                }
            }
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
                horizontalAlignment = Alignment.Start
            ) {
                // Header with Stop name and refresh button
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = stopName,
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = GlanceTheme.colors.onSurface
                            ),
                            maxLines = 1
                        )
                        if (stopId.isNotBlank()) {
                            Text(
                                text = "Código: $stopId",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant
                                )
                            )
                        }
                    }
                    
                    // Refresh Button
                    Button(
                        text = "↻",
                        onClick = actionRunCallback<RefreshAction>(),
                        modifier = GlanceModifier.size(32.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Show error or arrivals
                if (error != null) {
                    Text(
                        text = error,
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.error)
                    )
                } else if (arrivals.isEmpty()) {
                    Text(
                        text = "Sin llegadas programadas",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
                    )
                } else {
                    arrivals.take(3).forEach { arr ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = arr[0] + " ",
                                style = TextStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = GlanceTheme.colors.primary
                                )
                            )
                            Text(
                                text = arr[1],
                                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurface),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1
                            )
                            Text(
                                text = arr[2],
                                style = TextStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        val KEY_STOP_NAME = stringPreferencesKey("widget_stop_name")
        val KEY_STOP_ID = stringPreferencesKey("widget_stop_id")
        val KEY_ARRIVALS_STR = stringPreferencesKey("widget_arrivals")
        val KEY_LAST_UPDATE = longPreferencesKey("widget_last_update")
        val KEY_ERROR = stringPreferencesKey("widget_error")
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WidgetWorker.enqueueImmediate(context)
    }
}
