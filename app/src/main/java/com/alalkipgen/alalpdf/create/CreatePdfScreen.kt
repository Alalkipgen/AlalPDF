package com.alalkipgen.alalpdf.create

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class CreatePdfMode { TEXT, IMAGES, IMAGE_TEXT, SCAN }

@Composable
fun CreatePdfScreen(onBack: () -> Unit, onCreated: (Uri) -> Unit) {
    var mode by remember { mutableStateOf<CreatePdfMode?>(null) }
    val selected = mode
    if (selected == null) {
        ModePicker(onBack = onBack, onPick = { mode = it })
    } else {
        CreateFlow(mode = selected, onBack = { mode = null }, onCreated = onCreated)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModePicker(onBack: () -> Unit, onPick: (CreatePdfMode) -> Unit) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Create PDF") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeCard("Text PDF", "Write a note and turn it into a PDF", Icons.Default.TextFields) {
                onPick(CreatePdfMode.TEXT)
            }
            ModeCard("Image PDF", "Pick photos from your gallery", Icons.Default.PhotoLibrary) {
                onPick(CreatePdfMode.IMAGES)
            }
            ModeCard("Text + Image PDF", "Write a note and attach images", Icons.Default.NoteAdd) {
                onPick(CreatePdfMode.IMAGE_TEXT)
            }
            ModeCard("Scan PDF", "Use the camera to scan pages", Icons.Default.DocumentScanner) {
                onPick(CreatePdfMode.SCAN)
            }
            Text(
                "Everything is generated offline on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(46.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFlow(mode: CreatePdfMode, onBack: () -> Unit, onCreated: (Uri) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { CreatePdfRepository(context.contentResolver) }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var scans by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun buildPreview() {
        busy = true
        message = null
        scope.launch {
            runCatching {
                repository.buildPreview(context.cacheDir, PdfSpec(mode, title, body, images, scans))
            }
                .onSuccess { busy = false; previewFile = it }
                .onFailure { busy = false; message = it.message ?: "Unable to build preview" }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            images = images + uris
            if (mode == CreatePdfMode.IMAGES) buildPreview()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scans = scans + bitmap
            buildPreview()
        }
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
                .onFailure { busy = false; message = it.message ?: "Unable to save PDF" }
        }
    }

    // Image and scan modes jump straight to the picker or the camera.
    LaunchedEffect(mode) {
        when (mode) {
            CreatePdfMode.IMAGES -> imagePicker.launch("image/*")
            CreatePdfMode.SCAN -> camera.launch(null)
            else -> Unit
        }
    }

    val preview = previewFile
    if (preview != null) {
        // Back from the preview returns to the editor, never straight home.
        BackHandler { previewFile = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { previewFile = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editing")
                        }
                    },
                    title = {
                        Column {
                            Text("Preview", style = MaterialTheme.typography.titleMedium)
                            Text("Pinch or double tap to zoom", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = { TextButton(onClick = { previewFile = null }) { Text("Edit") } },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { output.launch((title.ifBlank { mode.defaultName }).sanitizePdfName()) },
                    icon = { Icon(Icons.Default.NoteAdd, null) },
                    text = { Text(if (busy) "Saving\u2026" else "Save PDF") },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                message?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PdfFilePreview(preview, Modifier.fillMaxSize())
            }
        }
        return
    }

    // Back from the editor returns to the Text / Image / Text+Image / Scan picker.
    BackHandler { onBack() }

    val heading = when (mode) {
        CreatePdfMode.TEXT -> "New note"
        CreatePdfMode.IMAGES -> "Selected images"
        CreatePdfMode.IMAGE_TEXT -> "Note with images"
        CreatePdfMode.SCAN -> "Scanned pages"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(heading) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { buildPreview() },
                icon = { Icon(Icons.Default.Visibility, null) },
                text = { Text(if (busy) "Building\u2026" else "Preview") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mode == CreatePdfMode.TEXT || mode == CreatePdfMode.IMAGE_TEXT) {
                // Paper-like writing card, the pattern used by note and scanner apps.
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        TextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = { Text("Title", style = MaterialTheme.typography.headlineSmall) },
                            textStyle = MaterialTheme.typography.headlineSmall,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = noteFieldColors(),
                        )
                        TextField(
                            value = body,
                            onValueChange = { body = it },
                            placeholder = { Text("Start writing\u2026") },
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                            colors = noteFieldColors(),
                        )
                    }
                }
            }

            if (mode == CreatePdfMode.IMAGES || mode == CreatePdfMode.IMAGE_TEXT) {
                if (images.isNotEmpty()) {
                    Text(
                        images.size.toString() + " image" + (if (images.size == 1) "" else "s"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images.size) { index ->
                            Box {
                                Surface(
                                    Modifier
                                        .size(width = 84.dp, height = 104.dp)
                                        .padding(top = 6.dp, end = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    LocalImage(images[index], Modifier.fillMaxSize())
                                }
                                IconButton(
                                    onClick = { images = images.filterIndexed { i, _ -> i != index } },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Remove image", Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (images.isEmpty()) "Add images" else "Add more images")
                }
            }

            if (mode == CreatePdfMode.SCAN) {
                if (scans.isNotEmpty()) {
                    Text(
                        scans.size.toString() + " page" + (if (scans.size == 1) "" else "s") + " scanned",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scans.size) { index ->
                            Surface(
                                Modifier.size(width = 84.dp, height = 104.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Image(
                                    bitmap = scans[index].asImageBitmap(),
                                    contentDescription = "Page " + (index + 1),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                FilledTonalButton(
                    onClick = { camera.launch(null) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.AddAPhoto, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (scans.isEmpty()) "Open camera" else "Scan another page")
                }
                if (scans.isNotEmpty()) {
                    TextButton(onClick = { scans = emptyList(); camera.launch(null) }) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start over")
                    }
                }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** Small local image loader so the app does not need an image loading library. */
@Composable
private fun LocalImage(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(input, null, options)
                }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun noteFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

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
