package com.example.nowwewait.di

import android.content.Context
import androidx.room.Room
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.DefaultDataRepository
import com.example.nowwewait.data.local.AppDatabase
import com.example.nowwewait.data.local.FavoriteStopDao
import com.example.nowwewait.data.local.StopArrivalsCacheDao
import com.example.nowwewait.data.local.UserPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDataRepository(
        defaultDataRepository: DefaultDataRepository
    ): DataRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "nowwewait.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideFavoriteStopDao(database: AppDatabase): FavoriteStopDao {
        return database.favoriteStopDao()
    }

    @Provides
    fun provideStopArrivalsCacheDao(database: AppDatabase): StopArrivalsCacheDao {
        return database.stopArrivalsCacheDao()
    }

    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences {
        return UserPreferences(context)
    }
}
