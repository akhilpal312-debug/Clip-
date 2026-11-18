package com.aiclipcreator.core.data

import android.content.Context
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.LiveStatus
import com.aiclipcreator.core.models.LiveStream
import com.aiclipcreator.core.storage.AppFiles
import com.aiclipcreator.core.video.VideoProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class LiveMonitorState(
    val running: Boolean = false,
    val streamId: String? = null,
    val streamTitle: String = "",
    val segmentIndex: Int = 0,
    val currentStage: String = "",
    val clipsProduced: Int = 0,
    val message: String = ""
)

/**
 * Rolling live pipeline: record a window of the live source, analyse it with the same engine used
 * for MP4s, produce clips, then continue with the next window. Recording is copy-based, so the
 * device is not re-encoding an endless stream.
 */
class LiveMonitor(
    private val context: Context,
    private val files: AppFiles,
    private val live: LiveRepository,
    private val videos: VideoRepository,
    private val jobs: JobRepository,
    private val clips: ClipRepository,
    private val prober: VideoProber,
    private val pipeline: AnalysisPipeline,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private val _state = MutableStateFlow(LiveMonitorState())
    val state: StateFlow<LiveMonitorState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun isRunningFor(streamId: String): Boolean = job?.isActive == true && _state.value.streamId == streamId

    fun start(stream: LiveStream, segmentSeconds: Int = 60) {
        if (job?.isActive == true) return
        job = scope.launch {
            var index = 0
            var clipsProduced = 0
            _state.value = LiveMonitorState(
                running = true,
                streamId = stream.id,
                streamTitle = stream.title,
                message = "Monitoring ${stream.title}"
            )
            val userSettings = settings.current()
            while (isActive) {
                val shouldContinue = try {
                    index++
                    _state.value = _state.value.copy(
                        segmentIndex = index,
                        currentStage = "Recording window $index",
                        running = true
                    )
                    val target = File(files.liveDir, "live_${stream.id}_${System.currentTimeMillis()}.mp4")
                    val recorded = live.recordWindow(stream, segmentSeconds, target)
                    if (recorded.isFailure) {
                        throw recorded.exceptionOrNull() ?: IllegalStateException("Recording failed")
                    }
                    _state.value = _state.value.copy(currentStage = "Analysing window $index")
                    val source = videos.registerFile(target, "Live: ${stream.title} #$index")
                        .getOrElse { throw it }
                    val jobEntity = jobs.create(
                        video = source,
                        settings = userSettings.copy(clipMode = ClipMode.BALANCED),
                        liveSessionId = stream.id
                    )
                    val result = pipeline.runJob(jobEntity.id) { stage, progress, detail ->
                        _state.value = _state.value.copy(
                            currentStage = "$stage ${(progress * 100).toInt()}% $detail"
                        )
                    }
                    if (result.isSuccess) {
                        val produced = clips.clipsForJob(jobEntity.id).size
                        clipsProduced += produced
                        live.update(stream.copy(status = LiveStatus.LIVE, clipsFound = clipsProduced, isLive = true))
                        _state.value = _state.value.copy(clipsProduced = clipsProduced)
                        push("Window $index analysed · $produced clip(s) · ${Formats.duration(segmentSeconds * 1000L)}")
                    } else {
                        push("Window $index failed: ${result.exceptionOrNull()?.message}")
                    }
                    // Keep the raw window only when a clip references it; otherwise reclaim space.
                    val anyClips = clips.clipsForJob(jobEntity.id).isNotEmpty()
                    if (!anyClips) target.delete()
                    true
                } catch (c: kotlinx.coroutines.CancellationException) {
                    false
                } catch (t: Throwable) {
                    Logx.e("Live window failed", t)
                    push("Live error: ${t.message}")
                    _state.value = _state.value.copy(currentStage = "Error: ${t.message}")
                    delay(5_000)
                    true
                }
                if (!shouldContinue) break
            }
            _state.value = _state.value.copy(running = false, currentStage = "Stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, currentStage = "Stopped")
    }

    private fun push(message: String) {
        Logx.i("[live] $message")
        _events.value = (_events.value + message).takeLast(40)
    }

    @Suppress("unused")
    private val cancelledFlag = AtomicBoolean(false)
}
