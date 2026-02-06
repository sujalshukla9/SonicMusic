package com.sonicmusic.app.presentation.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sonicmusic.app.presentation.navigation.Screen
import com.sonicmusic.app.presentation.navigation.SonicMusicNavHost
// TODO: Replace with proper icons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.presentation.player.PlayerUI

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    var isPlayerExpanded by remember { mutableStateOf(false) }
    
    // Permission Request
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle permission granted/denied if needed
    }
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val items = listOf(
        Screen.Home,
        Screen.Search,
        Screen.Library,
        Screen.Settings
    )

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 0.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { 
                            val icon = when(screen) {
                                Screen.Home -> Icons.Filled.Home
                                Screen.Search -> Icons.Filled.Search
                                Screen.Library -> Icons.AutoMirrored.Filled.List
                                Screen.Settings -> Icons.Filled.Settings
                                else -> Icons.Filled.Home
                            }
                            Icon(icon, contentDescription = null) 
                        },
                        label = { Text(screen.route.replaceFirstChar { it.uppercase() }) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Navigation Host - Edge to Edge
            // We pass the bottom padding (from NavigationBar) to the screens via specific CompositionLocal or just know it exists.
            // But usually screens handle their own content padding.
            SonicMusicNavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize() 
            )
            
            // Player UI Overlay
            PlayerUI(
                isExpanded = isPlayerExpanded,
                onExpand = { isPlayerExpanded = true },
                onCollapse = { isPlayerExpanded = false },
                bottomPadding = innerPadding.calculateBottomPadding()
            )
        }
    }
}

