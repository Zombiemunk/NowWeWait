package com.example.nowwewait.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteStopDao {
    @Query("SELECT * FROM favorite_stops ORDER BY displayOrder ASC")
    fun getFavorites(): Flow<List<FavoriteStopEntity>>

    @Query("SELECT * FROM favorite_stops ORDER BY displayOrder ASC")
    suspend fun getFavoritesList(): List<FavoriteStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(stop: FavoriteStopEntity)

    @Query("DELETE FROM favorite_stops WHERE id = :id")
    suspend fun deleteFavoriteById(id: String)

    @Query("SELECT MAX(displayOrder) FROM favorite_stops")
    suspend fun getMaxOrder(): Int?

    @Update
    suspend fun updateFavorites(favorites: List<FavoriteStopEntity>)
}

@Dao
interface StopArrivalsCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: StopArrivalsCacheEntity)

    @Query("SELECT * FROM stop_arrivals_cache WHERE stopId = :stopId")
    suspend fun getCache(stopId: String): StopArrivalsCacheEntity?

    @Query("DELETE FROM stop_arrivals_cache WHERE cacheTime < :beforeTime")
    suspend fun deleteOldCache(beforeTime: Long)
}
