package com.aiclipcreator.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.database.VideoDao
import com.aiclipcreator.core.database.toDomain
import com.aiclipcreator.core.database.toEntity
import com.aiclipcreator.core.models.VideoSource
import com.aiclipcreator.core.storage.AppFiles
import com.aiclipcreator.core.video.VideoProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VideoRepository(
    private val context: Context,
    private val dao: VideoDao,
    private val prober: VideoProber,
    private val files: AppFiles
) {

    fun observeAll(): Flow<List<VideoSource>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun byId(id: String): VideoSource? = withContext(Dispatchers.IO) { dao.byId(id)?.toDomain() }

    /** Imports one picked document: reads metadata, writes a thumbnail and persists the reference. */
    suspend fun import(uri: Uri, projectFolder: String = "Projects"): Result<VideoSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val (name, size) = queryNameAndSize(uri)
                val metadata = prober.probe(uri, size).getOrElse { throw it }
                val id = UUID.randomUUID().toString()
                val thumbnail = files.thumbnailFile(id)
                val thumbResult = prober.thumbnail(uri, thumbnail)
                if (thumbResult.isFailure) {
                    Logx.w("Thumbnail failed for $name: ${thumbResult.exceptionOrNull()?.message}")
                }
                val source = VideoSource(
                    id = id,
                    uri = uri.toString(),
                    displayName = name,
                    metadata = metadata,
                    importedAt = System.currentTimeMillis(),
                    thumbnailUri = thumbnail.takeIf { it.exists() }?.absolutePath,
                    projectFolder = projectFolder
                )
                dao.upsert(source.toEntity())
                source
            }
        }

    /** Registers an app-owned file (for example a live recording) as a library video. */
    suspend fun registerFile(file: File, displayName: String, projectFolder: String = "Projects"): Result<VideoSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(file.exists() && file.length() > 0) { "Recording file is empty" }
                val uri = Uri.fromFile(file)
                val metadata = prober.probe(uri, file.length()).getOrElse { throw it }
                val id = UUID.randomUUID().toString()
                val thumbnail = files.thumbnailFile(id)
                runCatching { prober.thumbnail(uri, thumbnail) }
                val source = VideoSource(
                    id = id,
                    uri = uri.toString(),
                    displayName = displayName,
                    metadata = metadata,
                    importedAt = System.currentTimeMillis(),
                    thumbnailUri = thumbnail.takeIf { it.exists() }?.absolutePath,
                    projectFolder = projectFolder
                )
                dao.upsert(source.toEntity())
                source
            }
        }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            dao.delete(id)
            files.thumbnailFile(id).delete()
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size == 0L) {
            size = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
        }
        return name to size
    }
}
