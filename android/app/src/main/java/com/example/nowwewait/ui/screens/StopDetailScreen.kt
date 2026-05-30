package com.example.nowwewait.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.nowwewait.R
import com.example.nowwewait.data.remote.ArrivalDto
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(
    stopId: String,
    stopName: String,
    onBack: () -> Unit,
    viewModel: StopDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(stopId) {
        viewModel.init(stopId)
    }

    val state by viewModel.uiState.collectAsState()
    val isFav by viewModel.isFavorite(stopId).collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stopName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.stop_code_label, stopId),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val successState = state as? StopDetailUiState.Success
                        if (successState != null) {
                            viewModel.toggleFavorite(
                                stopId = successState.stopId,
                                name = successState.stopName,
                                municipality = successState.municipality,
                                address = successState.address,
                                lat = successState.lat,
                                lng = successState.lng,
                                lines = successState.lines
                            )
                        } else {
                            // Fallback toggle using provided stopName
                            viewModel.toggleFavorite(
                                stopId = stopId,
                                name = stopName,
                                municipality = "",
                                address = "",
                                lat = 0.0,
                                lng = 0.0,
                                lines = emptyList()
                            )
                        }
                    }) {
                        Icon(
                            imageVector = if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFav) {
                                stringResource(R.string.unpin_from_favorites)
                            } else {
                                stringResource(R.string.pin_to_favorites)
                            },
                            tint = if (isFav) Color(0xFFFFD700) else LocalContentColor.current
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val s = state) {
                is StopDetailUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is StopDetailUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = s.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                is StopDetailUiState.Success -> {
                    SuccessContent(
                        state = s,
                        onRefresh = { viewModel.refresh() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    state: StopDetailUiState.Success,
    onRefresh: () -> Unit
) {
    val currentLocale = Locale.getDefault().language

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Offline Cache warning banner
        if (state.cacheTime > 0) {
            val secondsAgo = (System.currentTimeMillis() - state.cacheTime) / 1000
            val readableBadge = stringResource(R.string.offline_badge, secondsAgo.coerceAtLeast(0))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Offline",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = readableBadge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. Service Alerts Banner
        if (state.alerts.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Service Alert",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aviso de servicio / Service Alert",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    state.alerts.forEach { alert ->
                        val summary = if (currentLocale == "eu") {
                            alert.summaryEu.ifBlank { alert.summaryEs }
                        } else if (currentLocale == "en") {
                            alert.summaryEs // default to Spanish/English
                        } else {
                            alert.summaryEs
                        }
                        
                        val description = if (currentLocale == "eu") {
                            alert.descriptionEu.ifBlank { alert.descriptionEs }
                        } else {
                            alert.descriptionEs
                        }

                        Text(
                            text = "• $summary",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }

        // 3. Arrivals List
        if (state.arrivals.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.no_arrivals),
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.arrivals) { arrival ->
                    ArrivalCard(arrival = arrival)
                }
            }
        }

        // 4. Refresh indicator at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onRefresh) {
                Text(text = stringResource(R.string.re_scan))
            }
        }
    }
}

@Composable
fun ArrivalCard(arrival: ArrivalDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Line Badge and Destination
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Line bubble badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = arrival.line,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = arrival.destination,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (arrival.route.isNotBlank()) {
                        Text(
                            text = arrival.route,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (arrival.meters > 0) {
                        Text(
                            text = stringResource(R.string.walking_distance, arrival.meters),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Minutes remaining badge
            val minutes = arrival.minutes
            val badgeColor = when {
                minutes <= 1 -> MaterialTheme.colorScheme.errorContainer
                minutes <= 5 -> Color(0xFFFFF3E0) // soft orange
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val textColor = when {
                minutes <= 1 -> MaterialTheme.colorScheme.onErrorContainer
                minutes <= 5 -> Color(0xFFE65100) // deep orange
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when {
                        minutes <= 0 -> stringResource(R.string.now_arriving)
                        minutes == 1 -> stringResource(R.string.one_minute_remaining)
                        else -> stringResource(R.string.minutes_remaining, minutes)
                    },
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
