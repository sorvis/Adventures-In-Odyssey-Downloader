package com.odyssey.nas

import com.odyssey.app.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAS-side archive client. Every public method returns Result so callers can
 * handle "NAS not configured" / "NAS unreachable" without crashing the app.
 *
 * The app is designed to function fully without a NAS — daily check, download,
 * play, retention all work standalone. NAS failures degrade gracefully.
 */
@Singleton
class NasClient @Inject constructor(
    private val settings: SettingsRepo,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isConfigured(): Boolean = settings.flow.first().nasConfigured

    suspend fun upload(
        episodeId: Long,
        title: String,
        airDate: String?,
        description: String?,
        durationSecs: Long,
        sourceUrl: String,
        audio: File,
    ): Result<Unit> = call { base, token ->
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("episode_id", episodeId.toString())
            .addFormDataPart("title", title)
            .apply {
                airDate?.let     { addFormDataPart("air_date", it) }
                description?.let { addFormDataPart("description", it) }
                addFormDataPart("duration_secs", durationSecs.toString())
                addFormDataPart("source_url", sourceUrl)
            }
            .addFormDataPart("audio", audio.name,
                audio.asRequestBody("audio/mpeg".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("$base/episodes")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..201) error("upload HTTP ${resp.code}")
        }
    }

    suspend fun search(q: String?, album: String?, limit: Int = 50, offset: Int = 0):
            Result<List<NasEpisode>> = call { base, token ->
        val params = buildList {
            q?.takeIf { it.isNotBlank() }?.let     { add("q=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            album?.takeIf { it.isNotBlank() }?.let { add("album=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            add("limit=$limit"); add("offset=$offset")
        }.joinToString("&")
        val req = Request.Builder()
            .url("$base/episodes?$params")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("search HTTP ${resp.code}")
            json.decodeFromString<List<NasEpisode>>(resp.body!!.string())
        }
    }

    suspend fun listAlbums(): Result<List<NasAlbum>> = call { base, token ->
        val req = Request.Builder()
            .url("$base/albums")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("albums HTTP ${resp.code}")
            json.decodeFromString<List<NasAlbum>>(resp.body!!.string())
        }
    }

    /** Stream URL for a NAS-archived episode; ExoPlayer can range-fetch this. */
    suspend fun audioUrl(episodeId: Long): Result<NasAudio> {
        val s = settings.flow.first()
        if (!s.nasConfigured) return Result.failure(NasNotConfiguredException)
        return Result.success(NasAudio(
            url = "${s.nasUrl}/episodes/$episodeId/audio",
            authHeader = "Bearer ${s.nasToken}",
        ))
    }

    private suspend inline fun <T> call(crossinline block: (String, String) -> T): Result<T> {
        val s = settings.flow.first()
        if (!s.nasConfigured) return Result.failure(NasNotConfiguredException)
        return runCatching { block(s.nasUrl, s.nasToken) }
    }
}

object NasNotConfiguredException : RuntimeException("NAS not configured")

@Serializable
data class NasEpisode(
    val episode_id: Long,
    val title: String,
    val air_date: String? = null,
    val album: String? = null,
    val description: String? = null,
    val duration_secs: Long? = null,
    val file_size: Long,
)

@Serializable
data class NasAlbum(val name: String, val episode_count: Int)

data class NasAudio(val url: String, val authHeader: String)
