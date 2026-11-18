package com.aiclipcreator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.feature.analysis.AnalysisScreen
import com.aiclipcreator.feature.analysis.QueueScreen
import com.aiclipcreator.feature.clips.ClipDetailScreen
import com.aiclipcreator.feature.clips.ClipTab
import com.aiclipcreator.feature.clips.ClipsScreen
import com.aiclipcreator.feature.clips.GalleryScreen
import com.aiclipcreator.feature.editor.EditorScreen
import com.aiclipcreator.feature.home.HomeScreen
import com.aiclipcreator.feature.importvideo.VideoImportScreen
import com.aiclipcreator.feature.live.LiveScreen
import com.aiclipcreator.feature.models.ModelsScreen
import com.aiclipcreator.feature.settings.SettingsScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    Destination("home", "Home", Icons.Filled.Home),
    Destination("library", "Library", Icons.Filled.VideoLibrary),
    Destination("live", "Live", Icons.Filled.Podcasts),
    Destination("clips", "Clips", Icons.Filled.Movie),
    Destination("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun AiClipNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        containerColor = appColors.paper,
        bottomBar = {
            if (showBottomBar) {
                BottomBar(navController, currentRoute)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    container = container,
                    onImport = { navController.navigate("library") },
                    onQueue = { navController.navigate("queue") },
                    onLive = { navController.navigate("live") },
                    onClips = { navController.navigate("clips") },
                    onModels = { navController.navigate("models") },
                    onSettings = { navController.navigate("settings") },
                    onGallery = { navController.navigate("gallery") }
                )
            }
            composable("library") {
                VideoImportScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onQueue = { navController.navigate("queue") }
                )
            }
            composable("queue") {
                QueueScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenJob = { jobId -> navController.navigate("analysis/$jobId") }
                )
            }
            composable("analysis/{jobId}") { entry ->
                val jobId = entry.arguments?.getString("jobId").orEmpty()
                AnalysisScreen(
                    container = container,
                    jobId = jobId,
                    onBack = { navController.popBackStack() },
                    onOpenClip = { clipId -> navController.navigate("clip/$clipId") }
                )
            }
            composable("clips") {
                ClipsScreen(
                    container = container,
                    onOpenClip = { clipId -> navController.navigate("clip/$clipId") }
                )
            }
            composable("gallery") {
                GalleryScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable("clip/{clipId}") { entry ->
                val clipId = entry.arguments?.getString("clipId").orEmpty()
                ClipDetailScreen(
                    container = container,
                    clipId = clipId,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("editor/$clipId") },
                    onGallery = { navController.navigate("gallery") }
                )
            }
            composable("editor/{clipId}") { entry ->
                val clipId = entry.arguments?.getString("clipId").orEmpty()
                EditorScreen(
                    container = container,
                    clipId = clipId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("models") {
                ModelsScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable("live") {
                LiveScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onModels = { navController.navigate("models") }
                )
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    val colors = appColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        bottomDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .let { base ->
                        base.then(
                            Modifier
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.padding(2.dp)
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        tint = if (selected) colors.primary else colors.inkMuted,
                        modifier = Modifier
                            .size(22.dp)
                            .let { it }
                    )
                }
                Text(
                    destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.primary else colors.inkMuted
                )
            }
        }
    }
}

@Suppress("unused")
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Suppress("unused")
private val unusedTab = ClipTab.ALL
