package ru.partitrainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.partitrainer.BuildConfig
import ru.partitrainer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onPrivacy: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            Text(stringResource(R.string.about_text))
            Button(onClick = onPrivacy, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.privacy_policy)) }
            HorizontalDivider()
            Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.licenses_text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.privacy_policy)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.privacy_text))
        }
    }
}
