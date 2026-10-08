package app.betterbooks.core.database

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.betterbooks.core.database.db.BetterBooksDatabase
import app.betterbooks.core.database.db.Fts
import app.betterbooks.core.database.repo.AiStoreImpl
import app.betterbooks.core.database.repo.AnnotationRepositoryImpl
import app.betterbooks.core.database.repo.AudioRepositoryImpl
import app.betterbooks.core.database.repo.CardRepositoryImpl
import app.betterbooks.core.database.repo.LibraryRepositoryImpl
import app.betterbooks.core.database.repo.PositionRepositoryImpl
import app.betterbooks.core.database.repo.SourceOpener
import app.betterbooks.core.database.repo.SpineRepositoryImpl
import app.betterbooks.core.database.settings.DataStoreSettingsStore
import app.betterbooks.core.database.settings.KeystoreSecretCipher
import app.betterbooks.core.database.settings.SecretCipher
import app.betterbooks.core.model.AiStore
import app.betterbooks.core.model.AnnotationRepository
import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.BookIngestor
import app.betterbooks.core.model.CardRepository
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.PositionRepository
import app.betterbooks.core.model.SectionEditor
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.SpineRepository
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
    private val db: BetterBooksDatabase,
    booksDir: File,
    opener: SourceOpener,
    ingestor: BookIngestor,
    val settings: SettingsStore,
) : AutoCloseable {

    constructor(context: Context, ingestor: BookIngestor) : this(context, ingestor, KeystoreSecretCipher())

    private constructor(context: Context, ingestor: BookIngestor, cipher: SecretCipher) : this(
        db = Room.databaseBuilder(context.applicationContext, BetterBooksDatabase::class.java, DB_NAME)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(Fts.callback)
            .build(),
        booksDir = File(context.filesDir, "books"),
        opener = contentOpener(context.applicationContext),
        ingestor = ingestor,
        settings = DataStoreSettingsStore(settingsDataStore(context.applicationContext), cipher),
    )

    private val libraryImpl = LibraryRepositoryImpl(db, ingestor, booksDir, opener)
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
        const val DB_NAME = "betterbooks.db"

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
