package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The one PDFBox document of an open reader session.
 *
 * Loading PDFBox from a content stream uses `MemoryUsageSetting.setupMainMemoryOnly()`,
 * so the whole file sat in the Java heap, and the reader parsed it again for
 * the link index and again for the stored-text metadata. This holder copies
 * the content once to `cacheDir/pdfbox/<random>.pdf` (or reuses the unlocked
 * copy the session already made for an encrypted file), loads it on first use
 * with a temp-file-only scratch buffer and keeps it until the session closes.
 *
 * PDFBox is not thread-safe, so every access runs inside [read] under one lock
 * per document. [trim] and [close] never wait for a running [read]: the thread
 * that holds the lock finishes them on its way out, so a memory callback on
 * the main thread cannot block behind a long page.
 */
internal class PdfBoxDocument(
    private val cacheDir: File,
    private val password: String?,
    /** A file owned by someone else (the session's unlocked copy); never deleted here. */
    private val existingFile: File?,
    private val openSource: () -> InputStream?,
    private val initResources: () -> Unit,
) : Closeable {
    private val lock = ReentrantLock()

    @Volatile
    private var closed = false

    @Volatile
    private var trimRequested = false

    // Guarded by [lock].
    private var document: PDDocument? = null
    private var copy: File? = null
    private var failure: Throwable? = null

    val isClosed: Boolean
        get() = closed

    /**
     * Runs [block] with the loaded document while holding the document lock.
     *
     * A load failure (wrong password, corrupt file) is kept and rethrown on
     * every later call, so each caller keeps its own fallback and a broken
     * file is not copied and parsed again.
     */
    fun <T> read(block: (PDDocument) -> T): T {
        lock.lock()
        try {
            check(!closed) { "PDF session is closed" }
            return block(document ?: load())
        } finally {
            lock.unlock()
            if (closed || trimRequested) drain()
        }
    }

    /** Releases the parsed document under memory pressure. The copy stays for the next [read]. */
    fun trim() {
        trimRequested = true
        drain()
    }

    /** Closes the document and deletes the copy, now or as soon as the running [read] returns. */
    override fun close() {
        closed = true
        drain()
    }

    private fun drain() {
        // When the lock is busy, the thread holding it drains on its way out of read().
        if (!lock.tryLock()) return
        try {
            // A nested read() must not close the document under the outer one.
            if (lock.holdCount > 1) return
            if (closed) release() else if (trimRequested) closeDocument()
        } finally {
            lock.unlock()
        }
    }

    private fun load(): PDDocument {
        failure?.let { throw it }
        try {
            initResources()
            val file = existingFile ?: copy ?: copySource().also { copy = it }
            val scratch = File(cacheDir, TEMP_DIR).apply { mkdirs() }
            return PDDocument.load(
                file,
                password.orEmpty(),
                MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch),
            ).also { document = it }
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    private fun copySource(): File {
        val directory = File(cacheDir, COPY_DIR).apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.pdf")
        liveFiles += target.absolutePath
        try {
            val input = openSource() ?: error("Unable to read PDF")
            input.use { source -> target.outputStream().use { source.copyTo(it, COPY_BUFFER_BYTES) } }
            return target
        } catch (error: Throwable) {
            target.delete()
            liveFiles -= target.absolutePath
            throw error
        }
    }

    private fun closeDocument() {
        trimRequested = false
        val current = document ?: return
        document = null
        runCatching { current.close() }
    }

    private fun release() {
        closeDocument()
        copy?.let { file ->
            runCatching { file.delete() }
            liveFiles -= file.absolutePath
        }
        copy = null
    }

    companion object {
        private const val COPY_DIR = "pdfbox"
        private const val TEMP_DIR = "pdfbox-tmp"
        private const val DIR_PREFIX = "pdfbox"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val STALE_AFTER_MS = TimeUnit.HOURS.toMillis(24)

        /** Copies that an open holder may still reopen after [trim]; never treated as stale. */
        private val liveFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var resourcesReady = false

        fun forSession(
            context: Context,
            uri: Uri,
            password: String?,
            unlockedFile: File?,
        ): PdfBoxDocument = PdfBoxDocument(
            cacheDir = context.cacheDir,
            password = password,
            existingFile = unlockedFile,
            openSource = { context.contentResolver.openInputStream(uri) },
            initResources = { initResources(context) },
        )

        /** PdfBox-Android needs the asset manager once per process. */
        fun initResources(context: Context) {
            if (resourcesReady) return
            synchronized(this) {
                if (!resourcesReady) {
                    PDFBoxResourceLoader.init(context)
                    resourcesReady = true
                }
            }
        }

        /**
         * Writes a decrypted copy of [uri] to [target], for the engines that
         * cannot open encrypted files. A wrong or missing password still ends
         * in InvalidPasswordException from PDDocument.load.
         */
        fun writeUnlockedCopy(context: Context, uri: Uri, password: String?, target: File) {
            initResources(context)
            PdfBoxDocument(
                cacheDir = context.cacheDir,
                password = password,
                existingFile = null,
                openSource = { context.contentResolver.openInputStream(uri) },
                initResources = {},
            ).use { source ->
                source.read { document ->
                    document.isAllSecurityToBeRemoved = true
                    document.save(target)
                }
            }
        }

        /**
         * Deletes copies and scratch files that a crashed or killed process
         * left in `cacheDir/pdfbox*`. Files of documents open in this process
         * are kept.
         */
        fun deleteStaleFiles(cacheDir: File, now: Long = System.currentTimeMillis()) {
            val entries = cacheDir.listFiles { entry -> entry.name.startsWith(DIR_PREFIX) }.orEmpty()
            entries.flatMap { entry ->
                if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry)
            }.forEach { file ->
                if (file.isFile &&
                    now - file.lastModified() > STALE_AFTER_MS &&
                    file.absolutePath !in liveFiles
                ) {
                    runCatching { file.delete() }
                }
            }
        }
    }
}
