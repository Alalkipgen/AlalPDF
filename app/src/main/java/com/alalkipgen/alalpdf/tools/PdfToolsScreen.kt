package com.alalkipgen.alalpdf.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * WYSIWYG PDF editor.
 *
 * The page itself is rendered as the background, notes are positioned by
 * tapping the spot where they belong, and what the preview shows is what the
 * saved file contains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(uri: Uri, back: () -> Unit, saved: (Uri) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val repository = remember { PdfToolsRepository(context) }
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<PagePlan>>(emptyList()) }
    var selected by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf<List<TextNote>>(emptyList()) }
    var images by remember { mutableStateOf<List<ImageNote>>(emptyList()) }
    var editing by remember { mutableIntStateOf(-1) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var placing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uri) { pages = List(repository.count(uri)) { PagePlan(it) } }
    LaunchedEffect(uri, selected, pages) {
        val source = pages.getOrNull(selected)?.source ?: return@LaunchedEffect
        preview = repository.renderPage(uri, source, 900)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) images = images + ImageNote(page = selected, uri = picked)
    }
    val output = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target ->
        if (target != null) scope.launch {
            busy = true
            runCatching { repository.save(uri, target, EditPlan(pages, notes, images)) }
                .onSuccess { saved(target) }
                .onFailure { error = it.message ?: "Could not save the edited PDF" }
            busy = false
        }
    }

    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Edit PDF") },
                actions = {
                    TextButton(
                        onClick = { output.launch("Edited PDF.pdf") },
                        enabled = pages.isNotEmpty() && !busy,
                    ) { Text(if (busy) "Saving\u2026" else "Save copy") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (placing) "Tap the page where the text should go"
                else "Page " + (selected + 1) + " of " + pages.size.coerceAtLeast(1),
                style = MaterialTheme.typography.labelLarge,
            )

            // ---- Live page preview -------------------------------------
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(Color.White),
            ) {
                val bitmap = preview
                val pageWidth = maxWidth
                val pageHeight = if (bitmap != null) {
                    pageWidth * bitmap.height / bitmap.width
                } else {
                    pageWidth * 1.414f
                }
                Box(
                    Modifier
                        .width(pageWidth)
                        .height(pageHeight)
                        .pointerInput(placing, editing, selected, pageWidth, pageHeight) {
                            detectTapGestures { tap ->
                                val fx = (tap.x / size.width.toFloat()).coerceIn(0f, .95f)
                                val fy = (tap.y / size.height.toFloat()).coerceIn(0f, .95f)
                                when {
                                    placing -> {
                                        notes = notes + TextNote(page = selected, text = "New text", xFraction = fx, yFraction = fy)
                                        editing = notes.lastIndex
                                        placing = false
                                    }
                                    editing in notes.indices -> {
                                        notes = notes.toMutableList().apply {
                                            this[editing] = this[editing].copy(page = selected, xFraction = fx, yFraction = fy)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    // Notes are drawn exactly where they will be written.
                    notes.forEachIndexed { index, note ->
                        if (note.page != selected) return@forEachIndexed
                        val scaled = with(density) {
                            (note.fontSize * pageWidth.toPx() / 595f).toSp()
                        }
                        Box(
                            Modifier
                                .offset(x = pageWidth * note.xFraction, y = pageHeight * note.yFraction)
                                .width(pageWidth * note.widthFraction)
                                .background(if (note.whiteout) Color.White else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (index == editing) MaterialTheme.colorScheme.primary else Color.Transparent,
                                ),
                        ) {
                            Text(note.text, fontSize = scaled, color = Color.Black)
                        }
                    }
                    images.forEachIndexed { _, note ->
                        if (note.page != selected) return@forEachIndexed
                        Box(
                            Modifier
                                .offset(x = pageWidth * note.xFraction, y = pageHeight * note.yFraction)
                                .width(pageWidth * note.widthFraction)
                                .height(pageHeight * note.heightFraction)
                                .border(1.dp, MaterialTheme.colorScheme.secondary),
                        )
                    }
                }
            }

            // ---- Page strip --------------------------------------------
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pages.size) { index ->
                    FilterChip(
                        selected = index == selected,
                        onClick = { selected = index },
                        label = { Text((index + 1).toString()) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { placing = true; editing = -1 }) {
                    Icon(Icons.Default.Add, null); Text(" Add text")
                }
                FilledTonalButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add image")
                }
            }

            // ---- Selected note editor ----------------------------------
            if (editing in notes.indices) {
                val note = notes[editing]
                fun update(transform: (TextNote) -> TextNote) {
                    notes = notes.toMutableList().apply { this[editing] = transform(this[editing]) }
                }
                OutlinedTextField(
                    value = note.text,
                    onValueChange = { value -> update { it.copy(text = value) } },
                    label = { Text("Text (line breaks are kept)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Text("Text size " + note.fontSize.toInt())
                Slider(note.fontSize, { value -> update { it.copy(fontSize = value) } }, valueRange = 8f..48f)
                Text("Text width " + (note.widthFraction * 100).toInt() + "%")
                Slider(note.widthFraction, { value -> update { it.copy(widthFraction = value) } }, valueRange = .15f..0.95f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(note.whiteout, { value -> update { it.copy(whiteout = value) } })
                    Text(" Hide the content underneath")
                }
                if (note.whiteout) {
                    Text("Cover height " + (note.whiteoutHeightFraction * 100).toInt() + "%")
                    Slider(
                        note.whiteoutHeightFraction,
                        { value -> update { it.copy(whiteoutHeightFraction = value) } },
                        valueRange = .02f..0.5f,
                    )
                }
                Row {
                    TextButton(onClick = { editing = -1 }) { Text("Done") }
                    TextButton(onClick = {
                        notes = notes.toMutableList().apply { removeAt(editing) }
                        editing = -1
                    }) { Text("Delete text") }
                }
            }

            if (notes.isNotEmpty()) {
                Text("Text notes", style = MaterialTheme.typography.titleSmall)
                notes.forEachIndexed { index, note ->
                    ListItem(
                        headlineContent = {
                            Text(note.text.ifBlank { "(empty)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text("Page " + (note.page + 1)) },
                        trailingContent = {
                            TextButton(onClick = { selected = note.page; editing = index }) { Text("Edit") }
                        },
                    )
                }
            }

            if (images.isNotEmpty()) {
                Text("Images", style = MaterialTheme.typography.titleSmall)
                images.forEachIndexed { index, note ->
                    ListItem(
                        headlineContent = { Text("Image on page " + (note.page + 1)) },
                        trailingContent = {
                            TextButton(onClick = {
                                images = images.toMutableList().apply { removeAt(index) }
                            }) { Text("Remove") }
                        },
                    )
                }
            }

            // ---- Page management ---------------------------------------
            Text("Pages", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pages.size) { index ->
                    val page = pages[index]
                    Card(onClick = { selected = index }) {
                        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Page " + (page.source + 1) + " \u2022 " + page.rotation + "\u00b0", Modifier.weight(1f))
                            IconButton(onClick = {
                                if (index > 0) {
                                    pages = pages.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    selected = index - 1
                                }
                            }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                            IconButton(onClick = {
                                pages = pages.toMutableList().apply {
                                    this[index] = page.copy(rotation = (page.rotation + 90) % 360)
                                }
                            }) { Icon(Icons.Default.RotateRight, "Rotate") }
                            IconButton(onClick = {
                                if (pages.size > 1) {
                                    pages = pages.toMutableList().apply { removeAt(index) }
                                    notes = notes.filter { it.page != index }
                                    images = images.filter { it.page != index }
                                    selected = selected.coerceAtMost(pages.lastIndex)
                                }
                            }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
