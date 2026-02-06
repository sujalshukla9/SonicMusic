package com.sonicmusic.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun SonicMusicNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            com.sonicmusic.app.presentation.home.HomeScreen()
        }
        composable(Screen.Search.route) {
            com.sonicmusic.app.presentation.search.SearchScreen()
        }
        composable(Screen.Library.route) {
            com.sonicmusic.app.presentation.library.LibraryScreen()
        }
        composable(Screen.Settings.route) {
            com.sonicmusic.app.presentation.settings.SettingsScreen()
        }
    }
}
