package com.example.switchsynth.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.switchsynth.MainViewModel
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
                Text("Languages", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Voices", modifier = Modifier.padding(16.dp))
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
                Text("Select All")
            }
            Button(onClick = { viewModel.deselectAllLanguages() }) {
                Text("Deselect All")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Representative Languages for Scripts:")
        
        // Latin representative
        LanguageSelector("Latin", uiState.latinLanguage, uiState.availableLocales) {
            viewModel.setLatinLanguage(it)
        }

        // Others representative
        LanguageSelector("Others", uiState.othersLanguage, uiState.availableLocales) {
            viewModel.setOthersLanguage(it)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Supported Languages:")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.availableLocales, key = { it.toLanguageTag() }) { locale ->
                val tag = locale.toLanguageTag()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.selectedLanguages.contains(tag),
                        onCheckedChange = { viewModel.toggleLanguage(tag) }
                    )
                    Text(locale.displayName)
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(label: String, selected: String?, locales: List<Locale>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = locales.find { it.toLanguageTag() == selected }?.displayName ?: "Select..."

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
            Text("Use Accessibility Volume")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Speech Rate: ${"%.1f".format(uiState.speechRate)}")
        Slider(
            value = uiState.speechRate,
            onValueChange = { viewModel.setSpeechRate(it) },
            valueRange = 0.5f..3.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Pitch: ${"%.1f".format(uiState.speechPitch)}")
        Slider(
            value = uiState.speechPitch,
            onValueChange = { viewModel.setSpeechPitch(it) },
            valueRange = 0.5f..2.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Volume: ${"%.1f".format(uiState.speechVolume)}")
        Slider(
            value = uiState.speechVolume,
            onValueChange = { viewModel.setSpeechVolume(it) },
            valueRange = 0.0f..1.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Voice for Latin Script:")
        VoiceSelector(uiState.latinLanguage, uiState.latinVoiceId, uiState.availableVoices) {
            viewModel.setLatinVoice(it)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Voice for Other Scripts:")
        VoiceSelector(uiState.othersLanguage, uiState.othersVoiceId, uiState.availableVoices) {
            viewModel.setOthersVoice(it)
        }
    }
}

@Composable
fun VoiceSelector(language: String?, selectedVoiceId: String?, allVoices: List<VoiceInfo>, onSelect: (String) -> Unit) {
    if (language == null) {
        Text("Please select a representative language first in the Languages tab.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val selectedLocale = Locale.forLanguageTag(language)
    val filteredVoices = allVoices.filter { 
        // Match by base language (e.g. "hu" matches "hu" and "hu-HU")
        it.locale.language == selectedLocale.language 
    }
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = filteredVoices.find { it.id == selectedVoiceId }?.name ?: "Select Voice..."

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(currentDisplay)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (filteredVoices.isEmpty()) {
                DropdownMenuItem(text = { Text("No voices found for this language") }, onClick = {})
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
