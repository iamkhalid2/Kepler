package app.kepler.core.database

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.Fts
import app.kepler.core.database.db.KeplerMigrations
import app.kepler.core.database.repo.AiStoreImpl
import app.kepler.core.database.repo.AnnotationRepositoryImpl
import app.kepler.core.database.repo.AudioRepositoryImpl
import app.kepler.core.database.repo.CardRepositoryImpl
import app.kepler.core.database.repo.LibraryRepositoryImpl
import app.kepler.core.database.repo.PositionRepositoryImpl
import app.kepler.core.database.repo.SourceOpener
import app.kepler.core.database.repo.SpineRepositoryImpl
import app.kepler.core.database.settings.DataStoreSettingsStore
import app.kepler.core.database.settings.KeystoreSecretCipher
import app.kepler.core.database.settings.SecretCipher
import app.kepler.core.model.AiStore
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.BookIngestor
import app.kepler.core.model.CardRepository
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.SectionEditor
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * The single entry point to persistence. Create exactly one per process (for example in `Application`) and hand
 * out the repository interfaces; DataStore refuses two instances over the same file.
 */
class DataLayer internal constructor(
    private val db: KeplerDatabase,
    booksDir: File,
    opener: SourceOpener,
    ingestor: BookIngestor,
    val settings: SettingsStore,
) : AutoCloseable {

    constructor(context: Context, ingestor: BookIngestor) : this(context, ingestor, KeystoreSecretCipher())

    private constructor(context: Context, ingestor: BookIngestor, cipher: SecretCipher) : this(
        db = Room.databaseBuilder(context.applicationContext, KeplerDatabase::class.java, DB_NAME)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*KeplerMigrations.ALL)
            .addCallback(Fts.callback)
            .build(),
        booksDir = File(context.filesDir, "books"),
        opener = contentOpener(context.applicationContext),
        ingestor = ingestor,
        settings = DataStoreSettingsStore(settingsDataStore(context.applicationContext), cipher),
    )

    private val libraryImpl = LibraryRepositoryImpl(db, ingestor, booksDir, opener, audioDir = booksDir.parentFile?.let { File(it, "audio") })
    private val positionsImpl = PositionRepositoryImpl(db)
    private val spineImpl = SpineRepositoryImpl(db, onSectionsChanged = positionsImpl::refreshFractions)

    val library: LibraryRepository = libraryImpl
    val spine: SpineRepository = spineImpl
    val positions: PositionRepository = positionsImpl
    val annotations: AnnotationRepository = AnnotationRepositoryImpl(db)
    val audio: AudioRepository = AudioRepositoryImpl(db, settings)
    val cards: CardRepository = CardRepositoryImpl(db)
    val aiStore: AiStore = AiStoreImpl(db)
    /** Toggle which sections are narrated and count towards progress. */
    val sectionEditor: SectionEditor = spineImpl

    init {
        booksDir.mkdirs()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { libraryImpl.removeIncompleteImports() } }
    }

    override fun close() = db.close()

    internal companion object {
        const val DB_NAME = "kepler.db"

        fun contentOpener(context: Context) = SourceOpener { uri ->
            if (uri.contains("://")) {
                checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) { "The file could not be opened." }
            } else {
                FileInputStream(uri)
            }
        }

        private fun settingsDataStore(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { File(context.filesDir, "datastore/settings.preferences_pb").also { it.parentFile?.mkdirs() } },
            )
    }
}
