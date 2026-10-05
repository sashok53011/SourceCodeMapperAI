package online.devhorizon.sourcecodemapper.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import online.devhorizon.sourcecodemapper.model.ProviderConfig
import java.util.concurrent.TimeUnit

class AiException(message: String) : Exception(message)

class AiClient(timeoutSec: Int = 180) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun root(cfg: ProviderConfig): String = cfg.baseUrl.trimEnd('/')

    private fun isOllamaNative(cfg: ProviderConfig) = cfg.protocol.equals("ollama", ignoreCase = true)

    private fun authHeader(cfg: ProviderConfig): String? =
        cfg.apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    // ------------------------------------------------------------------ chat

    suspend fun chat(cfg: ProviderConfig, system: String, user: String, maxTokens: Int = 1600, temperature: Double = 0.2): String =
        withContext(Dispatchers.IO) {
            val messages = buildJsonArray {
                add(msg("system", system))
                add(msg("user", user))
            }
            val body = buildJsonObject {
                put("model", cfg.model)
                put("messages", messages)
                put("temperature", temperature)
                put("stream", false)
                if (!isOllamaNative(cfg)) put("max_tokens", maxTokens)
            }.toString()

            val url = if (isOllamaNative(cfg)) "${root(cfg)}/api/chat" else "${root(cfg)}/chat/completions"
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "SourceCodeMapperAI/1.0")
                .apply { authHeader(cfg)?.let { header("Authorization", it) } }
                .post(body.toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiException("HTTP ${resp.code}: ${text.take(400)}")
                }
                parseContent(text)
            }
        }

    private fun msg(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun parseContent(text: String): String {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AiException("Non-JSON response: ${text.take(300)}")
        // OpenAI style
        obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
            choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull()?.let { return it }
            choice["text"]?.jsonPrimitive?.contentOrNull()?.let { return it }
        }
        // Ollama native style
        obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull()?.let { return it }
        obj["response"]?.jsonPrimitive?.contentOrNull()?.let { return it }
        throw AiException("Unrecognised response shape: ${text.take(300)}")
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    // ------------------------------------------------------------------ models

    suspend fun listModels(cfg: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val url = if (isOllamaNative(cfg)) "${root(cfg)}/api/tags" else "${root(cfg)}/models"
        val req = Request.Builder().url(url)
            .header("User-Agent", "SourceCodeMapperAI/1.0")
            .apply { authHeader(cfg)?.let { header("Authorization", it) } }
            .get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException("HTTP ${resp.code}: ${text.take(300)}")
            val obj = json.parseToJsonElement(text).jsonObject
            val names = ArrayList<String>()
            obj["data"]?.jsonArray?.forEach { el ->
                el.jsonObject["id"]?.jsonPrimitive?.contentOrNull()?.let { names.add(it) }
            }
            obj["models"]?.jsonArray?.forEach { el ->
                el.jsonObject["name"]?.jsonPrimitive?.contentOrNull()?.let { names.add(it) }
                el.jsonObject["model"]?.jsonPrimitive?.contentOrNull()?.let { names.add(it) }
            }
            names.distinct()
        }
    }
}

/** Ollama OAuth device flow: open the browser, poll until the key becomes active. */
object OllamaAuth {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun connectUrl(name: String, key: String): String =
        "https://ollama.com/connect?name=${java.net.URLEncoder.encode(name, "UTF-8")}&key=$key"

    fun newKey(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Returns true once the key is authorised by ollama.com. */
    suspend fun poll(key: String): Boolean = withContext(Dispatchers.IO) {
        val endpoints = listOf("https://ollama.com/api/me", "https://ollama.com/api/tags")
        endpoints.any { ep ->
            runCatching {
                val req = Request.Builder().url(ep)
                    .header("Authorization", "Bearer $key")
                    .header("User-Agent", "SourceCodeMapperAI/1.0")
                    .get().build()
                http.newCall(req).execute().use { it.code == 200 }
            }.getOrDefault(false)
        }
    }
}
