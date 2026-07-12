package com.tarteelcompanion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quiz.QuizScreen
import com.tarteelcompanion.study.StudyScreen

/**
 * Top-level destinations. Home / Import / Study / Quiz / Archive are bottom-nav tabs;
 * Settings is reached from an icon on Home (plan U1 navigation decision).
 */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Import("import", "Import", Icons.Filled.PhotoLibrary),
    Study("study", "Study", Icons.Filled.School),
    Quiz("quiz", "Quiz", Icons.Filled.Quiz),
    Archive("archive", "Archive", Icons.Filled.Archive),
}

const val SETTINGS_ROUTE = "settings"

@Composable
fun TarteelCompanionApp() {
    TarteelCompanionTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val app = LocalContext.current.applicationContext as TarteelApp
        val quran by produceState<QuranRepository?>(initialValue = null) {
            value = app.quran.await()
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy
                                ?.any { it.route == destination.route } == true,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Destination.Home.route) { PlaceholderScreen("Home") }
                composable(Destination.Import.route) { PlaceholderScreen("Import") }
                composable(Destination.Study.route) { StudyScreen(quran) }
                composable(Destination.Quiz.route) { QuizScreen(quran) }
                composable(Destination.Archive.route) { PlaceholderScreen("Archive") }
                composable(SETTINGS_ROUTE) { PlaceholderScreen("Settings") }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}
