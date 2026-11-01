package pl.kronikarz.poc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.ui.debug.DebugScreen
import pl.kronikarz.poc.ui.home.HomeScreen
import pl.kronikarz.poc.ui.processing.ProcessingScreen
import pl.kronikarz.poc.ui.review.ReviewScreen
import pl.kronikarz.poc.ui.summary.SummaryScreen
import pl.kronikarz.poc.ui.theme.AlbumColors
import pl.kronikarz.poc.ui.theme.KronikarzTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KronikarzApp
        setContent {
            KronikarzTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = AlbumColors.Paper) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                app,
                                onVideoPicked = { uri ->
                                    app.processing.start(uri, app.settings.settings.value)
                                    nav.navigate("processing")
                                },
                                onOpenSession = { s -> nav.navigate("review/${s.id}") },
                                onOpenDebug = { nav.navigate("debug") },
                                onShowProcessing = { nav.navigate("processing") },
                            )
                        }
                        composable("processing") {
                            ProcessingScreen(
                                app,
                                onDone = { id ->
                                    nav.navigate("review/$id") { popUpTo("home") }
                                },
                                onBack = { nav.popBackStack("home", inclusive = false) },
                            )
                        }
                        composable(
                            "review/{sessionId}?personId={personId}",
                            arguments = listOf(
                                navArgument("sessionId") { type = NavType.StringType },
                                navArgument("personId") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            val sessionId = entry.arguments!!.getString("sessionId")!!
                            ReviewScreen(
                                app,
                                sessionId = sessionId,
                                personId = entry.arguments?.getString("personId"),
                                onFinished = { nav.navigate("summary/$sessionId") },
                                onShowAll = { nav.navigate("summary/$sessionId") },
                            )
                        }
                        composable("summary/{sessionId}") { entry ->
                            val sessionId = entry.arguments!!.getString("sessionId")!!
                            SummaryScreen(
                                app,
                                sessionId,
                                onOpenPerson = { pid ->
                                    nav.navigate("review/$sessionId?personId=$pid") { popUpTo("home") }
                                },
                                onHome = { nav.popBackStack("home", inclusive = false) },
                            )
                        }
                        composable("debug") { DebugScreen(app, onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
