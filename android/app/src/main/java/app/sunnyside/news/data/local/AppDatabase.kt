package app.sunnyside.news.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [StoryEntity::class, SavedStoryEntity::class, PetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stories(): StoryDao
    abstract fun saved(): SavedStoryDao
    abstract fun pets(): PetDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sunnyside.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
