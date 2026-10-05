package online.devhorizon.sourcecodemapper.analysis

import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.FileReport
import online.devhorizon.sourcecodemapper.model.ProviderConfig
import online.devhorizon.sourcecodemapper.model.ReportRow
import online.devhorizon.sourcecodemapper.model.RowVariant

/**
 * Merges the reports produced by several AI providers from the same code into a
 * single "super report": a consensus verdict plus the individual model opinions.
 */
object ExpressMerger {

    data class ProviderReport(val provider: ProviderConfig, val files: List<FileReport>)

    private fun rank(level: String) = when (level) {
        "vulnerability" -> 0; "problem" -> 1; "warning" -> 2; "ok" -> 3; "best" -> 4; else -> 3
    }

    fun merge(perProvider: List<ProviderReport>, lang: String): List<FileReport> {
        if (perProvider.isEmpty()) return emptyList()

        // filePath -> rowName -> variants
        val tree = LinkedHashMap<String, LinkedHashMap<String, MutableList<RowVariant>>>()
        val base = LinkedHashMap<String, LinkedHashMap<String, ReportRow>>()
        val language = LinkedHashMap<String, String>()

        for (pr in perProvider) {
            val providerName = Strings.providerTitle(lang, pr.provider.id, pr.provider.title)
            for (fr in pr.files) {
                language[fr.filePath] = fr.language
                val rowsByName = tree.getOrPut(fr.filePath) { LinkedHashMap() }
                val baseByName = base.getOrPut(fr.filePath) { LinkedHashMap() }
                for (row in fr.rows) {
                    rowsByName.getOrPut(row.name) { ArrayList() }
                        .add(RowVariant(providerName, row.detail, row.level, row.note, row.confidence))
                    baseByName.putIfAbsent(row.name, row)
                }
            }
        }

        val out = ArrayList<FileReport>()
        for ((path, rowsByName) in tree) {
            val merged = ArrayList<ReportRow>()
            for ((name, variants) in rowsByName) {
                val b = base[path]?.get(name) ?: continue
                if (b.kind == "file") {
                    merged.add(b.copy(variants = variants, confidence = "verified"))
                    continue
                }
                val levels = variants.map { it.level }
                val counts = levels.groupingBy { it }.eachCount()
                val maxCount = counts.values.maxOrNull() ?: 0
                val consensus = counts.filterValues { it == maxCount }.keys.minByOrNull { rank(it) } ?: "ok"
                val agreeing = variants.filter { it.level == consensus }
                val disagreeing = variants.filter { it.level != consensus }

                val noteParts = ArrayList<String>()
                agreeing.map { it.note }.firstOrNull { it.isNotBlank() }?.let { noteParts.add(it) }
                if (disagreeing.isNotEmpty()) {
                    noteParts.add(Strings.tr(lang, "merge_disagreement", disagreeing.joinToString("; ") { "${it.provider} → ${it.level}" }))
                }
                val detailParts = ArrayList<String>()
                agreeing.map { it.detail }.firstOrNull { it.isNotBlank() }?.let { detailParts.add(it) }
                if (disagreeing.isNotEmpty()) {
                    detailParts.add(Strings.tr(lang, "merge_opinions", variants.joinToString("\n") { "- ${it.provider} [${it.level}]: ${it.detail.take(400)}" }))
                }

                val confidence = when {
                    variants.size > 1 && disagreeing.isEmpty() -> "verified"
                    variants.size > 1 -> "conflicting"
                    else -> b.confidence
                }

                merged.add(
                    b.copy(
                        level = consensus,
                        detail = detailParts.joinToString("\n\n").ifBlank { b.detail },
                        note = noteParts.joinToString("\n").ifBlank { b.note },
                        confidence = confidence,
                        evidence = b.evidence,
                        variants = variants
                    )
                )
            }
            out.add(FileReport(path, language[path] ?: "", "", merged))
        }
        return out
    }

    /** Deterministic comparison summary — always available, even without a working model. */
    fun buildSuperSummary(perProvider: List<ProviderReport>, lang: String): String {
        if (perProvider.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(Strings.tr(lang, "super_title", perProvider.size)).append("\n\n")
        sb.append(Strings.tr(lang, "super_models", perProvider.joinToString(", ") {
            Strings.providerTitle(lang, it.provider.id, it.provider.title).substringBefore(" (")
        })).append("\n\n")

        var agree = 0; var conflict = 0; var total = 0
        val levelByModel = HashMap<String, HashMap<String, Int>>()

        val tree = HashMap<String, HashMap<String, MutableList<RowVariant>>>()
        for (pr in perProvider) {
            val providerName = Strings.providerTitle(lang, pr.provider.id, pr.provider.title)
            for (fr in pr.files) for (row in fr.rows) {
                if (row.kind == "file") continue
                tree.getOrPut(fr.filePath) { HashMap() }.getOrPut(row.name) { ArrayList() }
                    .add(RowVariant(providerName, row.detail, row.level, row.note, row.confidence))
            }
        }
        for ((_, rows) in tree) for ((_, variants) in rows) {
            total++
            val levels = variants.map { it.level }.distinct()
            if (levels.size <= 1) agree++ else conflict++
            for (v in variants) {
                val m = levelByModel.getOrPut(v.provider) { HashMap() }
                m[v.level] = (m[v.level] ?: 0) + 1
            }
        }
        sb.append(Strings.tr(lang, "super_compared", total)).append("\n")
        sb.append(Strings.tr(lang, "super_agree", agree, if (total > 0) agree * 100 / total else 0)).append("\n")
        sb.append(Strings.tr(lang, "super_conflicts", conflict)).append("\n\n")

        sb.append(Strings.get(lang, "super_profile")).append("\n")
        for ((model, counts) in levelByModel) {
            sb.append("- ").append(model).append(": ")
                .append(counts.entries.sortedBy { rank(it.key) }.joinToString(", ") { "${it.key}=${it.value}" })
                .append("\n")
        }
        sb.append("\n").append(Strings.get(lang, "super_note")).append("\n")
        return sb.toString()
    }
}
