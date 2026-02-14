package com.sonicmusic.app.data.remote.model

import com.google.gson.annotations.SerializedName

data class RegionResponse(
    @SerializedName(value = "countryCode", alternate = ["country_code"])
    val countryCode: String?,

    @SerializedName(value = "region_code", alternate = ["region", "regionName"])
    val region: String?,

    @SerializedName(value = "country", alternate = ["country_name"])
    val country: String?,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("success")
    val success: Boolean? = null
) {
    fun isSuccessful(): Boolean {
        return when {
            success != null -> success
            !status.isNullOrBlank() -> status.equals("success", ignoreCase = true)
            else -> !countryCode.isNullOrBlank()
        }
    }
}
