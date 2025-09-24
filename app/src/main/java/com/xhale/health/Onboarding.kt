package com.xhale.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.prefs.UserPrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPrefsRepository
) : ViewModel() {
    fun completeOnboarding() { viewModelScope.launch { prefs.setOnboardingDone(true) } }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome to XHale Health", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("We will guide you through scanning, connecting, and sampling.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = { viewModel.completeOnboarding(); onDone() }) { Text("Get Started") }
    }
}

@HiltViewModel
class DisclaimerViewModel @Inject constructor(
    private val prefs: UserPrefsRepository
) : ViewModel() {
    fun accept() { viewModelScope.launch { prefs.setDisclaimerAccepted(true) } }
}

@Composable
fun DisclaimerScreen(onAccept: () -> Unit, viewModel: DisclaimerViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Medical Disclaimer", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("This app is for informational purposes only and not medical advice.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = { viewModel.accept(); onAccept() }) { Text("I Understand") }
    }
}


