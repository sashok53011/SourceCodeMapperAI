package online.devhorizon.sourcecodemapper.analysis

object Language {

    private val byExt = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin",
        "java" to "Java",
        "js" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript",
        "jsx" to "JavaScript (JSX)",
        "ts" to "TypeScript", "tsx" to "TypeScript (TSX)",
        "py" to "Python",
        "c" to "C", "h" to "C/C++ header",
        "cpp" to "C++", "cc" to "C++", "cxx" to "C++", "hpp" to "C++ header",
        "cs" to "C#",
        "go" to "Go",
        "rs" to "Rust",
        "php" to "PHP",
        "rb" to "Ruby",
        "swift" to "Swift",
        "dart" to "Dart",
        "scala" to "Scala",
        "sh" to "Shell", "bash" to "Shell",
        "sql" to "SQL",
        "xml" to "XML",
        "html" to "HTML", "htm" to "HTML",
        "css" to "CSS", "scss" to "SCSS", "less" to "Less",
        "json" to "JSON",
        "yaml" to "YAML", "yml" to "YAML",
        "toml" to "TOML",
        "md" to "Markdown",
        "gradle" to "Gradle",
        "properties" to "Properties",
        "vue" to "Vue",
        "svelte" to "Svelte"
    )

    private val binaryExt = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svgz", "tif", "tiff",
        "mp3", "mp4", "avi", "mov", "mkv", "wav", "ogg", "webm", "flv",
        "zip", "rar", "7z", "gz", "tar", "jar", "apk", "aab", "class", "dex",
        "so", "dll", "exe", "dylib", "bin", "o", "a", "lib", "obj",
        "pdf", "woff", "woff2", "ttf", "otf", "eot", "psd", "ai", "db", "sqlite",
        "keystore", "jks", "p12", "pfx", "der", "pem", "sqlite3", "wasm", "pyc", "pyo"
    )

    val codeLanguages = setOf(
        "Kotlin", "Java", "JavaScript", "JavaScript (JSX)", "TypeScript", "TypeScript (TSX)",
        "Python", "C", "C/C++ header", "C++", "C++ header", "C#", "Go", "Rust", "PHP",
        "Ruby", "Swift", "Dart", "Scala", "Shell", "SQL", "Vue", "Svelte"
    )

    fun ext(path: String): String {
        val name = path.substringAfterLast('/')
        return if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    }

    fun detect(path: String): String {
        val e = ext(path)
        byExt[e]?.let { return it }
        val name = path.substringAfterLast('/').lowercase()
        if (name == "dockerfile") return "Docker"
        if (name == "makefile") return "Makefile"
        if (name == "cmakelists.txt") return "CMake"
        return if (e.isEmpty()) "Text" else e.uppercase()
    }

    fun isBinaryExt(path: String): Boolean = ext(path) in binaryExt

    fun isCode(language: String): Boolean = language in codeLanguages

    /**
     * Documentation and prose are never analysed: README, *.md, *.txt, changelogs, licences, logs.
     * Rule requested by the user: only real, executing code is taken into account.
     */
    fun isDoc(path: String): Boolean {
        val e = ext(path)
        if (e in setOf("md", "markdown", "mdown", "mkd", "txt", "text", "rst", "adoc", "asciidoc", "log")) return true
        val name = path.substringAfterLast('/').lowercase()
        return name.startsWith("readme") ||
            name.startsWith("license") || name.startsWith("licence") ||
            name.startsWith("changelog") || name.startsWith("changes") ||
            name.startsWith("contributing") || name.startsWith("code_of_conduct") ||
            name.startsWith("authors") || name.startsWith("notice") ||
            name.startsWith("copying") || name.startsWith("thanks")
    }

    /** Directories that are never interesting for a source map. */
    val ignoredDirs = setOf(
        ".git", ".hg", ".svn", "node_modules", "bower_components", "build", "out", "dist",
        ".gradle", ".idea", ".vscode", "venv", ".venv", "env", "__pycache__", ".pytest_cache",
        "target", "bin", "obj", "Pods", ".dart_tool", ".next", ".nuxt", "vendor", ".terraform",
        "coverage", ".cache", "Debug", "Release", ".mvn", "gradle-wrapper", "generated", "gen"
    )
}
