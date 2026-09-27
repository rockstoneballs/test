package app.sunnyside.news.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryDao {
    @Query("SELECT * FROM stories ORDER BY publishedAt DESC")
    fun observeAll(): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE id = :id")
    fun observe(id: String): Flow<StoryEntity?>

    @Query("SELECT * FROM stories WHERE kind = 'article' ORDER BY uplift DESC, publishedAt DESC LIMIT 1")
    suspend fun topStory(): StoryEntity?

    @Query("SELECT COUNT(*) FROM stories WHERE publishedAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM stories")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stories: List<StoryEntity>)

    @Transaction
    suspend fun replaceAll(stories: List<StoryEntity>) {
        clear()
        insertAll(stories)
    }
}

@Dao
interface SavedStoryDao {
    @Query("SELECT * FROM saved_stories ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedStoryEntity>>

    @Query("SELECT * FROM saved_stories WHERE id = :id")
    fun observe(id: String): Flow<SavedStoryEntity?>

    @Query("SELECT id FROM saved_stories")
    fun observeIds(): Flow<List<String>>

    @Upsert
    suspend fun save(story: SavedStoryEntity)

    @Query("DELETE FROM saved_stories WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface PetDao {
    @Query("SELECT * FROM pets ORDER BY date DESC, kind ASC")
    fun observeAll(): Flow<List<PetEntity>>

    @Query("SELECT * FROM pets WHERE date = :date")
    suspend fun forDate(date: String): List<PetEntity>

    @Upsert
    suspend fun upsertAll(pets: List<PetEntity>)

    @Query("DELETE FROM pets WHERE date < :before")
    suspend fun deleteBefore(before: String)
}
