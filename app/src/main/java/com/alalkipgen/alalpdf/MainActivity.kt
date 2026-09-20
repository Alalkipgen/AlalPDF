package com.alalkipgen.alalpdf

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alalkipgen.alalpdf.create.CreatePdfMode
import com.alalkipgen.alalpdf.create.CreatePdfScreen
import com.alalkipgen.alalpdf.create.CreatePdfRepository
import com.alalkipgen.alalpdf.create.PdfDraft
import com.alalkipgen.alalpdf.tools.PdfToolsScreen
import com.alalkipgen.alalpdf.data.AlalPdfDatabase
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import com.alalkipgen.alalpdf.library.DeviceScanRepository
import com.alalkipgen.alalpdf.library.FolderBrowserRoute
import com.alalkipgen.alalpdf.library.LibraryPrefs
import com.alalkipgen.alalpdf.library.LibraryScreen
import com.alalkipgen.alalpdf.library.LibraryViewModel
import com.alalkipgen.alalpdf.library.PdfLibraryRepository
import com.alalkipgen.alalpdf.library.RecentDocumentsStore
import com.alalkipgen.alalpdf.reader.PdfPrinter
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
private const val SCREEN_TOOLS = "tools"

@Composable
private fun AppRoot(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    incomingPdf: Uri?,
    onIncomingPdfConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val appScope = rememberCoroutineScope()
    val libraryRepository = remember(appContext) { PdfLibraryRepository(appContext) }
    val deviceScan = remember(appContext) { DeviceScanRepository(appContext) }
    val prefs = remember(appContext) { LibraryPrefs(appContext) }
    val dataRepository = remember(appContext) { AlalPdfRepository(AlalPdfDatabase.create(appContext).dao()) }
    val recentStore = remember(dataRepository) { RecentDocumentsStore(dataRepository) }
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(libraryRepository, recentStore, prefs, deviceScan)
    )
    val state by viewModel.uiState.collectAsState()
    val libraryListState = rememberLazyListState()

    var screen by rememberSaveable { mutableStateOf(SCREEN_LIBRARY) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var createMode by rememberSaveable { mutableStateOf<String?>(null) }
    var draftMode by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftHtml by rememberSaveable { mutableStateOf("") }
    var draftImages by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var createSession by rememberSaveable { mutableStateOf(0) }

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
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (deviceScan.hasStorageAccess()) viewModel.scanDevice()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.scanDevice()
    }

    fun requestDeviceScan() {
        when {
            deviceScan.hasStorageAccess() -> viewModel.scanDevice()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val settings = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + appContext.packageName),
                )
                runCatching { allFilesLauncher.launch(settings) }.onFailure {
                    runCatching {
                        allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
            else -> permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    when (screen) {
        SCREEN_CREATE -> {
            BackHandler { screen = SCREEN_LIBRARY; createMode = null }
            key(createSession) { CreatePdfScreen(
                onBack = { screen = SCREEN_LIBRARY; createMode = null },
                onCreated = { uri ->
                    viewModel.openDocument(uri)
                    selectedUri = uri.toString()
                    createMode = null
                    draftMode = null
                    draftTitle = ""
                    draftHtml = ""
                    draftImages = arrayListOf()
                    createSession++
                    screen = SCREEN_READER
                },
                initialMode = createMode?.let { runCatching { CreatePdfMode.valueOf(it) }.getOrNull() },
                initialDraft = draftMode?.let { PdfDraft(CreatePdfMode.valueOf(it),draftTitle,draftHtml,draftImages) },
            ) }
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
        SCREEN_TOOLS -> { val current=selectedUri;if(current==null)screen=SCREEN_LIBRARY else PdfToolsScreen(Uri.parse(current),{screen=SCREEN_READER}){saved->viewModel.openDocument(saved);selectedUri=saved.toString();screen=SCREEN_READER} }
        SCREEN_READER -> {
            val current = selectedUri
            if (current == null) screen = SCREEN_LIBRARY else ReaderRoute(
                uri = Uri.parse(current),
                libraryRepository = libraryRepository,
                dataRepository = dataRepository,
                prefs = prefs,
                onBack = { screen = SCREEN_LIBRARY; selectedUri = null },
                onEdit = { appScope.launch { val d=runCatching{CreatePdfRepository(appContext).readDraft(Uri.parse(current))}.getOrNull();if(d!=null){draftMode=d.mode.name;draftTitle=d.title;draftHtml=d.bodyHtml;draftImages=ArrayList(d.images);createMode=d.mode.name;createSession++;screen=SCREEN_CREATE}else screen=SCREEN_TOOLS } },
            )
        }
        else -> LibraryScreen(
            state = state,
            listState = libraryListState,
            currentTheme = currentTheme,
            onOpenPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
            onOpenFolder = { folderLauncher.launch(null) },
            onScanDevice = { requestDeviceScan() },
            onCreatePdf = { mode -> draftMode=null;draftTitle="";draftHtml="";draftImages=arrayListOf();createMode=mode?.name;createSession++;screen=SCREEN_CREATE },
            onThemeChange = onThemeChange,
            onSortChange = viewModel::setSort,
            onOpenDocument = { document ->
                viewModel.remember(document)
                selectedUri = document.uri.toString()
                screen = SCREEN_READER
            },
            onToggleFavorite = viewModel::toggleFavorite,
            onRemoveFromList = viewModel::removeFromList,
            onClearList = viewModel::clearList,
            onDelete = viewModel::delete,
            onRename = { document, newName -> viewModel.rename(document, newName) },
        )
    }
}

@Composable
private fun ReaderRoute(
    uri: Uri,
    libraryRepository: PdfLibraryRepository,
    dataRepository: AlalPdfRepository,
    prefs: LibraryPrefs,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    if (!libraryRepository.canRead(uri)) {
        Text("This PDF permission is no longer available. Please open it again.")
        return
    }

    val readerViewModel: PdfReaderViewModel = viewModel(
        key = "reader-$uri",
        factory = PdfReaderViewModel.Factory(PdfReaderRepository(appContext)),
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
    var title by rememberSaveable(uri.toString()) { mutableStateOf("Document") }
    var currentPage by rememberSaveable(uri.toString()) { mutableStateOf(0) }
    var positionLoaded by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var pendingPage by rememberSaveable(uri.toString()) { mutableStateOf<Int?>(null) }
    var showBookmarks by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    var password by rememberSaveable(uri.toString()) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        runCatching { libraryRepository.inspect(uri).name }.getOrNull()?.let { title = it }
        if (!positionLoaded) {
            val page = progressStore.page(uri)
            currentPage = page
            pendingPage = page
            positionLoaded = true
        }
    }
    LaunchedEffect(uri, width, positionLoaded) {
        if (positionLoaded) readerViewModel.load(uri, width, currentPage, nightMode,password)
    }
    LaunchedEffect(readerState.pageCount) {
        if (readerState.pageCount > 0) prefs.setPageCount(uri.toString(), readerState.pageCount)
    }

    BackHandler(enabled = !showBookmarks) { onBack() }
    PdfReaderScreen(
        state = readerState,
        title = title,
    initialPage = currentPage,
        nightMode = nightMode,
        pendingPage = pendingPage,
        onPendingPageConsumed = { pendingPage = null },
        onBack = onBack,
    onNightModeChange = { nightMode = it },
        onShare = {
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"))
            }
        },
        onPrint = { PdfPrinter.print(context, uri, title) },
        onOpenBookmarks = { showBookmarks = true },
        onAddBookmark = { bookmarksViewModel.add(currentPage) },
        onPageSelected = { page ->
            currentPage = page
            scope.launch { progressStore.save(uri, page) }
            readerViewModel.renderWindow(uri, page, width, nightMode)
        },
        onRender = { page -> readerViewModel.requestPage(uri, page, width) },
        onPasswordSubmit = { value -> password=value;readerViewModel.load(uri,width,currentPage,nightMode,value) },
        onEditPdf = onEdit,
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
