package com.example.nowwewait

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Main : NavKey

@Serializable
data class StopDetail(
    val stopId: String,
    val stopName: String
) : NavKey
