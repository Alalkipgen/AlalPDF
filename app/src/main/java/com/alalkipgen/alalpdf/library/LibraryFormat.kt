package com.alalkipgen.alalpdf.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

fun formatDate(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
