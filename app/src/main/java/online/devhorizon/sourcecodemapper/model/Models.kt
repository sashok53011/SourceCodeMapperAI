package online.devhorizon.sourcecodemapper.model

import kotlinx.serialization.Serializable

/** A configurable AI provider. */
@Serializable
data class ProviderConfig(
    val id: String,
    val title: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    /** "openai" -> OpenAI-compatible /chat/completions, "ollama" -> native /api/chat */
    val protocol: String = "openai",
    val freeModels: List<String> = emptyList()
)

@Serializable
data class AppSettings(
    val activeProviderId: String = "devhorizon",
    val providers: List<ProviderConfig> = emptyList(),
    val language: String = "ru",
    val maxFiles: Int = 2000,
    val maxFileKb: Int = 320,
    val maxTotalMb: Int = 25,
    val aiConcurrency: Int = 2,
    val enableAi: Boolean = true,
    val requestTimeoutSec: Int = 180
)

/** One file of the analysed repository. */
data class RepoFile(
    val path: String,
    val name: String,
    val language: String,
    val sizeBytes: Long,
    val lineCount: Int,
    val text: String?
)

/** A discovered code element (file, class, method, UI element, trigger, ...). */
data class CodeElement(
    val name: String,
    val kind: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val signature: String,
    val staticDetail: String,
    val snippets: List<LineRef>
)

@Serializable
data class LineRef(val n: Int, val text: String)

/** A security / quality finding. */
data class Finding(
    val filePath: String,
    val line: Int,
    val ruleId: String,
    val level: String,
    val message: String
)

/** One variant of a row produced by a single AI provider (used by the express report). */
@Serializable
data class RowVariant(
    val provider: String,
    val detail: String,
    val level: String,
    val note: String,
    val confidence: String = "likely"
)

/** One row of the 4-column report. */
@Serializable
data class ReportRow(
    val name: String,
    val summary: String,
    val detail: String,
    val lines: List<LineRef>,
    val level: String,
    val note: String,
    val kind: String = "",
    val filePath: String = "",
    /** verified | likely | unverified | conflicting */
    val confidence: String = "likely",
    /** Short pieces of evidence or the reason why something could not be verified. */
    val evidence: List<String> = emptyList(),
    /** Per-provider verdicts (express report). */
    val variants: List<RowVariant> = emptyList()
)

@Serializable
data class FileReport(
    val filePath: String,
    val language: String,
    val summary: String,
    val rows: List<ReportRow>
)

/** AI answer for a batch of elements. */
@Serializable
data class AiElementAnswer(
    val name: String,
    val detail: String = "",
    val level: String = "ok",
    val note: String = "",
    val confidence: String = "likely",
    val evidence: String = ""
)

/** Whole report payload handed to the HTML builder. */
data class ReportBundle(
    val repoName: String,
    val source: String,
    val generatedAt: String,
    val language: String,
    val totalFiles: Int,
    val totalLines: Int,
    val totalBytes: Long,
    val languages: List<LangStat>,
    val entryPoints: List<String>,
    val overview: String,
    val findings: List<Finding>,
    val files: List<FileReport>,
    val superReport: String = "",
    val providers: List<String> = emptyList()
)

@Serializable
data class LangStat(val name: String, val files: Int, val lines: Int)

data class Progress(
    val phase: String = "",
    val detail: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val log: List<String> = emptyList()
)

enum class Screen { HOME, REPO, PROGRESS, REPORT, SETTINGS }
