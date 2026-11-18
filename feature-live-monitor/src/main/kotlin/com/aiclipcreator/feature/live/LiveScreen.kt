package com.aiclipcreator.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.StatusDot
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.data.LiveMonitorState
import com.aiclipcreator.core.models.LiveSourceType
import com.aiclipcreator.core.models.LiveStatus
import com.aiclipcreator.core.models.LiveStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LiveViewModel(private val container: AppContainer) : ViewModel() {

    val streams: StateFlow<List<LiveStream>> = container.liveRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monitor: StateFlow<LiveMonitorState> = container.liveMonitor.state
    val events: StateFlow<List<String>> = container.liveMonitor.events

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addStream(url: String, title: String) {
        if (url.isBlank()) {
            _message.value = "Paste an HLS (.m3u8) or progressive HTTP video URL"
            return
        }
        viewModelScope.launch {
            _message.value = "Validating stream…"
            val settings = container.settingsRepository.current()
            val stream = container.liveRepository.add(url, title, LiveSourceType.HLS, settings.youtubeApiKey)
            _message.value = when (stream.status) {
                LiveStatus.LIVE -> "Stream added and reachable"
                LiveStatus.OFFLINE -> "Stream added, but it does not look live right now"
                LiveStatus.UNSUPPORTED -> stream.error ?: "Unsupported source"
                else -> "Stream added"
            }
        }
    }

    fun start(stream: LiveStream) {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            container.liveMonitor.start(stream, settings.liveSegmentSeconds)
        }
    }

    fun stop() = container.liveMonitor.stop()

    fun remove(stream: LiveStream) {
        viewModelScope.launch { container.liveRepository.delete(stream.id) }
    }

    fun refresh(stream: LiveStream) {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            container.liveRepository.refresh(stream, settings.youtubeApiKey)
            _message.value = "Status refreshed"
        }
    }
}

@Composable
fun rememberLiveViewModel(container: AppContainer): LiveViewModel = viewModel { LiveViewModel(container) }

@Composable
fun LiveScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm = rememberLiveViewModel(container)
    val streams by vm.streams.collectAsStateWithLifecycle()
    val monitor by vm.monitor.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val colors = appColors
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    ScreenScaffold(
        title = "Live monitor",
        subtitle = "Authorised HLS / HTTP sources, analysed locally",
        onBack = onBack
    ) {
        if (monitor.running) {
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = true)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Monitoring ${monitor.streamTitle}",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.ink
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Window", monitor.segmentIndex.toString())
                    InfoRow("Stage", monitor.currentStage)
                    InfoRow("Clips produced", monitor.clipsProduced.toString())
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Stop monitoring", icon = Icons.Filled.Stop) { vm.stop() }
                }
            }
        }

        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Add a live source", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL (.m3u8 or .mp4/.ts)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Validate & add", icon = Icons.Filled.Podcasts) { vm.addStream(url, title) }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "YouTube watch pages are not raw feeds. Add the creator's HLS URL to record; a YouTube Data " +
                        "API key in Settings enables metadata and live-status lookups only. DRM is never bypassed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
            }
        }

        SectionHeader("Sources (${streams.size})")
        if (streams.isEmpty()) {
            EmptyState(
                title = "No live sources",
                message = "Point the monitor at an HLS playlist you are authorised to record. Each recording " +
                    "window is analysed with the same engine as imported files."
            )
        }
        streams.forEach { stream ->
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = stream.isLive)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stream.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.ink,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stream.status.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (stream.status) {
                                LiveStatus.LIVE -> colors.success
                                LiveStatus.UNSUPPORTED, LiveStatus.ERROR -> colors.danger
                                else -> colors.inkMuted
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stream.url, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, maxLines = 2)
                    InfoRow("Last checked", Formats.dateTime(stream.lastCheckedAt))
                    InfoRow("Clips found", stream.clipsFound.toString())
                    stream.error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Monitor", icon = Icons.Filled.PlayArrow) { vm.start(stream) }
                        SecondaryButton("Check") { vm.refresh(stream) }
                        SecondaryButton("Remove") { vm.remove(stream) }
                    }
                }
            }
        }

        if (events.isNotEmpty()) {
            SectionHeader("Monitor log")
            Column {
                events.takeLast(10).reversed().forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
