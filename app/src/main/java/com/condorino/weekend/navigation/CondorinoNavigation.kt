package com.condorino.weekend.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.condorino.weekend.R
import com.condorino.weekend.ui.calendar.CalendarScreen
import com.condorino.weekend.ui.calendar.CalendarViewModel
import com.condorino.weekend.ui.compare.CompareScreen
import com.condorino.weekend.ui.favorites.FavoritesScreen
import com.condorino.weekend.ui.home.HomeScreen
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.random.RandomScreen
import com.condorino.weekend.ui.settings.SettingsScreen
import com.condorino.weekend.ui.settings.SettingsViewModel
import com.condorino.weekend.ui.settings.StandbyPricesScreen
import com.condorino.weekend.ui.theme.CondorinoColors
import com.condorino.weekend.ui.tripdetail.TripDetailScreen

object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val COMPARE = "compare"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val RANDOM = "random"
    const val PRICES = "prices"

    // Both detail routes are argument-free: the selected trip and the focused destination live in
    // their ViewModels. Trip ids contain timestamps, and URL-encoding them into a route is a
    // needless source of breakage.
    const val TRIP_DETAIL = "trip"
}

private data class TabItem(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TabItem(Routes.CALENDAR, R.string.nav_calendar, Icons.Filled.CalendarMonth),
    TabItem(Routes.COMPARE, R.string.nav_compare, Icons.Filled.CompareArrows),
    TabItem(Routes.FAVORITES, R.string.nav_favorites, Icons.Filled.Favorite),
    TabItem(Routes.SETTINGS, R.string.nav_more, Icons.Filled.Settings),
)

@Composable
fun CondorinoNavigation(
    plannerViewModel: PlannerViewModel,
    calendarViewModel: CalendarViewModel,
    settingsViewModel: SettingsViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination
    val showBottomBar = tabs.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        containerColor = CondorinoColors.Background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = CondorinoColors.Surface) {
                    tabs.forEach { tab ->
                        val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                            label = { Text(stringResource(tab.label), fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CondorinoColors.Background,
                                selectedTextColor = CondorinoColors.Amber,
                                indicatorColor = CondorinoColors.Amber,
                                unselectedIconColor = CondorinoColors.TextTertiary,
                                unselectedTextColor = CondorinoColors.TextTertiary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val plannerState by plannerViewModel.state.collectAsStateWithLifecycle()

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    state = plannerState,
                    viewModel = plannerViewModel,
                    onOpenTrip = {
                        plannerViewModel.selectTrip(it)
                        navController.navigate(Routes.TRIP_DETAIL)
                    },
                    onOpenSurprise = { navController.navigate(Routes.RANDOM) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.CALENDAR) {
                val calendarState by calendarViewModel.state.collectAsStateWithLifecycle()
                CalendarScreen(
                    state = calendarState,
                    viewModel = calendarViewModel,
                    onSelectWeekend = { friday ->
                        plannerViewModel.selectFriday(friday)
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.COMPARE) {
                CompareScreen(state = plannerState, viewModel = plannerViewModel)
            }

            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    state = plannerState,
                    viewModel = plannerViewModel,
                    onOpenTrip = {
                        plannerViewModel.selectTrip(it)
                        navController.navigate(Routes.TRIP_DETAIL)
                    },
                )
            }

            composable(Routes.SETTINGS) {
                val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = settingsState,
                    viewModel = settingsViewModel,
                    onOpenPrices = {
                        settingsViewModel.focusPrice(null)
                        navController.navigate(Routes.PRICES)
                    },
                )
            }

            composable(Routes.RANDOM) {
                RandomScreen(
                    state = plannerState,
                    viewModel = plannerViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenTrip = {
                        plannerViewModel.selectTrip(it)
                        navController.navigate(Routes.TRIP_DETAIL)
                    },
                )
            }

            composable(Routes.PRICES) {
                val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                StandbyPricesScreen(
                    state = settingsState,
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.TRIP_DETAIL) {
                TripDetailScreen(
                    state = plannerState,
                    viewModel = plannerViewModel,
                    onBack = { navController.popBackStack() },
                    onEditPrice = { iata ->
                        settingsViewModel.focusPrice(iata)
                        navController.navigate(Routes.PRICES)
                    },
                )
            }
        }
    }
}
