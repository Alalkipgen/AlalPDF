package com.alalkipgen.alalpdf.reader

import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ReadingProgressStore(private val repository: AlalPdfRepository) {
    suspend fun page(uri: Uri): Int = repository.page(uri)
    suspend fun save(uri: Uri, page: Int) = withContext(NonCancellable) {
        repository.savePage(uri, page)
    }
}