package com.aiclipcreator.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.GalleryClip
import java.io.File
import java.io.IOException

/**
 * Writes finished clips into the shared gallery under Movies/AI Clip Creator/<folder>.
 * Uses MediaStore (no storage permission required on Android 10+).
 */
class MediaStoreExporter(private val context: Context) {

    companion object {
        const val ROOT_FOLDER = "AI Clip Creator"
    }

    fun export(
        file: File,
        displayName: String,
        folder: String,
        durationMs: Long
    ): Result<Uri> = runCatching {
        require(file.exists() && file.length() > 0) { "Rendered file is missing or empty" }
        val relativePath = "${Environment.DIRECTORY_MOVIES}/$ROOT_FOLDER/$folder"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.DURATION, durationMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore rejected the insert")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Unable to open MediaStore output stream")
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
        Logx.i("Exported clip to $relativePath/$displayName")
        uri
    }

    fun rename(uri: Uri, newDisplayName: String): Result<Unit> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
            put(MediaStore.Video.Media.TITLE, newDisplayName.substringBeforeLast('.'))
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        if (updated <= 0) throw IOException("Unable to rename clip")
    }

    fun delete(uri: Uri): Result<Unit> = runCatching {
        val deleted = context.contentResolver.delete(uri, null, null)
        if (deleted <= 0) throw IOException("Unable to delete clip")
    }

    fun history(): List<GalleryClip> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }
        val args = arrayOf("%AI Clip Creator%")
        val result = mutableListOf<GalleryClip>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += GalleryClip(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        displayName = cursor.getString(nameCol) ?: "clip.mp4",
                        relativePath = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else "",
                        sizeBytes = cursor.getLong(sizeCol),
                        addedAt = cursor.getLong(dateCol) * 1000L,
                        durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    )
                }
            }
        }.onFailure { Logx.w("Gallery query failed: ${it.message}") }
        return result
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun playIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun folderUri(): Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
}
