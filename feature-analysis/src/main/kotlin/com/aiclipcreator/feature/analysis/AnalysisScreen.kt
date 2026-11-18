package com.aiclipcreator.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.CandidateMoment
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.ProcessingJob
import com.aiclipcreator.core.models.ProcessingStage
import com.aiclipcreator.core.models.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AnalysisState(
    val job: ProcessingJob? = null,
    val candidates: List<CandidateMoment> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val showTranscript: Boolean = false
)

class AnalysisViewModel(private val container: AppContainer, private val jobId: String) : ViewModel() {

    private val showTranscript = MutableStateFlow(false)

    val state: StateFlow<AnalysisState> = combine(
        container.jobRepository.observe(jobId),
        container.clipRepository.observeCandidates(jobId),
        container.clipRepository.observeTranscript(jobId),
        container.clipRepository.observeClipsForJob(jobId),
        showTranscript
    ) { job, candidates, transcript, clips, show ->
        AnalysisState(job, candidates, transcript, clips, show)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState())

    fun toggleTranscript() {
        showTranscript.value = !showTranscript.value
    }

    fun resume() = viewModelScope.launch {
        container.jobRepository.resume(jobId)
        container.startProcessing()
    }

    fun pause() = viewModelScope.launch {
        container.jobRepository.pause(jobId)
        container.stopProcessing()
    }
}

@Composable
fun rememberAnalysisViewModel(container: AppContainer, jobId: String): AnalysisViewModel =
    viewModel(key = "analysis-$jobId") { AnalysisViewModel(container, jobId) }

@Composable
fun AnalysisScreen(
    container: AppContainer,
    jobId: String,
    onBack: () -> Unit,
    onOpenClip: (String) -> Unit
) {
    val vm = rememberAnalysisViewModel(container, jobId)
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = appColors

    ScreenScaffold(
        title = "Video analysis",
        subtitle = state.job?.videoName ?: "Job",
        onBack = onBack
    ) {
        val job = state.job
        if (job == null) {
            EmptyState("Job not found", "This job may have been deleted.")
            return@ScreenScaffold
        }

        SoftSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                ProgressBlock(
                    label = "${job.state.name.lowercase().replaceFirstChar { it.uppercase() }} · ${job.stage.label}",
                    progress = job.progress,
                    detail = job.error
                )
                Spacer(Modifier.height(14.dp))
                InfoRow("Duration", Formats.duration(job.durationMs))
                InfoRow("Mode", job.mode.label)
                InfoRow("Aspect", job.aspectRatio.label)
                InfoRow("Speech segments", state.transcript.size.toString())
                InfoRow("Detected moments", state.candidates.size.toString())
                InfoRow("Clips rendered", state.clips.size.toString())
                Spacer(Modifier.height(12.dp))
                if (job.isActive) {
                    SecondaryButton("Pause") { vm.pause() }
                } else if (job.state.name == "PAUSED" || job.state.name == "FAILED") {
                    PrimaryButton("Resume analysis") { vm.resume() }
                }
            }
        }

        SectionHeader("Pipeline")
        PipelineStages(current = job.stage)

        SectionHeader("AI detected moments (${state.candidates.size})")
        if (state.candidates.isEmpty()) {
            EmptyState(
                title = "No moments yet",
                message = "Moments appear once the speech and visual analysis stages finish."
            )
        }
        state.candidates.take(20).forEach { candidate ->
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Formats.duration(candidate.startMs)} – ${Formats.duration(candidate.endMs)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${candidate.category.label} · ${Formats.score(candidate.engagement)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        candidate.transcript.ifBlank { "(no speech in this moment)" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        candidate.reasons.sortedByDescending { it.weight }.take(3)
                            .joinToString(" · ") { "${it.signal.label}: ${it.detail}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                }
            }
        }

        SectionHeader("Clips from this video (${state.clips.size})")
        state.clips.forEach { clip ->
            SoftSurface(modifier = Modifier.fillMaxWidth(), onClick = { onOpenClip(clip.id) }) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(clip.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2)
                        Text(
                            "${clip.quality.label} · ${Formats.duration(clip.durationMs)}" +
                                if (clip.galleryUri != null) " · in gallery" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted
                        )
                    }
                    SecondaryButton("Open") { onOpenClip(clip.id) }
                }
            }
        }

        SectionHeader(
            title = "Transcript",
            actionLabel = if (state.showTranscript) "Hide" else "Show",
            onAction = { vm.toggleTranscript() }
        )
        if (state.showTranscript) {
            if (state.transcript.isEmpty()) {
                Text("No speech recognised yet.", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            }
            state.transcript.take(80).forEach { segment ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        Formats.duration(segment.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        modifier = Modifier.width(58.dp)
                    )
                    Text(
                        segment.text.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineStages(current: ProcessingStage) {
    val colors = appColors
    val stages = listOf(
        ProcessingStage.PROBE, ProcessingStage.AUDIO_EXTRACT, ProcessingStage.VAD,
        ProcessingStage.TRANSCRIBE, ProcessingStage.AUDIO_FEATURES, ProcessingStage.VISION,
        ProcessingStage.SCENE_DETECT, ProcessingStage.SCORE, ProcessingStage.RENDER,
        ProcessingStage.EXPORT
    )
    val currentIndex = stages.indexOf(current)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stages.forEachIndexed { index, stage ->
            val done = currentIndex > index
            val active = currentIndex == index
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (done) colors.success else if (active) colors.primary else colors.outline,
                    modifier = Modifier.size(14.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stage.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        done -> colors.inkMuted
                        active -> colors.ink
                        else -> colors.inkMuted.copy(alpha = 0.6f)
                    }
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text("running", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                }
            }
        }
    }
}
