package com.alalkipgen.alalpdf.create

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class CreatePdfMode { TEXT, IMAGES, IMAGE_TEXT, SCAN }

@Composable fun CreatePdfScreen(
    onBack: () -> Unit,
    onCreated: (Uri) -> Unit,
    initialMode: CreatePdfMode? = null,
    initialDraft: PdfDraft? = null,
    editingUri: Uri? = null,
) {
    var modeName by rememberSaveable { mutableStateOf((initialDraft?.mode?:initialMode)?.name) }
    val mode = modeName?.let(CreatePdfMode::valueOf)
    if (mode == null) ModePicker(onBack) { modeName = it.name }
    else CreateFlow(
        mode,
        initialMode != null || initialDraft != null,
        { modeName = null },
        onBack,
        onCreated,
        initialDraft,
        editingUri,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ModePicker(onBack: () -> Unit, pick: (CreatePdfMode) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("Create PDF") }) }) { p ->
        Column(Modifier.padding(p).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple("Text PDF", CreatePdfMode.TEXT, Icons.Default.TextFields), Triple("Image PDF", CreatePdfMode.IMAGES, Icons.Default.PhotoLibrary),
                Triple("Text + Image PDF", CreatePdfMode.IMAGE_TEXT, Icons.Default.NoteAdd), Triple("Scan PDF", CreatePdfMode.SCAN, Icons.Default.DocumentScanner),
            ).forEach { (title, mode, icon) -> Card(onClick = { pick(mode) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(title, fontWeight = FontWeight.SemiBold) } } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CreateFlow(
    mode: CreatePdfMode,
    direct: Boolean,
    picker: () -> Unit,
    onBack: () -> Unit,
    onCreated: (Uri) -> Unit,
    draft: PdfDraft? = null,
    editingUri: Uri? = null,
) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val repository = remember(context) { CreatePdfRepository(context) }
    var title by rememberSaveable { mutableStateOf(draft?.title.orEmpty()) }; var html by rememberSaveable { mutableStateOf(draft?.bodyHtml.orEmpty()) }
    var imageStrings by rememberSaveable { mutableStateOf(ArrayList(draft?.images.orEmpty())) }; var scanPaths by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingScan by rememberSaveable{mutableStateOf<String?>(null)}; var previewPath by rememberSaveable { mutableStateOf<String?>(null) }; var busy by rememberSaveable { mutableStateOf(false) }; var message by rememberSaveable { mutableStateOf<String?>(null) }
    val editor = rememberRichTextController(); var editingLink by remember { mutableStateOf<EditorLink?>(null) }; var linkText by remember { mutableStateOf("") }; var linkUrl by remember { mutableStateOf("") }
    fun back() { if (direct) onBack() else picker() }
    fun build() {
        busy = true; message = null
        scope.launch { runCatching { repository.buildPreview(context.cacheDir, PdfSpec(mode, title, editor.html().ifBlank { html }, imageStrings.map(Uri::parse), scanPaths.map(::File))) }.onSuccess { previewPath = it.path; busy = false }.onFailure { busy = false; message = it.message } }
    }
    fun clearAfterSave(source: File) {
        source.delete()
        scanPaths.forEach { File(it).delete() }
        title = ""
        html = ""
        imageStrings = arrayListOf()
        scanPaths = arrayListOf()
        previewPath = null
        editor.clear()
    }
    fun saveTo(target: Uri) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            runCatching {
                val source = previewPath?.let(::File)?.takeIf(File::exists)
                    ?: repository.buildPreview(
                        context.cacheDir,
                        PdfSpec(
                            mode,
                            title,
                            editor.html().ifBlank { html },
                            imageStrings.map(Uri::parse),
                            scanPaths.map(::File),
                        ),
                    )
                repository.save(source, target)
                source
            }.onSuccess { source ->
                busy = false
                clearAfterSave(source)
                onCreated(target)
            }.onFailure {
                busy = false
                message = if (target == editingUri) {
                    "This file is read-only. Use Save As instead."
                } else {
                    it.message ?: "Unable to save PDF"
                }
            }
        }
    }
    fun linkDialog(link: EditorLink) { editor.select(link); editingLink = link; linkText = link.text; linkUrl = link.url }

    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }; imageStrings = ArrayList(imageStrings + uris.map(Uri::toString)); if (mode == CreatePdfMode.IMAGES && uris.isNotEmpty()) build()
    }
    fun scanUri():Uri{val f=File(File(context.cacheDir,"create-scans").apply{mkdirs()},"scan-${System.nanoTime()}.jpg");pendingScan=f.path;return FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",f)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->pendingScan?.let{path->if(ok){scanPaths=ArrayList(scanPaths+path);build()}else File(path).delete()};pendingScan=null}

    val output = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) saveTo(uri) }
    LaunchedEffect(mode) { if (mode == CreatePdfMode.IMAGES && imageStrings.isEmpty()) images.launch(arrayOf("image/*")); if (mode == CreatePdfMode.SCAN && scanPaths.isEmpty()) camera.launch(scanUri()) }

    val preview = previewPath?.let(::File)?.takeIf(File::exists)
    if (preview != null) {
        BackHandler { previewPath = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { previewPath = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Edit")
                        }
                    },
                    title = { Text("Preview") },
                    actions = {
                        TextButton(onClick = { previewPath = null }) { Text("Edit") }
                        if (editingUri != null) {
                            TextButton(onClick = { saveTo(editingUri) }, enabled = !busy) {
                                Text(if (busy) "Saving…" else "Save")
                            }
                            TextButton(
                                onClick = { output.launch((title.ifBlank { "Edited PDF" }).pdfName()) },
                                enabled = !busy,
                            ) { Text("Save As") }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (editingUri == null) {
                    ExtendedFloatingActionButton(
                        onClick = { output.launch((title.ifBlank { "New PDF" }).pdfName()) },
                        icon = { Icon(Icons.Default.Save, null) },
                        text = { Text(if (busy) "Saving…" else "Save PDF") },
                    )
                }
            },
        ) { p -> PdfFilePreview(preview, Modifier.fillMaxSize().padding(p)) }
        return
    }
    BackHandler { back() }
    val textMode = mode == CreatePdfMode.TEXT || mode == CreatePdfMode.IMAGE_TEXT
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Text(
                        when {
                            editingUri != null -> "Edit Text PDF"
                            textMode -> "Create Text PDF"
                            else -> "Create PDF"
                        },
                    )
                },
                actions = {
                    if (textMode) {
                        if (editingUri != null) {
                            IconButton(onClick = { saveTo(editingUri) }, enabled = !busy) {
                                Icon(Icons.Default.Save, "Save")
                            }
                            TextButton(
                                onClick = { output.launch((title.ifBlank { "Edited PDF" }).pdfName()) },
                                enabled = !busy,
                            ) { Text("Save As") }
                        }
                        IconButton(onClick = { build() }, enabled = !busy) {
                            Icon(Icons.Default.Visibility, "Preview")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!textMode) ExtendedFloatingActionButton(onClick = { build() }, icon = { Icon(Icons.Default.Visibility, null) }, text = { Text(if (busy) "Building…" else "Preview") })
        },
    ) { padding ->
        if (textMode) {
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                    Surface(Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 840.dp), shape = RoundedCornerShape(8.dp), shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                        RichTextEditor(
                            html = html,
                            onChange = { html = it; previewPath = null },
                            title = title,
                            onTitleChange = { title = it; previewPath = null },
                            controller = editor,
                            modifier = Modifier.fillMaxSize(),
                            onLongLink = ::linkDialog,
                        )
                    }
                }
                if (mode == CreatePdfMode.IMAGE_TEXT) {
                    MediaRow(imageStrings, false) { index -> imageStrings = ArrayList(imageStrings.filterIndexed { i, _ -> i != index }) }
                    TextButton(onClick = { images.launch(arrayOf("image/*")) }, modifier = Modifier.padding(horizontal = 12.dp)) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add images") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
                Surface(tonalElevation=4.dp){Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=8.dp,vertical=4.dp),horizontalArrangement=Arrangement.SpaceEvenly){IconButton(onClick={if(!editor.toggleBold())message="Select text first"}){Text("B",fontWeight=FontWeight.Bold)};IconButton(onClick={if(!editor.toggleItalic())message="Select text first"}){Text("I",fontStyle=FontStyle.Italic)};IconButton(onClick={editor.selectionLink()?.let(::linkDialog)?:run{message="Select text first"}}){Icon(Icons.Default.Link,"Link")};IconButton(onClick=editor::undo){Icon(Icons.Default.Undo,"Undo")};IconButton(onClick=editor::redo){Icon(Icons.Default.Redo,"Redo")}}}
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == CreatePdfMode.IMAGES) {
                    MediaRow(imageStrings, false) { index -> imageStrings = ArrayList(imageStrings.filterIndexed { i, _ -> i != index }) }
                    FilledTonalButton(onClick = { images.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add images") }
                }
                if (mode == CreatePdfMode.SCAN) {
                    MediaRow(scanPaths, true) {}
                    FilledTonalButton(onClick = { camera.launch(scanUri()) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CameraAlt, null); Text(" Scan another page") }
                    if (scanPaths.isNotEmpty()) TextButton(onClick = { scanPaths.forEach { File(it).delete() }; scanPaths = arrayListOf() }) { Text("Start over") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(90.dp))
            }
        }
    }
    editingLink?.let { link -> AlertDialog(onDismissRequest = { editingLink = null }, title = { Text(if (link.url.isBlank()) "Add link" else "Edit link") }, text = { Column { OutlinedTextField(linkText, { linkText = it }, label = { Text("Link text") }); OutlinedTextField(linkUrl, { linkUrl = it }, label = { Text("URL") }) } }, confirmButton = { TextButton(onClick = { if (linkText.isNotBlank() && linkUrl.isNotBlank()) editor.apply(link, linkText, linkUrl); editingLink = null }) { Text("Save") } }, dismissButton = { Row { if (link.url.isNotBlank()) TextButton(onClick = { editor.remove(link); editingLink = null }) { Text("Remove") }; TextButton(onClick = { editingLink = null }) { Text("Cancel") } } }) }
}

@Composable private fun MediaRow(items: List<String>, files: Boolean, remove: (Int) -> Unit) { if (items.isEmpty()) return; LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(items.size) { i -> Box { LocalImage(if (files) Uri.fromFile(File(items[i])) else Uri.parse(items[i]), Modifier.size(90.dp, 110.dp)); if (!files) IconButton(onClick = { remove(i) }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Remove") } } } } }
@Composable private fun LocalImage(uri: Uri, modifier: Modifier) { val context = LocalContext.current; var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }; LaunchedEffect(uri) { bitmap = withContext(Dispatchers.IO) { runCatching { if (uri.scheme == "file") BitmapFactory.decodeFile(uri.path) else context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }; bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) } ?: Surface(modifier) {} }
private fun String.pdfName(): String { val clean = trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "New PDF" }; return if (clean.endsWith(".pdf", true)) clean else "$clean.pdf" }
