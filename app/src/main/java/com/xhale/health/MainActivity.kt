package com.xhale.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import com.xhale.health.feature.home.HomeRoute
import com.xhale.health.feature.home.HomeViewModel
import com.xhale.health.feature.breath.BreathRoute
import com.xhale.health.feature.breath.BreathViewModel
import com.xhale.health.core.ui.XHTheme
import com.xhale.health.feature.auth.AuthRoute
import com.xhale.health.core.firebase.AuthRepository
import com.xhale.health.prefs.UserPrefsRepository
import com.xhale.health.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
        val vm: StartupViewModel = hiltViewModel()
        val uiState by vm.state.collectAsState()

        // Single host controls full app routing
        NavHost(
            navController = navController,
            startDestination = when (uiState.startDestination) {
                StartDestination.Onboarding -> "onboarding"
                StartDestination.Disclaimer -> "disclaimer"
                StartDestination.Auth -> "auth"
                StartDestination.Home -> "home"
            }
        ) {
            composable("onboarding") {
                OnboardingScreen(onDone = { navController.navigate("disclaimer") { popUpTo("onboarding") { inclusive = true } } })
            }
            composable("disclaimer") {
                DisclaimerScreen(onAccept = { navController.navigate("auth") { popUpTo("disclaimer") { inclusive = true } } })
            }
            composable("auth") {
                AuthRoute(onAuthSuccess = { navController.navigate("home") { popUpTo("auth") { inclusive = true } } })
            }
            composable("home") {
                val homeVm: HomeViewModel = hiltViewModel()
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0x33000000)) {
                            val destinations = listOf("home", "settings")
                            val backStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = backStackEntry?.destination?.route
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
                    Column(modifier = Modifier.padding(padding)) {
                        HomeRoute(
                            viewModel = homeVm,
                            onNavigateToBreath = { navController.navigate("breath") },
                            onSignOut = {
                                vm.onSignOut()
                                if (BuildConfig.FIREBASE_ENABLED) {
                                    navController.navigate("auth") { popUpTo("home") { inclusive = true } }
                                }
                            }
                        )
                    }
                }
            }
            composable("settings") {
                SettingsScreen()
            }
            composable("breath") {
                val bvm: BreathViewModel = hiltViewModel()
                BreathRoute(viewModel = bvm)
            }
        }
    }
}

enum class StartDestination { Onboarding, Disclaimer, Auth, Home }

data class StartupUiState(val startDestination: StartDestination = StartDestination.Onboarding)

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val prefs: UserPrefsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    val state: StateFlow<StartupUiState> = combine(
        prefs.onboardingDone,
        prefs.disclaimerAccepted,
        authRepository.authState
    ) { onboardingDone, disclaimerAccepted, authState ->
        if (!onboardingDone) return@combine StartupUiState(StartDestination.Onboarding)
        if (!disclaimerAccepted) return@combine StartupUiState(StartDestination.Disclaimer)
        if (BuildConfig.FIREBASE_ENABLED) {
            if (authState.user == null) StartupUiState(StartDestination.Auth) else StartupUiState(StartDestination.Home)
        } else {
            StartupUiState(StartDestination.Home)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupUiState())

    fun onSignOut() {
        if (BuildConfig.FIREBASE_ENABLED) {
            // fire and forget
            viewModelScope.launch {
                authRepository.signOut()
            }
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to XHale Health",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = "Your personal breathing companion for better health and wellness.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(Modifier.height(48.dp))
        
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
    }
}

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Important Disclaimer",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.height(24.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Please read and accept the following:",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "• This app is for informational purposes only\n" +
                          "• It is not a substitute for professional medical advice\n" +
                          "• Consult with healthcare professionals for medical concerns\n" +
                          "• Use at your own discretion and risk",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I Accept")
        }
    }
}

