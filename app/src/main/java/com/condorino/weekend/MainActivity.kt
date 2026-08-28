package com.condorino.weekend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
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

            CondorinoTheme(themeMode = themeMode) {
                val planner: PlannerViewModel = viewModel(
                    factory = PlannerViewModel.factory(
                        repository = container.tripRepository,
                        preferencesStore = container.preferencesStore,
                        standbyPriceRepository = container.standbyPriceRepository,
                        favoriteRepository = container.favoriteRepository,
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
                        airportReferenceCatalog = container.airportReferenceCatalog,
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
