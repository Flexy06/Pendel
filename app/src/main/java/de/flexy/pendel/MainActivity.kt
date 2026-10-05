package de.flexy.pendel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.dashboard.DashboardScreen
import de.flexy.pendel.ui.intersection.IntersectionScreen
import de.flexy.pendel.ui.map.MapScreen
import de.flexy.pendel.ui.routes.RouteDetailScreen
import de.flexy.pendel.ui.routes.RoutesScreen
import de.flexy.pendel.ui.settings.PrivacyScreen
import de.flexy.pendel.ui.settings.SettingsScreen
import de.flexy.pendel.ui.theme.PendelTheme
import de.flexy.pendel.ui.trips.TripDetailScreen
import de.flexy.pendel.ui.trips.TripsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PendelApp).container
        setContent {
            PendelTheme {
                val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(container) } })
                PendelRoot(vm)
            }
        }
    }
}

object Dest {
    const val DASHBOARD = "dashboard"
    const val MAP = "map"
    const val ROUTES = "routes"
    const val TRIPS = "trips"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val TRIP = "trip/{id}"
    const val ROUTE = "route/{id}"
    const val INTERSECTION = "intersection/{id}"
    fun trip(id: Long) = "trip/$id"
    fun route(id: Long) = "route/$id"
    fun intersection(id: Long) = "intersection/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Dest.DASHBOARD, "Übersicht", Icons.Outlined.SpaceDashboard),
    Tab(Dest.MAP, "Karte", Icons.Outlined.Map),
    Tab(Dest.ROUTES, "Routen", Icons.Outlined.Route),
    Tab(Dest.TRIPS, "Fahrten", Icons.AutoMirrored.Outlined.List),
)

@Composable
fun PendelRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == current }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.DASHBOARD,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable(Dest.DASHBOARD) { DashboardScreen(vm, nav) }
            composable(Dest.MAP) { MapScreen(vm, nav) }
            composable(Dest.ROUTES) { RoutesScreen(vm, nav) }
            composable(Dest.TRIPS) { TripsScreen(vm, nav) }
            composable(Dest.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Dest.PRIVACY) { PrivacyScreen(vm, nav) }
            composable(Dest.TRIP, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                TripDetailScreen(vm, nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable(Dest.ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                RouteDetailScreen(vm, nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable(Dest.INTERSECTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                IntersectionScreen(vm, nav, it.arguments?.getLong("id") ?: 0L)
            }
        }
    }
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
