package ru.partitrainer.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.partitrainer.AppContainer
import ru.partitrainer.R
import ru.partitrainer.core.omr.ClaudeOmr
import ru.partitrainer.core.omr.StaffBox
import ru.partitrainer.core.omr.StaffFinder
import ru.partitrainer.data.EngineId
import ru.partitrainer.data.Settings
import ru.partitrainer.omr.*
import ru.partitrainer.ui.appViewModel
import java.io.File

class PrepareViewModel(private val c: AppContainer, val path: String, val hash: String, val name: String, val isPdf: Boolean) : ViewModel() {
    class Thumb(val bitmap: Bitmap, val staves: List<StaffBox>, val analysisHeight: Int)

    private val renderer = PageRenderer(File(path), isPdf)
    val pageCount = MutableStateFlow(0)
    val thumbs = MutableStateFlow<Map<Int, Thumb>>(emptyMap())
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val rotations = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val staff = MutableStateFlow(StaffSelection.VOCAL_TOP)
    val twoStaff = MutableStateFlow(false)
    val settings = MutableStateFlow(Settings())
    val hasKey = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** Партии, найденные предварительным просмотром, и отмеченные для распознавания. */
    val parts = MutableStateFlow<List<ru.partitrainer.omr.homr.PartPick>>(emptyList())
    val pickedParts = MutableStateFlow<Set<Int>>(emptySet())
    val scanning = MutableStateFlow(false)

    /** Быстрый просмотр первой выбранной страницы: какие партии в нотах (несколько секунд). */
    fun scanParts() = viewModelScope.launch {
        val page = selected.value.minOrNull() ?: 0
        scanning.value = true; error.value = null
        try {
            val engine = HomrOnDeviceEngine(c.homrModels)
            val found = engine.scanParts({ p -> renderer.renderForOmr(p, rotations.value[p] ?: 0) }, page, settings.value.omrAccurate)
            parts.value = found
            pickedParts.value = found.indices.filter { !found[it].piano }.toSet()
            if (found.isEmpty()) error.value = "Нотоносцы на странице не найдены"
        } catch (e: OmrException) { error.value = e.userMessage }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error.value = "Не удалось просмотреть страницу: ${e.message}" }
        finally { scanning.value = false }
    }

    /** Пользователь поправил голос найденной партии. null — фортепиано. */
    fun setPartKind(i: Int, kind: ru.partitrainer.omr.homr.ChoirVoice?) { parts.value = ru.partitrainer.omr.homr.Parts.withKind(parts.value, i, kind) }

    fun togglePart(i: Int) { pickedParts.value = if (i in pickedParts.value) pickedParts.value - i else pickedParts.value + i }

    init {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { renderer.pageCount }.getOrElse { error.value = "Не удалось открыть PDF"; 0 } }
            pageCount.value = n
            selected.value = (0 until n).toSet()
            settings.value = c.settings.flow.first()
            hasKey.value = c.keyStore.hasKey()
            loadThumbs(n)
        }
        viewModelScope.launch { c.settings.flow.collect { settings.value = it; hasKey.value = c.keyStore.hasKey() } }
    }

    private suspend fun loadThumbs(n: Int) {
        for (i in 0 until n) {
            val rot = rotations.value[i] ?: 0
            val big = runCatching { renderer.thumbnail(i, 800, rot) }.getOrNull() ?: continue
            val staves = withContext(Dispatchers.Default) {
                val px = IntArray(big.width * big.height)
                big.getPixels(px, 0, big.width, 0, 0, big.width, big.height)
                val lum = IntArray(px.size) { val p = px[it]; ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10 }
                StaffFinder.find(lum, big.width, big.height)
            }
            val th = Bitmap.createScaledBitmap(big, 280, (big.height * 280f / big.width).toInt().coerceAtLeast(1), true)
            val h = big.height
            if (th !== big) big.recycle()
            thumbs.value = thumbs.value + (i to Thumb(th, staves, h))
        }
    }

    fun toggle(i: Int) { selected.value = if (i in selected.value) selected.value - i else selected.value + i }
    fun rotate(i: Int) {
        rotations.value = rotations.value + (i to (((rotations.value[i] ?: 0) + 90) % 360))
        viewModelScope.launch {
            val old = thumbs.value[i]
            thumbs.value = thumbs.value - i
            old?.bitmap?.recycle()
            val big = runCatching { renderer.thumbnail(i, 800, rotations.value[i] ?: 0) }.getOrNull() ?: return@launch
            val px = IntArray(big.width * big.height); big.getPixels(px, 0, big.width, 0, 0, big.width, big.height)
            val lum = IntArray(px.size) { val p = px[it]; ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10 }
            val staves = StaffFinder.find(lum, big.width, big.height)
            val th = Bitmap.createScaledBitmap(big, 280, (big.height * 280f / big.width).toInt().coerceAtLeast(1), true)
            val h = big.height; if (th !== big) big.recycle()
            thumbs.value = thumbs.value + (i to Thumb(th, staves, h))
        }
    }

    fun cost(): ClaudeOmr.Cost = ClaudeOmr.estimate(selected.value.size * staff.value.expand().size, ClaudeVisionEngine.MAX_SIDE * 3 / 4, ClaudeVisionEngine.MAX_SIDE)

    /** Запускает задание; возвращает id работы или null, если нужно подтверждение/ошибка. */
    suspend fun start(): String? {
        val s = c.settings.flow.first()
        val engine: OmrEngine = if (s.engine == EngineId.ON_DEVICE) HomrOnDeviceEngine(c.homrModels) { ClaudeTextRecognizer { c.keyStore.load() } } else ClaudeVisionEngine { c.keyStore.load() }
        (engine.availability() as? Availability.Unavailable)?.let { error.value = it.reason; return null }
        val pages = selected.value.sorted()
        if (pages.isEmpty()) { error.value = "Выберите хотя бы одну страницу"; return null }
        val partList = if (s.engine == EngineId.ON_DEVICE) pickedParts.value.sorted().mapNotNull { parts.value.getOrNull(it)?.encode() } else emptyList()
        val job = OmrJob(path, hash, isPdf, name, pages, pages.map { rotations.value[it] ?: 0 }, staff.value, s.engine, twoStaff.value, s.omrAccurate, s.omrLyrics, partList)
        return OmrJob.enqueue(c.context, job).toString()
    }

    suspend fun manualEntry(): Long {
        val page = selected.value.minOrNull() ?: 0
        return c.importer.createManualFromSource(path, hash, name, isPdf, page, rotations.value[page] ?: 0)
    }

    val models get() = c.homrModels

    suspend fun acceptWarning() = c.settings.update { it.copy(claudeWarningAccepted = true) }

    override fun onCleared() {
        renderer.close()
        thumbs.value.values.forEach { it.bitmap.recycle() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(path: String, hash: String, name: String, isPdf: Boolean, onBack: () -> Unit, onStarted: (String, String) -> Unit, onManual: (Long) -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel(key = hash) { PrepareViewModel(it, path, hash, name, isPdf) }
    val thumbs by vm.thumbs.collectAsState()
    val selected by vm.selected.collectAsState()
    val rotations by vm.rotations.collectAsState()
    val count by vm.pageCount.collectAsState()
    val staff by vm.staff.collectAsState()
    val twoStaff by vm.twoStaff.collectAsState()
    val settings by vm.settings.collectAsState()
    val hasKey by vm.hasKey.collectAsState()
    val error by vm.error.collectAsState()
    val models by vm.models.state.collectAsState()
    val parts by vm.parts.collectAsState()
    val picked by vm.pickedParts.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scope = rememberCoroutineScope()
    var warn by remember { mutableStateOf(false) }

    // Android 13+: уведомление о фоновом распознавании требует разрешения; без него работа всё равно идёт.
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { vm.start()?.let { id -> onStarted(id, name) } }
    }
    fun go() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else scope.launch { vm.start()?.let { id -> onStarted(id, name) } }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.prepare_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)

            // Предварительный просмотр партий (движок на устройстве)
            if (settings.engine == EngineId.ON_DEVICE && models is ru.partitrainer.omr.ModelState.Ready) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.parts_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.parts_hint), style = MaterialTheme.typography.bodySmall)
                        if (scanning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.parts_scanning), style = MaterialTheme.typography.bodySmall)
                        } else {
                            OutlinedButton(onClick = vm::scanParts, Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (parts.isEmpty()) R.string.parts_scan else R.string.parts_rescan, (selected.minOrNull() ?: 0) + 1))
                            }
                        }
                        for ((i, p) in parts.withIndex()) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(i in picked, role = Role.Checkbox, onValueChange = { vm.togglePart(i) }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(i in picked, onCheckedChange = null); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name)
                                    val where = stringResource(R.string.parts_where, p.staffIndex + 1) + when (p.stemVoice) { 1 -> stringResource(R.string.parts_upper); 2 -> stringResource(R.string.parts_lower); else -> "" }
                                    Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // поправить голос, если определился неверно
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { menu = true }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.parts_change)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        for ((k, label) in listOf(
                                            ru.partitrainer.omr.homr.ChoirVoice.SOPRANO to R.string.staff_soprano, ru.partitrainer.omr.homr.ChoirVoice.ALTO to R.string.staff_alto,
                                            ru.partitrainer.omr.homr.ChoirVoice.TENOR to R.string.staff_tenor, ru.partitrainer.omr.homr.ChoirVoice.BASS to R.string.staff_bass,
                                            null to R.string.parts_piano,
                                        )) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; vm.setPartKind(i, k) })
                                    }
                                }
                            }
                        }
                        if (parts.isNotEmpty()) Text(stringResource(if (picked.isEmpty()) R.string.parts_none_picked else R.string.parts_picked, picked.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(stringResource(R.string.which_staff), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                for ((sel, label) in listOf(
                    StaffSelection.VOCAL_TOP to R.string.staff_vocal, StaffSelection.RIGHT_HAND to R.string.staff_right,
                    StaffSelection.LEFT_HAND to R.string.staff_left, StaffSelection.ALL to R.string.staff_all,
                    StaffSelection.SOPRANO to R.string.staff_soprano, StaffSelection.ALTO to R.string.staff_alto,
                    StaffSelection.TENOR to R.string.staff_tenor, StaffSelection.BASS to R.string.staff_bass,
                    StaffSelection.CHOIR_ALL to R.string.staff_choir_all,
                )) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(staff == sel, role = Role.RadioButton, onClick = { vm.staff.value = sel }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(staff == sel, onClick = null); Spacer(Modifier.width(12.dp)); Text(stringResource(label))
                    }
                }
            }

            if (staff.isChoirVoice || staff == StaffSelection.CHOIR_ALL) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.choir_two_staff))
                        Text(stringResource(R.string.choir_two_staff_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(twoStaff, { vm.twoStaff.value = it })
                }
            }
            if (isPdf) {
                Text(stringResource(R.string.pages_title, selected.size, count), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.highlight_hint), style = MaterialTheme.typography.bodySmall)
            val cols = (LocalContext.current.resources.configuration.screenWidthDp / 170).coerceAtLeast(2)
            LazyVerticalGrid(GridCells.Fixed(cols), Modifier.heightIn(max = 1400.dp).height(((count + cols - 1) / cols * 330).coerceAtLeast(120).dp), userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until count).toList()) { i ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                                val t = thumbs[i]
                                if (t == null) CircularProgressIndicator() else {
                                    Box {
                                        Image(t.bitmap.asImageBitmap(), stringResource(R.string.page_n, i + 1), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                        val sel = when (staff) { StaffSelection.VOCAL_TOP -> 0; StaffSelection.RIGHT_HAND -> 1; StaffSelection.LEFT_HAND -> 2; StaffSelection.ALL -> null; else -> null }
                                        val hi = StaffFinder.select(t.staves, sel)
                                        val color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                                        Canvas(Modifier.matchParentSize()) {
                                            // Thumbnail вписан в бокс с ContentScale.Fit: считаем область изображения
                                            val ar = t.bitmap.width.toFloat() / t.bitmap.height
                                            val w = minOf(size.width, size.height * ar); val h = w / ar
                                            val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
                                            for (b in hi) {
                                                val y0 = oy + h * (b.top - 4f) / t.analysisHeight
                                                val y1 = oy + h * (b.bottom + 4f) / t.analysisHeight
                                                drawRect(color, Offset(ox, y0), Size(w, y1 - y0))
                                            }
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPdf) {
                                    Checkbox(i in selected, { vm.toggle(i) }, Modifier.minimumInteractiveComponentSize())
                                    Text("${i + 1}")
                                }
                                IconButton(onClick = { vm.rotate(i) }) { Icon(Icons.Default.RotateRight, stringResource(R.string.rotate_page, i + 1)) }
                                Text("${rotations[i] ?: 0}°", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            EngineCard(settings, hasKey, vm, models, onSettings)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { if (settings.engine == EngineId.CLAUDE_VISION && !settings.claudeWarningAccepted) warn = true else go() },
                enabled = selected.isNotEmpty() && (settings.engine != EngineId.ON_DEVICE || models == ru.partitrainer.omr.ModelState.Ready), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.start_recognition)) }
            OutlinedButton(
                onClick = { scope.launch { onManual(vm.manualEntry()) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.manual_from_page)) }
            Text(stringResource(R.string.manual_from_page_hint), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (warn) AlertDialog(
        onDismissRequest = { warn = false },
        title = { Text(stringResource(R.string.claude_warn_title)) },
        text = { Text(stringResource(R.string.claude_warn_text)) },
        confirmButton = { TextButton(onClick = { warn = false; scope.launch { vm.acceptWarning(); go() } }) { Text(stringResource(R.string.claude_warn_accept)) } },
        dismissButton = { TextButton(onClick = { warn = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EngineCard(s: Settings, hasKey: Boolean, vm: PrepareViewModel, models: ru.partitrainer.omr.ModelState, onSettings: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.engine_label), style = MaterialTheme.typography.labelMedium)
            if (s.engine == EngineId.CLAUDE_VISION) {
                Text(stringResource(R.string.engine_claude), style = MaterialTheme.typography.titleSmall)
                val c = vm.cost()
                Text(stringResource(R.string.cost_estimate, c.inputTokens, c.outputTokens, c.usd))
                if (!hasKey) Text(stringResource(R.string.no_key_hint), color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.engine_ondevice), style = MaterialTheme.typography.titleSmall)
                ru.partitrainer.ui.components.ModelsCard(models, vm.models.downloadSizeMb, vm.models::startDownload, vm.models::cancel)
            }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.change_engine)) }
        }
    }
}
