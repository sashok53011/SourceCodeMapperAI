package online.devhorizon.sourcecodemapper.analysis

import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.CodeElement
import online.devhorizon.sourcecodemapper.model.LineRef
import online.devhorizon.sourcecodemapper.model.RepoFile

/**
 * Deterministic, on-device extraction of code elements: files, classes, functions,
 * methods, properties, UI elements, triggers, manifest components, resources, etc.
 *
 * Comments and blank lines are ignored (only real, executing code is considered).
 * Every code line of a file ends up covered by exactly one element.
 * All human-readable descriptions are localized through [Strings].
 */
object StaticIndexer {

    private const val D = "\$"
    private const val MAX_SNIPPET = 200

    private val KW = setOf(
        "if", "for", "while", "switch", "catch", "return", "else", "do", "try", "new",
        "sizeof", "typeof", "delete", "throw", "case", "default", "super", "this", "when",
        "match", "print", "println", "assert", "lock", "using", "foreach", "yield"
    )

    private fun st(lang: String, key: String, vararg args: Any?): String =
        if (args.isEmpty()) Strings.get(lang, key) else Strings.tr(lang, key, *args)

    fun index(files: List<RepoFile>, lang: String): Map<String, List<CodeElement>> {
        val result = LinkedHashMap<String, List<CodeElement>>()
        for (f in files) {
            val text = f.text ?: continue
            val elements = when {
                Language.isCode(f.language) -> indexCode(f, text, lang)
                f.language == "XML" -> indexXml(f, text, lang)
                f.language == "HTML" -> indexHtml(f, text, lang)
                else -> emptyList()
            }
            result[f.path] = elements
        }
        return result
    }

    // ------------------------------------------------------------------ code

    private fun indexCode(file: RepoFile, text: String, lang: String): List<CodeElement> {
        val lines = text.split('\n')
        val pl = file.language
        val infos = Comments.analyze(text, pl)
        val hits = ArrayList<CodeElement>()

        for (i in lines.indices) {
            if (!infos[i].isCode) continue
            val line = lines[i]
            val ln = i + 1

            when (pl) {
                "Kotlin", "Java", "Scala" -> {
                    classRe.find(line)?.let {
                        hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_decl_in", it.groupValues[1].trim(), pl)))
                    }
                    lifecycleRe.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "lifecycle", file, ln, line, st(lang, "sd_lifecycle")))
                    }
                    funRe.find(line)?.let {
                        val isComposable = i > 0 && lines[i - 1].contains("@Composable")
                        val kind = if (isComposable) "composable" else "function"
                        hits.add(mk(it.groupValues[1], kind, file, ln, line, if (isComposable) st(lang, "sd_composable") else st(lang, "sd_function_in", pl)))
                    }
                    propRe.find(line)?.let {
                        val isConst = line.contains("const ")
                        hits.add(mk(it.groupValues[2], if (isConst) "constant" else "property", file, ln, line, st(lang, if (isConst) "sd_constant" else "sd_property")))
                    }
                    listenerRe.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_trigger_listener")))
                    }
                    importRe.find(line)?.let {
                        hits.add(mk(it.groupValues[1].substringAfterLast('.'), "import", file, ln, line, st(lang, "sd_import", it.groupValues[1])))
                    }
                }
                "JavaScript", "JavaScript (JSX)", "TypeScript", "TypeScript (TSX)", "Vue", "Svelte" -> {
                    classReJs.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "class", file, ln, line, st(lang, "sd_class_js")))
                    }
                    fnReJs.find(line)?.let {
                        val cap = it.groupValues[1].firstOrNull()?.isUpperCase() == true
                        hits.add(mk(it.groupValues[1], if (cap) "composable" else "function", file, ln, line, if (cap) st(lang, "sd_component") else st(lang, "sd_function_js")))
                    }
                    arrowReJs.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_arrow")))
                    }
                    eventReJs.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_event_listener")))
                    }
                    domIdRe.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "ui", file, ln, line, st(lang, "sd_dom_id")))
                    }
                    queryRe.find(line)?.let {
                        hits.add(mk(it.groupValues[1], "ui", file, ln, line, st(lang, "sd_dom_query")))
                    }
                    methodReJs.find(line)?.let {
                        val n = it.groupValues[1]
                        if (n !in KW && n.length > 1) hits.add(mk(n, "method", file, ln, line, st(lang, "sd_method_js")))
                    }
                }
                "Python" -> {
                    defRe.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_py"))) }
                    classRePy.find(line)?.let { hits.add(mk(it.groupValues[1], "class", file, ln, line, st(lang, "sd_class_py"))) }
                    decoratorRe.find(line)?.let { hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_decorator"))) }
                    routeRe.find(line)?.let { hits.add(mk(it.groupValues[2], "route", file, ln, line, st(lang, "sd_route", it.groupValues[1].uppercase()))) }
                }
                "Go" -> {
                    goFunc.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_go"))) }
                    goType.find(line)?.let { hits.add(mk(it.groupValues[1], kindFor(it.groupValues[2]), file, ln, line, st(lang, "sd_type_go", it.groupValues[2]))) }
                }
                "Rust" -> {
                    rustFn.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_rs"))) }
                    rustType.find(line)?.let { hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_type_rs", it.groupValues[1]))) }
                }
                "PHP" -> {
                    phpClass.find(line)?.let { hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_type_php"))) }
                    phpFn.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_php"))) }
                }
                "Ruby" -> {
                    rbDef.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_method_rb"))) }
                    rbClass.find(line)?.let { hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_type_rb"))) }
                }
                "Swift" -> {
                    swiftFn.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_swift"))) }
                    swiftType.find(line)?.let { hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_type_swift"))) }
                }
                "Dart" -> {
                    dartClass.find(line)?.let { hits.add(mk(it.groupValues[1], "class", file, ln, line, st(lang, "sd_class_dart"))) }
                    dartFn.find(line)?.let {
                        val n = it.groupValues[1]
                        if (n !in KW) hits.add(mk(n, "method", file, ln, line, st(lang, "sd_method_dart")))
                    }
                }
                "Shell" -> {
                    shFn.find(line)?.let { hits.add(mk(it.groupValues[1], "function", file, ln, line, st(lang, "sd_function_sh"))) }
                }
                "SQL" -> {
                    sqlRe.find(line)?.let { hits.add(mk(it.groupValues[2], "object", file, ln, line, st(lang, "sd_sql", it.groupValues[1].uppercase()))) }
                }
                "C", "C++", "C++ header", "C/C++ header", "C#" -> {
                    cClassRe.find(line)?.let { hits.add(mk(it.groupValues[2], kindFor(it.groupValues[1]), file, ln, line, st(lang, "sd_type_c", pl))) }
                    cFuncRe.find(line)?.let {
                        val n = it.groupValues[1]
                        if (n !in KW) hits.add(mk(n, "function", file, ln, line, st(lang, "sd_function_c", pl)))
                    }
                }
            }
        }

        val unique = hits.distinctBy { it.name to it.startLine }
        return withCoverage(lines, unique, file, infos, lang)
    }

    // ------------------------------------------------------------------ XML (Android-ish)

    private fun indexXml(file: RepoFile, text: String, lang: String): List<CodeElement> {
        val lines = text.split('\n')
        val infos = Comments.analyze(text, file.language)
        val hits = ArrayList<CodeElement>()
        for (i in lines.indices) {
            if (!infos[i].isCode) continue
            val line = lines[i]; val ln = i + 1
            idRe.find(line)?.let { hits.add(mk(it.groupValues[1], "ui", file, ln, line, st(lang, "sd_android_id"))) }
            onClickRe.find(line)?.let { hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_android_onclick"))) }
            usesPermission.find(line)?.let { hits.add(mk(it.groupValues[1], "permission", file, ln, line, st(lang, "sd_permission"))) }
            manifestComp.find(line)?.let { hits.add(mk(it.groupValues[2], "component", file, ln, line, st(lang, "sd_manifest_component", it.groupValues[1]))) }
            stringRes.find(line)?.let { hits.add(mk(it.groupValues[1], "resource", file, ln, line, st(lang, "sd_string_res"))) }
            otherRes.find(line)?.let { hits.add(mk(it.groupValues[2], "resource", file, ln, line, st(lang, "sd_resource", it.groupValues[1]))) }
        }
        val unique = hits.distinctBy { it.name to it.startLine }
        return withCoverage(lines, unique, file, infos, lang)
    }

    private fun indexHtml(file: RepoFile, text: String, lang: String): List<CodeElement> {
        val lines = text.split('\n')
        val infos = Comments.analyze(text, file.language)
        val hits = ArrayList<CodeElement>()
        for (i in lines.indices) {
            if (!infos[i].isCode) continue
            val line = lines[i]; val ln = i + 1
            htmlId.find(line)?.let { hits.add(mk(it.groupValues[1], "ui", file, ln, line, st(lang, "sd_html_ui"))) }
            domIdRe.find(line)?.let { hits.add(mk(it.groupValues[1], "ui", file, ln, line, st(lang, "sd_dom_id"))) }
            eventReJs.find(line)?.let { hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_event_listener"))) }
            onclickAttr.find(line)?.let { hits.add(mk(it.groupValues[1], "trigger", file, ln, line, st(lang, "sd_onclick_inline"))) }
            htmlTag.find(line)?.let {
                val tag = it.groupValues[1].lowercase()
                if (tag in setOf("script", "style", "form", "input", "button", "a", "canvas", "video", "audio", "select", "textarea", "table"))
                    hits.add(mk("<$tag>", "tag", file, ln, line, st(lang, "sd_html_tag")))
            }
        }
        val unique = hits.distinctBy { it.name to it.startLine }
        return withCoverage(lines, unique, file, infos, lang)
    }

    // ------------------------------------------------------------------ helpers

    private fun mk(name: String, kind: String, file: RepoFile, line: Int, text: String, detail: String): CodeElement =
        CodeElement(
            name = name,
            kind = kind,
            filePath = file.path,
            startLine = line,
            endLine = line,
            signature = text.trim().take(300),
            staticDetail = detail,
            snippets = emptyList()
        )

    private fun withCoverage(
        lines: List<String>,
        elements: List<CodeElement>,
        file: RepoFile,
        infos: List<Comments.LineInfo>,
        lang: String
    ): List<CodeElement> {
        val codeLines = infos.filter { it.isCode }.map { it.line }
        val codeSet = codeLines.toHashSet()
        val ordered = elements.sortedBy { it.startLine }

        val covered = BooleanArray(lines.size + 2)
        val enriched = ordered.map { e ->
            val start = e.startLine.coerceAtLeast(1)
            val end = blockEnd(lines, start - 1, file.language) + 1
            val ee = e.copy(
                endLine = maxOf(end, start),
                snippets = snippet(lines, start, end, codeSet)
            )
            for (l in start..ee.endLine) if (l in covered.indices) covered[l] = true
            ee
        }

        val items = ArrayList<Pair<Int, CodeElement>>()
        for (e in enriched) items.add(e.startLine to e)

        var cursor = 0
        while (cursor < codeLines.size) {
            val l = codeLines[cursor]
            if (!covered[l]) {
                var j = cursor
                val group = ArrayList<Int>()
                while (j < codeLines.size && !covered[codeLines[j]]) {
                    group.add(codeLines[j]); j++
                }
                items.add(l to raw(file, lines, group, lang))
                cursor = j
            } else cursor++
        }

        items.sortBy { it.first }
        return items.map { it.second }
    }

    private fun raw(file: RepoFile, lines: List<String>, lineNumbers: List<Int>, lang: String): CodeElement {
        if (lineNumbers.isEmpty()) {
            return CodeElement("${file.name}", "raw", file.path, 1, 1, "", st(lang, "sd_raw"), emptyList())
        }
        val from = lineNumbers.first()
        val to = lineNumbers.last()
        val linesWord = Strings.get(lang, "lines").lowercase()
        return CodeElement(
            name = "${file.name}: $linesWord $from–$to",
            kind = "raw",
            filePath = file.path,
            startLine = from,
            endLine = to,
            signature = lines.getOrNull(from - 1)?.trim()?.take(300) ?: "",
            staticDetail = st(lang, "sd_raw"),
            snippets = lineNumbers.take(MAX_SNIPPET).map { LineRef(it, lines.getOrElse(it - 1) { "" }) }
        )
    }

    private fun snippet(lines: List<String>, from: Int, to: Int, codeSet: Set<Int>): List<LineRef> {
        val out = ArrayList<LineRef>()
        var l = from
        while (l <= to && out.size < MAX_SNIPPET) {
            if (l in codeSet) out.add(LineRef(l, lines.getOrElse(l - 1) { "" }))
            l++
        }
        return out
    }

    private fun blockEnd(lines: List<String>, startIdx: Int, language: String): Int {
        if (startIdx !in lines.indices) return startIdx
        if (language == "Python") {
            val baseIndent = indent(lines[startIdx])
            var i = startIdx + 1
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) { i++; continue }
                if (indent(l) <= baseIndent) return i - 1
                i++
            }
            return lines.size - 1
        }
        var depth = 0; var seen = false; var i = startIdx
        while (i < lines.size) {
            for (c in lines[i]) {
                if (c == '{') { depth++; seen = true }
                else if (c == '}') depth--
            }
            if (seen && depth <= 0) return i
            if (i - startIdx > 400) return startIdx
            i++
        }
        return startIdx
    }

    private fun indent(s: String): Int = s.takeWhile { it == ' ' || it == '\t' }.length

    private fun kindFor(word: String): String = when (word.trim().lowercase()) {
        "interface", "trait", "protocol" -> "interface"
        "enum", "enum class" -> "enum"
        "object", "module" -> "object"
        "struct" -> "class"
        "view" -> "object"
        else -> "class"
    }

    // ------------------------------------------------------------------ regexes

    private val classRe = Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|sealed|data|final|static|inner|value|annotation)\s+)*(class|interface|enum\s+class|object|record)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val funRe = Regex("""^\s*(?:(?:public|private|protected|internal|override|open|abstract|suspend|inline|operator|infix|external|tailrec|expect|actual|static|final|synchronized|native|default)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?,\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val propRe = Regex("""^\s*(?:(?:public|private|protected|internal|const|lateinit|override|open|abstract|final|static)\s+)*(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val importRe = Regex("""^\s*import\s+([A-Za-z0-9_.*]+)""")
    private val lifecycleRe = Regex("""^\s*override\s+fun\s+(on[A-Z][A-Za-z0-9_]*)\s*\(""")
    private val listenerRe = Regex("""\.(setOn[A-Za-z0-9_]*Listener|addTextChangedListener|observe|registerReceiver|bindService|setOnClickListener)\s*\(""")

    private val classReJs = Regex("""^\s*(?:export\s+)?(?:default\s+)?class\s+([A-Za-z0-9_${D}]+)""")
    private val fnReJs = Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z0-9_${D}]+)""")
    private val arrowReJs = Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z0-9_${D}]+)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z0-9_${D}]+)\s*=>""")
    private val eventReJs = Regex("""addEventListener\s*\(\s*['"]([^'"]+)['"]""")
    private val domIdRe = Regex("""getElementById\s*\(\s*['"]([^'"]+)['"]""")
    private val queryRe = Regex("""querySelector(?:All)?\s*\(\s*['"]([^'"]+)['"]""")
    private val methodReJs = Regex("""^\s*(?:async\s+)?([A-Za-z_${D}][A-Za-z0-9_${D}]*)\s*\([^)]*\)\s*\{""")

    private val defRe = Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val classRePy = Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val decoratorRe = Regex("""^\s*@([A-Za-z_][A-Za-z0-9_.]*)""")
    private val routeRe = Regex("""@\w+\.(route|get|post|put|delete|patch)\s*\(\s*['"]([^'"]+)""")

    private val goFunc = Regex("""^\s*func\s+(?:\([^)]*\)\s*)?([A-Za-z_][A-Za-z0-9_]*)""")
    private val goType = Regex("""^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface)""")
    private val rustFn = Regex("""^\s*(?:pub\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val rustType = Regex("""^\s*(?:pub\s+)?(struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val phpClass = Regex("""^\s*(?:abstract\s+|final\s+)?(class|interface|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val phpFn = Regex("""^\s*(?:(?:public|private|protected|static|abstract|final)\s+)*function\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val rbDef = Regex("""^\s*def\s+([A-Za-z_][A-Za-z0-9_?!]*)""")
    private val rbClass = Regex("""^\s*(class|module)\s+([A-Za-z_][A-Za-z0-9_:]*)""")
    private val swiftFn = Regex("""^\s*(?:(?:public|private|internal|open|fileprivate|final|override|static|class)\s+)*func\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val swiftType = Regex("""^\s*(?:(?:public|private|internal|open|final)\s+)*(class|struct|enum|protocol|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val dartClass = Regex("""^\s*(?:abstract\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val dartFn = Regex("""^\s*(?:@\w+\s+)?(?:static\s+)?(?:[A-Za-z_][\w<>?,\[\]\s]*\s+)([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{]*\)\s*(?:async\s*)?\{""")
    private val shFn = Regex("""^\s*(?:function\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)\s*\{""")
    private val sqlRe = Regex("""^\s*[Cc][Rr][Ee][Aa][Tt][Ee]\s+(?:[Oo][Rr]\s+[Rr][Ee][Pp][Ll][Aa][Cc][Ee]\s+)?([A-Za-z]+)\s+([A-Za-z0-9_."]+)""")
    private val cClassRe = Regex("""^\s*(?:typedef\s+)?(class|struct|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val cFuncRe = Regex("""^\s*(?:[\w:<>]+(?:\s*[*&]+)?\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{]*\)\s*\{?\s*$""")

    private val idRe = Regex("""android:id\s*=\s*"@\+?id/([A-Za-z0-9_]+)""")
    private val onClickRe = Regex("""android:onClick\s*=\s*"([A-Za-z0-9_.]+)""")
    private val usesPermission = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)""")
    private val manifestComp = Regex("""<(activity|service|receiver|provider)[^>]*android:name\s*=\s*"([^"]+)""")
    private val stringRes = Regex("""<string\s+name\s*=\s*"([A-Za-z0-9_]+)""")
    private val otherRes = Regex("""<(style|color|dimen|drawable|array|integer|bool|attr|menu|layout)\s+name\s*=\s*"([A-Za-z0-9_.]+)""")

    private val htmlId = Regex("""(?:id|class|name)\s*=\s*["']([A-Za-z0-9_\- ]+)["']""")
    private val onclickAttr = Regex("""on(?:click|change|input|submit|load|mouseover)\s*=\s*["']([^"']+)["']""")
    private val htmlTag = Regex("""<\s*([A-Za-z][A-Za-z0-9]*)""")
}
