package com.aiclipcreator.core.network

import com.aiclipcreator.core.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YouTubeLiveInfo(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val isLive: Boolean,
    val concurrentViewers: Long?,
    val actualStartTime: String?,
    val scheduledStartTime: String?,
    val liveBroadcastContent: String?,
    val durationIso: String?
)

/**
 * Thin client for the official YouTube Data API v3 `videos.list` endpoint.
 * The API key is optional and supplied by the user in Settings; no other network calls are
 * made to YouTube. Watch URLs are never treated as raw media URLs.
 */
class YouTubeApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:youtube\.com/watch\?.*v=)([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/live/([A-Za-z0-9_-]{11})"""),
            Regex("""^([A-Za-z0-9_-]{11})$""")
        )
        return patterns.firstNotNullOfOrNull { it.find(url.trim())?.groupValues?.getOrNull(1) }
    }

    suspend fun liveDetails(videoId: String, apiKey: String): Result<YouTubeLiveInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "A YouTube Data API key is required for metadata lookup" }
                val parts = URLEncoder.encode("snippet,liveStreamingDetails,contentDetails", "UTF-8")
                val url = "https://www.googleapis.com/youtube/v3/videos?part=$parts&id=$videoId&key=$apiKey"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = runCatching {
                            JSONObject(body).getJSONObject("error").getJSONArray("errors")
                                .getJSONObject(0).getString("reason")
                        }.getOrElse { "HTTP ${response.code}" }
                        throw IllegalStateException("YouTube API error: $reason")
                    }
                    val root = JSONObject(body)
                    val items = root.optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        throw IllegalStateException("Video not found or private")
                    }
                    val item = items.getJSONObject(0)
                    val snippet = item.optJSONObject("snippet")
                    val live = item.optJSONObject("liveStreamingDetails")
                    val content = item.optJSONObject("contentDetails")
                    val broadcastContent = snippet?.optString("liveBroadcastContent")
                    val viewers = live?.optString("concurrentViewers")?.toLongOrNull()
                    YouTubeLiveInfo(
                        videoId = videoId,
                        title = snippet?.optString("title").orEmpty(),
                        channelTitle = snippet?.optString("channelTitle").orEmpty(),
                        isLive = broadcastContent == "live",
                        concurrentViewers = viewers,
                        actualStartTime = live?.optString("actualStartTime"),
                        scheduledStartTime = live?.optString("scheduledStartTime"),
                        liveBroadcastContent = broadcastContent,
                        durationIso = content?.optString("duration")
                    )
                }
            }.onFailure { Logx.w("YouTube lookup failed: ${it.message}") }
        }

    /** Resolves an HLS master playlist URL that the creator publishes for their own stream. */
    suspend fun headCheck(url: String): Result<Pair<Int, String?>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                response.code to response.header("Content-Type")
            }
        }
    }
}
