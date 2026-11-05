package ru.partitrainer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.partitrainer.MainViewModel
import ru.partitrainer.container
import ru.partitrainer.data.ImportResult
import ru.partitrainer.ui.screens.AboutScreen
import ru.partitrainer.ui.screens.EditorScreen
import ru.partitrainer.ui.screens.HomeScreen
import ru.partitrainer.ui.screens.PrepareScreen
import ru.partitrainer.ui.screens.PrivacyScreen
import ru.partitrainer.ui.screens.ProgressScreen
import ru.partitrainer.ui.screens.SettingsScreen
import ru.partitrainer.ui.screens.TrainerScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    fun trainer(id: Long) = "trainer/$id"
    fun review(id: Long) = "review/$id"
    fun prepare(path: String, hash: String, name: String, pdf: Boolean) =
        "prepare?path=${Uri.encode(path)}&hash=$hash&name=${Uri.encode(name)}&pdf=$pdf"
    fun progress(workId: String, title: String) = "progress/$workId?title=${Uri.encode(title)}"
}

@Composable
fun PartiNavHost(main: MainViewModel) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val incoming by main.incoming.collectAsState()

    // Внешние файлы: «Открыть с помощью» / «Поделиться»
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        main.incoming.value = null
        when (val r = ctx.container.importer.import(uri)) {
            is ImportResult.Opened -> nav.navigate(Routes.trainer(r.projectId))
            is ImportResult.NeedsRecognition -> nav.navigate(Routes.prepare(r.path, r.hash, r.name, r.isPdf))
            is ImportResult.Failed -> main.error.value = r.message
        }
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                externalError = main.error,
                onOpenTrainer = { nav.navigate(Routes.trainer(it)) },
                onOpenReview = { nav.navigate(Routes.review(it)) },
                onPrepare = { p, h, n, pdf -> nav.navigate(Routes.prepare(p, h, n, pdf)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(
            "prepare?path={path}&hash={hash}&name={name}&pdf={pdf}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType }, navArgument("hash") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType }, navArgument("pdf") { type = NavType.BoolType },
            ),
        ) { e ->
            val a = e.arguments!!
            PrepareScreen(
                path = a.getString("path")!!, hash = a.getString("hash")!!, name = a.getString("name")!!, isPdf = a.getBoolean("pdf"),
                onBack = { nav.popBackStack() },
                onStarted = { id, title -> nav.navigate(Routes.progress(id, title)) { popUpTo(Routes.HOME) } },
                onManual = { id -> nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable("progress/{id}?title={title}", arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType; defaultValue = "" })) { e ->
            ProgressScreen(
                workId = e.arguments!!.getString("id")!!,
                onDone = { pid -> nav.navigate(Routes.review(pid)) { popUpTo(Routes.HOME) } },
                onBack = { nav.popBackStack(Routes.HOME, false) },
                onManual = { nav.popBackStack(Routes.HOME, false) },
            )
        }
        composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            EditorScreen(
                projectId = e.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onDone = { id -> nav.navigate(Routes.trainer(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable("trainer/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            val id = e.arguments!!.getLong("id")
            TrainerScreen(projectId = id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.review(id)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }, onPrivacy = { nav.navigate(Routes.PRIVACY) }) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
    }
}
