package com.aiclipcreator.core.network

import com.aiclipcreator.core.common.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadOutcome {
    data class Success(val file: File, val bytes: Long) : DownloadOutcome()
    data class Failure(val message: String, val retryable: Boolean = true) : DownloadOutcome()
    data object Cancelled : DownloadOutcome()
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadOutcome()
}

/**
 * Downloads model artifacts to a `.part` file using HTTP Range requests, so an interrupted
 * install resumes where it stopped instead of restarting a multi-hundred-megabyte transfer.
 * The finished file is only moved into place after the SHA-256 check succeeds.
 */
class ResumableDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    suspend fun download(
        url: String,
        destination: File,
        expectedBytes: Long = 0L,
        expectedSha256: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val part = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        var attempt = 0
        var lastError = "Download failed"
        while (attempt < 3) {
            attempt++
            try {
                currentCoroutineContext().ensureActive()
                val existing = if (part.exists()) part.length() else 0L
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AIClipCreator/1.0 (Android)")
                    .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 416) {
                        // The part file is already complete (server has nothing past it).
                        return@withContext finish(part, destination, expectedBytes, expectedSha256, onProgress)
                    }
                    if (!response.isSuccessful) {
                        if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                            return@withContext DownloadOutcome.Failure(
                                "Server returned HTTP ${response.code} for $url",
                                retryable = false
                            )
                        }
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val serverLength = body.contentLength()
                    val resumed = response.code == 206
                    val startAt = if (resumed) existing else 0L
                    if (!resumed && existing > 0) {
                        part.delete()
                    }
                    val total = if (serverLength > 0) startAt + serverLength else expectedBytes
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(startAt)
                        var downloaded = startAt
                        val buffer = ByteArray(256 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
                            }
                            raf.fd.sync()
                        }
                    }
                }
                if (expectedBytes > 0 && part.length() < expectedBytes) {
                    throw IOException("Transfer ended early (${part.length()} of $expectedBytes bytes)")
                }
                return@withContext finish(part, destination, expectedBytes, expectedSha256, onProgress)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = t.message ?: t::class.java.simpleName
                Logx.w("Download attempt $attempt failed: $lastError")
                if (attempt >= 3) {
                    return@withContext DownloadOutcome.Failure(lastError)
                }
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        DownloadOutcome.Failure(lastError)
    }

    private fun finish(
        part: File,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit
    ): DownloadOutcome {
        if (expectedBytes > 0 && part.length() != expectedBytes) {
            return DownloadOutcome.Failure("Size mismatch: ${part.length()} != $expectedBytes")
        }
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                return DownloadOutcome.ChecksumMismatch(expectedSha256, actual)
            }
        }
        if (destination.exists()) destination.delete()
        if (!part.renameTo(destination)) {
            return DownloadOutcome.Failure("Unable to move verified file into place")
        }
        onProgress(destination.length(), destination.length())
        return DownloadOutcome.Success(destination, destination.length())
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
