package com.xhale.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import javax.inject.Inject
import com.xhale.health.core.ble.BleRepository

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
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                val vm: HomeViewModel = hiltHomeViewModel()
                HomeRoute(viewModel = vm, onNavigateToBreath = { navController.navigate("breath") })
            }
            composable("breath") {
                val vm: BreathViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                BreathRoute(viewModel = vm)
            }
        }
    }
}

@Composable
fun hiltHomeViewModel(): HomeViewModel {
    return androidx.hilt.navigation.compose.hiltViewModel()
}

