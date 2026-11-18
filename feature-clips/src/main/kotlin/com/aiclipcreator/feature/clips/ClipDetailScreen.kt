package com.aiclipcreator.feature.clips

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.EmptyState
import com.aiclipcreator.core.common.ui.InfoRow
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.GalleryClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ClipDetailState(
    val clip: Clip? = null,
    val message: String? = null,
    val busy: Boolean = false
)

class ClipDetailViewModel(private val container: AppContainer, private val clipId: String) : ViewModel() {
    private val _state = MutableStateFlow(ClipDetailState())
    val state: StateFlow<ClipDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(clip = container.clipRepository.clip(clipId))
        }
    }

    fun export() = viewModelScope.launch {
        val clip = _state.value.clip ?: return@launch
        _state.value = _state.value.copy(busy = true)
        val path = Uri.parse(clip.localFileUri).path
        if (path == null) {
            _state.value = _state.value.copy(busy = false, message = "Clip file missing")
            return@launch
        }
        val result = container.clipRepository.export(clip, clip.quality.folder, File(path))
        _state.value = _state.value.copy(
            busy = false,
            message = result.fold(
                onSuccess = { "Saved to Movies/AI Clip Creator/${clip.quality.folder}" },
                onFailure = { "Export failed: ${it.message}" }
            ),
            clip = container.clipRepository.clip(clipId)
        )
    }

    fun delete() = viewModelScope.launch {
        val clip = _state.value.clip ?: return@launch
        container.clipRepository.deleteClip(clip)
        _state.value = _state.value.copy(message = "Clip deleted", clip = null)
    }

    fun rename(newName: String) = viewModelScope.launch {
        val clip = _state.value.clip ?: return@launch
        val result = container.clipRepository.renameClipInGallery(clip, newName)
        _state.value = _state.value.copy(
            message = result.fold({ "Renamed" }, { "Rename failed: ${it.message}" }),
            clip = container.clipRepository.clip(clipId)
        )
    }
}

@Composable
fun rememberClipDetailViewModel(container: AppContainer, clipId: String): ClipDetailViewModel =
    viewModel(key = "clip-$clipId") { ClipDetailViewModel(container, clipId) }

@Composable
fun ClipDetailScreen(
    container: AppContainer,
    clipId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onGallery: () -> Unit
) {
    val vm = rememberClipDetailViewModel(container, clipId)
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = appColors
    val context = LocalContext.current
    val clip = state.clip
    var gallery by remember { mutableStateOf<List<GalleryClip>>(emptyList()) }

    LaunchedEffect(clip?.galleryUri) {
        gallery = container.clipRepository.galleryHistory()
    }

    ScreenScaffold(title = "Clip preview", subtitle = clip?.title, onBack = onBack) {
        if (clip == null) {
            EmptyState("Clip unavailable", "It may have been deleted.")
            return@ScreenScaffold
        }
        val player = remember { ExoPlayer.Builder(context).build() }
        DisposableEffect(clip.id) {
            val path = Uri.parse(clip.localFileUri).path
            if (path != null) {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                player.prepare()
            }
            onDispose { player.release() }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(420.dp)
            )
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }

        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Quality", clip.quality.label)
                InfoRow("Duration", Formats.duration(clip.durationMs))
                InfoRow("Format", clip.aspectRatio.label)
                InfoRow("Source", clip.sourceName)
                InfoRow(
                    "Timestamp",
                    "${Formats.duration(clip.startMs)} → ${Formats.duration(clip.endMs)}"
                )
                InfoRow("Text source", clip.textSource)
                InfoRow("Gallery", if (clip.galleryUri != null) "Exported" else "Not exported")
                if (clip.hashtags.isNotEmpty()) {
                    InfoRow("Hashtags", clip.hashtags.joinToString(" "))
                }
            }
        }

        SectionHeader("Description")
        Text(clip.description, style = MaterialTheme.typography.bodyLarge, color = colors.ink)

        SectionHeader("Captions", actionLabel = "Edit", onAction = onEdit)
        Text(
            clip.srt.lines().take(14).joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (state.busy) "Exporting…" else "Export to gallery", enabled = !state.busy) { vm.export() }
            SecondaryButton("Edit") { onEdit() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Share") {
                val uri = clip.galleryUri ?: return@SecondaryButton
                runCatching { context.startActivity(container.exporter.shareIntent(Uri.parse(uri))) }
            }
            SecondaryButton("Open gallery") { onGallery() }
            SecondaryButton("Delete") { vm.delete() }
        }

        SectionHeader("Export settings")
        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Aspect ratio, captions and frame-accurate trimming are changed in the editor, which " +
                        "re-renders the clip at the source resolution supported by this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Open editor") { onEdit() }
                if (gallery.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${gallery.size} clip(s) in Movies/AI Clip Creator",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
