package com.aiclipcreator.core.data

import android.content.Context
import android.net.Uri
import com.aiclipcreator.core.ai.ClipDetector
import com.aiclipcreator.core.ai.ModelManager
import com.aiclipcreator.core.ai.ModelRegistry
import com.aiclipcreator.core.audio.AudioAnalyzer
import com.aiclipcreator.core.audio.AudioExtractor
import com.aiclipcreator.core.audio.VoiceActivityDetector
import com.aiclipcreator.core.common.DeviceProfiler
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.database.AiClipDatabase
import com.aiclipcreator.core.models.DeviceCapabilities
import com.aiclipcreator.core.models.ProcessingJob
import com.aiclipcreator.core.models.StorageReport
import com.aiclipcreator.core.models.UserSettings
import com.aiclipcreator.core.models.VideoSource
import com.aiclipcreator.core.network.ResumableDownloader
import com.aiclipcreator.core.network.YouTubeApiClient
import com.aiclipcreator.core.storage.AppFiles
import com.aiclipcreator.core.storage.MediaStoreExporter
import com.aiclipcreator.core.video.ClipRenderer
import com.aiclipcreator.core.video.FrameSampler
import com.aiclipcreator.core.video.VideoProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single place where the app's object graph is built. Passed to feature ViewModels, which keeps
 * the modules free of a DI framework while staying testable.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AiClipDatabase = AiClipDatabase.get(appContext)
    val files = AppFiles(appContext)
    val exporter = MediaStoreExporter(appContext)
    val capabilities: DeviceCapabilities = DeviceProfiler.capabilities(appContext)
    val youtube = YouTubeApiClient()
    val downloader = ResumableDownloader()

    val settingsRepository = SettingsRepository(appContext)
    val modelRepository = ModelRepository(database.modelDao())
    val models = ModelManager(appContext, files, downloader, modelRepository, capabilities)

    val prober = VideoProber(appContext)
    val videoRepository = VideoRepository(appContext, database.videoDao(), prober, files)
    val jobRepository = JobRepository(database.jobDao(), database.checkpointDao())
    val clipRepository = ClipRepository(
        database.clipDao(),
        database.candidateDao(),
        database.transcriptDao(),
        database.exportDao(),
        exporter
    )
    val liveRepository = LiveRepository(appContext, database.liveDao(), youtube)

    val audioExtractor = AudioExtractor(appContext)
    val voiceActivity = VoiceActivityDetector()
    val audioAnalyzer = AudioAnalyzer()
    val frameSampler = FrameSampler(appContext)
    val clipRenderer = ClipRenderer(appContext)
    val clipDetector = ClipDetector()

    val pipeline = AnalysisPipeline(
        context = appContext,
        files = files,
        prober = prober,
        audioExtractor = audioExtractor,
        sampler = frameSampler,
        voiceActivity = voiceActivity,
        audioAnalyzer = audioAnalyzer,
        models = models,
        detector = clipDetector,
        renderer = clipRenderer,
        exporter = exporter,
        videos = videoRepository,
        jobs = jobRepository,
        clips = clipRepository,
        settingsRepo = settingsRepository
    )

    val liveMonitor = LiveMonitor(
        context = appContext,
        files = files,
        live = liveRepository,
        videos = videoRepository,
        jobs = jobRepository,
        clips = clipRepository,
        prober = prober,
        pipeline = pipeline,
        settings = settingsRepository,
        scope = scope
    )

    /** Wired by the app module to the foreground processing service. */
    var processingStarter: (() -> Unit)? = null
    var processingStopper: (() -> Unit)? = null

    fun startProcessing() {
        processingStarter?.invoke()
    }

    fun stopProcessing() {
        processingStopper?.invoke()
    }

    private val _processingJobId = MutableStateFlow<String?>(null)
    val processingJobId: StateFlow<String?> = _processingJobId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /** Imports a document picked through SAF and persists its metadata + thumbnail. */
    suspend fun importVideo(uri: Uri): Result<VideoSource> =
        videoRepository.import(uri).onFailure { _lastError.value = it.message }

    /** Registers an app-owned file (live recording) as a video source. */
    suspend fun registerRecording(file: File, displayName: String): Result<VideoSource> =
        videoRepository.registerFile(file, displayName)

    suspend fun enqueue(video: VideoSource, settings: UserSettings): ProcessingJob =
        jobRepository.create(video, settings)

    suspend fun enqueueAll(videos: List<VideoSource>, settings: UserSettings): List<ProcessingJob> =
        videos.map { jobRepository.create(it, settings) }

    /** Runs the next queued job. Returns true when another job is still waiting. */
    suspend fun processNextQueuedJob(): Boolean = withContext(Dispatchers.Default) {
        val job = jobRepository.nextActive()
        if (job == null) {
            _processingJobId.value = null
            return@withContext false
        }
        _processingJobId.value = job.id
        try {
            pipeline.runJob(job.id)
        } catch (c: kotlinx.coroutines.CancellationException) {
            Logx.i("Processing cancelled for ${job.id}")
        } catch (t: Throwable) {
            Logx.e("Processing crashed", t)
            _lastError.value = t.message
        } finally {
            _processingJobId.value = null
        }
        jobRepository.nextActive() != null
    }

    suspend fun recoverInterruptedJobs(): Int = jobRepository.recoverInterrupted()

    suspend fun storageReport(): StorageReport = withContext(Dispatchers.IO) { files.report() }

    fun refreshModels() {
        scope.launch { models.refresh() }
    }

    suspend fun modelSummary(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val ready = modelRepository.all().count { it.state == com.aiclipcreator.core.models.ModelState.READY }
        ready to ModelRegistry.all.size
    }

    fun shutdown() {
        liveMonitor.stop()
        models.release()
    }
}
