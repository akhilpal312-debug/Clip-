package com.aiclipcreator.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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
import com.aiclipcreator.core.common.ui.StatusDot
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.DeviceCapabilities
import com.aiclipcreator.core.models.JobState
import com.aiclipcreator.core.models.ProcessingJob
import com.aiclipcreator.core.models.StorageReport
import com.aiclipcreator.core.models.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class HomeState(
    val videoCount: Int = 0,
    val completedJobs: Int = 0,
    val activeJobs: List<ProcessingJob> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val modelsReady: Int = 0,
    val modelsTotal: Int = 0,
    val settings: UserSettings = UserSettings(),
    val storage: StorageReport? = null,
    val capabilities: DeviceCapabilities? = null
) {
    val hasActive: Boolean get() = activeJobs.isNotEmpty()
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val counts = combine(
        container.videoRepository.observeCount(),
        container.jobRepository.completedCount(),
        container.jobRepository.observeActive(),
        container.clipRepository.observeClips(),
        container.models.statuses
    ) { videos, completed, active, clips, models ->
        HomeState(
            videoCount = videos,
            completedJobs = completed,
            activeJobs = active,
            clips = clips,
            modelsReady = models.count { it.state.name == "READY" },
            modelsTotal = models.size
        )
    }

    val state: StateFlow<HomeState> = combine(
        counts,
        container.settingsRepository.settings
    ) { base, settings -> base.copy(settings = settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        viewModelScope.launch { container.models.refresh() }
        viewModelScope.launch {
            val storage = container.storageReport()
            val caps = container.capabilities
            storageSnapshot.value = storage to caps
        }
    }

    private val storageSnapshot = kotlinx.coroutines.flow.MutableStateFlow<Pair<StorageReport, DeviceCapabilities>?>(null)

    val extras: StateFlow<Pair<StorageReport, DeviceCapabilities>?> = storageSnapshot
}

@Composable
fun rememberHomeViewModel(container: AppContainer): HomeViewModel = viewModel { HomeViewModel(container) }

@Composable
fun HomeScreen(
    container: AppContainer,
    onImport: () -> Unit,
    onQueue: () -> Unit,
    onLive: () -> Unit,
    onClips: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onGallery: () -> Unit
) {
    val vm = rememberHomeViewModel(container)
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val colors = appColors

    ScreenScaffold(
        title = "AI Clip Creator",
        subtitle = "Find the moments worth sharing — entirely on this device"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Videos", state.videoCount.toString(), "in library", Modifier.weight(1f))
            StatTile("Clips", state.clips.size.toString(), "generated", Modifier.weight(1f))
            StatTile("Models", "${state.modelsReady}/${state.modelsTotal}", "ready", Modifier.weight(1f))
        }

        if (state.hasActive) {
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = true)
                        Spacer(Modifier.width(8.dp))
                        Text("Processing queue", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        Spacer(Modifier.weight(1f))
                        Text("${state.activeJobs.size} job(s)", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                    }
                    Spacer(Modifier.height(12.dp))
                    val job = state.activeJobs.first()
                    ProgressBlock(
                        label = job.videoName,
                        progress = job.progress,
                        detail = "${job.stage.label} · ${job.state.name.lowercase()}"
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Open queue", onClick = onQueue)
                }
            }
        }

        SectionHeader("Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Import MP4", Icons.Filled.Add, Modifier.weight(1f), onImport)
            QuickAction("Live stream", Icons.Filled.Podcasts, Modifier.weight(1f), onLive)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("AI models", Icons.Filled.CloudDownload, Modifier.weight(1f), onModels)
            QuickAction("Settings", Icons.Filled.Settings, Modifier.weight(1f), onSettings)
        }

        SectionHeader(
            title = "Latest clips",
            actionLabel = if (state.clips.isEmpty()) null else "View all",
            onAction = onClips
        )
        if (state.clips.isEmpty()) {
            EmptyState(
                title = "No clips yet",
                message = "Import a video, install a Whisper model and start analysis. Detected moments become " +
                    "captioned vertical clips saved to your gallery.",
                actionLabel = "Import a video",
                onAction = onImport
            )
        } else {
            state.clips.take(3).forEach { clip ->
                ClipRow(clip = clip, onOpen = onClips)
            }
        }

        extras?.let { (storage, caps) ->
            SectionHeader("This device")
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Hardware", "${caps.model} · ${caps.cores} cores")
                    InfoRow("Memory", "${caps.ramMb} MB total")
                    InfoRow("Profile", caps.profile.label)
                    InfoRow("Free storage", Formats.bytes(storage.freeBytes))
                    InfoRow("Models on disk", Formats.bytes(storage.modelsBytes))
                    InfoRow("Working files", Formats.bytes(storage.workingBytes))
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Gallery history", onClick = onGallery)
                }
            }
        }

        Text(
            "All analysis runs locally. Nothing is uploaded; model downloads are the only network traffic.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = appColors
    SoftSurface(modifier = modifier, onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.ink, maxLines = 1)
        }
    }
}

@Composable
private fun ClipRow(clip: Clip, onOpen: () -> Unit) {
    val colors = appColors
    SoftSurface(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = File(java.net.URI.create(clip.localFileUri).path ?: ""),
                contentDescription = clip.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(58.dp, 74.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    clip.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${clip.quality.label} · ${Formats.duration(clip.durationMs)} · ${clip.aspectRatio.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
            }
            if (clip.galleryUri != null) {
                StatusDot(active = true)
            } else {
                PrimaryButton("Open", onClick = onOpen)
            }
        }
    }
}
