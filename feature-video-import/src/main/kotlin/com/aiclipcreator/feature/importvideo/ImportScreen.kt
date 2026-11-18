package com.aiclipcreator.feature.importvideo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aiclipcreator.core.common.ui.InlineLoading
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ImportState(val importing: Boolean = false, val message: String? = null)

class ImportViewModel(private val container: AppContainer) : ViewModel() {

    val videos: StateFlow<List<VideoSource>> = container.videoRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow(ImportState())
    val status: StateFlow<ImportState> = _status

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _status.value = ImportState(importing = true)
            var imported = 0
            val failures = mutableListOf<String>()
            for (uri in uris) {
                val result = container.importVideo(uri)
                if (result.isSuccess) imported++ else failures += (result.exceptionOrNull()?.message ?: "Unknown error")
            }
            _status.value = ImportState(
                importing = false,
                message = buildString {
                    append("Imported $imported file(s).")
                    if (failures.isNotEmpty()) append(" Failed: ${failures.joinToString("; ").take(200)}")
                }
            )
        }
    }

    fun analyse(videos: List<VideoSource>) {
        if (videos.isEmpty()) return
        viewModelScope.launch {
            _status.value = ImportState(importing = true, message = "Queueing ${videos.size} video(s)…")
            val settings = container.settingsRepository.current()
            container.enqueueAll(videos, settings)
            _status.value = ImportState(
                message = "Queued ${videos.size} video(s). Processing starts from the queue screen."
            )
        }
    }

    fun delete(video: VideoSource) {
        viewModelScope.launch { container.videoRepository.delete(video.id) }
    }

    fun clearStatus() {
        _status.value = ImportState()
    }
}

@Composable
fun rememberImportViewModel(container: AppContainer): ImportViewModel = viewModel { ImportViewModel(container) }

@Composable
fun VideoImportScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onQueue: () -> Unit
) {
    val vm = rememberImportViewModel(container)
    val state by vm.videos.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val colors = appColors
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val videoUris = uris.filter { uri ->
            val type = container.appContext.contentResolver.getType(uri) ?: ""
            type.startsWith("video/") || type == "application/octet-stream"
        }
        vm.import(if (videoUris.isEmpty()) uris else videoUris)
    }

    ScreenScaffold(
        title = "Import video",
        subtitle = "MP4 and other supported containers, any length",
        onBack = onBack
    ) {
        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    "Files are opened in place through the system picker. Nothing is copied, compressed or " +
                        "uploaded before analysis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = "Choose videos",
                        icon = Icons.Filled.Add,
                        onClick = { picker.launch(arrayOf("video/*")) }
                    )
                }
                if (status.importing) {
                    Spacer(Modifier.height(8.dp))
                    InlineLoading("Reading metadata…")
                }
                status.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                }
            }
        }

        SectionHeader("Imported videos (${state.size})")
        if (state.isEmpty()) {
            EmptyState(
                title = "Library is empty",
                message = "Pick one or more videos. Multi-hour recordings are processed in chunks, so long " +
                    "files are supported."
            )
        }
        state.forEach { video ->
            val isSelected = video.id in selected
            SoftSurface(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selected = if (isSelected) selected - video.id else selected + video.id
                }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = video.thumbnailUri?.let { File(it) },
                        contentDescription = video.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp, 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            video.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${Formats.duration(video.metadata.durationMs)} · " +
                                "${video.metadata.displayWidth}×${video.metadata.displayHeight} · " +
                                Formats.bytes(video.metadata.sizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted
                        )
                        Text(
                            if (video.metadata.hasAudio) "Audio track detected" else "No audio track",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (video.metadata.hasAudio) colors.success else colors.warning
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove from library",
                        tint = colors.inkMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }

        if (state.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = if (selected.isEmpty()) "Select all" else "Clear selection",
                    onClick = {
                        selected = if (selected.isEmpty()) state.map { it.id }.toSet() else emptySet()
                    }
                )
                PrimaryButton(
                    text = "Queue ${if (selected.isEmpty()) state.size else selected.size} for analysis",
                    icon = Icons.Filled.PlayArrow,
                    onClick = {
                        val target = if (selected.isEmpty()) state else state.filter { it.id in selected }
                        vm.analyse(target)
                        onQueue()
                    }
                )
            }
        }
    }
}
