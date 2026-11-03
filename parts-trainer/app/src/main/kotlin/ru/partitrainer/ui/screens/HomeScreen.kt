package ru.partitrainer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.partitrainer.AppContainer
import ru.partitrainer.R
import ru.partitrainer.data.ImportResult
import ru.partitrainer.data.ProjectEntity
import ru.partitrainer.ui.appViewModel
import ru.partitrainer.util.ExportFormat
import ru.partitrainer.util.Exporter
import java.io.File
import java.text.DateFormat
import java.util.Date

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = c.projects.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    sealed interface Nav {
        data class Trainer(val id: Long) : Nav
        data class Review(val id: Long) : Nav
        data class Prepare(val path: String, val hash: String, val name: String, val pdf: Boolean) : Nav
    }
    val nav = MutableStateFlow<Nav?>(null)

    fun import(uri: Uri) = viewModelScope.launch {
        busy.value = true
        when (val r = c.importer.import(uri)) {
            is ImportResult.Opened -> nav.value = Nav.Trainer(r.projectId)
            is ImportResult.NeedsRecognition -> nav.value = Nav.Prepare(r.path, r.hash, r.name, r.isPdf)
            is ImportResult.Failed -> error.value = r.message
        }
        busy.value = false
    }

    fun openSample() = viewModelScope.launch {
        val existing = projects.value.firstOrNull { it.isSample }
        nav.value = Nav.Trainer(existing?.id ?: c.projects.ensureSample())
    }

    fun createManual() = viewModelScope.launch { nav.value = Nav.Review(c.importer.createManual()) }
    fun rename(id: Long, title: String) = viewModelScope.launch { c.projects.rename(id, title) }
    fun delete(id: Long) = viewModelScope.launch { c.projects.delete(id) }

    fun export(id: Long, format: ExportFormat, context: android.content.Context) = viewModelScope.launch {
        c.projects.load(id)?.let { Exporter.share(context, it, it.selectedVoiceId, format) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    externalError: MutableStateFlow<String?>,
    onOpenTrainer: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onPrepare: (String, String, String, Boolean) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val ctx = LocalContext.current
    val projects by vm.projects.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val extErr by externalError.collectAsState()
    val nav by vm.nav.collectAsState()

    LaunchedEffect(nav) {
        when (val n = nav) {
            is HomeViewModel.Nav.Trainer -> onOpenTrainer(n.id)
            is HomeViewModel.Nav.Review -> onOpenReview(n.id)
            is HomeViewModel.Nav.Prepare -> onPrepare(n.path, n.hash, n.name, n.pdf)
            null -> {}
        }
        if (nav != null) vm.nav.value = null
    }

    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::import) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(vm::import) }
    var photoMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, stringResource(R.string.about)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.add_score), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceButton(Icons.Default.PictureAsPdf, R.string.src_pdf) { pickDoc.launch(arrayOf("application/pdf")) }
                    Box {
                        SourceButton(Icons.Default.PhotoCamera, R.string.src_photo) { photoMenu = true }
                        DropdownMenu(photoMenu, { photoMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.src_camera)) }, onClick = {
                                photoMenu = false
                                val f = File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "photo_${System.currentTimeMillis()}.jpg") }
                                val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                cameraUri = u; camera.launch(u)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.src_gallery)) }, onClick = {
                                photoMenu = false
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        }
                    }
                    SourceButton(Icons.Default.Description, R.string.src_musicxml_midi) {
                        pickDoc.launch(arrayOf("application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml", "application/xml", "text/xml", "audio/midi", "audio/mid", "audio/x-midi", "application/x-midi", "application/octet-stream", "application/zip"))
                    }
                    SourceButton(Icons.Default.Edit, R.string.src_manual) { vm.createManual() }
                    SourceButton(Icons.Default.MusicNote, R.string.src_sample) { vm.openSample() }
                }
                if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item { Text(stringResource(R.string.my_scores), style = MaterialTheme.typography.titleMedium) }
            if (projects.isEmpty()) item { Text(stringResource(R.string.no_scores), style = MaterialTheme.typography.bodyMedium) }
            items(projects, key = { it.id }) { p ->
                ProjectCard(p, onOpen = { if (p.reviewed) onOpenTrainer(p.id) else onOpenReview(p.id) },
                    onEdit = { onOpenReview(p.id) }, onRename = { renaming = p }, onDelete = { deleting = p },
                    onExport = { f -> vm.export(p.id, f, ctx) })
            }
        }
    }

    val msg = error ?: extErr
    if (msg != null) AlertDialog(
        onDismissRequest = { vm.error.value = null; externalError.value = null },
        confirmButton = { TextButton(onClick = { vm.error.value = null; externalError.value = null }) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.error)) }, text = { Text(msg) },
    )
    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(p.id, text); renaming = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, p.title)) },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SourceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick, Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(label))
    }
}

@Composable
private fun ProjectCard(
    p: ProjectEntity, onOpen: () -> Unit, onEdit: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: (ExportFormat) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val date = remember(p.updatedAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt)) }
    val open = stringResource(R.string.open_score, p.title)
    ElevatedCard(onClick = onOpen, Modifier.fillMaxWidth().semantics { contentDescription = open }) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                val kind = when (p.sourceType) {
                    "SAMPLE" -> stringResource(R.string.kind_sample); "PDF" -> "PDF"; "PHOTO" -> stringResource(R.string.src_photo)
                    "MUSICXML" -> "MusicXML"; "MIDI" -> "MIDI"; else -> stringResource(R.string.src_manual)
                }
                Text("$kind · $date" + if (!p.reviewed) " · " + stringResource(R.string.needs_review) else "", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_notes)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_midi)) }, onClick = { menu = false; onExport(ExportFormat.MIDI) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_musicxml)) }, onClick = { menu = false; onExport(ExportFormat.MUSICXML) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
