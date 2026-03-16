package com.example.switchsynth.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.switchsynth.MainViewModel
import com.example.switchsynth.R
import com.example.switchsynth.UiState
import com.example.switchsynth.VoiceInfo
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text(stringResource(R.string.tab_languages), modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text(stringResource(R.string.tab_voices), modifier = Modifier.padding(16.dp))
            }
        }

        when (selectedTab) {
            0 -> LanguagesTab(uiState, viewModel)
            1 -> VoicesTab(uiState, viewModel)
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
        Text(stringResource(R.string.label_representative_languages))
        
        // Filter locales to show only those selected in "Supported Languages"
        val filteredLocales = uiState.availableLocales.filter { 
            uiState.selectedLanguages.contains(it.toLanguageTag())
        }

        // Latin representative
        LanguageSelector(stringResource(R.string.label_latin), uiState.latinLanguage, filteredLocales) {
            viewModel.setLatinLanguage(it)
        }

        // Others representative
        LanguageSelector(stringResource(R.string.label_others), uiState.othersLanguage, filteredLocales) {
            viewModel.setOthersLanguage(it)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.label_supported_languages))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.availableLocales, key = { it.toLanguageTag() }) { locale ->
                val tag = locale.toLanguageTag()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.selectedLanguages.contains(tag),
                        onCheckedChange = { viewModel.toggleLanguage(tag) }
                    )
                    Text(viewModel.getDisplayName(locale))
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(label: String, selected: String?, locales: List<Locale>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = locales.find { it.toLanguageTag() == selected }?.displayName ?: stringResource(R.string.placeholder_select_language)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$label: $currentDisplay")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            locales.forEach { locale ->
                DropdownMenuItem(
                    text = { Text(locale.displayName) },
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
fun VoicesTab(uiState: UiState, viewModel: MainViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = uiState.useAccessibilityVolume,
                onCheckedChange = { viewModel.setUseAccessibilityVolume(it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.switch_use_accessibility_volume))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(stringResource(R.string.label_speech_rate, uiState.speechRate))
        Slider(
            value = uiState.speechRate,
            onValueChange = { viewModel.setSpeechRate(it) },
            valueRange = 0.5f..3.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(stringResource(R.string.label_pitch, uiState.speechPitch))
        Slider(
            value = uiState.speechPitch,
            onValueChange = { viewModel.setSpeechPitch(it) },
            valueRange = 0.5f..2.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(stringResource(R.string.label_volume, uiState.speechVolume))
        Slider(
            value = uiState.speechVolume,
            onValueChange = { viewModel.setSpeechVolume(it) },
            valueRange = 0.0f..1.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(stringResource(R.string.label_voice_latin))
        VoiceSelector(viewModel, uiState.latinLanguage, uiState.latinVoiceId, uiState.availableVoices) {
            viewModel.setLatinVoice(it)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.label_voice_others))
        VoiceSelector(viewModel, uiState.othersLanguage, uiState.othersVoiceId, uiState.availableVoices) {
            viewModel.setOthersVoice(it)
        }
    }
}

@Composable
fun VoiceSelector(viewModel: MainViewModel, language: String?, selectedVoiceId: String?, allVoices: List<VoiceInfo>, onSelect: (String) -> Unit) {
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
