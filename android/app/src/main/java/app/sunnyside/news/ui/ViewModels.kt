package app.sunnyside.news.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.sunnyside.news.SunnysideApp
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.NewsRepository
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.Region
import app.sunnyside.news.data.RefreshSource
import app.sunnyside.news.data.Settings
import app.sunnyside.news.data.SettingsRepository
import app.sunnyside.news.data.Story
import app.sunnyside.news.data.ThemeMode
import app.sunnyside.news.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun <T> kotlinx.coroutines.flow.Flow<T>.asState(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

// ------------------------------------------------------------------ Home

data class HomeState(
    val stories: List<Story> = emptyList(),
    val kitten: Pet? = null,
    val puppy: Pet? = null,
    val savedIds: Set<String> = emptySet(),
    val category: Category? = null,
    val loaded: Boolean = false,
)

class HomeViewModel(private val repo: NewsRepository) : ViewModel() {
    private val category = MutableStateFlow<Category?>(null)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<HomeState> = combine(repo.stories, repo.pets, repo.savedIds, category) { stories, pets, saved, cat ->
        HomeState(
            stories = if (cat == null) stories else stories.filter { it.category == cat },
            kitten = pets.firstOrNull { it.kind == PetKind.Kitten },
            puppy = pets.firstOrNull { it.kind == PetKind.Puppy },
            savedIds = saved,
            category = cat,
            loaded = true,
        )
    }.asState(this, HomeState())

    init {
        viewModelScope.launch {
            // Refresh on launch; if we already have cached stories this happens quietly.
            refresh(showSpinner = repo.stories.first().isEmpty())
        }
    }

    fun selectCategory(value: Category?) = category.update { value }

    fun refresh(showSpinner: Boolean = true) {
        if (_refreshing.value) return
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            runCatching { repo.refresh() }
                .onSuccess { if (it == RefreshSource.Direct) _message.value = "Showing stories straight from our good-news sources" }
                .onFailure { _message.value = "Couldn't refresh — check your connection" }
            _refreshing.value = false
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun toggleSaved(story: Story) = viewModelScope.launch {
        repo.toggleSaved(story, story.id in state.value.savedIds)
    }
}

// ------------------------------------------------------------------ Explore

data class ExploreState(
    val query: String = "",
    val region: Region? = null,
    val category: Category? = null,
    val results: List<Story> = emptyList(),
    val regionCounts: Map<Region, Int> = emptyMap(),
    val categoryCounts: Map<Category, Int> = emptyMap(),
    val savedIds: Set<String> = emptySet(),
) {
    val filtering: Boolean get() = query.isNotBlank() || region != null || category != null
}

class ExploreViewModel(private val repo: NewsRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val region = MutableStateFlow<Region?>(null)
    private val category = MutableStateFlow<Category?>(null)

    val state: StateFlow<ExploreState> = combine(repo.stories, repo.savedIds, query, region, category) { stories, saved, q, r, c ->
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        ExploreState(
            query = q,
            region = r,
            category = c,
            results = stories.filter { s ->
                (r == null || s.region == r) &&
                    (c == null || s.category == c) &&
                    words.all { w -> s.title.lowercase().contains(w) || s.summary.lowercase().contains(w) || s.source.lowercase().contains(w) }
            },
            regionCounts = stories.groupingBy { it.region }.eachCount(),
            categoryCounts = stories.groupingBy { it.category }.eachCount(),
            savedIds = saved,
        )
    }.asState(this, ExploreState())

    fun setQuery(value: String) = query.update { value }
    fun setRegion(value: Region?) = region.update { value }
    fun setCategory(value: Category?) = category.update { value }
    fun clear() {
        query.value = ""; region.value = null; category.value = null
    }

    fun toggleSaved(story: Story) = viewModelScope.launch {
        repo.toggleSaved(story, story.id in state.value.savedIds)
    }
}

// ------------------------------------------------------------------ Saved

class SavedViewModel(private val repo: NewsRepository) : ViewModel() {
    val stories: StateFlow<List<Story>?> = repo.savedStories.map<List<Story>, List<Story>?> { it }.asState(this, null)

    fun remove(story: Story) = viewModelScope.launch { repo.toggleSaved(story, currentlySaved = true) }
}

// ------------------------------------------------------------------ Story detail

data class DetailState(
    val story: Story? = null,
    val saved: Boolean = false,
    val related: List<Story> = emptyList(),
    val savedIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
)

class DetailViewModel(private val repo: NewsRepository, handle: SavedStateHandle) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])

    val state: StateFlow<DetailState> = combine(repo.story(id), repo.savedIds, repo.stories) { story, saved, all ->
        DetailState(
            story = story,
            saved = id in saved,
            related = if (story == null) emptyList() else all.filter { it.category == story.category && it.id != id }.take(4),
            savedIds = saved,
            loaded = true,
        )
    }.asState(this, DetailState())

    fun toggleSaved() = viewModelScope.launch {
        val s = state.value
        s.story?.let { repo.toggleSaved(it, s.saved) }
    }

    fun toggleSaved(story: Story) = viewModelScope.launch {
        repo.toggleSaved(story, story.id in state.value.savedIds)
    }
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
    fun app(extras: androidx.lifecycle.viewmodel.CreationExtras) =
        extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SunnysideApp

    initializer { HomeViewModel(app(this).container.newsRepository) }
    initializer { ExploreViewModel(app(this).container.newsRepository) }
    initializer { SavedViewModel(app(this).container.newsRepository) }
    initializer { DetailViewModel(app(this).container.newsRepository, createSavedStateHandle()) }
    initializer { PetsViewModel(app(this).container.newsRepository, createSavedStateHandle()) }
    initializer { SettingsViewModel(app(this), app(this).container.settingsRepository) }
}
