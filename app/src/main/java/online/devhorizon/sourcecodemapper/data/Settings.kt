package online.devhorizon.sourcecodemapper.data

import android.content.Context
import kotlinx.serialization.json.Json
import online.devhorizon.sourcecodemapper.model.AppSettings
import online.devhorizon.sourcecodemapper.model.ProviderConfig

object DefaultProviders {

    val devhorizon = ProviderConfig(
        id = "devhorizon",
        title = "DevHorizon (OpenAI-compatible, no key)",
        baseUrl = "https://llm.devhorizon.online/v1",
        model = "gemma4-12b-qat-uncensored-hauhaucs-balanced",
        apiKey = "",
        protocol = "openai",
        freeModels = listOf(
            "gemma4-12b-qat-uncensored-hauhaucs-balanced",
            "gpt-oss-20b-uncensored-hauhaucs-aggressive",
            "qwen3.5-9b-uncensored-hauhaucs-aggressive",
            "agentica-org.deepcoder-14b-preview",
            "google/gemma-4-12b-qat"
        )
    )

    val ollama = ProviderConfig(
        id = "ollama",
        title = "Ollama Cloud (key or OAuth)",
        baseUrl = "https://ollama.com/v1",
        model = "",
        apiKey = "",
        protocol = "openai",
        freeModels = emptyList()
    )

    val opencodeGo = ProviderConfig(
        id = "opencode-go",
        title = "OpenCode Go (sk- key)",
        baseUrl = "https://opencode.ai/zen/go/v1",
        model = "space-bunny-free",
        apiKey = "",
        protocol = "openai",
        freeModels = listOf(
            "space-bunny-free",
            "longcat-2.5-preview-free",
            "deepseek-v4-flash",
            "glm-5.3-flash",
            "mimo-v2.6-flash",
            "hy3"
        )
    )

    val opencodeZen = ProviderConfig(
        id = "opencode-zen",
        title = "OpenCode Zen (sk- key)",
        baseUrl = "https://opencode.ai/zen/v1",
        model = "big-pickle",
        apiKey = "",
        protocol = "openai",
        freeModels = listOf(
            "big-pickle",
            "space-bunny-free",
            "mimo-v2.5-free",
            "nemotron-3-ultra-free",
            "ling-3.1-flash-free",
            "qwen3.8-flash"
        )
    )

    val all = listOf(devhorizon, ollama, opencodeGo, opencodeZen)

    fun defaults(): AppSettings = AppSettings(providers = all)
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("scm_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun load(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return DefaultProviders.defaults()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse { DefaultProviders.defaults() }
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString("settings", json.encodeToString(AppSettings.serializer(), settings)).apply()
    }

    fun recent(): List<String> = prefs.getString("recent", "")!!.split('\u0001').filter { it.isNotBlank() }

    fun addRecent(value: String) {
        val list = (listOf(value) + recent()).distinct().take(10)
        prefs.edit().putString("recent", list.joinToString("\u0001")).apply()
    }
}

class CacheStore(private val dir: java.io.File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(key: String) = java.io.File(dir, key + ".json")

    fun read(key: String): String? = runCatching {
        val f = file(key)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    fun write(key: String, value: String) {
        runCatching {
            dir.mkdirs()
            file(key).writeText(value)
        }
    }

    companion object {
        fun hash(vararg parts: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }.take(40)
        }

        fun ensure(dir: java.io.File): java.io.File {
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun of(context: Context, sub: String): CacheStore {
            val base = java.io.File(context.cacheDir, "scm_cache")
            return CacheStore(ensure(java.io.File(base, sub)))
        }
    }
}
