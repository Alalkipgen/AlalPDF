package com.alalkipgen.alalpdf.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FolderBrowserRoute(
    treeUri: Uri,
    onBack: () -> Unit,
    onUseFolder: () -> Unit,
    onOpenFile: (PdfDocument) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val browserViewModel: FolderBrowserViewModel = viewModel(
        key = treeUri.toString(),
        factory = FolderBrowserViewModel.Factory(appContext),
    )
    LaunchedEffect(treeUri) { browserViewModel.openRoot(treeUri) }
    val state by browserViewModel.uiState.collectAsState()
    val goBack: () -> Unit = { if (!browserViewModel.back()) onBack() }
    BackHandler { goBack() }
    FolderBrowserScreen(
        state = state,
        onBack = goBack,
        onEntry = { entry ->
            if (entry.isDirectory) {
                browserViewModel.enter(entry)
            } else {
                onOpenFile(PdfDocument(entry.uri, entry.name, entry.sizeBytes, entry.lastModified))
            }
        },
        onSort = browserViewModel::setSort,
        onToggleGrid = browserViewModel::toggleGrid,
        onUseFolder = onUseFolder,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    state: FolderBrowserUiState,
    onBack: () -> Unit,
    onEntry: (FolderEntry) -> Unit,
    onSort: (FolderSort) -> Unit,
    onToggleGrid: () -> Unit,
    onUseFolder: () -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "Folder" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("${state.pdfCount} PDF files", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            FolderSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                    onClick = { sortOpen = false; onSort(option) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onToggleGrid) {
                        Icon(
                            if (state.gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle layout",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onUseFolder,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text("Use this folder") }
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            if (state.breadcrumbs.isNotEmpty()) {
                Text(
                    state.breadcrumbs.joinToString(" › "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.errorMessage != null -> Text(
                        state.errorMessage,
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    state.entries.isEmpty() -> Text(
                        "No PDF files in this folder.",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.gridMode -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.entries, key = { it.uri.toString() }) { entry ->
                            EntryCard(entry) { onEntry(entry) }
                        }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.entries, key = { it.uri.toString() }) { entry ->
                            EntryCard(entry) { onEntry(entry) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EntryCard(entry: FolderEntry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.isDirectory) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                PdfBadge(Modifier.size(width = 36.dp, height = 46.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDirectory) {
                    Text(
                        listOf(formatBytes(entry.sizeBytes), formatDate(entry.lastModified))
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
