package app.sunnyside.news.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sunnyside.news.data.PostKind
import app.sunnyside.news.data.Story
import app.sunnyside.news.ui.components.PostCallbacks
import app.sunnyside.news.ui.detail.StoryDetailScreen
import app.sunnyside.news.ui.home.HomeScreen
import app.sunnyside.news.ui.pets.PetsScreen
import app.sunnyside.news.ui.saved.SavedScreen
import app.sunnyside.news.ui.search.SearchScreen
import app.sunnyside.news.ui.settings.SettingsScreen
import app.sunnyside.news.util.openInBrowser
import app.sunnyside.news.util.shareText

private enum class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector) {
    Home("home", "Home", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    Saved("saved", "Saved", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    Settings("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** Opens posts, links and the share sheet for whichever screen is showing posts. */
@Composable
private fun rememberPostCallbacks(nav: NavHostController, vm: PostsViewModel): PostCallbacks {
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()
    return remember(nav, vm, toolbar) {
        PostCallbacks(
            open = { nav.navigate("post/${it.id}") },
            toggleSave = { vm.toggleSave(it) },
            share = { shareText(context, it.title, shareBody(it)) },
            openOriginal = { story -> story.discussionUrl?.let { openInBrowser(context, it, toolbar) } },
        )
    }
}

private fun shareBody(story: Story): String {
    val link = if (story.kind == PostKind.Article) story.url else story.discussionUrl ?: story.url
    return "${story.title}\n\n$link\n\nShared from Sunnyside ☀️"
}

@Composable
fun SunnysideNavHost(openStoryId: String?, onStoryOpened: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = Tab.entries.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()

    LaunchedEffect(openStoryId) {
        if (openStoryId != null) {
            nav.navigate("post/$openStoryId")
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
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) {
                val vm: HomeViewModel = viewModel(factory = AppViewModels)
                HomeScreen(
                    viewModel = vm,
                    callbacks = rememberPostCallbacks(nav, vm),
                    snackbar = snackbar,
                    onOpenPets = { kind -> nav.navigate("pets/${kind.name}") },
                    onSearch = { nav.navigate("search") },
                )
            }
            composable(Tab.Saved.route) {
                val vm: SavedViewModel = viewModel(factory = AppViewModels)
                SavedScreen(viewModel = vm, callbacks = rememberPostCallbacks(nav, vm))
            }
            composable(Tab.Settings.route) {
                SettingsScreen(viewModel = viewModel(factory = AppViewModels))
            }
            composable("search") {
                val vm: SearchViewModel = viewModel(factory = AppViewModels)
                SearchScreen(viewModel = vm, callbacks = rememberPostCallbacks(nav, vm), onBack = { nav.popBackStack() })
            }
            composable("post/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val vm: DetailViewModel = viewModel(factory = AppViewModels)
                StoryDetailScreen(
                    viewModel = vm,
                    callbacks = rememberPostCallbacks(nav, vm),
                    onBack = { nav.popBackStack() },
                    onReadArticle = { openInBrowser(context, it, toolbar) },
                )
            }
            composable("pets/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                PetsScreen(viewModel = viewModel(factory = AppViewModels), onBack = { nav.popBackStack() })
            }
        }
    }
}
