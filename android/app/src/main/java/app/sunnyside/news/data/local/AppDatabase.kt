package app.sunnyside.news.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StoryEntity::class, SavedStoryEntity::class, PetEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stories(): StoryDao
    abstract fun saved(): SavedStoryDao
    abstract fun pets(): PetDao

    companion object {
        /** v2 adds Reddit-style fields (kind, votes, comments…) and the votes table. Saved posts are kept. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("stories", "saved_stories")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN kind TEXT NOT NULL DEFAULT 'article'")
                    db.execSQL("ALTER TABLE $table ADD COLUMN author TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN score INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN comments INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN discussionUrl TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN imageWidth INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN imageHeight INTEGER")
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS votes (id TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
            }
        }

        /** v3 removes voting. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS votes")
            }
        }

        /** v4 stores video links so clips can play. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN videoUrl TEXT")
                db.execSQL("ALTER TABLE saved_stories ADD COLUMN videoUrl TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sunnyside.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
