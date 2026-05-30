package com.example.nowwewait.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification

@Database(
    entities = [FavoriteStopEntity::class, StopArrivalsCacheEntity::class],
    version = 1,
    exportSchema = false
)
@SkipQueryVerification
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteStopDao(): FavoriteStopDao
    abstract fun stopArrivalsCacheDao(): StopArrivalsCacheDao
}
