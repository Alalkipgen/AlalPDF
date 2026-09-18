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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    onCreatePdf: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenDocument: (PdfDocument) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val documents = remember(state.documents, query) {
        if (query.isBlank()) state.documents else state.documents.filter { it.name.contains(query, ignoreCase = true) }
    }
    val continueReading = documents.firstOrNull { it.lastReadPage > 0 }
    val recent = documents.filter { it.uri != continueReading?.uri }

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
                                            (if (mode == currentTheme) "✓ " else "") +
                                                mode.name.lowercase().replaceFirstChar(Char::uppercase)
                                        )
                                    },
                                    onClick = { onThemeChange(mode); themeMenuOpen = false },
                                )
                            }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onOpenPdf, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Open a PDF")
            }
            OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Scan PDF folder")
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Scanning PDF files…")
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
                        continueReading?.let { document ->
                            item(key = "continue-header") { SectionLabel("CONTINUE READING") }
                            item(key = "continue-${document.uri}") {
                                ContinueReadingCard(document) { onOpenDocument(document) }
                            }
                        }
                        if (recent.isNotEmpty()) item(key = "files-header") { SectionLabel("PDF FILES") }
                        items(recent, key = { it.uri.toString() }) { document ->
                            DocumentRow(document) { onOpenDocument(document) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable internal fun PdfBadge(modifier: Modifier = Modifier) {
    Surface(modifier.size(width = 48.dp, height = 60.dp), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text("PDF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ContinueReadingCard(document: PdfDocument, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PdfBadge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("${formatBytes(document.sizeBytes)} · page ${document.lastReadPage + 1}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DocumentRow(document: PdfDocument, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PdfBadge(Modifier.size(width = 36.dp, height = 46.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(document.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(listOf(formatBytes(document.sizeBytes), formatDate(document.lastModified))
                    .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(if (searching) "Try a different file name." else "Open a PDF or scan a folder to get started.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}
