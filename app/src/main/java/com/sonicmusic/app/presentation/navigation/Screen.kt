package com.sonicmusic.app.presentation.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    
    // Player is a modal/overlay, but we might want a full screen route for deep linking or expansion
    data object Player : Screen("player")
}
