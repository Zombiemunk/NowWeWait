package com.example.nowwewait.data

import com.example.nowwewait.data.local.FavoriteStopDao
import com.example.nowwewait.data.local.FavoriteStopEntity
import com.example.nowwewait.data.local.StopArrivalsCacheDao
import com.example.nowwewait.data.local.StopArrivalsCacheEntity
import com.example.nowwewait.data.local.UserPreferences
import com.example.nowwewait.data.remote.AlertDto
import com.example.nowwewait.data.remote.ArrivalDto
import com.example.nowwewait.data.remote.NowWeWaitApi
import com.example.nowwewait.data.remote.StopDetailDto
import com.example.nowwewait.data.remote.StopDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface DataRepository {
    val favorites: Flow<List<FavoriteStopEntity>>
    suspend fun getFavoritesList(): List<FavoriteStopEntity>
    suspend fun addFavorite(stop: StopDto)
    suspend fun removeFavorite(stopId: String)
    suspend fun updateFavoritesOrder(favorites: List<FavoriteStopEntity>)
    
    suspend fun getNearbyStops(lat: Double, lng: Double, limit: Int = 3): List<StopDto>
    suspend fun searchStops(query: String): List<StopDto>
    suspend fun getStopDetail(stopId: String): StopDetailDto
    suspend fun getStopArrivals(stopId: String, forceRefresh: Boolean = false): Pair<List<ArrivalDto>, Long>
    suspend fun getAlertsForStop(stopId: String): List<AlertDto>
    
    // Preference helpers
    val lastLocation: Flow<Pair<Double, Double>?>
    suspend fun saveLastLocation(lat: Double, lng: Double)
    val widgetPinnedStopId: Flow<String?>
    suspend fun saveWidgetPinnedStopId(stopId: String)
}

@Singleton
class DefaultDataRepository @Inject constructor(
    private val api: NowWeWaitApi,
    private val favoriteStopDao: FavoriteStopDao,
    private val cacheDao: StopArrivalsCacheDao,
    private val preferences: UserPreferences
) : DataRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override val favorites: Flow<List<FavoriteStopEntity>> = favoriteStopDao.getFavorites()

    override suspend fun getFavoritesList(): List<FavoriteStopEntity> = favoriteStopDao.getFavoritesList()

    override suspend fun addFavorite(stop: StopDto) {
        val maxOrder = favoriteStopDao.getMaxOrder() ?: -1
        favoriteStopDao.insertFavorite(
            FavoriteStopEntity(
                id = stop.id,
                name = stop.name,
                municipality = stop.municipality,
                address = stop.address,
                lat = stop.lat,
                lng = stop.lng,
                linesCsv = stop.lines.joinToString(","),
                displayOrder = maxOrder + 1
            )
        )
    }

    override suspend fun removeFavorite(stopId: String) {
        favoriteStopDao.deleteFavoriteById(stopId)
    }

    override suspend fun updateFavoritesOrder(favorites: List<FavoriteStopEntity>) {
        favoriteStopDao.updateFavorites(favorites)
    }

    override suspend fun getNearbyStops(lat: Double, lng: Double, limit: Int): List<StopDto> {
        val response = api.getNearbyStops(lat, lng, limit)
        // Cache arrivals for each nearby stop on successful response
        val now = System.currentTimeMillis()
        for (stop in response) {
            if (stop.arrivals.isNotEmpty()) {
                val arrivalsJson = json.encodeToString(
                    ListSerializer(ArrivalDto.serializer()),
                    stop.arrivals
                )
                cacheDao.insertCache(StopArrivalsCacheEntity(stop.id, arrivalsJson, now))
            }
        }
        return response
    }

    override suspend fun searchStops(query: String): List<StopDto> {
        return api.searchStops(query)
    }

    override suspend fun getStopDetail(stopId: String): StopDetailDto {
        return api.getStopDetail(stopId)
    }

    override suspend fun getStopArrivals(stopId: String, forceRefresh: Boolean): Pair<List<ArrivalDto>, Long> {
        return try {
            val response = api.getStopArrivals(stopId)
            val now = System.currentTimeMillis()
            
            // Save to cache
            val arrivalsJson = json.encodeToString(
                ListSerializer(ArrivalDto.serializer()),
                response.arrivals
            )
            cacheDao.insertCache(StopArrivalsCacheEntity(stopId, arrivalsJson, now))
            
            response.arrivals to 0L // 0L indicates live from network
        } catch (e: Exception) {
            // Read from cache on network failure
            val cached = cacheDao.getCache(stopId)
            if (cached != null) {
                val cachedArrivals = json.decodeFromString(
                    ListSerializer(ArrivalDto.serializer()),
                    cached.arrivalsJson
                )
                cachedArrivals to cached.cacheTime
            } else {
                throw e
            }
        }
    }

    override suspend fun getAlertsForStop(stopId: String): List<AlertDto> {
        return try {
            api.getAlertsForStop(stopId)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override val lastLocation: Flow<Pair<Double, Double>?> = preferences.lastLocation

    override suspend fun saveLastLocation(lat: Double, lng: Double) {
        preferences.saveLastLocation(lat, lng)
    }

    override val widgetPinnedStopId: Flow<String?> = preferences.widgetPinnedStopId

    override suspend fun saveWidgetPinnedStopId(stopId: String) {
        preferences.saveWidgetPinnedStopId(stopId)
    }
}
