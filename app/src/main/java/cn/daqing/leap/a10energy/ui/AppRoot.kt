package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object Routes {
    const val Vehicle = "vehicle"
    const val Trips = "trips"
    const val Stats = "stats"
    const val Settings = "settings"
    const val Login = "login"
    const val Trip = "trip/{id}"
    const val Manual = "manual/{id}"
    const val Diagnostics = "diagnostics"
    const val Calibration = "calibration"

    fun trip(id: String) = "trip/$id"
    fun manual(id: String? = null) = "manual/${id ?: "new"}"
}

private val tabs = listOf(
    Routes.Vehicle to ("车况" to Icons.Filled.DirectionsCar),
    Routes.Trips to ("行程" to Icons.Filled.Route),
    Routes.Stats to ("统计" to Icons.Filled.BarChart),
    Routes.Settings to ("设置" to Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in setOf(Routes.Vehicle, Routes.Trips, Routes.Stats, Routes.Settings)
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { (dest, pair) ->
                        NavigationBarItem(
                            selected = route == dest,
                            onClick = {
                                nav.navigate(dest) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(pair.second, contentDescription = pair.first) },
                            label = { Text(pair.first) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.Vehicle, modifier = Modifier.padding(padding)) {
            composable(Routes.Vehicle) { VehicleScreen(nav) }
            composable(Routes.Trips) { TripsScreen(nav) }
            composable(Routes.Stats) { StatsScreen() }
            composable(Routes.Settings) { SettingsScreen(nav) }
            composable(Routes.Login) { LoginScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.Trip) { entry ->
                TripDetailScreen(entry.arguments?.getString("id").orEmpty(), nav)
            }
            composable(Routes.Manual) { entry ->
                val raw = entry.arguments?.getString("id").orEmpty()
                ManualTripScreen(raw.takeIf { it.isNotBlank() && it != "new" }, onDone = { nav.popBackStack() })
            }
            composable(Routes.Diagnostics) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.Calibration) { CalibrationScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
