package ru.partitrainer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.partitrainer.R
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.music.BeatsFormat
import ru.partitrainer.core.music.Notation
import ru.partitrainer.core.music.NoteNames

val LocalNotation = compositionLocalOf { Notation.SOLFEGE }

/** Чип ноты: название, октава, длительность в долях, слог; «держать» для длинных; подсветка вне диапазона. */
@Composable
fun NoteChip(note: Note, notation: Notation, outOfRange: Boolean, active: Boolean, selected: Boolean, onClick: () -> Unit, displayMidi: Int = note.midi) {
    val name = NoteNames.name(displayMidi, notation, SpellingState.flats)
    val octave = NoteNames.octave(displayMidi)
    val beats = BeatsFormat.format(note.beats)
    val hold = note.beats > BeatsFormat.HOLD_THRESHOLD
    val desc = buildString {
        append(stringResource(R.string.note_desc, name, octave, beats))
        note.lyric?.let { append(", "); append(stringResource(R.string.syllable_desc, it)) }
        if (hold) append(", " + stringResource(R.string.hold))
        if (outOfRange) append(", " + stringResource(R.string.out_of_range))
    }
    val container = when {
        active -> MaterialTheme.colorScheme.primary
        outOfRange -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        active -> MaterialTheme.colorScheme.onPrimary
        outOfRange -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp).semantics(mergeDescendants = true) { contentDescription = desc }.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = container, contentColor = content,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary) else if (outOfRange) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$name$octave", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(beats, fontSize = 13.sp)
            if (!note.lyric.isNullOrBlank()) Text(note.lyric!!, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (hold) Text(stringResource(R.string.hold), fontSize = 10.sp)
        }
    }
}

/** Пауза: приглушённый чип с пунктирной рамкой. */
@Composable
fun RestChip(beats: Double, selected: Boolean, onClick: () -> Unit) {
    val b = BeatsFormat.format(beats)
    val desc = stringResource(R.string.rest_desc, b)
    val color = MaterialTheme.colorScheme.outline
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .drawBehind {
                drawRoundRect(color, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(if (selected) 3.dp.toPx() else 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.rest_short), fontSize = 13.sp, color = color)
            Text(b, fontSize = 13.sp, color = color)
        }
    }
}
