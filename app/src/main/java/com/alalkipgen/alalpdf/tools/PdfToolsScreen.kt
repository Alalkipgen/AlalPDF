package com.alalkipgen.alalpdf.tools
import android.net.Uri
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
@OptIn(ExperimentalMaterial3Api::class) @Composable fun PdfToolsScreen(uri:Uri,back:()->Unit,saved:(Uri)->Unit){val c=LocalContext.current;val r=remember{PdfToolsRepository(c)};val sc=rememberCoroutineScope();var pages by remember{mutableStateOf<List<PagePlan>>(emptyList())};var sel by remember{mutableIntStateOf(0)};var text by remember{mutableStateOf("")};var image by remember{mutableStateOf<Uri?>(null)};var cover by remember{mutableStateOf(false)};LaunchedEffect(uri){pages=List(r.count(uri)){PagePlan(it)}};val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){image=it};val out=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){o->if(o!=null)sc.launch{r.save(uri,o,EditPlan(pages,sel,text,image,cover));saved(o)}};BackHandler(onBack=back);Scaffold(topBar={TopAppBar(navigationIcon={IconButton(onClick=back){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}},title={Text("Edit PDF")},actions={TextButton(onClick={out.launch("Edited PDF.pdf")}){Text("Save copy")}})}){p->LazyColumn(Modifier.padding(p).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(pages.size){i->val q=pages[i];Card(onClick={sel=i}){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Page ${q.source+1} • ${q.rotation}°",Modifier.weight(1f));IconButton({if(i>0)pages=pages.toMutableList().apply{add(i-1,removeAt(i))}}){Icon(Icons.Default.ArrowUpward,null)};IconButton({pages=pages.toMutableList().apply{this[i]=q.copy(rotation=(q.rotation+90)%360)}}){Icon(Icons.Default.RotateRight,null)};IconButton({if(pages.size>1)pages=pages.toMutableList().apply{removeAt(i)}}){Icon(Icons.Default.Delete,null)}}}};item{OutlinedTextField(text,{text=it},label={Text("Add/replace Pyidaungsu text")},modifier=Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){Switch(cover,{cover=it});Text("Cover top text")};TextButton({pick.launch(arrayOf("image/jpeg"))}){Text("Add/replace image")}}}}}
