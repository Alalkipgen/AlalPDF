package com.alalkipgen.alalpdf.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun LibraryScreen(state: LibraryUiState, onOpenPdf: () -> Unit, onOpenFolder: () -> Unit, onOpenDocument: (PdfDocument) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Alal PDF", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onOpenPdf, modifier = Modifier.fillMaxWidth()) { Text("Open PDF") }
        Button(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Choose PDF folder") }
        when {
            state.isLoading -> CircularProgressIndicator()
            state.errorMessage != null -> Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            state.documents.isEmpty() -> Text("No recent PDF files.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.documents, key = { it.uri.toString() }) { document ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(document.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatBytes(document.sizeBytes), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onOpenDocument(document) }) { Text("Read") }
                        HorizontalDivider(Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}