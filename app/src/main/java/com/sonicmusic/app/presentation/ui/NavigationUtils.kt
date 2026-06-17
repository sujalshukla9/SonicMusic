package com.sonicmusic.app.presentation.ui

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController

fun navigateToAlbum(
    navController: NavController,
    context: Context,
    browseId: String?,
    title: String? = null,
    artist: String? = null,
    thumbnailUrl: String? = null
) {
    if (browseId.isNullOrBlank()) {
        Toast.makeText(context, "Album details unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    navController.navigate(
        SubScreens.albumDetail(
            albumId = browseId,
            title = title ?: "",
            artist = artist ?: "",
            thumbnailUrl = thumbnailUrl
        )
    )
}

fun navigateToAlbum(navController: NavController, browseId: String?, albumName: String? = null) {
    if (browseId.isNullOrBlank()) return
    val encodedBrowseId = java.net.URLEncoder.encode(browseId, "UTF-8")
    navController.navigate("album/$encodedBrowseId")
}

