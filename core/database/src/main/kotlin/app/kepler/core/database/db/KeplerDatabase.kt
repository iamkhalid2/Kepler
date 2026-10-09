package app.kepler.core.database.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        BookEntity::class, SectionEntity::class, SentenceEntity::class, PositionEntity::class,
        ConsumedIntervalEntity::class, AnnotationEntity::class, BookmarkEntity::class,
        AudioSegmentEntity::class, SyncRowEntity::class, CardEntity::class, AiCacheEntity::class, AiUsageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class KeplerDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun sections(): SectionDao
    abstract fun sentences(): SentenceDao
    abstract fun consumed(): ConsumedDao
    abstract fun positions(): PositionDao
    abstract fun annotations(): AnnotationDao
    abstract fun audio(): AudioDao
    abstract fun cards(): CardDao
    abstract fun ai(): AiDao
}
