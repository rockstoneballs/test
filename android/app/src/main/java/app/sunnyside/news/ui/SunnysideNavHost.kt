package app.sunnyside.news.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sunnyside.news.ui.detail.StoryDetailScreen
import app.sunnyside.news.ui.explore.ExploreScreen
import app.sunnyside.news.ui.home.HomeScreen
import app.sunnyside.news.ui.pets.PetsScreen
import app.sunnyside.news.ui.saved.SavedScreen
import app.sunnyside.news.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector) {
    Today("today", "Today", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    Explore("explore", "Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    Saved("saved", "Saved", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    Settings("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun SunnysideNavHost(openStoryId: String?, onStoryOpened: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = Tab.entries.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }

    val openStory: (String) -> Unit = { id -> nav.navigate("story/$id") }

    LaunchedEffect(openStoryId) {
        if (openStoryId != null) {
            openStory(openStoryId)
            onStoryOpened()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selected else tab.unselected, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Today.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Today.route) {
                HomeScreen(
                    viewModel = viewModel(factory = AppViewModels),
                    snackbar = snackbar,
                    onOpenStory = openStory,
                    onOpenPets = { kind -> nav.navigate("pets/${kind.name}") },
                )
            }
            composable(Tab.Explore.route) {
                ExploreScreen(viewModel = viewModel(factory = AppViewModels), onOpenStory = openStory)
            }
            composable(Tab.Saved.route) {
                SavedScreen(viewModel = viewModel(factory = AppViewModels), onOpenStory = openStory)
            }
            composable(Tab.Settings.route) {
                SettingsScreen(viewModel = viewModel(factory = AppViewModels))
            }
            composable("story/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                StoryDetailScreen(
                    viewModel = viewModel(factory = AppViewModels),
                    onBack = { nav.popBackStack() },
                    onOpenStory = openStory,
                )
            }
            composable("pets/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                PetsScreen(viewModel = viewModel(factory = AppViewModels), onBack = { nav.popBackStack() })
            }
        }
    }
}
