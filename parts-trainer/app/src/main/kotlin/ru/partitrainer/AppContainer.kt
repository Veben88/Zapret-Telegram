package ru.partitrainer

import android.app.Application
import android.content.Context
import androidx.room.Room
import ru.partitrainer.audio.PlaybackEngine
import ru.partitrainer.audio.Synth
import ru.partitrainer.data.Importer
import ru.partitrainer.data.PartiDatabase
import ru.partitrainer.data.ProjectRepository
import ru.partitrainer.data.SecureKeyStore
import ru.partitrainer.data.SettingsRepository
import ru.partitrainer.omr.HomrModelManager
import ru.partitrainer.util.SourceFiles

/** Ручной DI: один контейнер на процесс. */
class AppContainer(val context: Context) {
    val db: PartiDatabase by lazy { Room.databaseBuilder(context, PartiDatabase::class.java, "parti.db").build() }
    val projects: ProjectRepository by lazy { ProjectRepository(db.dao()) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val keyStore: SecureKeyStore by lazy { SecureKeyStore(context) }
    val sources: SourceFiles by lazy { SourceFiles(context) }
    val importer: Importer by lazy { Importer(sources, projects) }
    val homrModels: HomrModelManager by lazy { HomrModelManager(context) }
    val layouts: ru.partitrainer.data.LayoutStore by lazy { ru.partitrainer.data.LayoutStore(context) }
    val backup: ru.partitrainer.util.BackupManager by lazy { ru.partitrainer.util.BackupManager(context, projects, layouts) }
    val synth: Synth by lazy { Synth() }
    val playback: PlaybackEngine by lazy { PlaybackEngine(context, synth) }
}

class PartiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

val Context.container: AppContainer get() = (applicationContext as PartiApp).container
