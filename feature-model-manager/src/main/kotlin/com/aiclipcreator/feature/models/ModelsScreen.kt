package com.aiclipcreator.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.EmptyState
import com.aiclipcreator.core.common.ui.InfoRow
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ProgressBlock
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.StatTile
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.ModelInstallEvent
import com.aiclipcreator.core.models.ModelState
import com.aiclipcreator.core.models.ModelStatus
import com.aiclipcreator.core.models.StorageReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    val statuses: StateFlow<List<ModelStatus>> = container.models.statuses
    val events: StateFlow<List<ModelInstallEvent>> = container.models.events

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _storage = MutableStateFlow<StorageReport?>(null)
    val storage: StateFlow<StorageReport?> = _storage

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            container.models.refresh()
            _storage.value = container.storageReport()
        }
    }

    fun install(status: ModelStatus) {
        val issue = container.models.compatibilityIssue(status.spec)
        if (issue != null) {
            _message.value = issue
            return
        }
        container.models.install(status.spec.id) { result ->
            result.exceptionOrNull()?.let { error ->
                _message.value = "Install failed: ${error.message}"
            }
        }
    }

    fun installAll() {
        val ids = container.models.suggestedInstallIds()
        if (ids.isEmpty()) {
            _message.value = "No compatible models for this device"
            return
        }
        val bytes = container.models.estimateDownloadBytes(ids)
        _message.value = "Installing ${ids.size} model(s), ${Formats.bytes(bytes)} total"
        container.models.installAll(ids)
    }

    fun pause(id: String) = container.models.pause(id)

    fun resume(id: String) = container.models.resume(id)

    fun retry(id: String) = container.models.retry(id)

    fun delete(id: String) {
        viewModelScope.launch {
            val result = container.models.delete(id)
            _message.value = result.fold({ "Model removed" }, { "Delete failed: ${it.message}" })
        }
    }

    fun verify(id: String) {
        viewModelScope.launch {
            val result = container.models.verify(id)
            _message.value = result.fold(
                { if (it) "Model verified and self-test passed" else "Verified, but self-test failed" },
                { "Verification failed: ${it.message}" }
            )
        }
    }
}

@Composable
fun rememberModelsViewModel(container: AppContainer): ModelsViewModel = viewModel { ModelsViewModel(container) }

@Composable
fun ModelsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm = rememberModelsViewModel(container)
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val colors = appColors

    val ready = statuses.count { it.state == ModelState.READY }
    val totalBytes = statuses.sumOf { it.spec.sizeBytes }
    val installing = statuses.count { it.state == ModelState.DOWNLOADING || it.state == ModelState.VERIFYING }

    ScreenScaffold(
        title = "AI models",
        subtitle = "Local runtimes, verified artifacts and downloads",
        onBack = onBack
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Ready", "$ready/${statuses.size}", null, Modifier.weight(1f))
            StatTile("Download", Formats.bytes(totalBytes), "full set", Modifier.weight(1f))
            StatTile("Active", installing.toString(), "installs", Modifier.weight(1f))
        }

        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Everything runs on device: whisper.cpp for speech, LiteRT for vision, ONNX Runtime for " +
                        "voice activity and llama.cpp for optional caption writing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Install all compatible", icon = Icons.Filled.CloudDownload) { vm.installAll() }
                    SecondaryButton("Refresh", icon = Icons.Filled.Refresh) { vm.refresh() }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                }
                storage?.let {
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Free storage", Formats.bytes(it.freeBytes))
                    InfoRow("Models on disk", Formats.bytes(it.modelsBytes))
                    InfoRow("Device profile", container.capabilities.profile.label)
                    InfoRow("Inference threads", container.models.threadCount.toString())
                }
            }
        }

        SectionHeader("Artifacts")
        statuses.forEach { status ->
            val spec = status.spec
            val issue = container.models.compatibilityIssue(spec)
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                            Text(
                                "${spec.category.label} · ${Formats.bytes(spec.sizeBytes)} · v${spec.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.inkMuted
                            )
                        }
                        Text(
                            status.state.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (status.state) {
                                ModelState.READY -> colors.success
                                ModelState.FAILED, ModelState.UNSUPPORTED -> colors.danger
                                ModelState.DOWNLOADING, ModelState.VERIFYING -> colors.primary
                                else -> colors.inkMuted
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        spec.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted
                    )
                    if (status.state == ModelState.DOWNLOADING || status.state == ModelState.VERIFYING) {
                        Spacer(Modifier.height(8.dp))
                        ProgressBlock(
                            label = "Installing",
                            progress = status.progress,
                            detail = Formats.bytes(status.downloadedBytes)
                        )
                    }
                    (issue ?: status.error)?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (status.state) {
                            ModelState.NOT_INSTALLED, ModelState.FAILED ->
                                PrimaryButton("Install", icon = Icons.Filled.CloudDownload) { vm.install(status) }
                            ModelState.DOWNLOADING ->
                                SecondaryButton("Pause", icon = Icons.Filled.Pause) { vm.pause(spec.id) }
                            ModelState.READY -> {
                                SecondaryButton("Verify") { vm.verify(spec.id) }
                                SecondaryButton("Delete", icon = Icons.Filled.Delete) { vm.delete(spec.id) }
                            }
                            else -> Unit
                        }
                        if (status.state == ModelState.FAILED) {
                            SecondaryButton("Retry") { vm.retry(spec.id) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "License: ${spec.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                    Text(
                        "Source: ${spec.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                    Text(
                        "SHA-256: ${spec.sha256.take(24)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                }
            }
        }

        SectionHeader("Download manager")
        if (events.isEmpty()) {
            EmptyState("No download activity", "Install a model to see live progress, checksum verification and self-test results.")
        }
        events.takeLast(12).reversed().forEach { event ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.stage,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier.width(78.dp)
                )
                Text(
                    "${event.modelId}: ${event.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
