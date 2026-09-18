package com.alalkipgen.alalpdf.reader

import android.net.Uri
import com.alalkipgen.alalpdf.data.AlalPdfRepository

class ReadingProgressStore(private val repository: AlalPdfRepository) {
    suspend fun page(uri: Uri): Int = repository.page(uri)
    suspend fun save(uri: Uri, page: Int) = repository.savePage(uri, page)
}