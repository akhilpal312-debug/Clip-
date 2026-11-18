package com.aiclipcreator.feature.editor

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiclipcreator.core.ai.CaptionBuilder
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.EmptyState
import com.aiclipcreator.core.common.ui.InfoRow
import com.aiclipcreator.core.common.ui.Pill
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ProgressBlock
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.CaptionCue
import com.aiclipcreator.core.models.CaptionStyle
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.VideoMetadata
import com.aiclipcreator.core.video.ExportPresets
import com.aiclipcreator.core.video.RenderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class EditorState(
    val clip: Clip? = null,
    val cues: List<CaptionCue> = emptyList(),
    val aspect: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val style: CaptionStyle = CaptionStyle(),
    val burnCaptions: Boolean = true,
    val normalizeAudio: Boolean = true,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val rendering: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
    val renderedClip: Clip? = null
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(1_000)
}

class EditorViewModel(
    private val container: AppContainer,
    private val clipId: String
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var metadata: VideoMetadata? = null
    private var sourceUri: String? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val clip = container.clipRepository.clip(clipId) ?: return
        val transcript = container.clipRepository.transcript(clip.jobId)
        val cues = if (transcript.isNotEmpty()) {
            CaptionBuilder.cuesFor(transcript, clip.startMs, clip.endMs)
        } else {
            emptyList()
        }
        val source = container.videoRepository.byId(clip.sourceVideoId)
        metadata = source?.metadata
        sourceUri = source?.uri ?: Uri.fromFile(File(Uri.parse(clip.localFileUri).path ?: "")).toString()
        val prefs = container.settingsRepository.current()
        _state.value = EditorState(
            clip = clip,
            cues = cues,
            aspect = clip.aspectRatio,
            style = clip.captionStyle,
            burnCaptions = prefs.burnCaptions,
            trimStartMs = clip.startMs,
            trimEndMs = clip.endMs
        )
    }

    fun updateCue(index: Int, text: String) {
        val cues = _state.value.cues.toMutableList()
        val cue = cues.getOrNull(index) ?: return
        cues[index] = CaptionBuilder.retimed(cue, text)
        _state.value = _state.value.copy(cues = cues)
    }

    fun setAspect(aspect: AspectRatio) {
        _state.value = _state.value.copy(aspect = aspect)
    }

    fun setStyle(style: CaptionStyle) {
        _state.value = _state.value.copy(style = style)
    }

    fun setBurn(enabled: Boolean) {
        _state.value = _state.value.copy(burnCaptions = enabled)
    }

    fun setNormalize(enabled: Boolean) {
        _state.value = _state.value.copy(normalizeAudio = enabled)
    }

    fun setTrim(start: Long, end: Long) {
        _state.value = _state.value.copy(
            trimStartMs = start.coerceAtLeast(0),
            trimEndMs = end.coerceAtLeast(start + 1_000)
        )
    }

    fun render() = viewModelScope.launch {
        val state = _state.value
        val clip = state.clip ?: return@launch
        val metadata = metadata
        if (metadata == null) {
            _state.value = state.copy(message = "Source video is no longer available")
            return@launch
        }
        val uri = sourceUri?.let { Uri.parse(it) } ?: return@launch
        _state.value = state.copy(rendering = true, progress = 0f, message = "Rendering…")
        val settings = container.settingsRepository.current()
        val geometry = ExportPresets.resolve(state.aspect, metadata, settings.profile)
        val shift = state.trimStartMs - clip.startMs
        val cues = state.cues.map { cue ->
            val start = (cue.startMs - shift).coerceAtLeast(0)
            cue.copy(startMs = start, endMs = (cue.endMs - shift).coerceAtLeast(start + 1))
        }.filter { it.endMs > it.startMs }
        val output = container.files.clipFile(
            Formats.fileName(clip.title.ifBlank { "edited" }, "Edited")
        )
        val request = RenderRequest(
            sourceUri = uri,
            outputFile = output,
            startMs = state.trimStartMs,
            endMs = state.trimEndMs,
            aspect = state.aspect,
            geometry = geometry,
            sourceMetadata = metadata,
            captions = cues,
            captionStyle = state.style,
            burnCaptions = state.burnCaptions,
            normalizeAudio = state.normalizeAudio
        )
        val result = container.clipRenderer.render(request) { p ->
            _state.value = _state.value.copy(progress = p)
        }
        if (result.isFailure) {
            _state.value = _state.value.copy(
                rendering = false,
                message = "Render failed: ${result.exceptionOrNull()?.message}"
            )
            return@launch
        }
        val srt = CaptionBuilder.toSrt(cues)
        val updated = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            jobId = clip.jobId,
            candidateId = clip.candidateId,
            startMs = state.trimStartMs,
            endMs = state.trimEndMs,
            aspectRatio = state.aspect,
            localFileUri = Uri.fromFile(output).toString(),
            srt = srt,
            captionStyle = state.style,
            createdAt = System.currentTimeMillis(),
            galleryUri = null,
            exportedAt = null
        )
        container.clipRepository.saveClip(updated)
        val exported = container.clipRepository.export(updated, updated.quality.folder, output)
        _state.value = _state.value.copy(
            rendering = false,
            progress = 1f,
            renderedClip = updated,
            message = exported.fold(
                { "Rendered and saved to the gallery" },
                { "Rendered, but gallery export failed: ${it.message}" }
            )
        )
    }
}

@Composable
fun rememberEditorViewModel(container: AppContainer, clipId: String): EditorViewModel =
    viewModel(key = "editor-$clipId") { EditorViewModel(container, clipId) }

@Composable
fun EditorScreen(
    container: AppContainer,
    clipId: String,
    onBack: () -> Unit
) {
    val vm = rememberEditorViewModel(container, clipId)
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = appColors
    val clip = state.clip

    ScreenScaffold(title = "Clip editor", subtitle = clip?.title, onBack = onBack) {
        if (clip == null) {
            EmptyState("Clip unavailable", "Open a clip from the clips list.")
            return@ScreenScaffold
        }

        SectionHeader("Format")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectRatio.entries.forEach { ratio ->
                Pill(ratio.label, selected = state.aspect == ratio, onClick = { vm.setAspect(ratio) })
            }
        }
        Text(
            "Rendered at the highest resolution the source and this device support; sources are never upscaled.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )

        SectionHeader("Trim")
        val clipDuration = clip.endMs - clip.startMs
        Column {
            Text(
                "${Formats.duration(state.trimStartMs)} → ${Formats.duration(state.trimEndMs)} " +
                    "(${Formats.duration(state.durationMs)})",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink
            )
            Slider(
                value = (state.trimStartMs - clip.startMs).coerceIn(0L, clipDuration).toFloat(),
                valueRange = 0f..clipDuration.toFloat().coerceAtLeast(1f),
                onValueChange = { offset ->
                    val start = clip.startMs + offset.toLong()
                    vm.setTrim(start, maxOf(state.trimEndMs, start + 2_000))
                }
            )
            Slider(
                value = (state.trimEndMs - clip.startMs).toFloat().coerceIn(0f, clipDuration.toFloat()),
                valueRange = 0f..clipDuration.toFloat().coerceAtLeast(1f),
                onValueChange = { offset ->
                    vm.setTrim(state.trimStartMs, clip.startMs + offset.toLong())
                }
            )
        }

        SectionHeader("Captions")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Burn captions into video", style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Switch(checked = state.burnCaptions, onCheckedChange = { vm.setBurn(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Normalise audio", style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Switch(checked = state.normalizeAudio, onCheckedChange = { vm.setNormalize(it) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Caption size", style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
            Slider(
                value = state.style.fontSizeSp.toFloat(),
                valueRange = 10f..30f,
                onValueChange = { vm.setStyle(state.style.copy(fontSizeSp = it.toInt())) }
            )
            Text("Vertical position", style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
            Slider(
                value = state.style.verticalPosition,
                valueRange = 0.3f..0.95f,
                onValueChange = { vm.setStyle(state.style.copy(verticalPosition = it)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Bold", state.style.bold, onClick = { vm.setStyle(state.style.copy(bold = !state.style.bold)) })
                Pill("UPPERCASE", state.style.uppercase, onClick = { vm.setStyle(state.style.copy(uppercase = !state.style.uppercase)) })
                Pill(
                    "Light text",
                    state.style.textColor == 0xFFFFFFFF.toInt(),
                    onClick = { vm.setStyle(state.style.copy(textColor = 0xFFFFFFFF.toInt(), outlineColor = 0xFF000000.toInt())) }
                )
                Pill(
                    "Dark text",
                    state.style.textColor == 0xFF111111.toInt(),
                    onClick = { vm.setStyle(state.style.copy(textColor = 0xFF111111.toInt(), outlineColor = 0xFFFFFFFF.toInt())) }
                )
            }
        }

        SectionHeader("Caption editor (${state.cues.size} cues)")
        if (state.cues.isEmpty()) {
            Text(
                "No word-level transcript for this clip; captions will be empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted
            )
        }
        state.cues.forEachIndexed { index, cue ->
            var value by remember(cue.text) { mutableStateOf(TextFieldValue(cue.text)) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${Formats.duration(cue.startMs)} – ${Formats.duration(cue.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        vm.updateCue(index, it.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

        SectionHeader("Render")
        if (state.rendering) {
            ProgressBlock("Rendering clip", state.progress, "Encoding H.264 + AAC")
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        PrimaryButton(
            text = if (state.rendering) "Rendering…" else "Render and export",
            enabled = !state.rendering
        ) { vm.render() }

        SectionHeader("Export preset")
        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Container", "MP4 (H.264 + AAC)")
                InfoRow("Resolution", "Source-limited, no upscaling")
                InfoRow("Caption mode", if (state.burnCaptions) "Burned in + SRT sidecar" else "SRT sidecar")
                InfoRow("Duration", Formats.duration(state.durationMs))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Exports land in Movies/AI Clip Creator/${clip.quality.folder}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
