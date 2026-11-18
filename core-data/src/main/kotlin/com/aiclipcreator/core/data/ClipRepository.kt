package com.aiclipcreator.core.data

import android.net.Uri
import com.aiclipcreator.core.database.CandidateDao
import com.aiclipcreator.core.database.ClipDao
import com.aiclipcreator.core.database.ExportDao
import com.aiclipcreator.core.database.TranscriptDao
import com.aiclipcreator.core.database.toDomain
import com.aiclipcreator.core.database.toEntity
import com.aiclipcreator.core.models.CandidateMoment
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.ExportRecord
import com.aiclipcreator.core.models.GalleryClip
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.storage.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ClipRepository(
    private val clipDao: ClipDao,
    private val candidateDao: CandidateDao,
    private val transcriptDao: TranscriptDao,
    private val exportDao: ExportDao,
    private val exporter: MediaStoreExporter
) {

    fun observeClips(): Flow<List<Clip>> = clipDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeClipsForJob(jobId: String): Flow<List<Clip>> =
        clipDao.observeByJob(jobId).map { list -> list.map { it.toDomain() } }

    fun observeClipCount(): Flow<Int> = clipDao.observeCount()

    fun observeCandidates(jobId: String): Flow<List<CandidateMoment>> =
        candidateDao.observe(jobId).map { list -> list.map { it.toDomain() } }

    fun observeExports(): Flow<List<ExportRecord>> =
        exportDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun candidates(jobId: String): List<CandidateMoment> =
        withContext(Dispatchers.IO) { candidateDao.byJob(jobId).map { it.toDomain() } }

    suspend fun saveCandidates(jobId: String, candidates: List<CandidateMoment>) = withContext(Dispatchers.IO) {
        candidateDao.delete(jobId)
        candidateDao.insertAll(candidates.map { it.toEntity() })
    }

    suspend fun saveTranscript(jobId: String, segments: List<TranscriptSegment>) = withContext(Dispatchers.IO) {
        transcriptDao.delete(jobId)
        transcriptDao.insertAll(segments.map { it.toEntity(jobId) })
    }

    suspend fun appendTranscript(jobId: String, segments: List<TranscriptSegment>) = withContext(Dispatchers.IO) {
        transcriptDao.insertAll(segments.map { it.toEntity(jobId) })
    }

    suspend fun transcript(jobId: String): List<TranscriptSegment> =
        withContext(Dispatchers.IO) { transcriptDao.byJob(jobId).map { it.toDomain() } }

    fun observeTranscript(jobId: String): Flow<List<TranscriptSegment>> =
        transcriptDao.observe(jobId).map { list -> list.map { it.toDomain() } }

    suspend fun clipsForJob(jobId: String): List<Clip> =
        withContext(Dispatchers.IO) { clipDao.byJob(jobId).map { it.toDomain() } }

    suspend fun clip(id: String): Clip? = withContext(Dispatchers.IO) { clipDao.byId(id)?.toDomain() }

    suspend fun saveClip(clip: Clip) = withContext(Dispatchers.IO) { clipDao.upsert(clip.toEntity()) }

    suspend fun updateCaption(clipId: String, title: String, description: String, hashtags: List<String>, srt: String) =
        withContext(Dispatchers.IO) {
            val existing = clipDao.byId(clipId)?.toDomain() ?: return@withContext
            clipDao.upsert(
                existing.copy(
                    title = title,
                    description = description,
                    hashtags = hashtags,
                    srt = srt
                ).toEntity()
            )
        }

    suspend fun export(clip: Clip, folder: String, file: File): Result<Uri> = withContext(Dispatchers.IO) {
        val importance = runCatching {
            exporter.export(file, "${clip.title.take(48).ifBlank { "clip" }}.mp4", folder, clip.durationMs)
        }.getOrElse { return@withContext Result.failure(it) }
        importance.map { uri ->
            clipDao.markExported(clip.id, uri.toString(), System.currentTimeMillis())
            exportDao.insert(
                ExportRecord(
                    id = UUID.randomUUID().toString(),
                    clipId = clip.id,
                    title = clip.title,
                    uri = uri.toString(),
                    folder = folder,
                    createdAt = System.currentTimeMillis(),
                    durationMs = clip.durationMs
                ).toEntity()
            )
            uri
        }
    }

    suspend fun deleteClip(clip: Clip): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { File(Uri.parse(clip.localFileUri).path ?: clip.localFileUri).delete() }
            clip.galleryUri?.let { uri -> runCatching { exporter.delete(Uri.parse(uri)) } }
            clipDao.delete(clip.id)
        }
    }

    suspend fun renameClipInGallery(clip: Clip, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = clip.galleryUri ?: throw IllegalStateException("Clip is not in the gallery yet")
            exporter.rename(Uri.parse(uri), if (newName.endsWith(".mp4")) newName else "$newName.mp4").getOrThrow()
            clipDao.updateText(clip.id, newName, clip.description, "", clip.srt)
        }
    }

    suspend fun galleryHistory(): List<GalleryClip> = withContext(Dispatchers.IO) { exporter.history() }

    suspend fun deleteFromGallery(clip: Clip): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            clip.galleryUri?.let { exporter.delete(Uri.parse(it)).getOrThrow() }
            Unit
        }
    }

    suspend fun deleteJobArtifacts(jobId: String) = withContext(Dispatchers.IO) {
        candidateDao.delete(jobId)
        transcriptDao.delete(jobId)
    }
}
