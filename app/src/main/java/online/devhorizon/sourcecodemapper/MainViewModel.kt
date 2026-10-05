package online.devhorizon.sourcecodemapper

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import online.devhorizon.sourcecodemapper.ai.AiClient
import online.devhorizon.sourcecodemapper.ai.OllamaAuth
import online.devhorizon.sourcecodemapper.analysis.Enricher
import online.devhorizon.sourcecodemapper.analysis.ExpressMerger
import online.devhorizon.sourcecodemapper.analysis.Language
import online.devhorizon.sourcecodemapper.analysis.SecurityRules
import online.devhorizon.sourcecodemapper.analysis.StaticIndexer
import online.devhorizon.sourcecodemapper.data.CacheStore
import online.devhorizon.sourcecodemapper.data.DefaultProviders
import online.devhorizon.sourcecodemapper.data.RepoAccess
import online.devhorizon.sourcecodemapper.data.SettingsStore
import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.AppSettings
import online.devhorizon.sourcecodemapper.model.CodeElement
import online.devhorizon.sourcecodemapper.model.FileReport
import online.devhorizon.sourcecodemapper.model.Finding
import online.devhorizon.sourcecodemapper.model.LangStat
import online.devhorizon.sourcecodemapper.model.Progress
import online.devhorizon.sourcecodemapper.model.ProviderConfig
import online.devhorizon.sourcecodemapper.model.RepoFile
import online.devhorizon.sourcecodemapper.model.ReportBundle
import online.devhorizon.sourcecodemapper.model.Screen
import online.devhorizon.sourcecodemapper.report.HtmlReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RepoStats(
    val totalFiles: Int = 0,
    val totalLines: Int = 0,
    val totalBytes: Long = 0,
    val languages: List<LangStat> = emptyList(),
    val entryPoints: List<String> = emptyList()
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val settings: AppSettings = DefaultProviders.defaults(),
    val repoName: String = "",
    val repoSource: String = "",
    val files: List<RepoFile> = emptyList(),
    val stats: RepoStats = RepoStats(),
    val findings: List<Finding> = emptyList(),
    val reportPath: String? = null,
    val progress: Progress = Progress(),
    val busy: Boolean = false,
    val message: String? = null,
    val testResult: String? = null,
    val oauthBusy: Boolean = false,
    val recent: List<String> = emptyList()
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val repoAccess = RepoAccess(app)
    private val aiCache = CacheStore.of(app, "ai")
    private val synthCache = CacheStore.of(app, "synth")
    private var analysisJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            settings = store.load(),
            recent = store.recent()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val context get() = getApplication<Application>()

    // ------------------------------------------------------------------ settings

    fun setScreen(screen: Screen) = _state.update { it.copy(screen = screen, message = null) }

    fun updateSettings(new: AppSettings) {
        store.save(new)
        _state.update { it.copy(settings = new) }
    }

    fun updateProvider(provider: ProviderConfig) {
        val s = _state.value.settings
        val list = s.providers.map { if (it.id == provider.id) provider else it }
        updateSettings(s.copy(providers = list))
    }

    fun addProvider() {
        val s = _state.value.settings
        val id = "custom-" + System.currentTimeMillis().toString().takeLast(7)
        val p = ProviderConfig(
            id = id,
            title = "Custom OpenAI-compatible",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            apiKey = "",
            protocol = "openai"
        )
        updateSettings(s.copy(providers = s.providers + p, activeProviderId = id))
    }

    fun deleteProvider(id: String) {
        val s = _state.value.settings
        val list = s.providers.filterNot { it.id == id }
        val active = if (s.activeProviderId == id) (list.firstOrNull()?.id ?: "devhorizon") else s.activeProviderId
        updateSettings(s.copy(providers = list, activeProviderId = active))
    }

    fun restoreDefaultProviders() {
        val s = _state.value.settings
        val byId = s.providers.associateBy { it.id }.toMutableMap()
        for (d in DefaultProviders.all) byId.putIfAbsent(d.id, d)
        updateSettings(s.copy(providers = byId.values.toList()))
    }

    fun setLanguage(lang: String) = updateSettings(_state.value.settings.copy(language = lang))

    private fun activeProvider(): ProviderConfig {
        val s = _state.value.settings
        return s.providers.firstOrNull { it.id == s.activeProviderId } ?: DefaultProviders.devhorizon
    }

    fun setActiveProvider(id: String) = updateSettings(_state.value.settings.copy(activeProviderId = id))

    fun setMessage(m: String?) = _state.update { it.copy(message = m) }

    // ------------------------------------------------------------------ repo intake

    fun openLocalRepo(uri: Uri) {
        val s = _state.value.settings
        val limits = RepoAccess.Limits(s.maxFiles, s.maxFileKb * 1024, s.maxTotalMb * 1024 * 1024)
        _state.update { it.copy(busy = true, message = null, progress = Progress(phase = "phase_ingest", running = true)) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repoAccess.openLocalTree(uri, limits) { log(it) } }
            }.onSuccess { (name, files) -> acceptRepo(name, "local", files) }
                .onFailure { fail(it) }
        }
    }

    fun cloneGithub(url: String, branch: String, token: String, useJgit: Boolean) {
        if (url.isBlank()) { setMessage("Enter a GitHub URL"); return }
        val s = _state.value.settings
        val limits = RepoAccess.Limits(s.maxFiles, s.maxFileKb * 1024, s.maxTotalMb * 1024 * 1024)
        _state.update { it.copy(busy = true, message = null, progress = Progress(phase = "phase_ingest", running = true)) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repoAccess.cloneGithub(url, branch, token, useJgit, limits) { log(it) } }
            }.onSuccess { (name, files) ->
                store.addRecent(url)
                acceptRepo(name, "github: $url", files)
            }.onFailure { fail(it) }
        }
    }

    private fun acceptRepo(name: String, source: String, files: List<RepoFile>) {
        val stats = computeStats(files)
        val docs = files.count { Language.isDoc(it.path) }
        _state.update {
            it.copy(
                repoName = name, repoSource = source, files = files, stats = stats,
                findings = emptyList(), reportPath = null, busy = false, screen = Screen.REPO,
                progress = Progress(phase = "phase_idle"), recent = store.recent()
            )
        }
        log("repo accepted: ${files.size} code files (docs excluded: $docs)")
    }

    private fun fail(t: Throwable) {
        _state.update { it.copy(busy = false, progress = Progress(), message = "Error: ${t.message}") }
    }

    private fun computeStats(files: List<RepoFile>): RepoStats {
        val byLang = files.groupBy { it.language }
        val langs = byLang.map { (k, v) -> LangStat(k, v.size, v.sumOf { it.lineCount }) }
            .sortedByDescending { it.lines }
        return RepoStats(
            totalFiles = files.size,
            totalLines = files.sumOf { it.lineCount },
            totalBytes = files.sumOf { it.sizeBytes },
            languages = langs,
            entryPoints = detectEntryPoints(files)
        )
    }

    private fun detectEntryPoints(files: List<RepoFile>): List<String> {
        val names = setOf(
            "mainactivity.kt", "mainactivity.java", "application.kt", "application.java",
            "main.py", "app.py", "manage.py", "wsgi.py", "asgi.py", "server.py",
            "index.js", "index.ts", "main.js", "main.ts", "server.js", "server.ts", "app.js", "app.ts",
            "main.go", "main.rs", "program.cs", "main.c", "main.cpp", "main.dart",
            "index.html", "dockerfile", "makefile", "androidmanifest.xml", "package.json",
            "build.gradle", "build.gradle.kts", "settings.gradle", "pom.xml", "requirements.txt",
            "cargo.toml", "go.mod", "pubspec.yaml", "composer.json"
        )
        return files.filter { it.name.lowercase() in names }.map { it.path }.take(60)
    }

    // ------------------------------------------------------------------ single-provider analysis

    fun startAnalysis() {
        if (_state.value.files.isEmpty()) { setMessage("Open a repository first"); return }
        if (analysisJob?.isActive == true) return
        val s = _state.value.settings
        val provider = activeProvider()

        _state.update { it.copy(busy = true) }
        analysisJob = viewModelScope.launch {
            try {
                val lang = s.language
                setProgress("phase_static", 0, _state.value.files.size, true)
                val files = _state.value.files
                val elements = withContext(Dispatchers.Default) { StaticIndexer.index(files) }
                log("static: ${elements.values.sumOf { it.size }} elements (comments and docs excluded)")

                val findings = withContext(Dispatchers.Default) { SecurityRules.scan(files, lang) }
                val findingsIndex = SecurityRules.index(files, lang)
                _state.update { it.copy(findings = findings) }
                log("findings: ${findings.size}")

                val enricher = Enricher(AiClient(s.requestTimeoutSec), aiCache, s, provider)
                val reports = runEnrichment(files, elements, findingsIndex, enricher, lang, s, "phase_ai")

                setProgress("phase_synth", 0, 1, true)
                val overview = synthesize(reports, provider, lang)
                log("overview synthesized (${overview.length} chars)")

                setProgress("phase_render", 0, 1, true)
                val bundle = buildBundle(reports, overview, findings, lang, superReport = "", providers = listOf(provider.title))
                val html = HtmlReport.build(bundle)
                val file = writeReport(html)
                _state.update {
                    it.copy(reportPath = file.absolutePath, busy = false, screen = Screen.REPORT, progress = Progress(phase = "phase_idle"))
                }
                log("report written: ${file.absolutePath} (${html.length} chars)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    log("analysis cancelled")
                    _state.update { it.copy(busy = false, progress = Progress(phase = "phase_idle")) }
                } else fail(t)
            }
        }
    }

    // ------------------------------------------------------------------ express analysis (all providers)

    fun startExpressAnalysis() {
        if (_state.value.files.isEmpty()) { setMessage("Open a repository first"); return }
        if (analysisJob?.isActive == true) return
        val s = _state.value.settings
        // Only providers that can actually answer: a model plus a key, or the keyless default.
        val providers = s.providers.filter {
            it.model.isNotBlank() && (it.apiKey.isNotBlank() || it.id == "devhorizon")
        }
        if (providers.isEmpty()) { setMessage("Configure at least one provider with a model and a key"); return }

        _state.update { it.copy(busy = true) }
        analysisJob = viewModelScope.launch {
            try {
                val lang = s.language
                val files = _state.value.files
                val elements = withContext(Dispatchers.Default) { StaticIndexer.index(files) }
                val findings = withContext(Dispatchers.Default) { SecurityRules.scan(files, lang) }
                val findingsIndex = SecurityRules.index(files, lang)
                _state.update { it.copy(findings = findings) }
                log("express: ${providers.size} providers, ${files.size} files")

                val perProvider = ArrayList<ExpressMerger.ProviderReport>()
                providers.forEachIndexed { idx, p ->
                    setProgress("phase_ai", idx, providers.size, true)
                    log("express: provider ${idx + 1}/${providers.size} → ${p.title} (${p.model})")
                    val enricher = Enricher(AiClient(s.requestTimeoutSec), aiCache, s, p)
                    val reports = runEnrichment(files, elements, findingsIndex, enricher, lang, s, "phase_ai")
                    perProvider.add(ExpressMerger.ProviderReport(p, reports))
                }

                setProgress("phase_synth", 0, 1, true)
                val merged = ExpressMerger.merge(perProvider)
                val deterministic = ExpressMerger.buildSuperSummary(perProvider, lang)
                val aiComparison = compareReports(perProvider, providers.first(), lang)
                val superReport = buildString {
                    append(deterministic)
                    if (aiComparison.isNotBlank()) { append("\n\n## Сравнение моделей (AI)\n\n"); append(aiComparison) }
                }
                val overview = synthesize(merged, providers.first(), lang)
                log("express merged: ${merged.sumOf { it.rows.size }} rows, super-report ${superReport.length} chars")

                setProgress("phase_render", 0, 1, true)
                val bundle = buildBundle(merged, overview, findings, lang, superReport, providers.map { it.title })
                val html = HtmlReport.build(bundle)
                val file = writeReport(html)
                _state.update {
                    it.copy(reportPath = file.absolutePath, busy = false, screen = Screen.REPORT, progress = Progress(phase = "phase_idle"))
                }
                log("express report written: ${file.absolutePath}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    log("express cancelled")
                    _state.update { it.copy(busy = false, progress = Progress(phase = "phase_idle")) }
                } else fail(t)
            }
        }
    }

    private suspend fun runEnrichment(
        files: List<RepoFile>,
        elements: Map<String, List<CodeElement>>,
        findingsIndex: Map<String, Finding>,
        enricher: Enricher,
        lang: String,
        s: AppSettings,
        phase: String
    ): List<FileReport> {
        val aiFiles = files.filter { elements[it.path]?.isNotEmpty() == true }
        var done = 0
        val semaphore = Semaphore(s.aiConcurrency.coerceIn(1, 8))
        return coroutineScope {
            aiFiles.map { f ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val r = runCatching {
                            enricher.enrich(f, elements[f.path].orEmpty(), findingsIndex, lang) { log(it) }
                        }.getOrElse { t ->
                            log("enrich failed ${f.path}: ${t.message}")
                            enricher.baseline(f, elements[f.path].orEmpty(), findingsIndex)
                        }
                        synchronized(this@MainViewModel) {
                            done++
                            setProgressInline(phase, done, aiFiles.size, true)
                        }
                        r
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun compareReports(
        perProvider: List<ExpressMerger.ProviderReport>,
        provider: ProviderConfig,
        lang: String
    ): String {
        if (perProvider.size < 2 || provider.model.isBlank()) return ""
        val langName = when (lang) { "ru" -> "Russian"; "de" -> "German"; else -> "English" }

        val disagreements = StringBuilder()
        val tree = LinkedHashMap<String, LinkedHashMap<String, MutableList<Pair<String, String>>>>()
        for (pr in perProvider) for (fr in pr.files) for (row in fr.rows) {
            if (row.kind == "file") continue
            tree.getOrPut(fr.filePath) { LinkedHashMap() }.getOrPut(row.name) { ArrayList() }
                .add(pr.provider.title to row.level)
        }
        var n = 0
        for ((path, rows) in tree) {
            for ((name, verdicts) in rows) {
                if (verdicts.map { it.second }.distinct().size > 1 && n < 40) {
                    n++
                    disagreements.append("- ").append(path).append(" → ").append(name).append(": ")
                        .append(verdicts.joinToString(", ") { "${it.first}=${it.second}" }).append("\n")
                }
            }
        }
        if (disagreements.isBlank()) return ""

        val prompt = """
Модели проанализировали один и тот же код, но разошлись в оценках.
Разбери расхождения и выдай итоговый вердикт по каждому пункту в $langName, в Markdown.
Для каждого пункта: какой уровень верен и почему, коротко и по делу. Если не хватает данных — так и скажи.

Расхождения:
$disagreements
"""
        return runCatching {
            AiClient(_state.value.settings.requestTimeoutSec)
                .chat(provider, "You are a senior reviewer reconciling disagreements between AI models.", prompt, maxTokens = 2000, temperature = 0.2)
        }.getOrElse {
            log("compareReports failed: ${it.message}")
            ""
        }
    }

    private fun buildBundle(
        reports: List<FileReport>,
        overview: String,
        findings: List<Finding>,
        lang: String,
        superReport: String,
        providers: List<String>
    ): ReportBundle {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return ReportBundle(
            repoName = _state.value.repoName,
            source = _state.value.repoSource,
            generatedAt = generatedAt,
            language = lang,
            totalFiles = _state.value.stats.totalFiles,
            totalLines = _state.value.stats.totalLines,
            totalBytes = _state.value.stats.totalBytes,
            languages = _state.value.stats.languages,
            entryPoints = _state.value.stats.entryPoints,
            overview = overview,
            findings = findings,
            files = reports,
            superReport = superReport,
            providers = providers
        )
    }

    suspend fun synthesize(reports: List<FileReport>, provider: ProviderConfig, lang: String): String {
        val s = _state.value.settings
        if (!s.enableAi || provider.model.isBlank()) return fallbackOverview(reports, lang)
        val repoHash = CacheStore.hash(_state.value.repoName, reports.joinToString(",") { it.filePath }, provider.id, provider.model, lang, "v3")
        synthCache.read(repoHash)?.let { return it }

        val langName = when (lang) { "ru" -> "Russian"; "de" -> "German"; else -> "English" }
        val filesList = reports.take(250).joinToString("\n") { "${it.filePath} (${it.language})" }
        val worst = _state.value.findings.sortedBy { levelRank(it.level) }.take(60)
            .joinToString("\n") { "- [${it.level}] ${it.filePath}:${it.line} ${it.message}" }
        val stats = _state.value.stats
        val byLang = stats.languages.joinToString(", ") { "${it.name}: ${it.files}f/${it.lines}L" }

        val prompt = """
Repository: ${_state.value.repoName}
Files: ${stats.totalFiles}, lines: ${stats.totalLines}
Languages: $byLang
Entry points: ${stats.entryPoints.joinToString(", ")}

File list:
$filesList

Notable security/quality findings:
${worst.ifBlank { "(none)" }}

Write a concise architecture overview in $langName as Markdown:
- what the project is and its purpose
- main modules / layers and how they interact
- key technologies and frameworks
- data flow and entry points
- top risks and recommendations (with file references)
Keep it under 500 words. Do not use code fences. Only rely on facts that are visible in the file list and findings.
        """.trimIndent()

        val result = runCatching {
            AiClient(s.requestTimeoutSec).chat(provider, "You are a senior software architect.", prompt, maxTokens = 1600, temperature = 0.3)
        }.getOrElse {
            log("synthesis failed: ${it.message}")
            return fallbackOverview(reports, lang)
        }
        synthCache.write(repoHash, result)
        return result
    }

    private fun fallbackOverview(reports: List<FileReport>, lang: String): String {
        val stats = _state.value.stats
        val sb = StringBuilder()
        sb.append("## ").append(_state.value.repoName).append("\n")
        sb.append("## ").append(Strings.get(lang, "files")).append(": ").append(stats.totalFiles)
            .append(", ").append(Strings.get(lang, "lines")).append(": ").append(stats.totalLines).append("\n\n")
        for (ls in stats.languages.take(15)) {
            sb.append("- ").append(ls.name).append(": ").append(ls.files).append(" / ").append(ls.lines).append("\n")
        }
        return sb.toString()
    }

    private fun writeReport(html: String): File {
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "report_${System.currentTimeMillis()}.html")
        f.writeText(html)
        return f
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        _state.update { it.copy(busy = false, progress = Progress(phase = "phase_idle")) }
    }

    // ------------------------------------------------------------------ provider test / oauth

    fun testProvider(cfg: ProviderConfig) {
        _state.update { it.copy(testResult = Strings.get(_state.value.settings.language, "testing")) }
        viewModelScope.launch {
            val r = runCatching { AiClient(60).listModels(cfg) }
            val lang = _state.value.settings.language
            _state.update {
                it.copy(
                    testResult = r.fold(
                        onSuccess = { models -> "${Strings.get(lang, "test_ok")} ${models.size} models: ${models.take(8).joinToString(", ")}" },
                        onFailure = { e -> "${Strings.get(lang, "test_fail")} ${e.message}" }
                    )
                )
            }
        }
    }

    fun startOllamaOAuth(openBrowser: (String) -> Unit) {
        val key = OllamaAuth.newKey()
        _state.update { it.copy(oauthBusy = true, message = null) }
        openBrowser(OllamaAuth.connectUrl(android.os.Build.MODEL ?: "android", key))
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 180_000
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                if (OllamaAuth.poll(key)) { ok = true; break }
                kotlinx.coroutines.delay(2500)
            }
            val lang = _state.value.settings.language
            if (ok) {
                val s = _state.value.settings
                val list = s.providers.map { if (it.id == "ollama") it.copy(apiKey = key) else it }
                updateSettings(s.copy(providers = list))
                _state.update { it.copy(oauthBusy = false, message = Strings.get(lang, "oauth_done")) }
            } else {
                _state.update { it.copy(oauthBusy = false, message = Strings.get(lang, "oauth_fail")) }
            }
        }
    }

    // ------------------------------------------------------------------ report file access

    fun reportFile(): File? = _state.value.reportPath?.let { File(it).takeIf { f -> f.exists() } }

    // ------------------------------------------------------------------ helpers

    private fun levelRank(level: String) = when (level) {
        "vulnerability" -> 0; "problem" -> 1; "warning" -> 2; "best" -> 3; else -> 4
    }

    private fun setProgress(phase: String, done: Int, total: Int, running: Boolean) {
        _state.update { it.copy(progress = Progress(phase = phase, done = done, total = total, running = running, log = it.progress.log)) }
    }

    private fun setProgressInline(phase: String, done: Int, total: Int, running: Boolean) {
        _state.update { it.copy(progress = Progress(phase = phase, done = done, total = total, running = running, log = it.progress.log)) }
    }

    private fun log(line: String) {
        _state.update {
            val log = (it.progress.log + line).takeLast(400)
            it.copy(progress = it.progress.copy(log = log))
        }
    }
}
