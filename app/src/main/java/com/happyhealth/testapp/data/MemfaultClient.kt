package com.happyhealth.testapp.data

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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
        const val CHUNKS_URL = "https://chunks.memfault.com/api/v0/chunks"
        const val PROJECT_KEY = "2xkNhje7HWN6cPIH2tBBwnwQB6paEsua"
    }

    /** Shared OkHttp client — connection pooling, HTTP/2, proper redirect handling. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .eventListenerFactory { TimingEventListener() }
        .build()

    /**
     * Upload Memfault debug chunks via multipart/mixed POST.
     * Returns the HTTP status code. Success = 202 (Accepted).
     * Call from Dispatchers.IO.
     */
    fun uploadChunks(deviceSerial: String, chunks: List<ByteArray>): Int {
        val totalBytes = chunks.sumOf { it.size }
        Log.d(TAG, "Uploading ${chunks.size} chunks ($totalBytes bytes) for $deviceSerial")

        val boundary = "mflt-chunk-boundary-${System.currentTimeMillis()}"

        val body = ByteArrayOutputStream()
        for (chunk in chunks) {
            body.write("--$boundary\r\n".toByteArray())
            body.write("Content-Type: application/octet-stream\r\n".toByteArray())
            body.write("Content-Length: ${chunk.size}\r\n".toByteArray())
            body.write("\r\n".toByteArray())
            body.write(chunk)
            body.write("\r\n".toByteArray())
        }
        body.write("--$boundary--\r\n".toByteArray())
        val bodyBytes = body.toByteArray()

        val mediaType = "multipart/mixed; boundary=\"$boundary\"".toMediaType()
        val request = Request.Builder()
            .url("$CHUNKS_URL/$deviceSerial")
            .header("Memfault-Project-Key", PROJECT_KEY)
            .post(bodyBytes.toRequestBody(mediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            Log.d(TAG, "Chunk upload response: HTTP ${response.code}")
            return response.code
        }
    }

    /** Fetch one page of releases. Call from Dispatchers.IO. */
    fun fetchReleases(page: Int, perPage: Int = 20): MemfaultReleasesResponse {
        val url = "$BASE_URL/releases/?page=$page&per_page=$perPage"
        Log.d(TAG, "Fetching releases: $url")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching releases: ${response.body?.string()}")
            }

            val bodyStr = response.body!!.string()
            val json = JSONObject(bodyStr)
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
        }
    }

    /** Get the artifact download URL for a specific version. */
    fun fetchArtifactUrl(version: String): String {
        val url = "$BASE_URL/releases/$version"
        Log.d(TAG, "Fetching artifact URL for version: $version")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching artifact for $version: ${response.body?.string()}")
            }

            val bodyStr = response.body!!.string()
            val json = JSONObject(bodyStr)
            val data = json.getJSONObject("data")
            val artifacts = data.getJSONArray("artifacts")
            if (artifacts.length() == 0) {
                throw IOException("No artifacts found for version $version")
            }
            val artifactUrl = artifacts.getJSONObject(0).getString("url")
            Log.d(TAG, "Artifact URL: $artifactUrl")
            return artifactUrl
        }
    }

    /**
     * Download the .img bytes from the artifact URL. Reports progress via callback.
     * Retries up to 3 times with a short read timeout — if S3 sends headers but
     * stalls on the body, we fail fast and retry with a fresh connection.
     */
    fun downloadArtifact(url: String, onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null): ByteArray {
        val maxRetries = 3
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return doDownloadArtifact(url, attempt, onProgress)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download attempt $attempt/$maxRetries failed: ${e.message}")
                if (attempt < maxRetries) {
                    // Force a new connection on retry by using a fresh client
                    Thread.sleep(1000L * attempt)
                }
            }
        }
        throw lastException ?: IOException("Download failed after $maxRetries attempts")
    }

    private fun doDownloadArtifact(
        url: String,
        attempt: Int,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?,
    ): ByteArray {
        Log.d(TAG, "Downloading artifact (attempt $attempt): $url")

        val request = Request.Builder()
            .url(url)
            .build()

        // Create a completely fresh OkHttpClient per download attempt — matching the
        // reference Android app pattern. This avoids sharing the connection pool/dispatcher
        // with the API client, which can cause S3 body downloads to stall.
        val downloadClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListenerFactory { TimingEventListener() }
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} downloading artifact")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            Log.d(TAG, "Artifact Content-Length: $contentLength, Content-Type: ${body.contentType()}")

            val buf = ByteArrayOutputStream(if (contentLength > 0) contentLength.toInt() else 65536)
            val tmp = ByteArray(16384)
            var totalRead = 0L
            body.byteStream().use { stream ->
                var len: Int
                while (stream.read(tmp).also { len = it } != -1) {
                    buf.write(tmp, 0, len)
                    totalRead += len
                    onProgress?.invoke(totalRead, contentLength)
                }
            }

            val bytes = buf.toByteArray()
            Log.d(TAG, "Downloaded ${bytes.size} bytes (attempt $attempt)")
            return bytes
        }
    }
}

/** OkHttp EventListener that logs detailed timing to Logcat for diagnostics. */
private class TimingEventListener : EventListener() {
    private var callStartMs = 0L

    private fun elapsed(): String = "${System.currentTimeMillis() - callStartMs}ms"

    override fun callStart(call: Call) {
        callStartMs = System.currentTimeMillis()
        Log.d(TAG, "[HTTP] callStart: ${call.request().url}")
    }
    override fun dnsStart(call: Call, domainName: String) {
        Log.d(TAG, "[HTTP] +${elapsed()} dnsStart: $domainName")
    }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        Log.d(TAG, "[HTTP] +${elapsed()} dnsEnd: $domainName -> ${inetAddressList.firstOrNull()}")
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        Log.d(TAG, "[HTTP] +${elapsed()} connectStart: $inetSocketAddress")
    }
    override fun secureConnectStart(call: Call) {
        Log.d(TAG, "[HTTP] +${elapsed()} secureConnectStart (TLS)")
    }
    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        Log.d(TAG, "[HTTP] +${elapsed()} secureConnectEnd: ${handshake?.tlsVersion}")
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        Log.d(TAG, "[HTTP] +${elapsed()} connectEnd: protocol=$protocol")
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
        Log.d(TAG, "[HTTP] +${elapsed()} connectionAcquired: ${connection.protocol()}")
    }
    override fun requestHeadersStart(call: Call) {
        Log.d(TAG, "[HTTP] +${elapsed()} requestHeadersStart")
    }
    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
        Log.d(TAG, "[HTTP] +${elapsed()} responseHeadersEnd: HTTP ${response.code}")
    }
    override fun responseBodyEnd(call: Call, byteCount: Long) {
        Log.d(TAG, "[HTTP] +${elapsed()} responseBodyEnd: $byteCount bytes")
    }
    override fun callEnd(call: Call) {
        Log.d(TAG, "[HTTP] +${elapsed()} callEnd (total)")
    }
    override fun callFailed(call: Call, ioe: IOException) {
        Log.d(TAG, "[HTTP] +${elapsed()} callFailed: ${ioe.message}")
    }
}
