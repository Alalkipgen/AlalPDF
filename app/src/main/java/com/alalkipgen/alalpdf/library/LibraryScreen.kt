package com.alalkipgen.alalpdf.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alalkipgen.alalpdf.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    currentTheme: ThemeMode,
    onOpenPdf: () -> Unit,
    onOpenFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onCreatePdf: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onOpenDocument: (PdfDocument) -> Unit,
    onToggleFavorite: (PdfDocument) -> Unit,
    onRemoveFromList: (PdfDocument) -> Unit,
    onClearList: () -> Unit,
    onDelete: (PdfDocument) -> Unit,
    onRename: (PdfDocument, String) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var detailsFor by remember { mutableStateOf<PdfDocument?>(null) }
    var renameFor by remember { mutableStateOf<PdfDocument?>(null) }
    var deleteFor by remember { mutableStateOf<PdfDocument?>(null) }

    val documents = remember(state.documents, query) {
        if (query.isBlank()) state.documents else state.documents.filter { it.name.contains(query, ignoreCase = true) }
    }
    val favorites = documents.filter { it.favorite }
    val continueReading = documents.firstOrNull { !it.favorite && it.lastReadPage > 0 }
    val others = documents.filter { !it.favorite && it.uri != continueReading?.uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search PDFs") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Column {
                            Text("Alal PDF", style = MaterialTheme.typography.titleLarge)
                            Text("Offline PDF reader", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search PDFs")
                    }
                    Box {
                        IconButton(onClick = { themeMenuOpen = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "Theme settings")
                        }
                        DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (mode == currentTheme) "\u2713 " else "") +
                                                mode.name.lowercase().replaceFirstChar(Char::uppercase)
                                        )
                                    },
                                    onClick = { onThemeChange(mode); themeMenuOpen = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            LibrarySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (sort == state.sort) "\u2713 " else "") + "Sort by " +
                                                sort.name.lowercase()
                                        )
                                    },
                                    onClick = { onSortChange(sort); overflowOpen = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear list") },
                                onClick = { overflowOpen = false; onClearList() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreatePdf) {
                Icon(Icons.Default.Add, contentDescription = "Create PDF")
            }
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onScanDevice, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Scan this phone for PDFs")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenPdf, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
                OutlinedButton(onClick = onOpenFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Folder")
                }
            }
            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Scanning PDF files\u2026")
                    }
                    state.errorMessage != null -> Text(
                        state.errorMessage,
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    documents.isEmpty() -> EmptyState(Modifier.align(Alignment.Center), searching = query.isNotBlank())
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        if (favorites.isNotEmpty()) {
                            item(key = "fav-header") { SectionLabel("FAVORITES") }
                            items(favorites, key = { "fav-" + it.uri.toString() }) { document ->
                                DocumentRow(document, onOpenDocument, onToggleFavorite,
                                    { detailsFor = it }, { renameFor = it }, { deleteFor = it }, onRemoveFromList)
                            }
                        }
                        continueReading?.let { document ->
                            item(key = "continue-header") { SectionLabel("CONTINUE READING") }
                            item(key = "continue-" + document.uri) {
                                ContinueReadingCard(document) { onOpenDocument(document) }
                            }
                        }
                        if (others.isNotEmpty()) item(key = "files-header") { SectionLabel("PDF FILES") }
                        items(others, key = { it.uri.toString() }) { document ->
                            DocumentRow(document, onOpenDocument, onToggleFavorite,
                                { detailsFor = it }, { renameFor = it }, { deleteFor = it }, onRemoveFromList)
                        }
                    }
                }
            }
        }
    }

    detailsFor?.let { document ->
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            title = { Text("File details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(document.name, style = MaterialTheme.typography.titleSmall)
                    Text("Size: " + formatBytes(document.sizeBytes))
                    Text("Modified: " + formatDate(document.lastModified))
                    if (document.pageCount > 0) Text("Pages: " + document.pageCount)
                    Text("Last read page: " + (document.lastReadPage + 1))
                    Text(document.uri.toString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { detailsFor = null }) { Text("Close") } },
        )
    }

    renameFor?.let { document ->
        var newName by remember(document.uri) { mutableStateOf(document.name.removeSuffix(".pdf")) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("File name") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(document, newName.trim()); renameFor = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } },
        )
    }

    deleteFor?.let { document ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete file?") },
            text = { Text(document.name + " will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(document); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ContinueReadingCard(document: PdfDocument, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(document.uri, 52.dp, 66.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(formatBytes(document.sizeBytes) + " \u00b7 page " + (document.lastReadPage + 1),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                document.progress?.let { value ->
                    LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DocumentRow(
    document: PdfDocument,
    onOpen: (PdfDocument) -> Unit,
    onToggleFavorite: (PdfDocument) -> Unit,
    onDetails: (PdfDocument) -> Unit,
    onRename: (PdfDocument) -> Unit,
    onDelete: (PdfDocument) -> Unit,
    onRemoveFromList: (PdfDocument) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = { onOpen(document) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(document.uri, 44.dp, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(document.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(listOf(formatBytes(document.sizeBytes), formatDate(document.lastModified))
                    .filter { it.isNotBlank() }.joinToString(" \u00b7 "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                document.progress?.let { value ->
                    if (document.lastReadPage > 0) {
                        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            IconButton(onClick = { onToggleFavorite(document) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = if (document.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "File options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("File details") },
                        onClick = { menuOpen = false; onDetails(document) })
                    DropdownMenuItem(text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename(document) })
                    DropdownMenuItem(text = { Text("Remove from list") },
                        onClick = { menuOpen = false; onRemoveFromList(document) })
                    DropdownMenuItem(text = { Text("Delete file") },
                        onClick = { menuOpen = false; onDelete(document) })
                }
            }
        }
    }
}

@Composable private fun EmptyState(modifier: Modifier = Modifier, searching: Boolean) {
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.size(96.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (searching) "No matches" else "No PDFs yet", style = MaterialTheme.typography.titleMedium)
        Text(if (searching) "Try a different file name." else "Scan this phone, open a PDF or pick a folder.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}
