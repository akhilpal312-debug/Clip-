package com.aiclipcreator.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ProgressBlock
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.StatTile
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.JobState
import com.aiclipcreator.core.models.ProcessingJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(private val container: AppContainer) : ViewModel() {

    val jobs: StateFlow<List<ProcessingJob>> = container.jobRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(job: ProcessingJob) = viewModelScope.launch {
        container.jobRepository.pause(job.id)
        container.stopProcessing()
    }

    fun resume(job: ProcessingJob) = viewModelScope.launch {
        container.jobRepository.resume(job.id)
        container.startProcessing()
    }

    fun retry(job: ProcessingJob) = viewModelScope.launch {
        container.jobRepository.retry(job.id)
        container.startProcessing()
    }

    fun cancel(job: ProcessingJob) = viewModelScope.launch {
        container.jobRepository.cancel(job.id)
        container.stopProcessing()
    }

    fun delete(job: ProcessingJob) = viewModelScope.launch {
        container.files.clearJob(job.id)
        container.clipRepository.deleteJobArtifacts(job.id)
        container.jobRepository.delete(job.id)
    }

    fun startQueue() = container.startProcessing()

    fun refreshInterrupted() = viewModelScope.launch { container.recoverInterruptedJobs() }
}

@Composable
fun rememberQueueViewModel(container: AppContainer): QueueViewModel = viewModel { QueueViewModel(container) }

@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit
) {
    val vm = rememberQueueViewModel(container)
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val colors = appColors
    val active = jobs.filter { it.isActive }
    val overall = if (active.isEmpty()) 0f else active.map { it.progress }.average().toFloat()

    ScreenScaffold(
        title = "Processing queue",
        subtitle = "Foreground processing with resumable checkpoints",
        onBack = onBack
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Queued", jobs.count { it.state == JobState.QUEUED }.toString(), null, Modifier.weight(1f))
            StatTile("Running", jobs.count { it.state == JobState.RUNNING }.toString(), null, Modifier.weight(1f))
            StatTile("Done", jobs.count { it.state == JobState.COMPLETED }.toString(), null, Modifier.weight(1f))
            StatTile("Failed", jobs.count { it.state == JobState.FAILED }.toString(), null, Modifier.weight(1f))
        }

        if (active.isNotEmpty()) {
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ProgressBlock("Overall progress", overall, "${active.size} active job(s)")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Start / continue", icon = Icons.Filled.PlayArrow) { vm.startQueue() }
                        SecondaryButton("Stop", icon = Icons.Filled.Pause) { vm.pause(active.first()) }
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
            EmptyState(
                title = "Nothing queued",
                message = "Import a video and queue it for analysis. Multiple videos run in priority order."
            )
        }

        SectionHeader("Jobs")
        jobs.forEach { job ->
            SoftSurface(modifier = Modifier.fillMaxWidth(), onClick = { onOpenJob(job.id) }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            job.videoName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            job.state.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (job.state) {
                                JobState.FAILED -> colors.danger
                                JobState.COMPLETED -> colors.success
                                JobState.PAUSED -> colors.warning
                                else -> colors.primary
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressBlock(
                        label = job.stage.label,
                        progress = job.progress,
                        detail = buildString {
                            append(Formats.duration(job.durationMs))
                            append(" · ")
                            append(job.mode.label)
                            job.error?.let { append(" · ").append(it) }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (job.state) {
                            JobState.RUNNING, JobState.QUEUED ->
                                SecondaryButton("Pause", icon = Icons.Filled.Pause) { vm.pause(job) }
                            JobState.PAUSED ->
                                PrimaryButton("Resume", icon = Icons.Filled.PlayArrow) { vm.resume(job) }
                            JobState.FAILED, JobState.CANCELLED ->
                                PrimaryButton("Retry", icon = Icons.Filled.Refresh) { vm.retry(job) }
                            else -> Unit
                        }
                        if (!job.isActive) {
                            SecondaryButton("Remove") { vm.delete(job) }
                        }
                    }
                }
            }
        }

        if (jobs.any { it.state == JobState.QUEUED }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Processing uses the foreground service, so you can leave the app. Jobs resume automatically " +
                    "after a reboot or a low-memory kill.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted
            )
        }
    }
}
