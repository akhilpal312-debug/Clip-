package com.aiclipcreator.core.storage

import android.content.Context
import com.aiclipcreator.core.common.DeviceProfiler
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.StorageReport
import java.io.File

/**
 * Every intermediate artifact lives under the app cache/files dirs so the user's originals
 * are never touched. Models live in filesDir/models and are only removed on explicit delete.
 */
class AppFiles(private val context: Context) {

    val root: File get() = File(context.filesDir, "ai_clip_creator").apply { mkdirs() }
    val modelsDir: File get() = File(root, "models").apply { mkdirs() }
    val workDir: File get() = File(context.cacheDir, "work").apply { mkdirs() }
    val clipsDir: File get() = File(root, "clips").apply { mkdirs() }
    val thumbsDir: File get() = File(root, "thumbs").apply { mkdirs() }
    val liveDir: File get() = File(root, "live").apply { mkdirs() }
    val exportsDir: File get() = File(root, "exports").apply { mkdirs() }

    fun jobDir(jobId: String): File = File(workDir, jobId).apply { mkdirs() }

    fun modelFile(fileName: String): File = File(modelsDir, fileName)

    fun partFile(fileName: String): File = File(modelsDir, "$fileName.part")

    fun clipFile(fileName: String): File = File(clipsDir, fileName)

    fun thumbnailFile(id: String): File = File(thumbsDir, "$id.jpg")

    fun clearJob(jobId: String) {
        runCatching { File(workDir, jobId).deleteRecursively() }
    }

    fun clearWorkingCopies() {
        runCatching { workDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun modelsSize(): Long = dirSize(modelsDir)

    fun workingSize(): Long = dirSize(workDir) + dirSize(liveDir)

    fun clipsSize(): Long = dirSize(clipsDir)

    fun deleteModelFile(fileName: String): Boolean {
        val file = File(modelsDir, fileName)
        val part = File(modelsDir, "$fileName.part")
        var deleted = false
        if (file.exists()) deleted = file.delete()
        if (part.exists()) part.delete()
        return deleted
    }

    fun report(): StorageReport = StorageReport(
        freeBytes = DeviceProfiler.freeStorageBytes(context),
        totalBytes = DeviceProfiler.totalStorageBytes(context),
        modelsBytes = modelsSize(),
        workingBytes = workingSize(),
        exportsBytes = clipsSize()
    )

    fun hasRoomFor(bytes: Long): Boolean {
        val free = DeviceProfiler.freeStorageBytes(context)
        // Keep a 300 MB safety margin so the device never fills up mid-render.
        return free - bytes > 300L * 1024L * 1024L
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (t: Throwable) {
            Logx.w("Unable to measure ${dir.name}: ${t.message}")
            0L
        }
    }
}
