package com.example.nowwewait.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_stops")
data class FavoriteStopEntity(
    @PrimaryKey val id: String,
    val name: String,
    val municipality: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val linesCsv: String,
    val displayOrder: Int
)

@Entity(tableName = "stop_arrivals_cache")
data class StopArrivalsCacheEntity(
    @PrimaryKey val stopId: String,
    val arrivalsJson: String, // Serialized List<ArrivalDto>
    val cacheTime: Long
)
