package com.condorino.weekend

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.condorino.weekend.domain.model.ThemeMode
import com.condorino.weekend.navigation.CondorinoNavigation
import com.condorino.weekend.ui.calendar.CalendarViewModel
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.settings.SettingsViewModel
import com.condorino.weekend.ui.theme.CondorinoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as CondorinoApp).container

        setContent {
            val themeMode by container.preferencesStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            // The update-available notification only reaches the user with this granted. Asked
            // once, up front, rather than at the moment a release happens to appear — Android would
            // otherwise show the system prompt while the app is in the background, where it cannot.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {}
                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                    if (granted != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            CondorinoTheme(themeMode = themeMode) {
                val planner: PlannerViewModel = viewModel(
                    factory = PlannerViewModel.factory(
                        repository = container.tripRepository,
                        preferencesStore = container.preferencesStore,
                        standbyPriceRepository = container.standbyPriceRepository,
                        favoriteRepository = container.favoriteRepository,
                        commercialPriceSource = container.commercialPriceSource,
                        travelRecommendationSource = container.travelRecommendationSource,
                    ),
                )
                val calendar: CalendarViewModel = viewModel(
                    factory = CalendarViewModel.factory(container.tripRepository),
                )
                val settings: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(
                        preferencesStore = container.preferencesStore,
                        standbyPriceRepository = container.standbyPriceRepository,
                        tripRepository = container.tripRepository,
                        sources = container.allSources,
                        commercialPriceSource = container.commercialPriceSource,
                        travelRecommendationSource = container.travelRecommendationSource,
                        airportReferenceCatalog = container.airportReferenceCatalog,
                        updateRepository = container.updateRepository,
                    ),
                )

                CondorinoNavigation(
                    plannerViewModel = planner,
                    calendarViewModel = calendar,
                    settingsViewModel = settings,
                )
            }
        }
    }
}
