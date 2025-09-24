package com.xhale.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xhale.health.feature.home.HomeRoute
import com.xhale.health.feature.home.HomeViewModel
import com.xhale.health.feature.breath.BreathRoute
import com.xhale.health.feature.breath.BreathViewModel
import com.xhale.health.core.ui.XHTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import com.xhale.health.settings.SettingsScreen
import com.xhale.health.prefs.UserPrefsRepository
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App() {
    XHTheme {
        val navController = rememberNavController()

        val destinations = listOf("home", "settings")
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0x33000000)) {
                    destinations.forEach { route ->
                        val selected = currentRoute == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (route == "home") {
                                    Icon(Icons.Filled.Home, contentDescription = "Home")
                                } else {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            },
                            label = { Text(if (route == "home") "Home" else "Settings") }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "onboarding",
                modifier = Modifier
                    .then(Modifier)
                    .padding(padding)
            ) {
                composable("onboarding") {
                    OnboardingScreen(onDone = { navController.navigate("disclaimer") { popUpTo("onboarding") { inclusive = true } } })
                }
                composable("disclaimer") {
                    DisclaimerScreen(onAccept = { navController.navigate("home") { popUpTo("disclaimer") { inclusive = true } } })
                }
                composable("home") {
                    val vm: HomeViewModel = hiltViewModel()
                    HomeRoute(
                        viewModel = vm,
                        onNavigateToBreath = { navController.navigate("breath") },
                        onSignOut = {
                            // TODO: Implement sign out when Firebase is added
                        }
                    )
                }
                composable("settings") {
                    SettingsScreen()
                }
                composable("breath") {
                    val vm: BreathViewModel = hiltViewModel()
                    BreathRoute(viewModel = vm)
                }
            }
        }
    }
}

