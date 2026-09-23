package com.alalkipgen.alalpdf.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Bounded dispatchers for native PDF work.
 *
 * Root cause this exists for: every render, thumbnail, text and link request
 * used to be launched on [Dispatchers.IO] and then blocked inside a
 * `synchronized` block on the shared [PdfDocumentSession]. A fast fling over a
 * long document queues one request per page, so all 64 IO threads ended up
 * parked on the same monitor. Anything else that needed an IO thread - the
 * Room write, the SAF permission check, the reading checkpoint - could not get
 * one, the main thread waited on it, and the system killed the process. That
 * is exactly the reported "scrolling freezes and then the app quits by itself".
 *
 * Neither PdfRenderer nor PDFium can rasterize two pages at once anyway, so
 * bounding parallelism costs no throughput. The difference is that callers now
 * *suspend* while they wait for their turn instead of occupying a thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object PdfWorkDispatchers {
    /** Rasterization. android.graphics.pdf.PdfRenderer allows one page at a time. */
    val render: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** PDFium character geometry and page text; PDFium is process-wide serialized. */
    val text: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** PDFBox link/metadata passes, kept off the text engine so neither blocks the other. */
    val metadata: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
