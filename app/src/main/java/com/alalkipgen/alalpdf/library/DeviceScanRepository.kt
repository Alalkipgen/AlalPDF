package com.alalkipgen.alalpdf.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds every PDF on the device. MediaStore covers indexed files; a direct file
 * walk covers everything else once the user grants all-files access.
 */
class DeviceScanRepository(private val context: Context) {

    fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    suspend fun scan(): List<PdfDocument> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, PdfDocument>()
        runCatching { queryMediaStore(found) }
        runCatching {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.canRead()) walk(root, found, 0)
        }
        found.values.sortedWith(
            compareByDescending<PdfDocument> { it.lastModified }.thenBy { it.name.lowercase() }
        )
    }

    private fun queryMediaStore(found: MutableMap<String, PdfDocument>) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = MediaStore.Files.FileColumns.MIME_TYPE + " = ?"
        context.contentResolver.query(collection, projection, selection, arrayOf("application/pdf"), null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val modified = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) cursor.getLong(dateIndex) * 1000L else 0L
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val readable = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                    if (!readable) continue
                    val key = key(name, size)
                    if (!found.containsKey(key)) {
                        found[key] = PdfDocument(uri = uri, name = name, sizeBytes = size, lastModified = modified)
                    }
                }
            }
    }

    private fun walk(dir: File, found: MutableMap<String, PdfDocument>, depth: Int) {
        if (depth > 12) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "Android") continue
                walk(child, found, depth + 1)
            } else if (child.name.endsWith(".pdf", ignoreCase = true) && child.canRead()) {
                val key = key(child.name, child.length())
                if (!found.containsKey(key)) {
                    found[key] = PdfDocument(
                        uri = Uri.fromFile(child),
                        name = child.name,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                    )
                }
            }
        }
    }

    private fun key(name: String, size: Long): String = name.lowercase() + "|" + size
}
