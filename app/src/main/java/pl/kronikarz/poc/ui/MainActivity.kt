package pl.kronikarz.poc.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.processing.ProcessingState
import pl.kronikarz.poc.ui.debug.DebugScreen
import pl.kronikarz.poc.ui.home.HomeScreen
import pl.kronikarz.poc.ui.processing.ProcessingScreen
import pl.kronikarz.poc.ui.review.ReviewScreen
import pl.kronikarz.poc.ui.summary.SummaryScreen
import pl.kronikarz.poc.ui.theme.AlbumColors
import pl.kronikarz.poc.ui.theme.KronikarzTheme

class MainActivity : ComponentActivity() {
    /** Wideo udostępnione z innej aplikacji (ACTION_SEND), czekające na rozpoczęcie przetwarzania. */
    private val sharedVideo = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KronikarzApp
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            KronikarzTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = AlbumColors.Paper) {
                    val nav = rememberNavController()
                    val shared by sharedVideo
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            // Udostępnione wideo obsługujemy dopiero, gdy ekran główny jest na miejscu
                            // (wcześniej nawigacja do „processing” mogła zostać nadpisana startem grafu).
                            LaunchedEffect(shared) {
                                val uri = shared ?: return@LaunchedEffect
                                sharedVideo.value = null
                                app.processing.start(uri, app.settings.settings.value)
                            }
                            // Nawigacja sterowana stanem: gdy trwa przetwarzanie, ekran główny sam
                            // przechodzi do postępu. (Wywołanie navigate() z wyniku pickera, gdy aktywność
                            // dopiero wraca na pierwszy plan, potrafiło przepaść — sprawdzone na emulatorze.)
                            val running = app.processing.state.collectAsState().value is ProcessingState.Running
                            LaunchedEffect(running) {
                                if (running) nav.navigate("processing") { launchSingleTop = true }
                            }
                            HomeScreen(
                                app,
                                onVideoPicked = { uri -> app.processing.start(uri, app.settings.settings.value) },
                                onOpenSession = { s -> nav.navigate("review/${s.id}") },
                                onOpenDebug = { nav.navigate("debug") },
                                onShowProcessing = { nav.navigate("processing") { launchSingleTop = true } },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null) sharedVideo.value = uri
    }
}
