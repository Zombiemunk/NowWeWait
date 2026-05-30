package com.example.nowwewait.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class StopDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val municipality: String,
    val address: String,
    val distance: Int? = null,
    val lines: List<String> = emptyList(),
    val arrivals: List<ArrivalDto> = emptyList()
)

@Serializable
data class ArrivalDto(
    val line: String,
    val route: String,
    val destination: String,
    val meters: Int,
    val minutes: Int
)

@Serializable
data class AlertDto(
    val id: String,
    val summaryEs: String,
    val summaryEu: String,
    val descriptionEs: String,
    val descriptionEu: String,
    val affectedLines: List<String>,
    val startTime: String? = null,
    val endTime: String? = null
)

@Serializable
data class StopDetailDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val municipality: String,
    val address: String,
    val lines: List<LineInfoDto> = emptyList()
)

@Serializable
data class LineInfoDto(
    val line_code: String,
    val line_name: String
)

@Serializable
data class StopArrivalsDto(
    val stopId: String,
    val arrivals: List<ArrivalDto>
)
