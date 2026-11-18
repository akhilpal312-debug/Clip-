package com.aiclipcreator.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.InfoRow
import com.aiclipcreator.core.common.ui.Pill
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.StatTile
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.ClipCountMode
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.DurationMode
import com.aiclipcreator.core.models.Language
import com.aiclipcreator.core.models.ProductionProfile
import com.aiclipcreator.core.models.StorageReport
import com.aiclipcreator.core.models.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<UserSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _storage = kotlinx.coroutines.flow.MutableStateFlow<StorageReport?>(null)
    val storage: StateFlow<StorageReport?> = _storage

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch { _storage.value = container.storageReport() }
    }

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    fun clearWorkingFiles() {
        viewModelScope.launch {
            container.files.clearWorkingCopies()
            refreshStorage()
        }
    }

    fun verifyModels() {
        viewModelScope.launch {
            container.models.statuses.value.filter { it.state.name == "READY" }.forEach {
                container.models.verify(it.spec.id)
            }
        }
    }
}

@Composable
fun rememberSettingsViewModel(container: AppContainer): SettingsViewModel = viewModel { SettingsViewModel(container) }

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onModels: () -> Unit
) {
    val vm = rememberSettingsViewModel(container)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val colors = appColors
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Settings",
        subtitle = "Processing, ranking and storage",
        onBack = onBack
    ) {
        SectionHeader("Processing profile")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductionProfile.entries.forEach { profile ->
                Pill(profile.label, settings.profile == profile) {
                    vm.update { it.copy(profile = profile) }
                }
            }
        }
        Text(
            "Lite uses fewer sampled frames and Whisper Tiny; Advanced samples more frames and allows larger " +
                "models. Speed always depends on the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )

        SectionHeader("Language")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { language ->
                Pill(language.label, settings.language == language) {
                    vm.update { it.copy(language = language) }
                }
            }
        }

        SectionHeader("Clip selection")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClipMode.entries.forEach { mode ->
                Pill(mode.label, settings.clipMode == mode) {
                    vm.update { it.copy(clipMode = mode) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("All qualified", settings.clipCountMode == ClipCountMode.ALL_QUALIFIED) {
                vm.update { it.copy(clipCountMode = ClipCountMode.ALL_QUALIFIED) }
            }
            Pill("Custom count", settings.clipCountMode == ClipCountMode.CUSTOM) {
                vm.update { it.copy(clipCountMode = ClipCountMode.CUSTOM) }
            }
        }
        if (settings.clipCountMode == ClipCountMode.CUSTOM) {
            Text("Maximum clips per video: ${settings.customClipCount}", style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            Slider(
                value = settings.customClipCount.toFloat(),
                valueRange = 1f..20f,
                onValueChange = { value -> vm.update { it.copy(customClipCount = value.toInt().coerceAtLeast(1)) } }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("AI-decided duration", settings.durationMode == DurationMode.AI_DECIDED) {
                vm.update { it.copy(durationMode = DurationMode.AI_DECIDED) }
            }
            Pill("Custom duration", settings.durationMode == DurationMode.CUSTOM) {
                vm.update { it.copy(durationMode = DurationMode.CUSTOM) }
            }
        }
        Text(
            "Clip length: ${Formats.duration(settings.minClipMs)} – ${Formats.duration(settings.maxClipMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink
        )
        Slider(
            value = settings.minClipMs / 1000f,
            valueRange = 5f..60f,
            onValueChange = { value ->
                vm.update { it.copy(minClipMs = (value * 1000).toLong(), maxClipMs = maxOf(it.maxClipMs, (value * 1000).toLong() + 5_000)) }
            }
        )
        Slider(
            value = settings.maxClipMs / 1000f,
            valueRange = 20f..180f,
            onValueChange = { value ->
                vm.update { it.copy(maxClipMs = (value * 1000).toLong(), minClipMs = minOf(it.minClipMs, (value * 1000).toLong() - 2_000)) }
            }
        )

        SectionHeader("Output")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectRatio.entries.forEach { ratio ->
                Pill(ratio.label, settings.aspectRatio == ratio) {
                    vm.update { it.copy(aspectRatio = ratio) }
                }
            }
        }
        ToggleRow("Burn captions", settings.burnCaptions) { value -> vm.update { it.copy(burnCaptions = value) } }
        ToggleRow("Normalise audio", settings.normalizeAudio) { value -> vm.update { it.copy(normalizeAudio = value) } }
        ToggleRow("Auto-export to gallery", settings.autoExportToGallery) { value -> vm.update { it.copy(autoExportToGallery = value) } }
        ToggleRow("Download models on Wi-Fi only", settings.wifiOnlyDownloads) { value -> vm.update { it.copy(wifiOnlyDownloads = value) } }
        ToggleRow("Thermal-aware processing", settings.thermalThrottle) { value -> vm.update { it.copy(thermalThrottle = value) } }

        SectionHeader("Live monitoring")
        Text("Recording window: ${settings.liveSegmentSeconds}s", style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Slider(
            value = settings.liveSegmentSeconds.toFloat(),
            valueRange = 30f..180f,
            onValueChange = { value -> vm.update { it.copy(liveSegmentSeconds = value.toInt()) } }
        )

        SectionHeader("YouTube metadata (optional)")
        Text(
            "A YouTube Data API v3 key enables official live-status and title lookups. It is stored only on this " +
                "device and is never required for analysis.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )
        OutlinedTextField(
            value = if (showKey) (apiKey.ifBlank { settings.youtubeApiKey }) else (if (settings.youtubeApiKey.isBlank()) "" else "••••••••"),
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Save key") { vm.update { it.copy(youtubeApiKey = apiKey.trim()) } }
            SecondaryButton(if (showKey) "Hide" else "Show") { showKey = !showKey }
        }

        SectionHeader("Storage & performance")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Free", Formats.bytes(storage?.freeBytes ?: 0), null, Modifier.weight(1f))
            StatTile("Models", Formats.bytes(storage?.modelsBytes ?: 0), null, Modifier.weight(1f))
            StatTile("Working", Formats.bytes(storage?.workingBytes ?: 0), null, Modifier.weight(1f))
        }
        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Device", container.capabilities.model)
                InfoRow("Profile", container.capabilities.profile.label)
                InfoRow("Cores", container.capabilities.cores.toString())
                InfoRow("RAM", "${container.capabilities.ramMb} MB")
                InfoRow("ABI", container.capabilities.abi)
                InfoRow("Thermal status", container.capabilities.thermalStatus.toString())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Refresh") { vm.refreshStorage() }
                    SecondaryButton("Clear temp files") { vm.clearWorkingFiles() }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("AI models") { onModels() }
            }
        }

        SectionHeader("Privacy")
        Text(
            "Videos, audio, transcripts and clips stay on this device. Only model downloads and optional YouTube " +
                "metadata lookups use the network.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = appColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
