package com.alalkipgen.alalpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alalkipgen.alalpdf.create.CreatePdfScreen
import com.alalkipgen.alalpdf.data.AlalPdfDatabase
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import com.alalkipgen.alalpdf.library.FolderBrowserRoute
import com.alalkipgen.alalpdf.library.LibraryScreen
import com.alalkipgen.alalpdf.library.LibraryViewModel
import com.alalkipgen.alalpdf.library.PdfLibraryRepository
import com.alalkipgen.alalpdf.library.RecentDocumentsStore
import com.alalkipgen.alalpdf.reader.PdfReaderRepository
import com.alalkipgen.alalpdf.reader.PdfReaderScreen
import com.alalkipgen.alalpdf.reader.PdfReaderViewModel
import com.alalkipgen.alalpdf.reader.ReadingProgressStore
import com.alalkipgen.alalpdf.reader.bookmark.BookmarksScreen
import com.alalkipgen.alalpdf.reader.bookmark.BookmarksViewModel
import com.alalkipgen.alalpdf.ui.theme.AlalPdfTheme
import com.alalkipgen.alalpdf.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var incomingPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPdf = intent.pdfUri()
        setContent {
            var mode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM.name) }
            val theme = ThemeMode.valueOf(mode)
            AlalPdfTheme(theme) {
                AppRoot(
                    currentTheme = theme,
                    onThemeChange = { mode = it.name },
                    incomingPdf = incomingPdf,
                    onIncomingPdfConsumed = { incomingPdf = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPdf = intent.pdfUri()
    }
}

private fun Intent.pdfUri(): Uri? {
    if (action != Intent.ACTION_VIEW) return null
    val uri = data ?: return null
    val looksLikePdf = type == "application/pdf" || uri.lastPathSegment?.endsWith(".pdf", true) == true
    return uri.takeIf { looksLikePdf }
}

private const val SCREEN_LIBRARY = "library"
private const val SCREEN_FOLDER = "folder"
private const val SCREEN_READER = "reader"
private const val SCREEN_CREATE = "create"

@Composable
private fun AppRoot(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    incomingPdf: Uri?,
    onIncomingPdfConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val libraryRepository = remember(appContext) { PdfLibraryRepository(appContext) }
    val dataRepository = remember(appContext) { AlalPdfRepository(AlalPdfDatabase.create(appContext).dao()) }
    val recentStore = remember(dataRepository) { RecentDocumentsStore(dataRepository) }
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(libraryRepository, recentStore))
    val state by viewModel.uiState.collectAsState()

    var screen by rememberSaveable { mutableStateOf(SCREEN_LIBRARY) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadRecent() }
    LaunchedEffect(incomingPdf) {
        incomingPdf?.let { uri ->
            libraryRepository.persistReadPermission(uri)
            runCatching { libraryRepository.inspect(uri) }.onSuccess(viewModel::remember)
            selectedUri = uri.toString()
            screen = SCREEN_READER
            onIncomingPdfConsumed()
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            libraryRepository.persistReadPermission(it)
            viewModel.openDocument(it)
            selectedUri = it.toString()
            screen = SCREEN_READER
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            libraryRepository.persistReadPermission(it)
            folderUri = it.toString()
            screen = SCREEN_FOLDER
        }
    }

    when (screen) {
        SCREEN_CREATE -> {
            BackHandler { screen = SCREEN_LIBRARY }
            CreatePdfScreen(
                onBack = { screen = SCREEN_LIBRARY },
                onCreated = { uri ->
                    viewModel.openDocument(uri)
                    selectedUri = uri.toString()
                    screen = SCREEN_READER
                },
            )
        }
        SCREEN_FOLDER -> {
            val tree = folderUri
            if (tree == null) screen = SCREEN_LIBRARY else FolderBrowserRoute(
                treeUri = Uri.parse(tree),
                onBack = { screen = SCREEN_LIBRARY },
                onUseFolder = { viewModel.openFolder(Uri.parse(tree)); screen = SCREEN_LIBRARY },
                onOpenFile = { document ->
                    viewModel.remember(document)
                    selectedUri = document.uri.toString()
                    screen = SCREEN_READER
                },
            )
        }
        SCREEN_READER -> {
            val current = selectedUri
            if (current == null) screen = SCREEN_LIBRARY else ReaderRoute(
                uri = Uri.parse(current),
                libraryRepository = libraryRepository,
                dataRepository = dataRepository,
                onBack = { screen = SCREEN_LIBRARY; selectedUri = null },
            )
        }
        else -> LibraryScreen(
            state = state,
            currentTheme = currentTheme,
            onOpenPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
            onOpenFolder = { folderLauncher.launch(null) },
            onCreatePdf = { screen = SCREEN_CREATE },
            onThemeChange = onThemeChange,
            onOpenDocument = { document ->
                viewModel.remember(document)
                selectedUri = document.uri.toString()
                screen = SCREEN_READER
            },
        )
    }
}

@Composable
private fun ReaderRoute(uri: Uri, libraryRepository: PdfLibraryRepository, dataRepository: AlalPdfRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    if (!libraryRepository.canRead(uri)) {
        Text("This PDF permission is no longer available. Please open it again.")
        return
    }

    val readerViewModel: PdfReaderViewModel = viewModel(
        key = "reader-$uri",
        factory = PdfReaderViewModel.Factory(PdfReaderRepository(context.contentResolver)),
    )
    readerViewModel.initialize(appContext)
    val readerState by readerViewModel.uiState.collectAsState()
    val progressStore = remember(dataRepository) { ReadingProgressStore(dataRepository) }
    val bookmarksViewModel: BookmarksViewModel = viewModel(
        key = "bookmarks-$uri",
        factory = BookmarksViewModel.Factory(dataRepository, uri),
    )
    val bookmarks by bookmarksViewModel.bookmarks.collectAsState()
    val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 16.dp).roundToPx().coerceAtLeast(1) }
    var nightMode by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var title by remember(uri) { mutableStateOf("Document") }
    var initialPage by remember(uri) { mutableStateOf<Int?>(null) }
    var currentPage by remember(uri) { mutableStateOf(0) }
    var pendingPage by remember(uri) { mutableStateOf<Int?>(null) }
    var showBookmarks by rememberSaveable(uri.toString()) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        runCatching { libraryRepository.inspect(uri).name }.getOrNull()?.let { title = it }
        val page = progressStore.page(uri)
        currentPage = page
        initialPage = page
        readerViewModel.load(uri, width, page, nightMode)
    }

    initialPage?.let { startPage ->
        BackHandler(enabled = !showBookmarks) { onBack() }
        PdfReaderScreen(
            state = readerState,
            title = title,
            initialPage = startPage,
            nightMode = nightMode,
            pendingPage = pendingPage,
            onPendingPageConsumed = { pendingPage = null },
            onBack = onBack,
            onNightModeChange = { nightMode = it; readerViewModel.load(uri, width, currentPage, nightMode = it) },
            onShare = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"))
            },
            onOpenBookmarks = { showBookmarks = true },
            onAddBookmark = { bookmarksViewModel.add(currentPage) },
            onPageSelected = { page ->
                currentPage = page
                scope.launch { progressStore.save(uri, page) }
                readerViewModel.render(uri, page, width, nightMode)
            },
            onRender = { page -> readerViewModel.render(uri, page, width, nightMode) },
        )
        if (showBookmarks) {
            BackHandler { showBookmarks = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BookmarksScreen(
                    bookmarks = bookmarks,
                    onBack = { showBookmarks = false },
                    onOpenPage = { page -> pendingPage = page; showBookmarks = false },
                    onDelete = bookmarksViewModel::delete,
                    onAddCurrentPage = { bookmarksViewModel.add(currentPage) },
                )
            }
        }
    }
}
