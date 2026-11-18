package com.aiclipcreator.core.data

import com.aiclipcreator.core.database.CheckpointDao
import com.aiclipcreator.core.database.JobDao
import com.aiclipcreator.core.database.toDomain
import com.aiclipcreator.core.database.toEntity
import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.DurationMode
import com.aiclipcreator.core.models.JobCheckpoint
import com.aiclipcreator.core.models.JobState
import com.aiclipcreator.core.models.ProcessingJob
import com.aiclipcreator.core.models.ProcessingStage
import com.aiclipcreator.core.models.UserSettings
import com.aiclipcreator.core.models.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class JobRepository(
    private val jobDao: JobDao,
    private val checkpointDao: CheckpointDao
) {

    fun observeAll(): Flow<List<ProcessingJob>> = jobDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActive(): Flow<List<ProcessingJob>> = jobDao.observeActive().map { list -> list.map { it.toDomain() } }

    fun observe(id: String): Flow<ProcessingJob?> = jobDao.observe(id).map { it?.toDomain() }

    fun completedCount(): Flow<Int> = jobDao.observeCompletedCount()

    suspend fun byId(id: String): ProcessingJob? = withContext(Dispatchers.IO) { jobDao.byId(id)?.toDomain() }

    suspend fun nextActive(): ProcessingJob? = withContext(Dispatchers.IO) { jobDao.nextActive()?.toDomain() }

    suspend fun create(
        video: VideoSource,
        settings: UserSettings,
        liveSessionId: String? = null,
        aspect: AspectRatio = settings.aspectRatio,
        priority: Int = 0
    ): ProcessingJob = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val job = ProcessingJob(
            id = UUID.randomUUID().toString(),
            videoId = video.id,
            videoName = video.displayName,
            sourceUri = video.uri,
            state = JobState.QUEUED,
            stage = ProcessingStage.IDLE,
            progress = 0f,
            priority = priority,
            mode = settings.clipMode,
            profile = settings.profile,
            language = settings.language,
            createdAt = now,
            updatedAt = now,
            durationMs = video.metadata.durationMs,
            clipMode = settings.durationMode,
            minClipMs = settings.minClipMs,
            maxClipMs = settings.maxClipMs,
            aspectRatio = aspect,
            liveSessionId = liveSessionId
        )
        jobDao.upsert(job.toEntity())
        job
    }

    suspend fun update(job: ProcessingJob) = withContext(Dispatchers.IO) {
        jobDao.upsert(job.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun updateProgress(
        id: String,
        stage: ProcessingStage,
        progress: Float,
        state: JobState = JobState.RUNNING,
        error: String? = null
    ) = withContext(Dispatchers.IO) {
        val existing = jobDao.byId(id)?.toDomain() ?: return@withContext
        jobDao.upsert(
            existing.copy(
                stage = stage,
                state = state,
                progress = progress.coerceIn(0f, 1f),
                error = error,
                updatedAt = System.currentTimeMillis()
            ).toEntity()
        )
    }

    suspend fun setState(id: String, state: JobState, error: String? = null) = withContext(Dispatchers.IO) {
        val existing = jobDao.byId(id)?.toDomain() ?: return@withContext
        jobDao.upsert(
            existing.copy(
                state = state,
                error = error,
                updatedAt = System.currentTimeMillis(),
                stage = if (state == JobState.COMPLETED) ProcessingStage.DONE else existing.stage
            ).toEntity()
        )
    }

    suspend fun pause(id: String) = setState(id, JobState.PAUSED)
    suspend fun resume(id: String) = setState(id, JobState.QUEUED)
    suspend fun retry(id: String) = setState(id, JobState.QUEUED, error = null)
    suspend fun cancel(id: String) = setState(id, JobState.CANCELLED)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        checkpointDao.delete(id)
        jobDao.delete(id)
    }

    suspend fun saveCheckpoint(jobId: String, stage: ProcessingStage, lastProcessedMs: Long, payload: String = "") =
        withContext(Dispatchers.IO) {
            checkpointDao.upsert(
                JobCheckpoint(
                    jobId = jobId,
                    stage = stage,
                    lastProcessedMs = lastProcessedMs,
                    payload = payload,
                    updatedAt = System.currentTimeMillis()
                ).toEntity()
            )
            val job = jobDao.byId(jobId)?.toDomain() ?: return@withContext
            jobDao.upsert(job.copy(checkpointStage = stage.name, lastProcessedMs = lastProcessedMs).toEntity())
        }

    suspend fun checkpoint(jobId: String): JobCheckpoint? =
        withContext(Dispatchers.IO) { checkpointDao.byJob(jobId)?.toDomain() }

    /** Marks jobs that were running before a process death as resumable. */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        val running = jobDao.running()
        for (job in running) {
            val domain = job.toDomain()
            jobDao.upsert(
                domain.copy(
                    state = JobState.QUEUED,
                    error = "Resumed after interruption",
                    updatedAt = System.currentTimeMillis()
                ).toEntity()
            )
        }
        running.size
    }
}
