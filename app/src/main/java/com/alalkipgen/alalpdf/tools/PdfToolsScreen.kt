package com.alalkipgen.alalpdf.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PdfToolsScreen(uri: Uri, back: () -> Unit, saved: (Uri) -> Unit) {
    val context=LocalContext.current; val repository=remember{PdfToolsRepository(context)}; val scope=rememberCoroutineScope()
    var pages by remember{mutableStateOf<List<PagePlan>>(emptyList())}; var selected by remember{mutableIntStateOf(0)}
    var text by remember{mutableStateOf("")}; var image by remember{mutableStateOf<Uri?>(null)}; var cover by remember{mutableStateOf(false)}
    var x by remember{mutableFloatStateOf(.08f)}; var y by remember{mutableFloatStateOf(.08f)}; var font by remember{mutableFloatStateOf(14f)}
    var error by remember{mutableStateOf<String?>(null)}; var busy by remember{mutableStateOf(false)}
    LaunchedEffect(uri){pages=List(repository.count(uri)){PagePlan(it)}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){image=it}
    val output=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){target->if(target!=null)scope.launch{busy=true;runCatching{repository.save(uri,target,EditPlan(pages,selected,text,image,cover,x,y,font))}.onSuccess{saved(target)}.onFailure{error=it.message};busy=false}}
    BackHandler(onBack=back)
    Scaffold(topBar={TopAppBar(navigationIcon={IconButton(onClick=back){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}},title={Text("Edit PDF")},actions={TextButton(onClick={output.launch("Edited PDF.pdf")},enabled=pages.isNotEmpty()&&!busy){Text(if(busy)"Saving…" else "Save copy")}})}){padding->
        LazyColumn(Modifier.padding(padding).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(pages.size){index->val page=pages[index];Card(onClick={selected=index}){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Page ${page.source+1} • ${page.rotation}°",Modifier.weight(1f));IconButton({if(index>0){pages=pages.toMutableList().apply{add(index-1,removeAt(index))};selected=(index-1)}}){Icon(Icons.Default.ArrowUpward,"Move")};IconButton({pages=pages.toMutableList().apply{this[index]=page.copy(rotation=(page.rotation+90)%360)}}){Icon(Icons.Default.RotateRight,"Rotate")};IconButton({if(pages.size>1){pages=pages.toMutableList().apply{removeAt(index)};selected=selected.coerceAtMost(pages.lastIndex)}}){Icon(Icons.Default.Delete,"Delete")}}}}
            item{OutlinedTextField(text,{text=it},label={Text("Replacement or added text")},modifier=Modifier.fillMaxWidth(),minLines=2);Row(verticalAlignment=Alignment.CenterVertically){Switch(cover,{cover=it});Text("Cover content behind replacement")};Text("Horizontal position");Slider(x,{x=it},valueRange=0f..0.85f);Text("Vertical position");Slider(y,{y=it},valueRange=0f..0.9f);Text("Text size ${font.toInt()}");Slider(font,{font=it},valueRange=8f..48f);FilledTonalButton({picker.launch(arrayOf("image/*"))},Modifier.fillMaxWidth()){Icon(Icons.Default.AddPhotoAlternate,null);Text(if(image==null)" Add image" else " Change image")};if(image!=null)TextButton({image=null}){Text("Remove added image")};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    }
}
