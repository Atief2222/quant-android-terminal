package com.quant.terminal.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    // Gist Raw URL untuk mengambil domain tunnel aktif otomatis
    private const val GIST_RAW_URL = "https://gist.githubusercontent.com/Atief2222/af6beb50b48ba4f24b7e672c4b174184/raw/active_server_url.txt"
    
    @Volatile
    var activeBaseUrl: String = "https://standby.trycloudflare.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Mengambil URL tunnel Cloudflare terbaru dari GitHub Gist */
    suspend fun resolveActiveUrl(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GIST_RAW_URL)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val url = response.body?.string()?.trim() ?: ""
                    if (url.startsWith("http")) {
                        activeBaseUrl = url
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        activeBaseUrl
    }

    /** Mengirim pesan percakapan ke Endpoint AI Mentor di backend server */
    suspend fun sendAiChat(message: String, history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("message", message)
                val historyArray = JSONArray()
                for (item in history) {
                    val obj = JSONObject().apply {
                        put("role", item.role)
                        put("text", item.text)
                    }
                    historyArray.put(obj)
                }
                put("history", historyArray)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$activeBaseUrl/api/ai-mentor-chat")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val json = JSONObject(responseStr)
                    json.optString("reply", "Tidak ada balasan dari server.")
                } else {
                    "Error Server (${response.code}): Periksa koneksi backend bot."
                }
            }
        } catch (e: Exception) {
            "Gagal menghubungi server: ${e.localizedMessage ?: "Timeout / URL tidak aktif"}"
        }
    }
}
