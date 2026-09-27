package app.sunnyside.news.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { System, Light, Dark }

data class Settings(
    val morningBriefing: Boolean = true,
    val briefingHour: Int = 7,
    val briefingMinute: Int = 0,
    val theme: ThemeMode = ThemeMode.System,
    val onboarded: Boolean = false,
    val sort: SortMode = SortMode.Hot,
    val view: ViewMode = ViewMode.Card,
    /** Communities shown on Home. Everyone starts in all of them. */
    val joined: Set<Community> = Community.entries.toSet(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val morning = booleanPreferencesKey("morning_briefing")
        val hour = intPreferencesKey("briefing_hour")
        val minute = intPreferencesKey("briefing_minute")
        val theme = stringPreferencesKey("theme")
        val onboarded = booleanPreferencesKey("onboarded")
        val sort = stringPreferencesKey("sort")
        val view = stringPreferencesKey("view")
        val joined = stringSetPreferencesKey("joined")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            morningBriefing = p[Keys.morning] ?: true,
            briefingHour = p[Keys.hour] ?: 7,
            briefingMinute = p[Keys.minute] ?: 0,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            onboarded = p[Keys.onboarded] ?: false,
            sort = p[Keys.sort]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.Hot,
            view = p[Keys.view]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.Card,
            joined = p[Keys.joined]?.mapNotNull { name -> Community.entries.firstOrNull { it.name == name } }?.toSet()
                ?: Community.entries.toSet(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setMorningBriefing(enabled: Boolean) = context.dataStore.edit { it[Keys.morning] = enabled }

    suspend fun setBriefingTime(hour: Int, minute: Int) = context.dataStore.edit {
        it[Keys.hour] = hour
        it[Keys.minute] = minute
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }

    suspend fun setOnboarded() = context.dataStore.edit { it[Keys.onboarded] = true }

    suspend fun setSort(mode: SortMode) = context.dataStore.edit { it[Keys.sort] = mode.name }

    suspend fun setView(mode: ViewMode) = context.dataStore.edit { it[Keys.view] = mode.name }

    suspend fun toggleJoined(community: Community) {
        val joined = current().joined
        val next = if (community in joined) joined - community else joined + community
        context.dataStore.edit { p -> p[Keys.joined] = next.map { it.name }.toSet() }
    }
}
