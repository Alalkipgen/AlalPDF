package com.alalkipgen.alalpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.alalkipgen.alalpdf.ui.theme.AlalPdfTheme
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AlalPdfTheme { LibraryRoute() } } }
}

@Composable private fun LibraryRoute() {
    val repository = PdfLibraryRepository(androidx.compose.ui.platform.LocalContext.current.applicationContext)
    val contentResolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val recentStore = RecentDocumentsStore(androidx.compose.ui.platform.LocalContext.current.applicationContext)
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository, recentStore))
    val state by viewModel.uiState.collectAsState()
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadRecent() }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { repository.persistReadPermission(it); viewModel.openDocument(it) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { repository.persistReadPermission(it); viewModel.openFolder(it) }
    }
    if (selectedUri == null) {
        LibraryScreen(state, { pdfLauncher.launch(arrayOf("application/pdf")) }, { folderLauncher.launch(null) }) { viewModel.remember(it); selectedUri = it.uri.toString() }
    } else {
        val readerViewModel: PdfReaderViewModel = viewModel(factory = PdfReaderViewModel.Factory(PdfReaderRepository(contentResolver)))
        readerViewModel.initialize(androidx.compose.ui.platform.LocalContext.current.applicationContext)
        val readerState by readerViewModel.uiState.collectAsState()
        val uri = android.net.Uri.parse(selectedUri)
        val progressStore = ReadingProgressStore(androidx.compose.ui.platform.LocalContext.current.applicationContext)
        if (!repository.hasPersistedReadPermission(uri)) {
            Text("This PDF permission is no longer available. Please open it again.")
            return
        }
        val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 16.dp).roundToPx().coerceAtLeast(1) }
        androidx.compose.runtime.LaunchedEffect(uri) { readerViewModel.load(uri, width, progressStore.page(uri)) }
        PdfReaderScreen(uri, readerState, initialPage = progressStore.page(uri)) { page -> progressStore.save(uri, page); readerViewModel.render(uri, page, width) }
    }
}