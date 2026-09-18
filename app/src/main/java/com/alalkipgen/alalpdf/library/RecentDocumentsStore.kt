package com.alalkipgen.alalpdf.library

import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecentDocumentsStore(private val repository: AlalPdfRepository) {
    val recent: Flow<List<PdfDocument>> = repository.recent().map { list -> list.map { PdfDocument(Uri.parse(it.uri), it.displayName, it.sizeBytes, it.lastModified) } }
    suspend fun add(document: PdfDocument) = repository.remember(document)
}