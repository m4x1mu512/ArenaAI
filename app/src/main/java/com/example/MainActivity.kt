package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AppSettings
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.ProviderConnectionsRepository
import com.example.ui.navigation.ChatRoute
import com.example.ui.navigation.CoarenaNavGraph
import com.example.ui.navigation.SetupRoute
import com.example.ui.theme.CoarenaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Jetpack Compose entry point with edge-to-edge display and Hilt injection.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var providerConnectionsRepository: ProviderConnectionsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val isInitialized by settingsRepository.isInitialized.collectAsStateWithLifecycle(
                initialValue = false,
            )
            val providerConnections by providerConnectionsRepository.connections.collectAsStateWithLifecycle(
                initialValue = emptyList(),
            )

            CoarenaTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!isInitialized) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val hasProviderConnection = providerConnections.any { it.isEnabled }
                        val startRoute: Any = if (settings.hasApiKey || hasProviderConnection) ChatRoute else SetupRoute
                        CoarenaNavGraph(startDestination = startRoute)
                    }
                }
            }
        }
    }
}
