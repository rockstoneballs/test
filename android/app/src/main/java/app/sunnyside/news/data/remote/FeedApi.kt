package app.sunnyside.news.data.remote

import app.sunnyside.news.data.FeedDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Downloads the aggregated good-news feed published by the scraper. */
class FeedApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val feedUrl: String,
) {
    suspend fun fetch(): FeedDto = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Feed request failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty feed")
            json.decodeFromString(FeedDto.serializer(), body)
        }
    }
}
