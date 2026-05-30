package com.example.nowwewait.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface NowWeWaitApi {
    @GET("nearby")
    suspend fun getNearbyStops(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("n") limit: Int = 3
    ): List<StopDto>

    @GET("stops")
    suspend fun searchStops(
        @Query("q") query: String
    ): List<StopDto>

    @GET("stops/{id}")
    suspend fun getStopDetail(
        @Path("id") id: String
    ): StopDetailDto

    @GET("stops/{id}/arrivals")
    suspend fun getStopArrivals(
        @Path("id") id: String
    ): StopArrivalsDto

    @GET("alerts")
    suspend fun getAlertsForStop(
        @Query("stop") stopId: String
    ): List<AlertDto>
}
