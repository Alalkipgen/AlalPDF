package com.alalkipgen.alalpdf.reader

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
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
    /** Small persistent previews used by the page grid. */
    val thumbnails: SnapshotStateMap<Int, Bitmap> = mutableStateMapOf(),
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

/**
 * Single-consumer, visible-page-first render queue.
 *
 * Everything that touches a native engine - full page renders, previews and
 * grid thumbnails - goes through this one queue. The previous version only
 * queued page renders and launched a separate unbounded coroutine for every
 * thumbnail, so scrolling quickly through a long document started one native
 * render per page that scrolled past, all of them fighting for the same lock.
 */
class PdfReaderViewModel(private val repository: PdfReaderRepository) : ViewModel() {
    private enum class RenderKind { PAGE, THUMBNAIL }

    private data class RenderRequest(
        val uri: Uri,
        val page: Int,
        val width: Int,
        val generation: Int,
        val priority: Int,
        val order: Long,
        val kind: RenderKind,
    )

    private val pages = mutableStateMapOf<Int, Bitmap>()
    private val thumbnails = mutableStateMapOf<Int, Bitmap>()
    private val pageAspectRatios = mutableStateMapOf<Int, Float>()
    private val _uiState = MutableStateFlow(
        PdfReaderUiState(pages = pages, thumbnails = thumbnails, pageAspectRatios = pageAspectRatios)
    )
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private lateinit var cache: BitmapPageCache
    private var applicationContext: Context? = null
    private var maxRenderWidth = 1_200
    private val renderedWidths = ConcurrentHashMap<Int, Int>()
    private val queueLock = Any()
    private val queue = PriorityQueue<RenderRequest>(
        64,
        compareBy<RenderRequest> { it.priority }.thenBy { it.order },
    )

    // Best pending request per page, so enqueueing is O(1) instead of a linear
    // scan of the PriorityQueue on the main thread for every composed item.
    // Superseded entries stay in the queue and are skipped when they are polled.
    private val pendingPages = HashMap<Int, RenderRequest>()
    private val pendingThumbs = HashMap<Int, RenderRequest>()

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val sequence = AtomicLong(0)
    private val textRequests = ConcurrentHashMap<Int, Boolean>()
    private val linkRequests = ConcurrentHashMap<Int, Boolean>()
    private val failedThumbnails = ConcurrentHashMap<Int, Boolean>()
    private val thumbnailOrderLock = Any()
    private val thumbnailOrder = LinkedHashMap<Int, Unit>(32, 0.75f, true)
    private var documentJob: Job = SupervisorJob()
    private var documentScope = CoroutineScope(documentJob + Dispatchers.Default)
    private var searchJob: Job? = null

    @Volatile private var generation = 0
    @Volatile private var loadedUri: String? = null
    @Volatile private var loadedRevision = Int.MIN_VALUE
    @Volatile private var focusedPage = 0
    @Volatile private var scrollDirection = 1
    @Volatile private var scrolling = false

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = releaseMemory(critical = true)
        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                    releaseMemory(critical = true)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    releaseMemory(critical = false)
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (signal in wakeUp) drainQueue()
        }
    }

    fun initialize(context: Context) {
        if (!::cache.isInitialized) {
            val appContext = context.applicationContext
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            val lowRam = activityManager?.isLowRamDevice == true
            cache = BitmapPageCache(
                ReaderMemoryPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory(), lowRam)
            )
            maxRenderWidth = ReaderMemoryPolicy.maxRenderWidth(lowRam)
            applicationContext = appContext
            appContext.registerComponentCallbacks(memoryCallbacks)
        }
    }

    /**
     * Told by the reader whether the list is currently moving.
     *
     * A fling is the one moment where doing less work looks better: only the
     * page under the finger is rasterized, everything else waits in the queue
     * and is dropped for free when it leaves the viewport.
     */
    fun setScrolling(active: Boolean) {
        if (scrolling == active) return
        scrolling = active
        if (!active) {
            synchronized(queueLock) { compactLocked() }
            wakeUp.trySend(Unit)
        }
    }

    fun load(
        uri: Uri,
        width: Int,
        initialPage: Int = 0,
        nightMode: Boolean = false,
        password: String? = null,
        reloadToken: Int = 0,
    ) {
        if (!::cache.isInitialized) return
        val key = uri.toString()
        if (key == loadedUri && reloadToken == loadedRevision && _uiState.value.pageCount > 0) {
            renderWindow(uri, initialPage, width, nightMode)
            return
        }

        restartDocumentScope()
        generation++
        val currentGeneration = generation
        loadedUri = key
        loadedRevision = reloadToken
        focusedPage = initialPage.coerceAtLeast(0)
        scrolling = false
        clearQueue()
        searchJob = null
        textRequests.clear()
        linkRequests.clear()
        failedThumbnails.clear()
        synchronized(thumbnailOrderLock) { thumbnailOrder.clear() }
        cache.clear()
        renderedWidths.clear()
        Snapshot.withMutableSnapshot { pages.clear(); thumbnails.clear(); pageAspectRatios.clear() }
        _uiState.value = PdfReaderUiState(
            pages = pages,
            thumbnails = thumbnails,
            pageAspectRatios = pageAspectRatios,
        )

        documentScope.launch {
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
     * Never started while the list is moving: PDFium holds a process-wide lock
     * for the whole character sweep of a page, which would otherwise delay the
     * render of the page the user is actually looking at.
     */
    fun requestPageText(uri: Uri, page: Int) {
        if (page < 0 || uri.toString() != loadedUri || scrolling) return
        if (textRequests.putIfAbsent(page, true) != null) return
        val expectedGeneration = generation
        documentScope.launch {
            runCatching {
                val text = repository.pageText(uri, page)
                ensureActive()
                val runs = repository.pageTextRuns(uri, page)
                text to runs
            }.onSuccess { (text, runs) ->
                if (expectedGeneration != generation ||
                    abs(page - focusedPage) > TEXT_CACHE_DISTANCE
                ) {
                    textRequests.remove(page)
                    return@onSuccess
                }
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

    fun requestPageLinks(uri: Uri, page: Int) {
        if (page < 0 || uri.toString() != loadedUri || scrolling) return
        if (linkRequests.putIfAbsent(page, true) != null) return
        val expectedGeneration = generation
        documentScope.launch {
            runCatching { repository.pageLinks(uri, page) }
                .onSuccess { links ->
                    if (expectedGeneration != generation ||
                        abs(page - focusedPage) > TEXT_CACHE_DISTANCE
                    ) {
                        linkRequests.remove(page)
                        return@onSuccess
                    }
                    _uiState.update { state ->
                        state.copy(pageLinks = state.pageLinks + (page to links))
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    linkRequests.remove(page)
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
        searchJob = documentScope.launch {
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
                    val remaining = (MAX_SEARCH_RESULTS - found.size).coerceAtLeast(0)
                    if (remaining > 0) found.addAll(hits.take(remaining))
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

    /**
     * Promote only the page the user stopped on. While scrolling, previews win;
     * after the idle debounce this request jumps ahead of neighbour work.
     */
    fun promoteFocusedPage(uri: Uri, pageIndex: Int, width: Int) {
        val count = _uiState.value.pageCount
        if (count <= 0 || uri.toString() != loadedUri) return
        val page = pageIndex.coerceIn(0, count - 1)
        if (page != focusedPage) return
        synchronized(queueLock) {
            enqueueLocked(
                uri,
                page,
                renderWidth(width),
                generation,
                IDLE_FULL_RENDER_PRIORITY,
                RenderKind.PAGE,
            )
        }
        wakeUp.trySend(Unit)
    }

    /**
     * Requests whatever the list item needs. Pages far from the current one are
     * served by the thumbnail cache, which is what makes the page grid able to
     * show all 109 pages instead of the two or three still in the render window.
     */
    fun requestPage(uri: Uri, pageIndex: Int, width: Int) {
        val count = _uiState.value.pageCount
        if (count <= 0 || uri.toString() != loadedUri) return
        val safePage = pageIndex.coerceIn(0, count - 1)
        val distance = abs(safePage - focusedPage)
        if (distance > DISPLAY_DISTANCE) {
            // A fling composes every page it passes. Asking each of them for a
            // native thumbnail is what used to flood the queue and the IO pool,
            // so distant pages are only fetched once the list has settled.
            if (!scrolling) requestThumbnail(uri, safePage)
            return
        }
        synchronized(queueLock) {
            enqueueLocked(uri, safePage, widthFor(distance, width), generation, distance, RenderKind.PAGE)
        }
        wakeUp.trySend(Unit)
    }

    fun requestThumbnail(uri: Uri, pageIndex: Int) {
        val count = _uiState.value.pageCount
        if (count <= 0 || uri.toString() != loadedUri) return
        val page = pageIndex.coerceIn(0, count - 1)
        if (thumbnails.containsKey(page) || failedThumbnails.containsKey(page)) return
        synchronized(queueLock) {
            enqueueLocked(
                uri,
                page,
                THUMBNAIL_WIDTH_PX,
                generation,
                THUMBNAIL_PRIORITY,
                RenderKind.THUMBNAIL,
            )
        }
        wakeUp.trySend(Unit)
    }

    private fun requestWindow(uri: Uri, pageIndex: Int, width: Int, expectedGeneration: Int) {
        scrollDirection = (pageIndex - focusedPage).coerceIn(-1, 1).takeIf { it != 0 } ?: scrollDirection
        focusedPage = pageIndex
        pruneDisplayedPages(pageIndex)
        pruneTextLayers(pageIndex)
        synchronized(queueLock) {
            val order = ReaderMemoryPolicy.renderOrder(
                pageIndex,
                _uiState.value.pageCount,
                DISPLAY_DISTANCE,
                scrollDirection,
            )
            order.forEachIndexed { priority, page ->
                val distance = abs(page - pageIndex)
                enqueueLocked(
                    uri,
                    page,
                    widthFor(distance, width),
                    expectedGeneration,
                    priority,
                    RenderKind.PAGE,
                )
            }
        }
        wakeUp.trySend(Unit)
    }

    /** Only the focused page is worth a full-width ARGB render. */
    private fun widthFor(distance: Int, width: Int): Int =
        if (distance <= FULL_QUALITY_DISTANCE) renderWidth(width) else PREVIEW_WIDTH_PX

    private fun enqueueLocked(
        uri: Uri,
        page: Int,
        width: Int,
        expectedGeneration: Int,
        priority: Int,
        kind: RenderKind,
    ) {
        if (page !in 0 until _uiState.value.pageCount) return

        if (kind == RenderKind.PAGE) {
            if (pages[page] != null && (renderedWidths[page] ?: 0) >= width) return
            val cached = cache.get(page)
            if (cached != null && (renderedWidths[page] ?: 0) >= width) {
                Snapshot.withMutableSnapshot { pages[page] = cached }
                return
            }
        } else if (thumbnails.containsKey(page)) {
            return
        }

        val pendingMap = if (kind == RenderKind.PAGE) pendingPages else pendingThumbs
        val existing = pendingMap[page]
        if (existing != null &&
            existing.generation == expectedGeneration &&
            existing.priority <= priority &&
            existing.width >= width
        ) {
            return
        }

        if (queue.size >= ReaderScrollPolicy.MAX_QUEUE_LENGTH && !evictWorstLocked(priority)) return

        val request = RenderRequest(
            uri = uri,
            page = page,
            width = width,
            generation = expectedGeneration,
            priority = priority,
            order = sequence.incrementAndGet(),
            kind = kind,
        )
        pendingMap[page] = request
        queue.add(request)
    }

    /** Drops the least useful queued item so a long fling cannot grow the queue. */
    private fun evictWorstLocked(incomingPriority: Int): Boolean {
        var worst: RenderRequest? = null
        for (request in queue) {
            val current = worst
            if (current == null ||
                request.priority > current.priority ||
                (request.priority == current.priority && request.order < current.order)
            ) {
                worst = request
            }
        }
        val victim = worst ?: return false
        if (victim.priority < incomingPriority) return false
        queue.remove(victim)
        releasePendingLocked(victim)
        return true
    }

    private fun compactLocked() {
        if (queue.isEmpty()) return
        val survivors = ArrayList<RenderRequest>(queue.size)
        for (request in queue) {
            val obsolete = request.generation != generation ||
                (request.kind == RenderKind.PAGE &&
                    ReaderScrollPolicy.isStale(request.page, focusedPage, DISPLAY_DISTANCE)) ||
                (request.kind == RenderKind.THUMBNAIL && thumbnails.containsKey(request.page))
            if (!obsolete) survivors += request
        }
        if (survivors.size == queue.size) return
        queue.clear()
        pendingPages.clear()
        pendingThumbs.clear()
        survivors.forEach { request ->
            queue.add(request)
            if (request.kind == RenderKind.PAGE) {
                pendingPages[request.page] = request
            } else {
                pendingThumbs[request.page] = request
            }
        }
    }

    private fun releasePendingLocked(request: RenderRequest) {
        val pendingMap = if (request.kind == RenderKind.PAGE) pendingPages else pendingThumbs
        if (pendingMap[request.page] === request) pendingMap.remove(request.page)
    }

    private fun canRunLocked(request: RenderRequest): Boolean {
        if (request.generation != generation) return true
        if (!scrolling) return true
        return ReaderScrollPolicy.runsWhileScrolling(
            isPage = request.kind == RenderKind.PAGE,
            distance = abs(request.page - focusedPage),
        )
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            queue.clear()
            pendingPages.clear()
            pendingThumbs.clear()
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val request: RenderRequest = synchronized(queueLock) {
                val head = queue.peek() ?: return
                // Deferred work stays in the queue and resumes on settle rather
                // than being polled and re-added on every frame.
                if (!canRunLocked(head)) return
                queue.poll()
                releasePendingLocked(head)
                head
            }
            if (request.generation != generation || request.uri.toString() != loadedUri) continue
            when (request.kind) {
                RenderKind.THUMBNAIL -> runThumbnail(request)
                RenderKind.PAGE -> runPageRender(request)
            }
        }
    }

    private suspend fun runPageRender(request: RenderRequest) {
        if (ReaderScrollPolicy.isStale(request.page, focusedPage, DISPLAY_DISTANCE)) return
        if (pages[request.page] != null && (renderedWidths[request.page] ?: 0) >= request.width) return

        // Pass one: publish a cheap but readable preview, then put the expensive
        // full-width request at the back of the visible-window queue. Otherwise
        // each page finishes its full render before the next page gets even a
        // preview, which is what produces white placeholders while scrolling.
        if ((renderedWidths[request.page] ?: 0) == 0 && request.width > PREVIEW_WIDTH_PX) {
            val previewReady = runCatching {
                repository.renderPreview(request.uri, request.page, PREVIEW_WIDTH_PX)
            }.onSuccess { preview ->
                publish(request.page, preview, PREVIEW_WIDTH_PX, request.generation, cache = true)
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }.isSuccess
            if (previewReady) {
                synchronized(queueLock) {
                    enqueueLocked(
                        request.uri,
                        request.page,
                        request.width,
                        request.generation,
                        FULL_RENDER_PRIORITY + request.priority,
                        RenderKind.PAGE,
                    )
                }
                wakeUp.trySend(Unit)
                return
            }
        }

        // Pass two: the real render.
        runCatching {
            if (request.width <= PREVIEW_WIDTH_PX) {
                repository.renderPreview(request.uri, request.page, request.width)
            } else {
                repository.render(request.uri, request.page, request.width)
            }
        }
            .onSuccess { bitmap ->
                publish(request.page, bitmap, request.width, request.generation, cache = true)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (error is OutOfMemoryError) recoverFromOutOfMemory(request)
            }
    }

    private suspend fun runThumbnail(request: RenderRequest) {
        if (thumbnails.containsKey(request.page)) return
        runCatching { repository.thumbnail(request.uri, request.page) }
            .onSuccess { bitmap ->
                if (request.generation == generation) publishThumbnail(request.page, bitmap)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                // Stop retrying a page that cannot be rasterized; the list item
                // recomposes constantly and used to re-request it forever.
                failedThumbnails[request.page] = true
                if (error is OutOfMemoryError) releaseMemory(critical = true)
            }
    }

    private suspend fun recoverFromOutOfMemory(request: RenderRequest) {
        releaseMemory(critical = true)
        if (request.generation != generation || request.page != focusedPage) return
        runCatching {
            repository.renderPreview(request.uri, request.page, FALLBACK_PREVIEW_WIDTH_PX)
        }.onSuccess { bitmap ->
            publish(
                request.page,
                bitmap,
                FALLBACK_PREVIEW_WIDTH_PX,
                request.generation,
                cache = true,
            )
        }
    }

    private fun publish(page: Int, bitmap: Bitmap, width: Int, expectedGeneration: Int, cache: Boolean) {
        if (expectedGeneration != generation) return
        if ((renderedWidths[page] ?: 0) > width) return
        if (cache) this.cache.put(page, bitmap)
        renderedWidths[page] = width

        // Retain the previous bitmap until the better render completes, then
        // replace it in one snapshot so the list never flashes.
        if (abs(page - focusedPage) <= DISPLAY_DISTANCE) {
            Snapshot.withMutableSnapshot {
                pageAspectRatios[page] = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                pages[page] = bitmap
            }
        }
    }

    private fun pruneDisplayedPages(centerPage: Int) {
        val stale = pages.keys.filter { abs(it - centerPage) > DISPLAY_DISTANCE }
        if (stale.isEmpty()) return
        Snapshot.withMutableSnapshot { stale.forEach { page -> pages.remove(page) } }
    }

    /**
     * Character geometry is substantially larger than rendered text. Keep only
     * the pages that can be selected now; revisiting a page reloads it through
     * the PDFium LRU instead of retaining the whole document in Compose state.
     */
    private fun pruneTextLayers(centerPage: Int) {
        val keep: (Int) -> Boolean = { page -> abs(page - centerPage) <= TEXT_CACHE_DISTANCE }
        val current = _uiState.value
        val needsPrune = current.pageTexts.keys.any { !keep(it) } ||
            current.textRuns.keys.any { !keep(it) } ||
            current.pageLinks.keys.any { !keep(it) }
        if (needsPrune) {
            _uiState.update { state ->
                state.copy(
                    pageTexts = state.pageTexts.filterKeys(keep),
                    textRuns = state.textRuns.filterKeys(keep),
                    pageLinks = state.pageLinks.filterKeys(keep),
                )
            }
        }
        textRequests.keys.filterNot(keep).forEach(textRequests::remove)
        linkRequests.keys.filterNot(keep).forEach(linkRequests::remove)
    }

    /** Bounded UI thumbnail map; the disk cache remains the long-lived tier. */
    private fun publishThumbnail(page: Int, bitmap: Bitmap) {
        val evicted = ArrayList<Int>()
        synchronized(thumbnailOrderLock) {
            thumbnailOrder[page] = Unit
            while (thumbnailOrder.size > MAX_UI_THUMBNAILS) {
                val eldest = thumbnailOrder.entries.iterator()
                if (!eldest.hasNext()) break
                val entry = eldest.next()
                evicted += entry.key
                eldest.remove()
            }
        }
        Snapshot.withMutableSnapshot {
            evicted.forEach(thumbnails::remove)
            thumbnails[page] = bitmap
            if (!pageAspectRatios.containsKey(page)) {
                pageAspectRatios[page] =
                    bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
            }
        }
    }

    private fun renderWidth(width: Int): Int =
        ReaderMemoryPolicy.stableRenderWidth(width, maxRenderWidth)

    private fun releaseMemory(critical: Boolean) {
        if (!::cache.isInitialized) return
        if (critical) cache.clear() else cache.trimToBytes(CACHE_LOW_MEMORY_BYTES)
        repository.trimMemory()
        if (critical) {
            clearQueue()
            textRequests.clear()
            linkRequests.clear()
            failedThumbnails.clear()
            synchronized(thumbnailOrderLock) { thumbnailOrder.clear() }
            Snapshot.withMutableSnapshot {
                pages.keys.filter { it != focusedPage }.forEach { page ->
                    pages.remove(page)
                    renderedWidths.remove(page)
                }
                thumbnails.clear()
            }
            _uiState.update { state ->
                state.copy(
                    pageTexts = emptyMap(),
                    textRuns = emptyMap(),
                    pageLinks = emptyMap(),
                    searchResults = emptyList(),
                    searchRunning = false,
                )
            }
        }
    }

    private fun restartDocumentScope() {
        documentJob.cancel()
        documentJob = SupervisorJob()
        documentScope = CoroutineScope(documentJob + Dispatchers.Default)
    }

    /**
     * A Compose viewModel keyed to a reader session remains in the Activity's
     * ViewModelStore after the reader leaves composition. Explicitly close its
     * native engines before opening the structured editor.
     */
    fun releaseDocument() {
        generation++
        loadedUri = null
        loadedRevision = Int.MIN_VALUE
        scrolling = false
        documentJob.cancel()
        searchJob = null
        clearQueue()
        textRequests.clear()
        linkRequests.clear()
        failedThumbnails.clear()
        synchronized(thumbnailOrderLock) { thumbnailOrder.clear() }
        Snapshot.withMutableSnapshot {
            pages.clear()
            thumbnails.clear()
            pageAspectRatios.clear()
        }
        renderedWidths.clear()
        if (::cache.isInitialized) cache.clear()
        _uiState.value = PdfReaderUiState(
            pages = pages,
            thumbnails = thumbnails,
            pageAspectRatios = pageAspectRatios,
        )
        repository.close()
    }

    override fun onCleared() {
        generation++
        wakeUp.close()
        documentJob.cancel()
        searchJob = null
        clearQueue()
        synchronized(thumbnailOrderLock) { thumbnailOrder.clear() }
        Snapshot.withMutableSnapshot { pages.clear(); thumbnails.clear(); pageAspectRatios.clear() }
        if (::cache.isInitialized) cache.clear()
        repository.close()
        applicationContext?.unregisterComponentCallbacks(memoryCallbacks)
        applicationContext = null
        super.onCleared()
    }

    class Factory(private val repository: PdfReaderRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PdfReaderViewModel(repository) as T
    }

    private companion object {
        /** How many pages around the current one stay mounted. */
        const val DISPLAY_DISTANCE = 2

        /** How many of those are rendered at full width. */
        const val FULL_QUALITY_DISTANCE = 0

        const val PREVIEW_WIDTH_PX = 420
        const val FALLBACK_PREVIEW_WIDTH_PX = 280
        const val THUMBNAIL_WIDTH_PX = 160
        const val CACHE_LOW_MEMORY_BYTES = 4 * 1024 * 1024
        const val FULL_RENDER_PRIORITY = 100
        const val IDLE_FULL_RENDER_PRIORITY = -100

        /** Worse than any page work, so the grid never delays the reader. */
        const val THUMBNAIL_PRIORITY = 10_000
        const val TEXT_CACHE_DISTANCE = 3
        const val MAX_UI_THUMBNAILS = 24
        const val MAX_SEARCH_RESULTS = 500
    }
}
