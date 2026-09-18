package com.alalkipgen.alalpdf.create

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

enum class CreatePdfMode { TEXT, IMAGES, IMAGE_TEXT, SCAN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePdfScreen(onBack: () -> Unit, onCreated: (Uri) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { CreatePdfRepository(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(CreatePdfMode.TEXT) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var scanBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        images = uris
        message = if (uris.isEmpty()) "No images selected" else uris.size.toString() + " image(s) selected"
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        scanBitmap = bitmap
        message = if (bitmap == null) "Scan cancelled" else "Scan captured"
    }
    val output = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = previewFile
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { repository.save(source, uri) }
                .onSuccess {
                    busy = false
                    runCatching { source.delete() }
                    onCreated(uri)
                }
                .onFailure {
                    busy = false
                    message = it.message ?: "Unable to save PDF"
                }
        }
    }

    fun buildPreview() {
        busy = true
        message = null
        scope.launch {
            val spec = PdfSpec(mode, title, body, images, scanBitmap)
            runCatching { repository.buildPreview(context.cacheDir, spec) }
                .onSuccess { busy = false; previewFile = it }
                .onFailure { busy = false; message = it.message ?: "Unable to build preview" }
        }
    }

    val preview = previewFile
    if (preview != null) {
        BackHandler { previewFile = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { previewFile = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editing")
                        }
                    },
                    title = { Text("Preview") },
                    actions = {
                        TextButton(onClick = { previewFile = null }) { Text("Edit") }
                        TextButton(
                            onClick = { output.launch((title.ifBlank { mode.defaultName }).sanitizePdfName()) },
                            enabled = !busy,
                        ) { Text(if (busy) "Saving\u2026" else "Save") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                message?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.error)
                }
                PdfFilePreview(preview, Modifier.fillMaxSize())
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Create PDF") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModeButton("Text PDF", Icons.Default.TextFields, mode == CreatePdfMode.TEXT) { mode = CreatePdfMode.TEXT }
            ModeButton("Image PDF", Icons.Default.Image, mode == CreatePdfMode.IMAGES) { mode = CreatePdfMode.IMAGES }
            ModeButton("Image + Text PDF", Icons.Default.NoteAdd, mode == CreatePdfMode.IMAGE_TEXT) { mode = CreatePdfMode.IMAGE_TEXT }
            ModeButton("Scan PDF", Icons.Default.CameraAlt, mode == CreatePdfMode.SCAN) { mode = CreatePdfMode.SCAN }

            if (mode == CreatePdfMode.TEXT || mode == CreatePdfMode.IMAGE_TEXT) {
                OutlinedTextField(title, { title = it }, label = { Text("File title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 8,
                    modifier = Modifier.fillMaxWidth())
            }
            if (mode == CreatePdfMode.IMAGES || mode == CreatePdfMode.IMAGE_TEXT) {
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (images.isEmpty()) "Choose images" else "Change images (" + images.size + ")")
                }
            }
            if (mode == CreatePdfMode.SCAN) {
                OutlinedButton(onClick = { camera.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanBitmap == null) "Open camera" else "Retake scan")
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = { buildPreview() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Building preview\u2026" else "Preview PDF")
            }
            Text(
                "Check the preview first, then save. Everything is processed offline on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null); Text(label) }
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null); Text(label) }
        }
    }
}

private val CreatePdfMode.defaultName: String get() = when (this) {
    CreatePdfMode.TEXT -> "New Text PDF"
    CreatePdfMode.IMAGES -> "New Image PDF"
    CreatePdfMode.IMAGE_TEXT -> "New Image and Text PDF"
    CreatePdfMode.SCAN -> "New Scan PDF"
}

private fun String.sanitizePdfName(): String {
    val clean = trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "New PDF" }
    return if (clean.endsWith(".pdf", true)) clean else clean + ".pdf"
}
