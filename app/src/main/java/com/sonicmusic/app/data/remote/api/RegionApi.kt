package com.sonicmusic.app.data.remote.api

import com.sonicmusic.app.data.remote.model.RegionResponse
import retrofit2.http.GET
import retrofit2.http.Url

interface RegionApi {
    @GET("json/")
    suspend fun getRegion(): RegionResponse

    @GET
    suspend fun getRegionByUrl(@Url url: String): RegionResponse
}
