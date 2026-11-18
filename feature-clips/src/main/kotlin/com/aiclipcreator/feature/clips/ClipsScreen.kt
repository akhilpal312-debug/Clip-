package com.aiclipcreator.feature.clips

import android.net.Uri
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.EmptyState
import com.aiclipcreator.core.common.ui.Pill
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.GalleryClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class ClipTab(val label: String) { BEST("Best Moments"), BALANCED("Balanced Clips"), ALL("All") }

class ClipsViewModel(private val container: AppContainer) : ViewModel() {

    private val tab = MutableStateFlow(ClipTab.ALL)
    private val gallery = MutableStateFlow<List<GalleryClip>>(emptyList())

    val currentTab: StateFlow<ClipTab> = tab
    val galleryClips: StateFlow<List<GalleryClip>> = gallery

    val clips: StateFlow<List<Clip>> = combine(
        container.clipRepository.observeClips(),
        tab
    ) { all, selected ->
        when (selected) {
            ClipTab.ALL -> all
            ClipTab.BEST -> all.filter { it.quality == com.aiclipcreator.core.models.ClipQuality.BEST }
            ClipTab.BALANCED -> all.filter { it.quality == com.aiclipcreator.core.models.ClipQuality.BALANCED }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTab(value: ClipTab) {
        tab.value = value
    }

    fun refreshGallery() = viewModelScope.launch {
        gallery.value = container.clipRepository.galleryHistory()
    }

    fun delete(clip: Clip) = viewModelScope.launch {
        container.clipRepository.deleteClip(clip)
        gallery.value = container.clipRepository.galleryHistory()
    }

    fun export(clip: Clip) = viewModelScope.launch {
        val file = File(Uri.parse(clip.localFileUri).path ?: return@launch)
        container.clipRepository.export(clip, clip.quality.folder, file)
        gallery.value = container.clipRepository.galleryHistory()
    }

    fun deleteGallery(uri: String) = viewModelScope.launch {
        runCatching { container.exporter.delete(Uri.parse(uri)) }
        gallery.value = container.clipRepository.galleryHistory()
    }
}

@Composable
fun rememberClipsViewModel(container: AppContainer): ClipsViewModel = viewModel { ClipsViewModel(container) }

@Composable
fun ClipsScreen(
    container: AppContainer,
    onOpenClip: (String) -> Unit,
    initialTab: ClipTab = ClipTab.ALL
) {
    val vm = rememberClipsViewModel(container)
    val clips by vm.clips.collectAsStateWithLifecycle()
    val tab by vm.currentTab.collectAsStateWithLifecycle()
    val colors = appColors
    val context = LocalContext.current
    var initialised by remember { mutableStateOf(false) }
    if (!initialised) {
        vm.selectTab(initialTab)
        initialised = true
    }

    ScreenScaffold(
        title = "Clips",
        subtitle = "Generated highlights, ready to publish"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClipTab.entries.forEach { item ->
                Pill(item.label, selected = tab == item, onClick = { vm.selectTab(item) })
            }
        }

        if (clips.isEmpty()) {
            EmptyState(
                title = "No clips in this view",
                message = "Clips are created during analysis. Best Moments use a strict threshold; " +
                    "Balanced Clips include more variety."
            )
        }

        clips.forEach { clip ->
            SoftSurface(modifier = Modifier.fillMaxWidth(), onClick = { onOpenClip(clip.id) }) {
                Row(modifier = Modifier.padding(12.dp)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(Uri.parse(clip.localFileUri).path ?: ""))
                            .videoFrameMillis(1_000)
                            .build(),
                        contentDescription = clip.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp, 96.dp)
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
                        Text(
                            "Source ${Formats.duration(clip.startMs)} → ${Formats.duration(clip.endMs)}" +
                                if (clip.galleryUri != null) " · exported" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Preview") { onOpenClip(clip.id) }
                            SecondaryButton("Share", icon = Icons.Filled.Share) {
                                val uri = clip.galleryUri ?: return@SecondaryButton
                                context.startActivity(container.exporter.shareIntent(Uri.parse(uri)))
                            }
                        }
                    }
                }
            }
        }

        SectionHeader("Gallery history", actionLabel = "Refresh", onAction = { vm.refreshGallery() })
        val gallery by vm.galleryClips.collectAsStateWithLifecycle()
        if (gallery.isEmpty()) {
            SecondaryButton("Scan gallery") { vm.refreshGallery() }
        }
        gallery.forEach { exported ->
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(exported.displayName, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1)
                        Text(
                            "${exported.relativePath} · ${Formats.bytes(exported.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                            maxLines = 1
                        )
                    }
                    SecondaryButton("Open") {
                        runCatching { context.startActivity(container.exporter.playIntent(Uri.parse(exported.uri))) }
                    }
                }
            }
        }
    }
}
