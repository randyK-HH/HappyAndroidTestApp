package com.happyhealth.testapp.data

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MemfaultClient"

data class MemfaultRelease(
    val id: Int,
    val version: String,
    val createdDate: String,
)

data class MemfaultPaging(
    val page: Int,
    val pageCount: Int,
    val totalCount: Int,
)

data class MemfaultReleasesResponse(
    val releases: List<MemfaultRelease>,
    val paging: MemfaultPaging,
)

class MemfaultClient {
    companion object {
        const val BASE_URL = "https://memfault.happy.dev"
        const val PROJECT_KEY = "2xkNhje7HWN6cPIH2tBBwnwQB6paEsua"
    }

    /** Fetch one page of releases. Call from Dispatchers.IO. */
    fun fetchReleases(page: Int, perPage: Int = 20): MemfaultReleasesResponse {
        val url = URL("$BASE_URL/releases?page=$page&per_page=$perPage")
        Log.d(TAG, "Fetching releases: $url")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Memfault-Project-Key", PROJECT_KEY)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw IOException("HTTP $code fetching releases: $errorBody")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val dataArray = json.getJSONArray("data")
            val releases = mutableListOf<MemfaultRelease>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                releases.add(
                    MemfaultRelease(
                        id = obj.getInt("id"),
                        version = obj.getString("version"),
                        createdDate = obj.optString("created_date", ""),
                    )
                )
            }

            val pagingObj = json.optJSONObject("paging")
            val paging = if (pagingObj != null) {
                MemfaultPaging(
                    page = pagingObj.optInt("page", page),
                    pageCount = pagingObj.optInt("page_count", 1),
                    totalCount = pagingObj.optInt("total_count", releases.size),
                )
            } else {
                MemfaultPaging(page = page, pageCount = 1, totalCount = releases.size)
            }

            Log.d(TAG, "Fetched ${releases.size} releases (page $page/${paging.pageCount})")
            return MemfaultReleasesResponse(releases, paging)
        } finally {
            conn.disconnect()
        }
    }

    /** Get the artifact download URL for a specific version. */
    fun fetchArtifactUrl(version: String): String {
        val url = URL("$BASE_URL/releases/$version")
        Log.d(TAG, "Fetching artifact URL for version: $version")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Memfault-Project-Key", PROJECT_KEY)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw IOException("HTTP $code fetching artifact for $version: $errorBody")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val data = json.getJSONObject("data")
            val artifacts = data.getJSONArray("artifacts")
            if (artifacts.length() == 0) {
                throw IOException("No artifacts found for version $version")
            }
            val artifactUrl = artifacts.getJSONObject(0).getString("url")
            Log.d(TAG, "Artifact URL: $artifactUrl")
            return artifactUrl
        } finally {
            conn.disconnect()
        }
    }

    /** Download the .img bytes from the artifact URL. */
    fun downloadArtifact(url: String): ByteArray {
        Log.d(TAG, "Downloading artifact: $url")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                throw IOException("HTTP $code downloading artifact")
            }

            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            conn.inputStream.use { stream ->
                var len: Int
                while (stream.read(tmp).also { len = it } != -1) {
                    buf.write(tmp, 0, len)
                }
            }

            val bytes = buf.toByteArray()
            Log.d(TAG, "Downloaded ${bytes.size} bytes")
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}
