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
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository))
    val state by viewModel.uiState.collectAsState()
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { repository.persistReadPermission(it); viewModel.openDocument(it) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { repository.persistReadPermission(it); viewModel.openFolder(it) }
    }
    if (selectedUri == null) {
        LibraryScreen(state, { pdfLauncher.launch(arrayOf("application/pdf")) }, { folderLauncher.launch(null) }) { selectedUri = it.uri.toString() }
    } else {
        val readerViewModel: PdfReaderViewModel = viewModel(factory = PdfReaderViewModel.Factory(PdfReaderRepository(contentResolver)))
        val readerState by readerViewModel.uiState.collectAsState()
        val uri = android.net.Uri.parse(selectedUri)
        if (!repository.hasPersistedReadPermission(uri)) {
            Text("This PDF permission is no longer available. Please open it again.")
            return
        }
        val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 16.dp).roundToPx().coerceAtLeast(1) }
        androidx.compose.runtime.LaunchedEffect(uri) { readerViewModel.load(uri, width) }
        PdfReaderScreen(uri, readerState) { readerViewModel.render(uri, it, width) }
    }
}