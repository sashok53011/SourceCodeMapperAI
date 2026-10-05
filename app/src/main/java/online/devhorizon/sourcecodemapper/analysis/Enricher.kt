package online.devhorizon.sourcecodemapper.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.devhorizon.sourcecodemapper.ai.AiClient
import online.devhorizon.sourcecodemapper.data.CacheStore
import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.AiElementAnswer
import online.devhorizon.sourcecodemapper.model.AppSettings
import online.devhorizon.sourcecodemapper.model.CodeElement
import online.devhorizon.sourcecodemapper.model.FileReport
import online.devhorizon.sourcecodemapper.model.Finding
import online.devhorizon.sourcecodemapper.model.ProviderConfig
import online.devhorizon.sourcecodemapper.model.RepoFile
import online.devhorizon.sourcecodemapper.model.ReportRow

class Enricher(
    private val ai: AiClient,
    private val cache: CacheStore,
    private val settings: AppSettings,
    private val provider: ProviderConfig
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val batchSize = 35
    private val promptVersion = "v2"

    fun baseline(file: RepoFile, elements: List<CodeElement>, findings: Map<String, Finding>, lang: String): FileReport {
        val fileRow = ReportRow(
            name = file.path,
            summary = Strings.tr(lang, "row_file_summary", file.language, file.lineCount),
            detail = Strings.tr(lang, "row_file_detail", file.language, file.sizeBytes, file.lineCount),
            lines = emptyList(),
            level = "ok",
            note = "",
            kind = "file",
            filePath = file.path,
            confidence = "verified",
            evidence = listOf(Strings.tr(lang, "ev_file", file.path))
        )

        val rows = ArrayList<ReportRow>()
        rows.add(fileRow)
        for (e in elements) {
            val found = findingsFor(e, findings)
            val level = found.minByOrNull { rank(it.level) }?.level ?: "ok"
            val note = found.minByOrNull { rank(it.level) }?.message ?: ""
            val evidence = if (found.isNotEmpty()) {
                found.map { Strings.tr(lang, "ev_rule", it.filePath, it.line, it.ruleId, it.level) }
            } else {
                listOf(Strings.tr(lang, "ev_static", e.filePath, e.startLine, e.endLine, Strings.kindLabel(lang, e.kind)))
            }
            rows.add(
                ReportRow(
                    name = e.name,
                    summary = e.staticDetail,
                    detail = buildDetail(e, lang),
                    lines = e.snippets,
                    level = level,
                    note = note,
                    kind = e.kind,
                    filePath = e.filePath,
                    confidence = if (found.isNotEmpty()) "verified" else "likely",
                    evidence = evidence
                )
            )
        }
        return FileReport(file.path, file.language, "", rows)
    }

    private fun buildDetail(e: CodeElement, lang: String): String {
        val ext = e.filePath.substringAfterLast('.', "")
        return Strings.tr(
            lang, "row_detail",
            Strings.kindLabel(lang, e.kind), ext,
            if (e.signature.isNotBlank()) e.signature.take(200) else "—",
            e.startLine, e.endLine
        )
    }

    private fun findingsFor(e: CodeElement, findings: Map<String, Finding>): List<Finding> =
        (e.startLine..e.endLine).mapNotNull { findings["${e.filePath}:$it"] }

    private fun rank(level: String) = when (level) {
        "vulnerability" -> 0; "problem" -> 1; "warning" -> 2; "best" -> 3; else -> 4
    }

    // ------------------------------------------------------------------ AI

    suspend fun enrich(
        file: RepoFile,
        elements: List<CodeElement>,
        findings: Map<String, Finding>,
        lang: String,
        log: (String) -> Unit
    ): FileReport {
        val base = baseline(file, elements, findings, lang)
        if (!settings.enableAi || provider.model.isBlank()) return base

        val cacheKey = CacheStore.hash(file.path, file.text.orEmpty(), provider.id, provider.model, lang, promptVersion)
        cache.read(cacheKey)?.let { cached ->
            return runCatching { merge(base, parse(cached), lang) }.getOrElse { base }
        }

        val contentElements = elements.filter { it.kind != "file" }
        if (contentElements.isEmpty()) return base

        val answers = ArrayList<AiElementAnswer>()
        contentElements.chunked(batchSize).forEachIndexed { idx, batch ->
            val user = buildPrompt(file, batch, findings, lang)
            try {
                val raw = ai.chat(provider, SYSTEM, user, maxTokens = 2400, temperature = 0.15)
                answers.addAll(parse(raw))
                log("AI: ${file.path} batch ${idx + 1} ok (${batch.size} elements)")
            } catch (t: Throwable) {
                log("AI: ${file.path} batch ${idx + 1} failed: ${t.message}")
            }
        }

        if (answers.isEmpty()) return base
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AiElementAnswer.serializer()), answers)
        cache.write(cacheKey, encoded)
        return merge(base, answers, lang)
    }

    private fun buildPrompt(file: RepoFile, batch: List<CodeElement>, findings: Map<String, Finding>, lang: String): String {
        val langName = when (lang) { "ru" -> "Russian"; "de" -> "German"; else -> "English" }
        val ctx = batch.joinToString("\n") { e ->
            val f = (e.startLine..e.endLine).mapNotNull { findings["${e.filePath}:$it"] }
                .joinToString("; ") { it.level + ":" + it.message }
            val code = e.snippets.take(12).joinToString("\\n") { "${it.n}: ${it.text.take(160)}" }
            """{"name":"${esc(e.name)}","kind":"${e.kind}","decl":"${esc(e.signature.take(160))}","lines":"${e.startLine}-${e.endLine}","findings":"${esc(f)}","code":"${esc(code)}"}"""
        }
        return """
You are a senior software architect and security reviewer.
Repository file: ${file.path}
Language: ${file.language}
You MUST answer ONLY with a valid JSON array, no markdown fences, no commentary.
For EACH element below produce one object with exactly these fields:
- "name": the element name EXACTLY as given
- "detail": 1-3 sentences in $langName explaining what it is, and which method / technology / type / class it uses (this is column 2)
- "level": one of "vulnerability", "problem", "warning", "ok", "best"
- "note": short justification or recommendation in $langName (this is column 4). Say explicitly if the code may cause a problem, if it is a vulnerability, or if it is a good/best practice.
- "confidence": one of "verified" (you can prove it from the lines shown), "likely" (reasonable inference), "unverified" (you cannot confirm it).
- "evidence": a SHORT concrete proof taken from the shown lines, or, when "confidence" is "unverified", the reason why it could not be confirmed.

RULES:
- Base every claim strictly on the lines provided. Do not invent behaviour.
- If the code is ambiguous or you cannot confirm what it does, set "confidence":"unverified" and put the reason in "evidence".
- Never claim a vulnerability unless the shown line actually contains it.

Elements (JSON, one per line):
$ctx

Return ONLY the JSON array of objects.
        """.trimIndent()
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun parse(raw: String): List<AiElementAnswer> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        val candidate = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        runCatching {
            val arr = json.parseToJsonElement(candidate) as JsonArray
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                AiElementAnswer(
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    detail = o["detail"]?.jsonPrimitive?.content.orEmpty(),
                    level = o["level"]?.jsonPrimitive?.content?.lowercase()?.trim().orEmpty().ifBlank { "ok" },
                    note = o["note"]?.jsonPrimitive?.content.orEmpty(),
                    confidence = o["confidence"]?.jsonPrimitive?.content?.lowercase()?.trim().orEmpty().ifBlank { "likely" },
                    evidence = o["evidence"]?.jsonPrimitive?.content.orEmpty()
                )
            }.filter { it.name.isNotBlank() }
        }
        return emptyList()
    }

    private fun merge(base: FileReport, answers: List<AiElementAnswer>, lang: String): FileReport {
        if (answers.isEmpty()) return base
        val byName = answers.associateBy { it.name.trim().lowercase() }
        val rows = base.rows.map { row ->
            val a = byName[row.name.trim().lowercase()]
            if (a == null) row
            else row.copy(
                detail = a.detail.ifBlank { row.detail },
                level = normalizeLevel(a.level, row.level),
                note = a.note.ifBlank { row.note },
                confidence = normalizeConfidence(a.confidence, row.confidence),
                evidence = if (a.evidence.isBlank()) row.evidence else row.evidence + Strings.tr(lang, "ev_ai_prefix", a.evidence)
            )
        }
        return base.copy(rows = rows)
    }

    private fun normalizeConfidence(ai: String, fallback: String): String {
        val c = ai.lowercase()
        return when {
            c.contains("verified") -> "verified"
            c.contains("likely") || c.contains("probable") -> "likely"
            c.contains("unverified") || c.contains("unknown") || c.contains("unsure") -> "unverified"
            else -> fallback
        }
    }

    private fun normalizeLevel(ai: String, fallback: String): String {
        val l = ai.lowercase()
        return when {
            l.contains("vulnerab") -> "vulnerability"
            l.contains("problem") -> "problem"
            l.contains("warn") -> "warning"
            l.contains("best") -> "best"
            l.contains("ok") || l.contains("pass") || l.contains("good") -> "ok"
            else -> fallback
        }
    }

    companion object {
        const val SYSTEM =
            "You are a senior software architect and security reviewer. " +
                "You always answer with a single valid JSON array and nothing else."
    }
}
