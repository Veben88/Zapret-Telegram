package ru.partitrainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ru.partitrainer.R
import ru.partitrainer.omr.OmrJob
import java.util.UUID

/** Прогресс распознавания: идёт в фоне (WorkManager), можно отменить или уйти с экрана. */
@Composable
fun ProgressScreen(workId: String, onDone: (Long) -> Unit, onBack: () -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val id = remember(workId) { UUID.fromString(workId) }
    val info by wm.getWorkInfoByIdFlow(id).collectAsState(initial = null)
    val state = info?.state

    LaunchedEffect(state) {
        if (state == WorkInfo.State.SUCCEEDED) {
            val pid = info!!.outputData.getLong(OmrJob.KEY_PROJECT, -1)
            if (pid >= 0) onDone(pid)
        }
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                WorkInfo.State.FAILED -> {
                    val msg = info!!.outputData.getString(OmrJob.KEY_ERROR) ?: stringResource(R.string.recognition_failed)
                    Text(stringResource(R.string.recognition_failed), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp)); Text(msg)
                    Spacer(Modifier.height(24.dp))
                    if (info!!.outputData.getBoolean(OmrJob.KEY_MANUAL, true)) {
                        Text(stringResource(R.string.offer_manual), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
                }
                WorkInfo.State.CANCELLED -> {
                    Text(stringResource(R.string.cancelled)); Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
                else -> {
                    val p = info?.progress
                    val done = p?.getInt(OmrJob.KEY_DONE, 0) ?: 0
                    val total = p?.getInt(OmrJob.KEY_TOTAL, 0) ?: 0
                    val msg = p?.getString(OmrJob.KEY_MSG) ?: stringResource(R.string.starting)
                    Text(stringResource(R.string.recognizing), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { wm.cancelWorkById(id) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.run_in_background)) }
                    }
                }
            }
        }
    }
}
