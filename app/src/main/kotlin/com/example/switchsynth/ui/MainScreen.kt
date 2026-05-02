package com.example.switchsynth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.switchsynth.MainViewModel
import com.example.switchsynth.R
import com.example.switchsynth.UiState
import com.example.switchsynth.UnicodeScripts
import com.example.switchsynth.VoiceInfo
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = when (selectedTab) {
                0 -> stringResource(R.string.tab_languages)
                1 -> stringResource(R.string.tab_voices)
                else -> stringResource(R.string.tab_misc)
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() }
        )

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> LanguagesTab(uiState, viewModel)
                1 -> VoicesTab(uiState, viewModel)
                2 -> MiscTab(uiState, viewModel)
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text(stringResource(R.string.tab_languages), modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text(stringResource(R.string.tab_voices), modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text(stringResource(R.string.tab_misc), modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun MiscTab(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.label_emoji_reading))
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.activeScripts.isEmpty()) {
            Text(stringResource(R.string.label_no_scripts), style = MaterialTheme.typography.bodySmall)
        } else {
            // Show language names, not script names
            var expanded by remember { mutableStateOf(false) }
            val currentLangTag = uiState.scriptLanguages[uiState.emojiVoice]
            val currentDisplay = if (currentLangTag != null) {
                Locale.forLanguageTag(currentLangTag).getDisplayName(Locale.getDefault())
            } else {
                val firstScript = uiState.activeScripts.firstOrNull()
                val firstLang = if (firstScript != null) uiState.scriptLanguages[firstScript] else null
                if (firstLang != null) Locale.forLanguageTag(firstLang).getDisplayName(Locale.getDefault())
                else stringResource(R.string.placeholder_select_language)
            }

            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(currentDisplay)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    uiState.activeScripts.forEach { script ->
                        val langTag = uiState.scriptLanguages[script] ?: return@forEach
                        val langName = Locale.forLanguageTag(langTag).getDisplayName(Locale.getDefault())
                        DropdownMenuItem(
                            text = { Text(langName) },
                            onClick = {
                                viewModel.setEmojiVoice(script)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LanguagesTab(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { viewModel.selectAllLanguages() }) {
                Text(stringResource(R.string.btn_select_all))
            }
            Button(onClick = { viewModel.deselectAllLanguages() }) {
                Text(stringResource(R.string.btn_deselect_all))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.label_supported_languages))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.availableLocales, key = { it.toLanguageTag() }) { locale ->
                val tag = locale.toLanguageTag()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = uiState.selectedLanguages.contains(tag),
                            onValueChange = { viewModel.toggleLanguage(tag) },
                            role = Role.Checkbox
                        )
                ) {
                    Checkbox(
                        checked = uiState.selectedLanguages.contains(tag),
                        onCheckedChange = null
                    )
                    Text(viewModel.getDisplayName(locale))
                }
            }
        }
    }
}

@Composable
fun VoicesTab(uiState: UiState, viewModel: MainViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = uiState.useAccessibilityVolume,
                        onValueChange = { viewModel.setUseAccessibilityVolume(it) },
                        role = Role.Switch
                    )
            ) {
                Switch(
                    checked = uiState.useAccessibilityVolume,
                    onCheckedChange = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.switch_use_accessibility_volume))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uiState.activeScripts.isEmpty()) {
            item {
                Text(stringResource(R.string.label_no_scripts), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(uiState.activeScripts) { script ->
                ScriptSettingsCard(uiState, viewModel, script)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun ScriptSettingsCard(uiState: UiState, viewModel: MainViewModel, script: String) {
    var expanded by remember { mutableStateOf(false) }

    val scriptLangTag = uiState.scriptLanguages[script]
    val selectedVoiceId = uiState.scriptVoices[script]

    val langsForScript = UnicodeScripts.getLanguagesForScript(script, uiState.selectedLanguages)
    val localesForScript = langsForScript.mapNotNull { tag ->
        uiState.availableLocales.find { it.toLanguageTag() == tag }
    }

    val displayLangName = if (scriptLangTag != null) {
        Locale.forLanguageTag(scriptLangTag).getDisplayName(Locale.getDefault())
    } else {
        localesForScript.firstOrNull()?.getDisplayName(Locale.getDefault()) ?: script
    }

    val currentVoiceName = if (scriptLangTag != null) {
        val selectedLocale = Locale.forLanguageTag(scriptLangTag)
        val selectedStableName = viewModel.getStableLanguageName(selectedLocale)
        val filteredVoices = uiState.availableVoices.filter {
            viewModel.getStableLanguageName(it.locale) == selectedStableName
        }
        filteredVoices.find { it.id == selectedVoiceId }?.name
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Header — always visible, tap to expand/collapse
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayLangName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (currentVoiceName != null) {
                        Text(
                            text = currentVoiceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expanded settings panel
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    // Language picker (only if multiple languages share this script)
                    if (localesForScript.size > 1) {
                        ScriptLanguagePicker(
                            currentLangTag = scriptLangTag,
                            locales = localesForScript,
                            onSelect = { viewModel.setScriptLanguage(script, it) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Voice selector
                    ScriptVoiceSelector(
                        viewModel = viewModel,
                        language = scriptLangTag,
                        selectedVoiceId = selectedVoiceId,
                        allVoices = uiState.availableVoices,
                        onSelect = { viewModel.setScriptVoice(script, it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Speech rate
                    val scriptRate = uiState.scriptSpeechRates[script] ?: 1.0f
                    Text(stringResource(R.string.label_speech_rate, scriptRate))
                    Slider(
                        value = scriptRate,
                        onValueChange = { viewModel.setScriptSpeechRate(script, it) },
                        valueRange = 0.5f..3.0f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Pitch
                    val scriptPitch = uiState.scriptSpeechPitches[script] ?: 1.0f
                    Text(stringResource(R.string.label_pitch, scriptPitch))
                    Slider(
                        value = scriptPitch,
                        onValueChange = { viewModel.setScriptSpeechPitch(script, it) },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Volume
                    val scriptVolume = uiState.scriptSpeechVolumes[script] ?: 1.0f
                    Text(stringResource(R.string.label_volume, scriptVolume))
                    Slider(
                        value = scriptVolume,
                        onValueChange = { viewModel.setScriptSpeechVolume(script, it) },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ScriptLanguagePicker(
    currentLangTag: String?,
    locales: List<Locale>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = locales.find { it.toLanguageTag() == currentLangTag }?.getDisplayName(Locale.getDefault())
        ?: locales.firstOrNull()?.getDisplayName(Locale.getDefault())
        ?: ""

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.label_change_language) + " " + currentDisplay)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            locales.forEach { locale ->
                DropdownMenuItem(
                    text = { Text(locale.getDisplayName(Locale.getDefault())) },
                    onClick = {
                        onSelect(locale.toLanguageTag())
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ScriptVoiceSelector(
    viewModel: MainViewModel,
    language: String?,
    selectedVoiceId: String?,
    allVoices: List<VoiceInfo>,
    onSelect: (String) -> Unit
) {
    if (language == null) {
        Text(stringResource(R.string.error_select_language_first), style = MaterialTheme.typography.bodySmall)
        return
    }

    val selectedLocale = Locale.forLanguageTag(language)
    val selectedStableName = viewModel.getStableLanguageName(selectedLocale)

    val filteredVoices = allVoices.filter {
        viewModel.getStableLanguageName(it.locale) == selectedStableName
    }
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = filteredVoices.find { it.id == selectedVoiceId }?.name ?: stringResource(R.string.placeholder_select_voice)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(currentDisplay)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (filteredVoices.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.error_no_voices_found)) }, onClick = {})
            }
            filteredVoices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.name) },
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
