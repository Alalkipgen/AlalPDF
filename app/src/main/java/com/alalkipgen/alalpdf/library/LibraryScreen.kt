package com.alalkipgen.alalpdf.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alalkipgen.alalpdf.create.CreatePdfMode
import com.alalkipgen.alalpdf.ui.theme.ThemeMode
import kotlin.math.roundToInt

private enum class LibraryFilter(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
    SCANNED("Scanned"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    currentTheme: ThemeMode,
    onOpenPdf: () -> Unit,
    onOpenFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onCreatePdf: (CreatePdfMode?) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onOpenDocument: (PdfDocument) -> Unit,
    onToggleFavorite: (PdfDocument) -> Unit,
    onRemoveFromList: (PdfDocument) -> Unit,
    onClearList: () -> Unit,
    onDelete: (PdfDocument) -> Unit,
    onRename: (PdfDocument, String) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var detailsFor by remember { mutableStateOf<PdfDocument?>(null) }
    var renameFor by remember { mutableStateOf<PdfDocument?>(null) }
    var deleteFor by remember { mutableStateOf<PdfDocument?>(null) }

    val documents = remember(state.documents, query, filter) {
        state.documents
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { document ->
                when (filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.RECENT -> document.lastReadPage > 0
                    LibraryFilter.FAVORITES -> document.favorite
                    LibraryFilter.SCANNED -> document.name.contains("scan", ignoreCase = true)
                }
            }
    }

    Scaffold(
        floatingActionButton = {
            CreateFabMenu(
                expanded = createMenuOpen,
                onExpandedChange = { createMenuOpen = it },
                onPick = { mode -> createMenuOpen = false; onCreatePdf(mode) },
            )
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search PDFs") },
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { sortMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Sort files")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        LibrarySort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text("Sort by " + sort.name.lowercase())
                                },
                                leadingIcon = {
                                    if (sort == state.sort) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                onClick = { onSortChange(sort); sortMenuOpen = false },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pick folder") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = { overflowOpen = false; onOpenFolder() },
                        )
                        HorizontalDivider()
                        ThemeMode.entries.forEach { themeMode ->
                            DropdownMenuItem(
                                text = { Text(themeMode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                leadingIcon = {
                                    if (themeMode == currentTheme) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = { onThemeChange(themeMode); overflowOpen = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Clear list") },
                            onClick = { overflowOpen = false; onClearList() },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { entry ->
                    FilterChip(
                        selected = filter == entry,
                        onClick = { filter = entry },
                        label = { Text(entry.label) },
                        leadingIcon = {
                            if (filter == entry) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                }
            }

            if (filter == LibraryFilter.ALL && query.isBlank()) {
                // Two equally useful entry points, side by side: scanning the
                // whole phone and opening a single file with the system picker.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickActionCard(
                        title = "Scan this phone",
                        subtitle = "Find every PDF",
                        icon = Icons.Default.PhoneAndroid,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = onScanDevice,
                    )
                    QuickActionCard(
                        title = "Open PDF",
                        subtitle = "Pick a file",
                        icon = Icons.Default.Description,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = onOpenPdf,
                    )
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
                    documents.isEmpty() -> EmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        searching = query.isNotBlank() || filter != LibraryFilter.ALL,
                        onScanDevice = onScanDevice,
                        onOpenPdf = onOpenPdf,
                    )
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 110.dp),
                    ) {
                        item(key = "files-header") { SectionLabel("PDF FILES") }
                        items(documents, key = { it.uri.toString() }) { document ->
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
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("File name") }) },
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

/** Material 3 style FAB menu: the create modes fan out above the button. */
@Composable
private fun CreateFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (CreatePdfMode) -> Unit,
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FabMenuItem("Scan PDF", Icons.Default.DocumentScanner) { onPick(CreatePdfMode.SCAN) }
                FabMenuItem("Text + Image", Icons.Default.NoteAdd) { onPick(CreatePdfMode.IMAGE_TEXT) }
                FabMenuItem("Image PDF", Icons.Default.PhotoLibrary) { onPick(CreatePdfMode.IMAGES) }
                FabMenuItem("Text PDF", Icons.Default.TextFields) { onPick(CreatePdfMode.TEXT) }
            }
        }
        if (expanded) {
            FloatingActionButton(onClick = { onExpandedChange(false) }) {
                Icon(Icons.Default.Close, contentDescription = "Close create menu")
            }
        } else {
            ExtendedFloatingActionButton(
                onClick = { onExpandedChange(true) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Create PDF") },
            )
        }
    }
}

@Composable
private fun FabMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.height(48.dp).clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    container: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(Modifier.size(40.dp), shape = CircleShape, color = accent) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.surface)
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
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
    Card(
        onClick = { onOpen(document) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(document.uri, 48.dp, 60.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(document.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        formatBytes(document.sizeBytes).takeIf { it.isNotBlank() },
                        (document.pageCount.toString() + " pages").takeIf { document.pageCount > 0 },
                        formatDate(document.lastModified).takeIf { it.isNotBlank() },
                    ).joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                document.progress?.let { value ->
                    if (document.lastReadPage > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { value },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (value * 100).roundToInt().toString() + "%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { onToggleFavorite(document) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (document.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (document.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
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

@Composable private fun EmptyState(
    modifier: Modifier = Modifier,
    searching: Boolean,
    onScanDevice: () -> Unit,
    onOpenPdf: () -> Unit,
) {
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
        Text(
            if (searching) "Try a different name or filter." else "Scan this phone to find every PDF you already have.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!searching) {
            Spacer(Modifier.height(4.dp))
            ExtendedFloatingActionButton(
                onClick = onScanDevice,
                icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                text = { Text("Scan this phone") },
            )
            TextButton(onClick = onOpenPdf) { Text("Open a file") }
        }
    }
}
