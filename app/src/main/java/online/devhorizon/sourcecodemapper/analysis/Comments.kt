package online.devhorizon.sourcecodemapper.analysis

/**
 * Detects comment-only and blank lines so that the analysis (and the report)
 * takes into account only real, executing code — as requested by the user.
 */
object Comments {

    data class LineInfo(
        val line: Int,
        val text: String,
        val isComment: Boolean,
        val isBlank: Boolean
    ) {
        val isCode: Boolean get() = !isComment && !isBlank
    }

    private val cLike = setOf(
        "Kotlin", "Java", "Scala", "C", "C++", "C++ header", "C/C++ header", "C#",
        "Go", "Rust", "PHP", "Swift", "Dart", "TypeScript", "TypeScript (TSX)"
    )
    private val slashSlash = setOf(
        "Kotlin", "Java", "Scala", "C", "C++", "C++ header", "C/C++ header", "C#",
        "Go", "Rust", "PHP", "Swift", "Dart", "JavaScript", "JavaScript (JSX)",
        "TypeScript", "TypeScript (TSX)", "Gradle"
    )

    private fun lineToken(language: String): String? = when (language) {
        in slashSlash -> "//"
        "Python", "Shell", "Ruby", "YAML", "Properties", "Docker", "Makefile", "TOML" -> "#"
        "SQL" -> "--"
        "CSS", "SCSS", "Less" -> null
        else -> null
    }

    fun analyze(text: String, language: String): List<LineInfo> {
        val lines = text.split('\n')
        val out = ArrayList<LineInfo>(lines.size)
        var inBlock = false
        var blockEnd = ""
        val token = lineToken(language)
        val markup = language == "XML" || language == "HTML"
        val blockStar = language in cLike || language == "CSS" || language == "SCSS" || language == "Less"
        val jsLikeBlock = language in setOf("JavaScript", "JavaScript (JSX)", "TypeScript", "TypeScript (TSX)")

        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            var comment = false
            if (inBlock) {
                comment = true
                if (t.contains(blockEnd)) inBlock = false
            } else when {
                markup -> {
                    val s = raw.indexOf("<!--")
                    if (s >= 0) {
                        comment = true
                        if (raw.indexOf("-->", s + 4) < 0) { inBlock = true; blockEnd = "-->" }
                    }
                }
                blockStar || jsLikeBlock -> {
                    val s = raw.indexOf("/*")
                    val l = if (token != null) raw.indexOf(token) else -1
                    if (s >= 0 && (l < 0 || s < l)) {
                        comment = true
                        if (raw.indexOf("*/", s + 2) < 0) { inBlock = true; blockEnd = "*/" }
                    } else if (l >= 0) {
                        comment = true
                    }
                }
                token != null -> if (t.startsWith(token)) comment = true
            }
            out.add(LineInfo(i + 1, raw, comment, t.isEmpty()))
        }
        return out
    }

    fun isCommentLine(line: String, language: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return when (language) {
            in slashSlash -> t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")
            "Python", "Shell", "Ruby", "YAML", "Properties", "Docker", "Makefile", "TOML" -> t.startsWith("#")
            "SQL", "Lua" -> t.startsWith("--")
            "XML", "HTML" -> t.startsWith("<!--")
            "CSS", "SCSS", "Less" -> t.startsWith("/*") || t.startsWith("*")
            else -> false
        }
    }
}
