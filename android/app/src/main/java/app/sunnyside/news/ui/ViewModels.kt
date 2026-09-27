package app.sunnyside.news.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.sunnyside.news.SunnysideApp
import app.sunnyside.news.data.NewsRepository
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.Ranking
import app.sunnyside.news.data.RefreshSource
import app.sunnyside.news.data.Settings
import app.sunnyside.news.data.SettingsRepository
import app.sunnyside.news.data.SortMode
import app.sunnyside.news.data.Story
import app.sunnyside.news.data.ThemeMode
import app.sunnyside.news.data.ViewMode
import app.sunnyside.news.ui.components.PostUserState
import app.sunnyside.news.work.Scheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun <T> Flow<T>.asState(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Shared by every screen that shows posts: saves, sort and layout. */
abstract class PostsViewModel(
    protected val repo: NewsRepository,
    protected val settingsRepo: SettingsRepository,
) : ViewModel() {
    val user: StateFlow<PostUserState> =
        repo.savedIds.map { PostUserState(it) }.asState(this, PostUserState())

    val settings: StateFlow<Settings> = settingsRepo.settings.asState(this, Settings())

    fun toggleSave(story: Story) = viewModelScope.launch {
        repo.toggleSaved(story, story.id in repo.savedIds.first())
    }

    fun setSort(mode: SortMode) = viewModelScope.launch { settingsRepo.setSort(mode) }

    fun toggleView() = viewModelScope.launch {
        settingsRepo.setView(if (settingsRepo.current().view == ViewMode.Card) ViewMode.Compact else ViewMode.Card)
    }
}

// ------------------------------------------------------------------ Home

data class HomeState(
    val posts: List<Story> = emptyList(),
    val kitten: Pet? = null,
    val puppy: Pet? = null,
    val loaded: Boolean = false,
)

class HomeViewModel(repo: NewsRepository, settingsRepo: SettingsRepository) : PostsViewModel(repo, settingsRepo) {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    val updatedAt: StateFlow<Long?> = repo.feedUpdatedAt

    val state: StateFlow<HomeState> = combine(repo.stories, repo.pets, settingsRepo.settings) { stories, pets, s ->
        HomeState(
            posts = Ranking.sort(stories, s.sort),
            kitten = pets.firstOrNull { it.kind == PetKind.Kitten },
            puppy = pets.firstOrNull { it.kind == PetKind.Puppy },
            loaded = true,
        )
    }.asState(this, HomeState())

    init {
        viewModelScope.launch {
            // Refresh on launch; if we already have cached posts this happens quietly.
            refresh(showSpinner = repo.stories.first().isEmpty())
        }
    }

    /** Called when the app comes back to the foreground: new posts land every half hour. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - repo.lastRefreshAt > STALE_AFTER_MS) refresh(showSpinner = false)
    }

    fun refresh(showSpinner: Boolean = true) {
        if (_refreshing.value) return
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            runCatching { repo.refresh() }
                .onSuccess { if (it == RefreshSource.Direct) _message.value = "Showing stories straight from our good-news sources" }
                .onFailure { if (showSpinner) _message.value = "Couldn't refresh — check your connection" }
            _refreshing.value = false
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private companion object {
        const val STALE_AFTER_MS = 10 * 60 * 1000L
    }
}

// ------------------------------------------------------------------ Search

class SearchViewModel(repo: NewsRepository, settingsRepo: SettingsRepository) : PostsViewModel(repo, settingsRepo) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<Story>> = combine(repo.stories, settingsRepo.settings, _query) { stories, s, q ->
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            emptyList()
        } else {
            Ranking.sort(
                stories.filter { story ->
                    val hay = "${story.title} ${story.summary} ${story.source} ${story.topic.label} ${story.region.label}".lowercase()
                    words.all { it in hay }
                },
                s.sort,
            )
        }
    }.asState(this, emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }
}

// ------------------------------------------------------------------ Saved

class SavedViewModel(repo: NewsRepository, settingsRepo: SettingsRepository) : PostsViewModel(repo, settingsRepo) {
    val stories: StateFlow<List<Story>?> = repo.savedStories.map<List<Story>, List<Story>?> { it }.asState(this, null)
}

// ------------------------------------------------------------------ Post detail

data class DetailState(val story: Story? = null, val related: List<Story> = emptyList(), val loaded: Boolean = false)

class DetailViewModel(repo: NewsRepository, settingsRepo: SettingsRepository, handle: SavedStateHandle) :
    PostsViewModel(repo, settingsRepo) {
    private val id: String = checkNotNull(handle["id"])

    val state: StateFlow<DetailState> = combine(repo.story(id), repo.stories) { story, all ->
        DetailState(
            story = story,
            related = if (story == null) {
                emptyList()
            } else {
                Ranking.sort(all.filter { it.topic == story.topic && it.id != id }, SortMode.Hot).take(5)
            },
            loaded = true,
        )
    }.asState(this, DetailState())
}

// ------------------------------------------------------------------ Pets

class PetsViewModel(repo: NewsRepository, handle: SavedStateHandle) : ViewModel() {
    val kind: PetKind = PetKind.valueOf(checkNotNull(handle["kind"]))
    val pets: StateFlow<List<Pet>> = repo.pets.map { list -> list.filter { it.kind == kind } }.asState(this, emptyList())
}

// ------------------------------------------------------------------ Settings

class SettingsViewModel(private val app: SunnysideApp, private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.asState(this, Settings())

    fun setMorningBriefing(enabled: Boolean) = viewModelScope.launch {
        repo.setMorningBriefing(enabled)
        Scheduler.scheduleMorningBriefing(app, repo.current(), reschedule = true)
    }

    fun setBriefingTime(hour: Int, minute: Int) = viewModelScope.launch {
        repo.setBriefingTime(hour, minute)
        Scheduler.scheduleMorningBriefing(app, repo.current(), reschedule = true)
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repo.setTheme(mode) }
}

// ------------------------------------------------------------------ Factory

val AppViewModels: ViewModelProvider.Factory = viewModelFactory {
    fun app(extras: CreationExtras) = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SunnysideApp
    fun news(extras: CreationExtras) = app(extras).container.newsRepository
    fun prefs(extras: CreationExtras) = app(extras).container.settingsRepository

    initializer { HomeViewModel(news(this), prefs(this)) }
    initializer { SearchViewModel(news(this), prefs(this)) }
    initializer { SavedViewModel(news(this), prefs(this)) }
    initializer { DetailViewModel(news(this), prefs(this), createSavedStateHandle()) }
    initializer { PetsViewModel(news(this), createSavedStateHandle()) }
    initializer { SettingsViewModel(app(this), prefs(this)) }
}
