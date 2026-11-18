package com.aiclipcreator.core.ai

import android.content.Context
import com.aiclipcreator.core.common.DeviceProfiler
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.DeviceCapabilities
import com.aiclipcreator.core.models.ModelInstallEvent
import com.aiclipcreator.core.models.ModelSpec
import com.aiclipcreator.core.models.ModelState
import com.aiclipcreator.core.models.ModelStatus
import com.aiclipcreator.core.network.DownloadOutcome
import com.aiclipcreator.core.network.ResumableDownloader
import com.aiclipcreator.core.storage.AppFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Persistence boundary so the AI module does not depend on Room directly. */
data class StoredModel(
    val id: String,
    val state: ModelState,
    val progress: Float,
    val downloadedBytes: Long,
    val localPath: String?,
    val error: String?,
    val selfTestPassed: Boolean,
    val updatedAt: Long
)

interface ModelStore {
    suspend fun all(): List<StoredModel>
    suspend fun save(model: StoredModel)
    suspend fun delete(id: String)
}

/**
 * Downloads, verifies, installs and self-tests every model artifact, and owns the runtime
 * instances so they can be loaded and unloaded independently.
 */
class ModelManager(
    private val context: Context,
    private val files: AppFiles,
    private val downloader: ResumableDownloader,
    private val store: ModelStore,
    val capabilities: DeviceCapabilities
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _statuses = MutableStateFlow(ModelRegistry.all.map { ModelStatus(it, ModelState.NOT_INSTALLED) })
    val statuses: StateFlow<List<ModelStatus>> = _statuses.asStateFlow()

    private val _events = MutableStateFlow<List<ModelInstallEvent>>(emptyList())
    val events: StateFlow<List<ModelInstallEvent>> = _events.asStateFlow()

    val whisper = WhisperRuntime()
    val llm = LlmRuntime()
    val vision = VisionAnalyzer(context)
    val vad = SileroVad()

    val threadCount: Int = DeviceProfiler.inferenceThreads(capabilities)

    suspend fun refresh() {
        val stored = runCatching { store.all() }.getOrDefault(emptyList()).associateBy { it.id }
        _statuses.value = ModelRegistry.all.map { spec ->
            val record = stored[spec.id]
            val file = files.modelFile(spec.fileName)
            val effectiveState = when {
                record == null && !file.exists() -> ModelState.NOT_INSTALLED
                record != null && record.state == ModelState.READY && file.exists() -> ModelState.READY
                record != null && record.state == ModelState.READY && !file.exists() -> ModelState.NOT_INSTALLED
                record != null -> record.state
                else -> ModelState.INSTALLED
            }
            ModelStatus(
                spec = spec,
                state = effectiveState,
                progress = record?.progress ?: 0f,
                downloadedBytes = record?.downloadedBytes ?: 0L,
                localPath = file.takeIf { it.exists() }?.absolutePath,
                error = record?.error,
                selfTestPassed = record?.selfTestPassed ?: false,
                updatedAt = record?.updatedAt ?: 0L
            )
        }
        autoLoadReadyRuntimes()
    }

    /** Returns a human readable reason when the artifact cannot run on this device. */
    fun compatibilityIssue(spec: ModelSpec): String? {
        if (!capabilities.abi.split(",").any { it in spec.architectures }) {
            return "Requires ABIs ${spec.architectures.joinToString()}, device is ${capabilities.abi}"
        }
        if (capabilities.ramMb < spec.minRamMb) {
            return "Needs ${spec.minRamMb} MB RAM, device has ${capabilities.ramMb} MB"
        }
        if (spec.runtime == com.aiclipcreator.core.models.ModelRuntime.LLAMA_CPP && !NativeLibs.llmCompiled) {
            return "This build was compiled without the llama.cpp runtime (rebuild with -PnativeLlm=true)"
        }
        if ((spec.runtime == com.aiclipcreator.core.models.ModelRuntime.WHISPER_CPP) && !NativeLibs.whisperAvailable) {
            return "whisper.cpp native runtime is unavailable in this build"
        }
        return null
    }

    fun storageIssue(spec: ModelSpec): String? {
        val free = DeviceProfiler.freeStorageBytes(context)
        val needed = (spec.sizeBytes / (1024L * 1024L)) + 120
        return if (free / (1024L * 1024L) < needed) {
            "Need ${needed} MB free, only ${Formats.mb(free)} MB available"
        } else null
    }

    fun estimateDownloadBytes(ids: List<String>): Long =
        ModelRegistry.all.filter { it.id in ids }.sumOf { it.sizeBytes }

    fun suggestedInstallIds(): List<String> =
        ModelRegistry.forProfile(capabilities.profile)
            .filter { compatibilityIssue(it) == null }
            .map { it.id }

    fun statusOf(id: String): ModelStatus? = _statuses.value.firstOrNull { it.spec.id == id }

    private fun publish(event: ModelInstallEvent) {
        _events.value = (_events.value + event).takeLast(60)
        Logx.i("[model] ${event.modelId} ${event.stage}: ${event.message}")
    }

    private suspend fun update(status: ModelStatus) {
        _statuses.value = _statuses.value.map { if (it.spec.id == status.spec.id) status else it }
        runCatching {
            store.save(
                StoredModel(
                    id = status.spec.id,
                    state = status.state,
                    progress = status.progress,
                    downloadedBytes = status.downloadedBytes,
                    localPath = status.localPath,
                    error = status.error,
                    selfTestPassed = status.selfTestPassed,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun install(
        id: String,
        onComplete: (Result<Unit>) -> Unit = {}
    ): Job? {
        val spec = ModelRegistry.byId(id) ?: return null
        if (jobs[id]?.isActive == true) return jobs[id]
        val job = scope.launch {
            val result = runCatching { installInternal(spec) }
            onComplete(result)
        }
        jobs[id] = job
        return job
    }

    fun installAll(ids: List<String> = suggestedInstallIds()) {
        scope.launch {
            installMutex.withLock {
                for (id in ids) {
                    val spec = ModelRegistry.byId(id) ?: continue
                    if (statusOf(id)?.state == ModelState.READY) continue
                    runCatching { installInternal(spec) }
                }
            }
        }
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        val spec = ModelRegistry.byId(id) ?: return
        val current = statusOf(id) ?: return
        scope.launch {
            update(current.copy(state = ModelState.NOT_INSTALLED, error = "Paused — resume to continue"))
        }
    }

    fun resume(id: String) {
        val spec = ModelRegistry.byId(id) ?: return
        if (statusOf(id)?.state == ModelState.READY) return
        install(spec.id)
    }

    fun retry(id: String) {
        val spec = ModelRegistry.byId(id) ?: return
        install(spec.id)
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        jobs[id]?.cancel()
        val spec = ModelRegistry.byId(id) ?: throw IllegalArgumentException("Unknown model")
        if (spec.runtime == com.aiclipcreator.core.models.ModelRuntime.WHISPER_CPP) whisper.close()
        if (spec.runtime == com.aiclipcreator.core.models.ModelRuntime.LLAMA_CPP) llm.close()
        if (spec.runtime == com.aiclipcreator.core.models.ModelRuntime.LITERT) vision.close()
        if (spec.runtime == com.aiclipcreator.core.models.ModelRuntime.ONNXRUNTIME) vad.close()
        if (!files.deleteModelFile(spec.fileName)) {
            // File may already be gone; only fail if it still exists.
            if (files.modelFile(spec.fileName).exists()) throw IllegalStateException("Unable to delete ${spec.fileName}")
        }
        store.delete(id)
        update(
            ModelStatus(spec = spec, state = ModelState.NOT_INSTALLED, progress = 0f, updatedAt = System.currentTimeMillis())
        )
        publish(ModelInstallEvent(spec.id, "deleted", "Removed ${spec.name}", 0f))
    }

    suspend fun verify(id: String): Result<Boolean> = runCatching {
        val spec = ModelRegistry.byId(id) ?: throw IllegalArgumentException("Unknown model")
        val file = files.modelFile(spec.fileName)
        if (!file.exists()) throw IllegalStateException("${spec.name} is not installed")
        val digest = withContextIo { downloader.sha256(file) }
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            files.deleteModelFile(spec.fileName)
            throw IllegalStateException("Checksum mismatch — file was removed")
        }
        val ok = selfTest(spec)
        update(
            (statusOf(id) ?: ModelStatus(spec, ModelState.READY)).copy(
                state = if (ok) ModelState.READY else ModelState.FAILED,
                selfTestPassed = ok,
                error = if (ok) null else "Installed but the runtime self-test failed",
                progress = 1f,
                localPath = file.absolutePath
            )
        )
        ok
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private suspend fun installInternal(spec: ModelSpec): Unit {
        val issue = compatibilityIssue(spec)
        if (issue != null) {
            update(
                (statusOf(spec.id) ?: ModelStatus(spec, ModelState.NOT_INSTALLED)).copy(
                    state = ModelState.UNSUPPORTED,
                    error = issue
                )
            )
            publish(ModelInstallEvent(spec.id, "unsupported", issue, 0f))
            return
        }
        val storage = storageIssue(spec)
        if (storage != null) {
            update(
                (statusOf(spec.id) ?: ModelStatus(spec, ModelState.NOT_INSTALLED)).copy(
                    state = ModelState.FAILED,
                    error = storage
                )
            )
            publish(ModelInstallEvent(spec.id, "storage", storage, 0f))
            return
        }
        val destination = files.modelFile(spec.fileName)
        val base = statusOf(spec.id) ?: ModelStatus(spec, ModelState.NOT_INSTALLED)
        if (destination.exists() && downloader.sha256(destination).equals(spec.sha256, ignoreCase = true)) {
            publish(ModelInstallEvent(spec.id, "verified", "Existing file already matches the checksum", 1f))
            val ok = selfTest(spec)
            update(
                base.copy(
                    state = if (ok) ModelState.READY else ModelState.FAILED,
                    progress = 1f,
                    downloadedBytes = destination.length(),
                    localPath = destination.absolutePath,
                    selfTestPassed = ok,
                    error = if (ok) null else "Runtime self-test failed"
                )
            )
            return
        }
        update(base.copy(state = ModelState.DOWNLOADING, progress = 0f, error = null))
        publish(ModelInstallEvent(spec.id, "download", "Downloading ${spec.name}", 0f))
        val outcome = downloader.download(
            url = spec.url,
            destination = destination,
            expectedBytes = spec.sizeBytes,
            expectedSha256 = spec.sha256
        ) { downloaded, total ->
            val progress = if (total > 0) downloaded.toFloat() / total else 0f
            val snapshot = _statuses.value.firstOrNull { it.spec.id == spec.id }
            if (snapshot != null) {
                _statuses.value = _statuses.value.map {
                    if (it.spec.id == spec.id) it.copy(progress = progress, downloadedBytes = downloaded) else it
                }
            }
            publish(
                ModelInstallEvent(
                    spec.id,
                    "download",
                    "${Formats.bytes(downloaded)} / ${Formats.bytes(total)}",
                    progress
                )
            )
        }
        when (outcome) {
            is DownloadOutcome.Success -> {
                update(
                    base.copy(
                        state = ModelState.VERIFYING,
                        progress = 1f,
                        downloadedBytes = outcome.bytes,
                        localPath = destination.absolutePath
                    )
                )
                publish(ModelInstallEvent(spec.id, "verify", "SHA-256 verified", 1f))
                val ok = selfTest(spec)
                update(
                    base.copy(
                        state = if (ok) ModelState.READY else ModelState.FAILED,
                        progress = 1f,
                        downloadedBytes = outcome.bytes,
                        localPath = destination.absolutePath,
                        selfTestPassed = ok,
                        error = if (ok) null else "Runtime self-test failed after install"
                    )
                )
                publish(
                    ModelInstallEvent(
                        spec.id,
                        if (ok) "ready" else "failed",
                        if (ok) "${spec.name} installed and verified" else "${spec.name} failed the runtime self-test",
                        1f
                    )
                )
            }
            is DownloadOutcome.ChecksumMismatch -> {
                update(base.copy(state = ModelState.FAILED, error = "Checksum mismatch (expected ${outcome.expected.take(12)}…)"))
                publish(ModelInstallEvent(spec.id, "failed", "Checksum mismatch", 0f))
            }
            is DownloadOutcome.Failure -> {
                update(base.copy(state = ModelState.FAILED, error = outcome.message))
                publish(ModelInstallEvent(spec.id, "failed", outcome.message, 0f))
            }
            DownloadOutcome.Cancelled -> {
                update(base.copy(state = ModelState.NOT_INSTALLED, error = "Paused"))
            }
        }
    }

    /** Loads the model into its runtime and runs a genuine inference pass. */
    private suspend fun selfTest(spec: ModelSpec): Boolean = withContextIo {
        val file = files.modelFile(spec.fileName)
        if (!file.exists()) return@withContextIo false
        when (spec.runtime) {
            com.aiclipcreator.core.models.ModelRuntime.WHISPER_CPP -> {
                val loaded = whisper.load(file).isSuccess
                if (loaded && whisper.isReady) whisper.selfTest() else false
            }
            com.aiclipcreator.core.models.ModelRuntime.LLAMA_CPP -> {
                val loaded = llm.load(file, threadCount).isSuccess
                if (loaded) llm.selfTest() else false
            }
            com.aiclipcreator.core.models.ModelRuntime.LITERT -> {
                val loaded = vision.load(file, threadCount).isSuccess
                if (loaded) vision.selfTest() else false
            }
            com.aiclipcreator.core.models.ModelRuntime.ONNXRUNTIME -> {
                val loaded = vad.load(file, threadCount).isSuccess
                if (loaded) vad.selfTest() else false
            }
        }
    }

    private fun autoLoadReadyRuntimes() {
        scope.launch {
            val ready = _statuses.value.filter { it.state == ModelState.READY }
            val whisperSpec = ready.firstOrNull {
                it.spec.runtime == com.aiclipcreator.core.models.ModelRuntime.WHISPER_CPP &&
                    it.spec.id == ModelRegistry.ID_WHISPER_TINY
            } ?: ready.firstOrNull { it.spec.runtime == com.aiclipcreator.core.models.ModelRuntime.WHISPER_CPP }
            if (whisperSpec != null && !whisper.isReady) {
                whisper.load(files.modelFile(whisperSpec.spec.fileName))
            }
            val llmSpec = ready.firstOrNull { it.spec.runtime == com.aiclipcreator.core.models.ModelRuntime.LLAMA_CPP }
            if (llmSpec != null && !llm.isReady) {
                llm.load(files.modelFile(llmSpec.spec.fileName), threadCount)
            }
            val visionSpec = ready.firstOrNull { it.spec.runtime == com.aiclipcreator.core.models.ModelRuntime.LITERT }
            if (visionSpec != null && !vision.isReady) {
                vision.load(files.modelFile(visionSpec.spec.fileName), threadCount)
            }
            val vadSpec = ready.firstOrNull { it.spec.runtime == com.aiclipcreator.core.models.ModelRuntime.ONNXRUNTIME }
            if (vadSpec != null && !vad.isReady) {
                vad.load(files.modelFile(vadSpec.spec.fileName), threadCount)
            }
        }
    }

    fun unloadAll() {
        whisper.close()
        llm.close()
        vision.close()
        vad.close()
    }

    fun release() {
        jobs.values.forEach { it.cancel() }
        unloadAll()
    }

    fun fileFor(spec: ModelSpec): File = files.modelFile(spec.fileName)
}
