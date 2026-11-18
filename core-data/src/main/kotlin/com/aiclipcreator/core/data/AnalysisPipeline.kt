package com.aiclipcreator.core.data

import android.content.Context
import android.net.Uri
import com.aiclipcreator.core.ai.CaptionBuilder
import com.aiclipcreator.core.ai.ClipDetector
import com.aiclipcreator.core.ai.ClipTextGenerator
import com.aiclipcreator.core.ai.DetectionInput
import com.aiclipcreator.core.ai.ModelManager
import com.aiclipcreator.core.ai.ModelRegistry
import com.aiclipcreator.core.audio.AudioAnalyzer
import com.aiclipcreator.core.audio.AudioConfig
import com.aiclipcreator.core.audio.AudioExtractor
import com.aiclipcreator.core.audio.VoiceActivityDetector
import com.aiclipcreator.core.audio.WavReader
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.AudioFeatureFrame
import com.aiclipcreator.core.models.CandidateMoment
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.ClipCountMode
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.ClipQuality
import com.aiclipcreator.core.models.FrameAnalysis
import com.aiclipcreator.core.models.JobState
import com.aiclipcreator.core.models.ModelState
import com.aiclipcreator.core.models.ProcessingStage
import com.aiclipcreator.core.models.ProductionProfile
import com.aiclipcreator.core.models.RenderResult
import com.aiclipcreator.core.models.SceneCut
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.UserSettings
import com.aiclipcreator.core.models.VideoMetadata
import com.aiclipcreator.core.storage.AppFiles
import com.aiclipcreator.core.storage.MediaStoreExporter
import com.aiclipcreator.core.video.ClipRenderer
import com.aiclipcreator.core.video.ExportGeometry
import com.aiclipcreator.core.video.ExportPresets
import com.aiclipcreator.core.video.FrameSampler
import com.aiclipcreator.core.video.RenderRequest
import com.aiclipcreator.core.video.SceneDetector
import com.aiclipcreator.core.video.VideoProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * End-to-end analysis and clip production for one job.
 *
 * Every stage checkpoints its output, so a job interrupted by a reboot, a low-memory kill or a
 * user pause continues from the last completed stage instead of restarting hours of work.
 */
class AnalysisPipeline(
    private val context: Context,
    private val files: AppFiles,
    private val prober: VideoProber,
    private val audioExtractor: AudioExtractor,
    private val sampler: FrameSampler,
    private val voiceActivity: VoiceActivityDetector,
    private val audioAnalyzer: AudioAnalyzer,
    private val models: ModelManager,
    private val detector: ClipDetector,
    private val renderer: ClipRenderer,
    private val exporter: MediaStoreExporter,
    private val videos: VideoRepository,
    private val jobs: JobRepository,
    private val clips: ClipRepository,
    private val settingsRepo: SettingsRepository
) {

    private val captionGenerator = ClipTextGenerator(models.llm)

    suspend fun runJob(
        jobId: String,
        onUpdate: (ProcessingStage, Float, String) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val job = jobs.byId(jobId) ?: return@withContext Result.failure(IllegalStateException("Job not found"))
        val settings = settingsRepo.current()
        val jobDir = files.jobDir(jobId)
        try {
            jobs.updateProgress(jobId, ProcessingStage.PROBE, 0.01f, JobState.RUNNING)
            onUpdate(ProcessingStage.PROBE, 0.01f, "Reading video metadata")

            val source = videos.byId(job.videoId)
            val metadata: VideoMetadata = source?.metadata
                ?: prober.probe(Uri.parse(job.sourceUri)).getOrElse { throw it }
            val sourceName = source?.displayName ?: job.videoName
            val sourceUri = Uri.parse(source?.uri ?: job.sourceUri)
            val isLive = job.liveSessionId != null

            // ---------------- 1. audio ----------------
            val wavFile = File(jobDir, "audio.wav")
            if (!wavFile.exists() || wavFile.length() < 2048) {
                if (!metadata.hasAudio) throw IllegalStateException("This video has no audio track to analyse")
                onUpdate(ProcessingStage.AUDIO_EXTRACT, 0.03f, "Extracting audio")
                audioExtractor.extract(sourceUri, wavFile) { p ->
                    onUpdate(ProcessingStage.AUDIO_EXTRACT, 0.03f + p * 0.12f, "Extracting audio")
                }.getOrElse { throw it }
            }
            jobs.saveCheckpoint(jobId, ProcessingStage.AUDIO_EXTRACT, 0)
            currentCoroutineContext().ensureActive()

            // ---------------- 2. speech ----------------
            var transcript = clips.transcript(jobId)
            if (transcript.isEmpty()) {
                jobs.updateProgress(jobId, ProcessingStage.TRANSCRIBE, 0.16f)
                onUpdate(ProcessingStage.TRANSCRIBE, 0.16f, "Transcribing speech on device")
                transcript = transcribe(wavFile, settings, jobId, onUpdate)
            }
            jobs.saveCheckpoint(jobId, ProcessingStage.TRANSCRIBE, 0, "segments=${transcript.size}")
            currentCoroutineContext().ensureActive()

            // ---------------- 3. audio features + VAD ----------------
            jobs.updateProgress(jobId, ProcessingStage.AUDIO_FEATURES, 0.58f)
            onUpdate(ProcessingStage.AUDIO_FEATURES, 0.58f, "Measuring audio energy")
            val audioFrames = extractAudioFeatures(wavFile)
            val speechSegments = WavReader(wavFile).use { reader ->
                val blockSize = AudioConfig.SAMPLE_RATE * 60
                val block = reader.readWindow(0, blockSize)
                voiceActivity.detect(block, 0L)
            }
            val speechCoverage = if (metadata.durationMs > 0) {
                speechSegments.sumOf { it.durationMs }.toFloat() / metadata.durationMs
            } else 0f
            currentCoroutineContext().ensureActive()

            // ---------------- 4. vision + scenes ----------------
            jobs.updateProgress(jobId, ProcessingStage.VISION, 0.64f)
            onUpdate(ProcessingStage.VISION, 0.64f, "Analysing frames")
            val (frameAnalyses, sceneCuts) = analyseFrames(sourceUri, metadata, settings.profile, onUpdate)
            currentCoroutineContext().ensureActive()

            // ---------------- 5. detection ----------------
            jobs.updateProgress(jobId, ProcessingStage.SCORE, 0.72f)
            onUpdate(ProcessingStage.SCORE, 0.72f, "Ranking moments")
            val ranking = settings.ranking.copy(minClipMs = job.minClipMs, maxClipMs = job.maxClipMs)
            val candidates = detector.detect(
                DetectionInput(
                    jobId = job.id,
                    transcript = transcript,
                    sceneCuts = sceneCuts,
                    audioFrames = audioFrames,
                    frames = frameAnalyses,
                    totalDurationMs = metadata.durationMs,
                    mode = job.mode,
                    config = ranking
                )
            )
            if (candidates.isEmpty()) {
                throw IllegalStateException(
                    "No qualified moments were found. Try the Balanced mode or lower the ranking thresholds."
                )
            }
            clips.saveCandidates(jobId, candidates)
            jobs.saveCheckpoint(jobId, ProcessingStage.SCORE, 0, "candidates=${candidates.size}")

            // ---------------- 6. clips ----------------
            val selected = applyCountLimit(candidates, settings)
            val geometry = ExportPresets.resolve(settings.aspectRatio, metadata, settings.profile)
            val existing = clips.clipsForJob(jobId).associateBy { it.candidateId }
            var completed = existing.size.coerceAtMost(selected.size)

            for (candidate in selected) {
                currentCoroutineContext().ensureActive()
                if (existing.containsKey(candidate.id)) continue
                val baseProgress = 0.74f + (completed.toFloat() / selected.size.coerceAtLeast(1)) * 0.26f
                jobs.updateProgress(jobId, ProcessingStage.RENDER, baseProgress)
                onUpdate(
                    ProcessingStage.RENDER,
                    baseProgress,
                    "Rendering ${completed + 1} of ${selected.size}"
                )
                val clip = renderCandidate(
                    jobId = jobId,
                    candidate = candidate,
                    transcript = transcript,
                    frames = frameAnalyses,
                    metadata = metadata,
                    settings = settings,
                    geometry = geometry,
                    sourceUri = sourceUri,
                    sourceVideoId = job.videoId,
                    sourceName = sourceName,
                    isLive = isLive
                )
                if (clip != null) {
                    clips.saveClip(clip)
                    if (settings.autoExportToGallery) {
                        val folder = if (isLive) "Live Streams" else clip.quality.folder
                        val exported = clips.export(clip, folder, File(Uri.parse(clip.localFileUri).path!!))
                        if (exported.isFailure) {
                            Logx.w("Gallery export failed: ${exported.exceptionOrNull()?.message}")
                        }
                    }
                }
                completed++
            }

            jobs.updateProgress(jobId, ProcessingStage.DONE, 1f, JobState.COMPLETED)
            onUpdate(ProcessingStage.DONE, 1f, "Finished")
            files.clearJob(jobId)
            Result.success(Unit)
        } catch (c: CancellationException) {
            Logx.i("Job $jobId cancelled/paused")
            runCatching { jobs.setState(jobId, JobState.PAUSED, "Paused") }
            throw c
        } catch (t: Throwable) {
            Logx.e("Job $jobId failed", t)
            jobs.setState(jobId, JobState.FAILED, t.message ?: "Processing failed")
            onUpdate(ProcessingStage.FAILED, 0f, t.message ?: "Processing failed")
            Result.failure(t)
        }
    }

    // ------------------------------------------------------------------ transcription

    private suspend fun transcribe(
        wavFile: File,
        settings: UserSettings,
        jobId: String,
        onUpdate: (ProcessingStage, Float, String) -> Unit
    ): List<TranscriptSegment> {
        if (!models.whisper.isReady) {
            val ready = models.statuses.value.firstOrNull {
                it.spec.id == ModelRegistry.ID_WHISPER_TINY ||
                    it.spec.id == ModelRegistry.ID_WHISPER_BASE
            }
            val anyReady = models.statuses.value.filter {
                it.state == ModelState.READY && it.spec.category == com.aiclipcreator.core.models.ModelCategory.SPEECH
            }
            if (anyReady.isEmpty()) {
                throw IllegalStateException(
                    "No speech model is installed. Open AI Models → Install Whisper, then retry."
                )
            }
            val spec = anyReady.first { it.spec.id == ModelRegistry.ID_WHISPER_TINY }.let { it } // prefer tiny
            models.whisper.load(models.fileFor(spec.spec)).getOrElse { throw it }
            if (!models.whisper.isReady) throw IllegalStateException("Speech model could not be loaded")
            @Suppress("UNUSED_VARIABLE") val unused = ready
        }
        val threadCount = models.threadCount
        val language = settings.language.whisperCode
        WavReader(wavFile).use { reader ->
            val windowSamples = AudioConfig.WHISPER_WINDOW_SAMPLES
            val total = reader.totalSamples
            val all = ArrayList<TranscriptSegment>(64)
            var position = 0L
            var windowIndex = 0
            val windows = (total / windowSamples) + 1
            while (position < total) {
                currentCoroutineContext().ensureActive()
                val samples = reader.readWindow(position, windowSamples)
                val rms = rms(samples)
                val offsetMs = position * 1000L / AudioConfig.SAMPLE_RATE
                if (rms > 0.006f) {
                    val result = models.whisper.transcribe(
                        samples = samples,
                        language = language,
                        threads = threadCount,
                        wordTimestamps = true
                    )
                    if (result.error != null) {
                        Logx.w("Whisper window error: ${result.error}")
                    }
                    for (segment in result.segments) {
                        if (segment.noSpeechProbability > 0.65f) continue
                        val shifted = segment.copy(
                            startMs = segment.startMs + offsetMs,
                            endMs = segment.endMs + offsetMs,
                            language = result.detectedLanguage.ifBlank { segment.language },
                            words = segment.words.map {
                                it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs)
                            }
                        )
                        val duplicate = all.lastOrNull()?.let { previous ->
                            shifted.text.trim() == previous.text.trim() &&
                                abs(shifted.startMs - previous.startMs) < 1_500
                        } ?: false
                        if (!duplicate) all += shifted
                    }
                }
                position += windowSamples
                windowIndex++
                val progress = 0.16f + (windowIndex.toFloat() / windows) * 0.40f
                onUpdate(ProcessingStage.TRANSCRIBE, progress, "Transcribing ${windowIndex}/${windows} windows")
                if (windowIndex % 10 == 0 && all.isNotEmpty()) {
                    runCatching { clips.appendTranscript(jobId, all.takeLast(20)) }
                }
            }
            if (all.isNotEmpty()) {
                runCatching { clips.appendTranscript(jobId, all.takeLast(20)) }
            }
            return all
        }
    }

    // ------------------------------------------------------------------ audio features

    private suspend fun extractAudioFeatures(wavFile: File): List<AudioFeatureFrame> {
        val frames = ArrayList<AudioFeatureFrame>(1024)
        WavReader(wavFile).use { reader ->
            val blockSamples = AudioConfig.SAMPLE_RATE * 120
            var position = 0L
            while (position < reader.totalSamples) {
                currentCoroutineContext().ensureActive()
                val samples = reader.readWindow(position, blockSamples)
                if (samples.isEmpty()) break
                val offsetMs = position * 1000L / AudioConfig.SAMPLE_RATE
                frames += audioAnalyzer.analyze(samples, offsetMs)
                position += blockSamples
            }
        }
        return frames
    }

    // ------------------------------------------------------------------ vision

    private suspend fun analyseFrames(
        uri: Uri,
        metadata: VideoMetadata,
        profile: ProductionProfile,
        onUpdate: (ProcessingStage, Float, String) -> Unit
    ): Pair<List<FrameAnalysis>, List<SceneCut>> {
        val budget = when (profile) {
            ProductionProfile.LITE -> 300
            ProductionProfile.BALANCED -> 600
            ProductionProfile.ADVANCED -> 900
        }
        val interval = sampler.intervalFor(metadata.durationMs, budget)
        val analyses = ArrayList<FrameAnalysis>(budget)
        val scores = ArrayList<Pair<Long, Float>>(budget)
        var previousHistogram: FloatArray? = null
        var count = 0
        sampler.sample(uri, metadata.durationMs, interval, targetWidth = 320, maxFrames = budget) { frame ->
            val bitmap = android.graphics.Bitmap.createBitmap(frame.pixels, frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
            val analysis: FrameAnalysis
            if (models.vision.isReady) {
                val (result, histogram) = models.vision.analyze(bitmap, frame.timeMs, previousHistogram, null)
                analysis = result
                scores += frame.timeMs to (if (previousHistogram != null) SceneDetector.difference(previousHistogram!!, histogram) else 0f)
                previousHistogram = histogram
            } else {
                val histogram = SceneDetector.histogram(frame.pixels, frame.width, frame.height)
                val diff = previousHistogram?.let { SceneDetector.difference(it, histogram) } ?: 0f
                scores += frame.timeMs to diff
                previousHistogram = histogram
                analysis = FrameAnalysis(
                    timeMs = frame.timeMs,
                    brightness = SceneDetector.averageBrightness(frame.pixels),
                    sharpness = SceneDetector.sharpness(frame.pixels, frame.width, frame.height),
                    motion = diff,
                    faceCount = 0,
                    largestFaceArea = 0f,
                    faceCenterX = 0.5f,
                    faceCenterY = 0.5f,
                    labels = emptyList(),
                    activity = diff
                )
            }
            bitmap.recycle()
            analyses += analysis
            count++
            if (count % 25 == 0) {
                onUpdate(ProcessingStage.VISION, 0.64f + (count.toFloat() / budget) * 0.07f, "Analysed $count frames")
            }
        }
        return analyses to SceneDetector.cuts(scores)
    }

    // ------------------------------------------------------------------ clips

    private fun applyCountLimit(candidates: List<CandidateMoment>, settings: UserSettings): List<CandidateMoment> {
        if (settings.clipCountMode == ClipCountMode.ALL_QUALIFIED) return candidates
        val best = candidates.filter { it.quality == ClipQuality.BEST }
        val balanced = candidates.filter { it.quality == ClipQuality.BALANCED }
        val limit = settings.customClipCount.coerceAtLeast(1)
        // Prefer best moments, then fill with balanced ones up to the requested count.
        return (best + balanced).distinctBy { it.id }.take(limit).sortedBy { it.startMs }
    }

    private suspend fun renderCandidate(
        jobId: String,
        candidate: CandidateMoment,
        transcript: List<TranscriptSegment>,
        frames: List<FrameAnalysis>,
        metadata: VideoMetadata,
        settings: UserSettings,
        geometry: ExportGeometry,
        sourceUri: Uri,
        sourceVideoId: String,
        sourceName: String,
        isLive: Boolean
    ): Clip? {
        val cues = CaptionBuilder.cuesFor(transcript, candidate.startMs, candidate.endMs)
        val text = captionGenerator.generate(candidate.transcript, sourceName, candidate.category.label)
        val frameSlice = frames.filter { it.timeMs in candidate.startMs..candidate.endMs && it.faceCount > 0 }
        val cropCenterX = frameSlice.map { it.faceCenterX }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0.5f
        val cropCenterY = frameSlice.map { it.faceCenterY }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0.5f

        val outputFile = files.clipFile(
            Formats.fileName(text.title.ifBlank { "clip" }, candidate.quality.label)
        )
        val request = RenderRequest(
            sourceUri = sourceUri,
            outputFile = outputFile,
            startMs = candidate.startMs,
            endMs = candidate.endMs,
            aspect = settings.aspectRatio,
            geometry = geometry,
            sourceMetadata = metadata,
            captions = cues,
            captionStyle = settings.captionStyle,
            burnCaptions = settings.burnCaptions,
            normalizeAudio = settings.normalizeAudio,
            cropCenterX = cropCenterX,
            cropCenterY = cropCenterY
        )
        val renderResult: Result<RenderResult> = renderer.render(request)
        if (renderResult.isFailure) {
            Logx.w("Clip render failed for ${candidate.startMs}-${candidate.endMs}: ${renderResult.exceptionOrNull()?.message}")
            return null
        }
        val srt = CaptionBuilder.toSrt(cues)
        runCatching { File(outputFile.absolutePath + ".srt").writeText(srt) }
        return Clip(
            id = UUID.randomUUID().toString(),
            jobId = jobId,
            candidateId = candidate.id,
            title = text.title,
            description = text.description,
            hashtags = text.hashtags,
            startMs = candidate.startMs,
            endMs = candidate.endMs,
            quality = candidate.quality,
            aspectRatio = settings.aspectRatio,
            localFileUri = Uri.fromFile(outputFile).toString(),
            srt = srt,
            captionStyle = settings.captionStyle,
            createdAt = System.currentTimeMillis(),
            sourceVideoId = sourceVideoId,
            sourceName = sourceName,
            transcript = candidate.transcript,
            isLive = isLive,
            textSource = text.source
        )
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }
}
