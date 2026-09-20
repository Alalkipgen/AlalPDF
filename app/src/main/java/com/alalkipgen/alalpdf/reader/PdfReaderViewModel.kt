package com.alalkipgen.alalpdf.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Lifecycle of the document's text layer.
 *
 * Previously every failure collapsed into "pageTexts is empty", which the UI
 * reported as "No selectable text" regardless of the real cause.
 */
enum class PdfTextLoadState {
    /** Nothing has been extracted yet. */
    Loading,

    /** At least one page produced text. */
    Ready,

    /** Extraction succeeded but the page has no embedded text (scanned image). */
    ImageOnly,

    /** Extraction threw. See [PdfReaderUiState.textError]. */
    Failed,
}

data class PdfReaderUiState(
    val isLoading: Boolean = true,
    val pageCount: Int = 0,
    val pages: SnapshotStateMap<Int, Bitmap> = mutableStateMapOf(),
    val pageAspectRatios: SnapshotStateMap<Int, Float> = mutableStateMapOf(),
    val defaultAspectRatio: Float = 1.414f,
    val pageLinks: Map<Int, List<PdfPageLink>> = emptyMap(),
    /** Lazily filled, keyed by zero-based page index. */
    val pageTexts: Map<Int, String> = emptyMap(),
    /** Positioned runs per page; only available on API 35+. */
    val textRuns: Map<Int, List<PdfTextRun>> = emptyMap(),
    val textState: PdfTextLoadState = PdfTextLoadState.Loading,
    val textError: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PdfSearchResult> = emptyList(),
    val searchProgress: Float = 0f,
    val searchRunning: Boolean = false,
    val requiresPassword:Boolean=false,
    val errorMessage: String? = null,
)

/** Serial visible-page-first render queue for PdfRenderer. */
class PdfReaderViewModel(private val repository: PdfReaderRepository) : ViewModel() {
    private data class RenderRequest(
        val uri: Uri,
        val page: Int,
        val width: Int,
        val generation: Int,
        val priority: Int,
        val order: Long,
    )

    private val pages = mutableStateMapOf<Int, Bitmap>()
    private val pageAspectRatios = mutableStateMapOf<Int, Float>()
    private val _uiState = MutableStateFlow(
        PdfReaderUiState(pages = pages, pageAspectRatios = pageAspectRatios)
    )
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private lateinit var cache: BitmapPageCache
    private val renderedWidths = ConcurrentHashMap<Int, Int>()
    private val queueLock = Any()
    private val queue = PriorityQueue<RenderRequest>(compareBy<RenderRequest> { it.priority }.thenBy { it.order })
    private val queuedPages = mutableSetOf<Pair<Int, Int>>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val sequence = AtomicLong(0)
    private val textRequests = ConcurrentHashMap<Int, Boolean>()
    private var searchJob: Job? = null

    @Volatile private var generation = 0
    @Volatile private var loadedUri: String? = null
    @Volatile private var focusedPage = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (signal in wakeUp) drainQueue()
        }
    }

    fun initialize(context: Context) {
        if (!::cache.isInitialized) {
            val memoryClass = (context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 128) * 1024 * 1024
            cache = BitmapPageCache(memoryClass / 4)
        }
    }

    fun load(uri: Uri, width: Int, initialPage: Int = 0, nightMode: Boolean = false,password:String?=null) {
        if (!::cache.isInitialized) return
        val key = uri.toString()
        if (key == loadedUri && _uiState.value.pageCount > 0) {
            renderWindow(uri, initialPage, width, nightMode)
            return
        }

        generation++
        val currentGeneration = generation
        loadedUri = key
        focusedPage = initialPage.coerceAtLeast(0)
        synchronized(queueLock) { queue.clear(); queuedPages.clear() }
        searchJob?.cancel()
        textRequests.clear()
        cache.clear()
        renderedWidths.clear()
        Snapshot.withMutableSnapshot { pages.clear(); pageAspectRatios.clear() }
        _uiState.value = PdfReaderUiState(pages = pages, pageAspectRatios = pageAspectRatios)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val count = repository.pageCount(uri,password)
                ensureActive()
                if (currentGeneration != generation) return@runCatching
                _uiState.update { state -> state.copy(
                    isLoading = false,
                    pageCount = count,
                    errorMessage = null,
                ) }
                if (count > 0) {
                    requestWindow(
                        uri,
                        initialPage.coerceIn(0, count - 1),
                        width,
                        currentGeneration,
                    )
                    requestPageText(uri, initialPage.coerceIn(0, count - 1))
                }
                runCatching { repository.links(uri) }.onSuccess { links ->
                    if (currentGeneration == generation) _uiState.update { state -> state.copy(pageLinks = links) }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (currentGeneration == generation) _uiState.update { state ->
                    state.copy(isLoading=false,requiresPassword=error is PdfPasswordRequiredException,errorMessage=if(error is PdfPasswordRequiredException)null else error.message?:"Unable to open PDF")
                }
            }
        }
    }

    /**
     * Extracts the text of one page, at most once per page per document.
     *
     * The failure branch matters: extraction can still throw (corrupt object
     * stream, unsupported encryption, out of memory) and that used to be
     * discarded silently, leaving the UI stuck on "No selectable text".
     */
    fun requestPageText(uri: Uri, page: Int) {
        if (page < 0 || uri.toString() != loadedUri) return
        if (textRequests.putIfAbsent(page, true) != null) return
        val expectedGeneration = generation
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = repository.pageText(uri, page)
                val runs = repository.pageTextRuns(uri, page)
                text to runs
            }.onSuccess { (text, runs) ->
                if (expectedGeneration != generation) return@onSuccess
                _uiState.update { state ->
                    state.copy(
                        pageTexts = state.pageTexts + (page to text),
                        textRuns = if (runs.isEmpty()) state.textRuns else state.textRuns + (page to runs),
                        textState = when {
                            text.isNotBlank() -> PdfTextLoadState.Ready
                            state.textState == PdfTextLoadState.Ready -> PdfTextLoadState.Ready
                            else -> PdfTextLoadState.ImageOnly
                        },
                        textError = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                textRequests.remove(page)
                if (expectedGeneration != generation) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        textState = PdfTextLoadState.Failed,
                        textError = error.describeForUser(),
                    )
                }
            }
        }
    }

    /**
     * Streams search results page by page so a 109-page document reports
     * progress instead of blocking, and so early hits are usable immediately.
     */
    fun search(uri: Uri, query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(searchQuery = query, searchResults = emptyList(), searchProgress = 0f, searchRunning = false)
            }
            return
        }
        val expectedGeneration = generation
        _uiState.update {
            it.copy(searchQuery = query, searchResults = emptyList(), searchProgress = 0f, searchRunning = true)
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val count = _uiState.value.pageCount
            val found = ArrayList<PdfSearchResult>()
            var failure: Throwable? = null
            for (page in 0 until count) {
                ensureActive()
                if (expectedGeneration != generation) return@launch
                val text = runCatching { repository.pageText(uri, page) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        if (failure == null) failure = error
                    }
                    .getOrDefault("")
                val hits = PdfTextSearch.matches(page, text, trimmed)
                if (hits.isNotEmpty()) {
                    found.addAll(hits)
                    val snapshot = ArrayList(found)
                    _uiState.update { it.copy(searchResults = snapshot) }
                }
                _uiState.update { it.copy(searchProgress = (page + 1f) / count.coerceAtLeast(1)) }
            }
            val error = failure
            _uiState.update { state ->
                state.copy(
                    searchRunning = false,
                    searchProgress = 1f,
                    textError = if (error != null && found.isEmpty()) error.describeForUser() else state.textError,
                    textState = if (error != null && found.isEmpty()) PdfTextLoadState.Failed else state.textState,
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchQuery = "", searchResults = emptyList(), searchProgress = 0f, searchRunning = false)
        }
    }

    private fun Throwable.describeForUser(): String = when (this) {
        is OutOfMemoryError ->
            "Ran out of memory while reading this document's text layer."
        else -> message?.takeIf(String::isNotBlank) ?: (this::class.java.simpleName)
    }

    fun renderWindow(uri: Uri, pageIndex: Int, width: Int, nightMode: Boolean = false) {
        val count = _uiState.value.pageCount
        if (count <= 0 || uri.toString() != loadedUri) return
        requestWindow(uri, pageIndex.coerceIn(0, count - 1), width, generation)
    }

    /** Requests a composed placeholder without changing which page is pinned. */
    fun requestPage(uri: Uri, pageIndex: Int, width: Int) {
        val count = _uiState.value.pageCount
        if (count <= 0 || uri.toString() != loadedUri) return
        val safePage = pageIndex.coerceIn(0, count - 1)
        val distance = abs(safePage - focusedPage)
        if (distance > DISPLAY_DISTANCE) return
        synchronized(queueLock) {
            enqueueLocked(uri, safePage, renderWidth(width), generation, distance)
        }
        wakeUp.trySend(Unit)
    }

    private fun requestWindow(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int) {
        focusedPage = pageIndex
        pruneDisplayedPages(pageIndex)
        val safeWidth = renderWidth(width)
        synchronized(queueLock) {
            val stale = queue.filter {
                it.generation != expectedGeneration || abs(it.page - pageIndex) > DISPLAY_DISTANCE
            }
            if (stale.isNotEmpty()) {
                queue.removeAll(stale.toSet())
                stale.forEach { queuedPages.remove(it.generation to it.page) }
            }
            listOf(pageIndex, pageIndex + 1, pageIndex - 1)
                .forEachIndexed { priority, page ->
                    enqueueLocked(uri, page, safeWidth, expectedGeneration, priority)
                }
        }
        wakeUp.trySend(Unit)
    }

    private fun enqueueLocked(uri: Uri, page: Int, width: Int, expectedGeneration: Int, priority: Int) {
        if (page !in 0 until _uiState.value.pageCount) return
        if (pages[page] != null && (renderedWidths[page] ?: 0) >= width) return

        val cached = cache.get(page)
        if (cached != null && (renderedWidths[page] ?: 0) >= width) {
            Snapshot.withMutableSnapshot { pages[page] = cached }
            return
        }

        val key = expectedGeneration to page
        val existing = queue.firstOrNull { it.generation == expectedGeneration && it.page == page }
        if (existing != null) {
            if (existing.priority <= priority && existing.width >= width) return
            queue.remove(existing)
            queuedPages.remove(key)
        }
        if (!queuedPages.add(key)) return
        queue.add(RenderRequest(uri, page, width, expectedGeneration, priority, sequence.incrementAndGet()))
    }

    private suspend fun drainQueue() {
        while (true) {
            val request = synchronized(queueLock) {
                queue.poll()?.also { queuedPages.remove(it.generation to it.page) }
            } ?: return
            if (request.generation != generation || request.uri.toString() != loadedUri) continue
            if (pages[request.page] != null && (renderedWidths[request.page] ?: 0) >= request.width) continue

            runCatching { repository.render(request.uri, request.page, request.width) }
                .onSuccess { bitmap ->
                    if (request.generation != generation) return@onSuccess
                    cache.put(request.page, bitmap)
                    renderedWidths[request.page] = request.width

                    // Retain the old portrait bitmap until the wider landscape
                    // render completes, then replace it in one snapshot.
                    if (abs(request.page - focusedPage) <= DISPLAY_DISTANCE) {
                        Snapshot.withMutableSnapshot {
                            pageAspectRatios[request.page] = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                            pages[request.page] = bitmap
                        }
                    }
                }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    private fun pruneDisplayedPages(centerPage: Int) {
        Snapshot.withMutableSnapshot {
            pages.keys
                .filter { abs(it - centerPage) > DISPLAY_DISTANCE }
                .forEach { pages.remove(it) }
        }
    }

    private fun renderWidth(width: Int): Int = width.coerceIn(1, MAX_RENDER_WIDTH_PX)

    override fun onCleared() {
        generation++
        wakeUp.close()
        searchJob?.cancel()
        synchronized(queueLock) { queue.clear(); queuedPages.clear() }
        Snapshot.withMutableSnapshot { pages.clear(); pageAspectRatios.clear() }
        if (::cache.isInitialized) cache.clear()
        repository.close()
        super.onCleared()
    }

    class Factory(private val repository: PdfReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PdfReaderViewModel(repository) as T
    }

    private companion object {
        const val DISPLAY_DISTANCE = 1
        const val MAX_RENDER_WIDTH_PX = 1_400
    }
}
