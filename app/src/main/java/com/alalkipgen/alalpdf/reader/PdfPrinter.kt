package com.alalkipgen.alalpdf.reader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/** Prints a PDF through the Android print framework (also offers "Save as PDF"). */
object PdfPrinter {
    fun print(context: Context, uri: Uri, documentName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val safeName = documentName.ifBlank { "document.pdf" }
        manager.print(safeName, UriPrintAdapter(context, uri, safeName), null)
    }
}

private class UriPrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val documentName: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(documentName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (destination == null) {
            callback.onWriteFailed("No output target")
            return
        }
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    callback.onWriteFailed("Unable to read this PDF")
                    return
                }
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message ?: "Printing failed")
        }
    }
}
