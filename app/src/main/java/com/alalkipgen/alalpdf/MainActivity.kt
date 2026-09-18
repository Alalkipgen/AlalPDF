package com.alalkipgen.alalpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.alalkipgen.alalpdf.ui.theme.AlalPdfTheme
import com.alalkipgen.alalpdf.ui.theme.ThemeMode
import com.alalkipgen.alalpdf.library.LibraryScreen
import com.alalkipgen.alalpdf.library.LibraryViewModel
import com.alalkipgen.alalpdf.library.PdfLibraryRepository
import com.alalkipgen.alalpdf.library.RecentDocumentsStore
import com.alalkipgen.alalpdf.reader.ReadingProgressStore
import com.alalkipgen.alalpdf.reader.PdfReaderRepository
import com.alalkipgen.alalpdf.reader.PdfReaderScreen
import com.alalkipgen.alalpdf.reader.PdfReaderViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.alalkipgen.alalpdf.data.AlalPdfDatabase
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.launch
import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { var mode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM.name) }; AlalPdfTheme(ThemeMode.valueOf(mode)) { LibraryRoute(onThemeChange = { mode = it.name }) } } }
}

@Composable private fun LibraryRoute(onThemeChange: (ThemeMode) -> Unit) {
    val repository = PdfLibraryRepository(androidx.compose.ui.platform.LocalContext.current.applicationContext)
    val context = androidx.compose.ui.platform.LocalContext.current
    val contentResolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val dataRepository = remember { AlalPdfRepository(AlalPdfDatabase.create(androidx.compose.ui.platform.LocalContext.current.applicationContext).dao()) }
    val recentStore = remember { RecentDocumentsStore(dataRepository) }
    val scope = rememberCoroutineScope()
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository, recentStore))
    val state by viewModel.uiState.collectAsState()
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadRecent() }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            repository.persistReadPermission(it)
            viewModel.openDocument(it)
            selectedUri = it.toString()
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { repository.persistReadPermission(it); viewModel.openFolder(it) }
    }
    if (selectedUri == null) {
        LibraryScreen(state, { pdfLauncher.launch(arrayOf("application/pdf")) }, { folderLauncher.launch(null) }, onThemeChange) { viewModel.remember(it); selectedUri = it.uri.toString() }
    } else {
        val readerViewModel: PdfReaderViewModel = viewModel(factory = PdfReaderViewModel.Factory(PdfReaderRepository(contentResolver)))
        readerViewModel.initialize(androidx.compose.ui.platform.LocalContext.current.applicationContext)
        val readerState by readerViewModel.uiState.collectAsState()
        val uri = android.net.Uri.parse(selectedUri)
        val progressStore = remember { ReadingProgressStore(dataRepository) }
        if (!repository.hasPersistedReadPermission(uri)) {
            Text("This PDF permission is no longer available. Please open it again.")
            return
        }
        val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 16.dp).roundToPx().coerceAtLeast(1) }
        var nightMode by rememberSaveable(uri.toString()) { mutableStateOf(false) }
        var initialPage by remember(uri) { mutableStateOf<Int?>(null) }
        androidx.compose.runtime.LaunchedEffect(uri) {
            initialPage = progressStore.page(uri)
            readerViewModel.load(uri, width, initialPage ?: 0, nightMode)
        }
        initialPage?.let { page ->
            PdfReaderScreen(readerState, initialPage = page, nightMode = nightMode, onNightModeChange = { nightMode = it; readerViewModel.load(uri, width, page, nightMode = it) }, onShare = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share PDF"))
            }, onPageSelected = { visiblePage ->
                scope.launch { progressStore.save(uri, visiblePage) }
                readerViewModel.render(uri, visiblePage, width, nightMode)
            }) { page -> readerViewModel.render(uri, page, width, nightMode) }
        }
    }
}
