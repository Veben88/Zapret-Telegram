package ru.partitrainer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.partitrainer.R
import ru.partitrainer.omr.ModelState

/** Состояние моделей движка «На устройстве» с кнопками загрузки/отмены/удаления. */
@Composable
fun ModelsCard(state: ModelState, sizeMb: Int, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            ModelState.Ready -> {
                Text(stringResource(R.string.models_ready), style = MaterialTheme.typography.bodyMedium)
                if (onDelete != null) OutlinedButton(onClick = onDelete, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.models_delete)) }
            }
            ModelState.Missing -> {
                Text(stringResource(R.string.models_missing, sizeMb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownload, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.models_download, sizeMb)) }
            }
            is ModelState.Downloading -> {
                val p = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
                Text(stringResource(R.string.models_downloading, (p * 100).toInt(), state.doneBytes / 1_000_000, state.totalBytes / 1_000_000))
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            }
            is ModelState.Failed -> {
                Text(stringResource(R.string.models_failed, state.message), color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownload, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
