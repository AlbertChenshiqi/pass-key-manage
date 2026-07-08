package com.passkey.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun authHeaders(apiKey: String) = buildList {
        add("Accept" to "application/json")
        if (apiKey.isNotBlank()) {
            add("Authorization" to "Bearer $apiKey")
            add("X-API-Key" to apiKey)
        }
    }

    suspend fun checkHealth(settings: UserSettings): ConnectionStatus = withContext(Dispatchers.IO) {
        val base = settings.serverUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext ConnectionStatus("idle", "未配置服务器")
        }

        val endpoints = listOf("$base/api/health", "$base/health", base)
        var lastError = "无法连接服务器"

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { authHeaders(settings.apiKey).forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                client.newCall(request).execute().use { res ->
                    when {
                        res.isSuccessful -> return@withContext ConnectionStatus("ok", "连接正常")
                        res.code == 401 || res.code == 403 ->
                            return@withContext ConnectionStatus("warning", "服务器可达（需检查 Key）")
                        else -> lastError = "HTTP ${res.code}"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "网络错误"
            }
        }
        ConnectionStatus("error", "连接失败：$lastError")
    }

    suspend fun fetchPayload(settings: UserSettings): Result<VaultPayload> = withContext(Dispatchers.IO) {
        val base = settings.serverUrl.trim().trimEnd('/')
        val endpoints = listOf("$base/api/2fa/accounts", "$base/api/2fa", "$base/2fa/accounts")
        var lastError = "未找到有效数据"

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply { authHeaders(settings.apiKey).forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                client.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) {
                        lastError = "HTTP ${res.code}"
                        return@use
                    }
                    val body = res.body?.string().orEmpty()
                    val payload = parsePayload(body)
                    if (payload != null) return@withContext Result.success(payload)
                }
            } catch (e: Exception) {
                lastError = e.message ?: "请求失败"
            }
        }
        Result.failure(Exception(lastError))
    }

    suspend fun pushPayload(settings: UserSettings, payload: VaultPayload): Result<VaultPayload> =
        withContext(Dispatchers.IO) {
            val base = settings.serverUrl.trim().trimEnd('/')
            val url = "$base/api/2fa/accounts"
            val body = json.encodeToString(VaultPayload.serializer(), payload)
                .toRequestBody(jsonMedia)

            try {
                val request = Request.Builder()
                    .url(url)
                    .put(body)
                    .apply { authHeaders(settings.apiKey).forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                client.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        return@withContext Result.failure(Exception(err?.ifBlank { null } ?: "HTTP ${res.code}"))
                    }
                    val parsed = parsePayload(text)
                        ?: return@withContext Result.failure(Exception("响应格式不正确"))
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parsePayload(body: String): VaultPayload? {
        return runCatching {
            val payload = json.decodeFromString<VaultPayload>(body)
            if (payload.accounts.isNotEmpty()) payload
            else {
                val arr = json.decodeFromString<List<VaultItem>>(body)
                VaultPayload(accounts = arr)
            }
        }.getOrNull()
    }
}
