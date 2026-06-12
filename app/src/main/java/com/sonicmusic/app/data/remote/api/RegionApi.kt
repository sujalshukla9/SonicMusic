package com.sonicmusic.app.data.remote.api

import com.sonicmusic.app.data.remote.model.RegionResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

interface RegionApi {
    @Headers("Cache-Control: no-cache")
    @GET("json/")
    suspend fun getRegion(): RegionResponse

    @Headers("Cache-Control: no-cache")
    @GET
    suspend fun getRegionByUrl(@Url url: String): RegionResponse
}
