package com.example.nowwewait

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.nowwewait.ui.main.MainScreen
import com.example.nowwewait.ui.screens.StopDetailScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        modifier = Modifier.fillMaxSize(),
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(
                    onNavigateToDetail = { stopId, stopName ->
                        backStack.add(StopDetail(stopId, stopName))
                    }
                )
            }
            entry<StopDetail> { key ->
                StopDetailScreen(
                    stopId = key.stopId,
                    stopName = key.stopName,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}
