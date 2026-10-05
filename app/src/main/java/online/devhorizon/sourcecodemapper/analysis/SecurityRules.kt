package online.devhorizon.sourcecodemapper.analysis

import online.devhorizon.sourcecodemapper.model.Finding
import online.devhorizon.sourcecodemapper.model.RepoFile

/**
 * Heuristic security / quality rules. They provide the deterministic baseline for
 * column 4 ("Code assessment"); the AI refines and explains them.
 */
object SecurityRules {

    private const val D = "\$"

    data class Rule(
        val id: String,
        val level: String,
        val regex: Regex,
        val msg: Map<String, String>
    ) {
        fun text(lang: String): String = msg[lang] ?: msg["en"] ?: id
    }

    private val rules: List<Rule> = listOf(
        Rule(
            "hardcoded_secret", "vulnerability",
            Regex("""(?i)(?:api[_-]?key|apikey|secret|password|passwd|pwd|token|bearer|client[_-]?secret)\s*[:=]\s*["'][^"']{6,}["']"""),
            mapOf(
                "en" to "Hardcoded credential or secret in source code",
                "ru" to "Захардкоженный секрет или учётные данные в коде",
                "de" to "Fest codiertes Geheimnis oder Zugangsdaten im Quellcode"
            )
        ),
        Rule(
            "private_key", "vulnerability",
            Regex("""BEGIN [A-Z ]*PRIVATE KEY"""),
            mapOf(
                "en" to "Private key committed to the repository",
                "ru" to "Приватный ключ закоммичен в репозиторий",
                "de" to "Privater Schlüssel im Repository"
            )
        ),
        Rule(
            "aws_key", "vulnerability",
            Regex("""AKIA[0-9A-Z]{16}"""),
            mapOf("en" to "Possible AWS access key id", "ru" to "Возможный AWS access key", "de" to "Möglicher AWS-Access-Key")
        ),
        Rule(
            "google_api_key", "vulnerability",
            Regex("""AIza[0-9A-Za-z\-_]{35}"""),
            mapOf("en" to "Possible Google API key", "ru" to "Возможный Google API-ключ", "de" to "Möglicher Google-API-Schlüssel")
        ),
        Rule(
            "eval", "problem",
            Regex("""\beval\s*\("""),
            mapOf(
                "en" to "Dynamic code evaluation (eval) — code injection risk",
                "ru" to "Динамическое выполнение кода (eval) — риск инъекции",
                "de" to "Dynamische Codeausführung (eval) — Injektionsrisiko"
            )
        ),
        Rule(
            "sql_concat", "problem",
            Regex("""(?i)(select|insert|update|delete)\b[^;]{0,200}["']\s*\+"""),
            mapOf(
                "en" to "SQL built by string concatenation — SQL injection risk",
                "ru" to "SQL склеивается конкатенацией строк — риск SQL-инъекции",
                "de" to "SQL per String-Verkettung — SQL-Injektionsrisiko"
            )
        ),
        Rule(
            "http_url", "warning",
            Regex("""["']http://[^"']+"""),
            mapOf(
                "en" to "Plain HTTP URL — traffic is not encrypted",
                "ru" to "Незашифрованный HTTP-адрес",
                "de" to "Unverschlüsselte HTTP-URL"
            )
        ),
        Rule(
            "trust_all", "vulnerability",
            Regex("""(?i)(trustallcerts|allowallhostnameverifier|setDefaultHostnameVerifier|checkServerTrusted\s*\(\s*\)\s*\{\s*\})"""),
            mapOf(
                "en" to "TLS certificate validation disabled (trust all)",
                "ru" to "Отключена проверка TLS-сертификата (доверять всем)",
                "de" to "TLS-Zertifikatsprüfung deaktiviert (alle vertrauen)"
            )
        ),
        Rule(
            "webview_js", "warning",
            Regex("""setJavaScriptEnabled\s*\(\s*true\s*\)"""),
            mapOf(
                "en" to "JavaScript enabled in WebView",
                "ru" to "В WebView включён JavaScript",
                "de" to "JavaScript im WebView aktiviert"
            )
        ),
        Rule(
            "js_interface", "vulnerability",
            Regex("""addJavascriptInterface"""),
            mapOf(
                "en" to "addJavascriptInterface exposes native code to JS",
                "ru" to "addJavascriptInterface открывает нативный код для JS",
                "de" to "addJavascriptInterface gibt nativen Code an JS frei"
            )
        ),
        Rule(
            "allow_backup", "warning",
            Regex("""android:allowBackup\s*=\s*["]true["]"""),
            mapOf("en" to "allowBackup=true — app data can be extracted via adb", "ru" to "allowBackup=true — данные можно выгрузить через adb", "de" to "allowBackup=true — Daten per adb extrahierbar")
        ),
        Rule(
            "debuggable", "vulnerability",
            Regex("""android:debuggable\s*=\s*["]true["]"""),
            mapOf("en" to "android:debuggable=true — debug build shipped", "ru" to "android:debuggable=true — отладочная сборка в релизе", "de" to "android:debuggable=true — Debug-Build ausgeliefert")
        ),
        Rule(
            "cleartext", "warning",
            Regex("""usesCleartextTraffic\s*=\s*["]true["]"""),
            mapOf("en" to "Cleartext HTTP traffic allowed", "ru" to "Разрешён незашифрованный HTTP-трафик", "de" to "Klartext-HTTP erlaubt")
        ),
        Rule(
            "exported", "warning",
            Regex("""android:exported\s*=\s*["]true["]"""),
            mapOf("en" to "Exported component — reachable by other apps", "ru" to "Экспортированный компонент — доступен другим приложениям", "de" to "Exportierte Komponente — von anderen Apps erreichbar")
        ),
        Rule(
            "weak_crypto", "problem",
            Regex("""(?i)(MessageDigest\.getInstance\s*\(\s*"(md5|sha-?1)"|Cipher\.getInstance\s*\(\s*"(des|rc4|desede))"""),
            mapOf("en" to "Weak cryptographic algorithm (MD5/SHA1/DES/RC4)", "ru" to "Слабый криптоалгоритм (MD5/SHA1/DES/RC4)", "de" to "Schwacher Krypto-Algorithmus (MD5/SHA1/DES/RC4)")
        ),
        Rule(
            "insecure_random", "warning",
            Regex("""(?i)(Math\.random\s*\(|new\s+Random\s*\()"""),
            mapOf("en" to "Non-cryptographic randomness — unsafe for secrets/tokens", "ru" to "Не-криптографическая случайность — небезопасно для токенов", "de" to "Nicht-kryptografische Zufälligkeit — unsicher für Token")
        ),
        Rule(
            "todo", "warning",
            Regex("""\b(TODO|FIXME|HACK|XXX)\b"""),
            mapOf("en" to "Unfinished work marker (TODO/FIXME/HACK)", "ru" to "Метка незавершённой работы (TODO/FIXME/HACK)", "de" to "Marker für unfertige Arbeit (TODO/FIXME/HACK)")
        ),
        Rule(
            "empty_catch", "warning",
            Regex("""catch\s*\([^)]*\)\s*\{\s*\}"""),
            mapOf("en" to "Empty catch block — error is swallowed", "ru" to "Пустой catch — ошибка проглатывается", "de" to "Leerer catch-Block — Fehler wird verschluckt")
        ),
        Rule(
            "command_exec", "vulnerability",
            Regex("""(?i)(Runtime\.getRuntime\(\)\.exec|ProcessBuilder|os\.system|subprocess\.(call|Popen|run)|child_process)"""),
            mapOf("en" to "Operating-system command execution", "ru" to "Выполнение системных команд", "de" to "Ausführung von Systembefehlen")
        ),
        Rule(
            "unsafe_deser", "vulnerability",
            Regex("""(?i)(ObjectInputStream|\.readObject\s*\(|pickle\.loads|yaml\.load\s*\()"""),
            mapOf("en" to "Unsafe deserialization", "ru" to "Небезопасная десериализация", "de" to "Unsichere Deserialisierung")
        ),
        Rule(
            "log_sensitive", "warning",
            Regex("""(?i)Log\.[dviwe]\([^)]*(password|token|secret)"""),
            mapOf("en" to "Sensitive value may be written to logs", "ru" to "Секретное значение может попасть в логи", "de" to "Sensibler Wert könnte ins Log gelangen")
        ),
        Rule(
            "hardcoded_ip", "warning",
            Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""),
            mapOf("en" to "Hardcoded IP address", "ru" to "Захардкоженный IP-адрес", "de" to "Fest codierte IP-Adresse")
        ),
        Rule(
            "inner_html", "warning",
            Regex("""innerHTML\s*=""""),
            mapOf("en" to "innerHTML assignment — XSS risk", "ru" to "Присваивание innerHTML — риск XSS", "de" to "innerHTML-Zuweisung — XSS-Risiko")
        ),
        Rule(
            "good_hash", "best",
            Regex("""MessageDigest\.getInstance\s*\(\s*"(sha-?256|sha-?512)""""),
            mapOf("en" to "Strong hash function (SHA-256/512)", "ru" to "Сильная хеш-функция (SHA-256/512)", "de" to "Starke Hash-Funktion (SHA-256/512)")
        ),
        Rule(
            "good_cipher", "best",
            Regex("""Cipher\.getInstance\s*\(\s*"AES/GCM"""),
            mapOf("en" to "Authenticated encryption (AES/GCM)", "ru" to "Аутентифицированное шифрование (AES/GCM)", "de" to "Authentifizierte Verschlüsselung (AES/GCM)")
        ),
        Rule(
            "good_https", "best",
            Regex("""https://[A-Za-z0-9.-]+"""),
            mapOf("en" to "Uses HTTPS", "ru" to "Используется HTTPS", "de" to "Verwendet HTTPS")
        )
    )

    fun scan(files: List<RepoFile>, lang: String): List<Finding> {
        val out = ArrayList<Finding>()
        for (f in files) {
            val text = f.text ?: continue
            val lines = text.split('\n')
            val infos = Comments.analyze(text, f.language)
            for (i in lines.indices) {
                if (!infos[i].isCode) continue
                val line = lines[i]
                if (line.length > 4000) continue
                for (r in rules) {
                    if (r.regex.containsMatchIn(line)) {
                        out.add(Finding(f.path, i + 1, r.id, r.level, r.text(lang)))
                    }
                }
            }
        }
        return out
    }

    /** Map of "path:line" -> first (most severe) finding, for column 4 baselines. */
    fun index(files: List<RepoFile>, lang: String): Map<String, Finding> {
        val rank = mapOf("vulnerability" to 0, "problem" to 1, "warning" to 2, "best" to 3)
        val best = HashMap<String, Finding>()
        for (f in scan(files, lang)) {
            val key = "${f.filePath}:${f.line}"
            val cur = best[key]
            if (cur == null || (rank[f.level] ?: 9) < (rank[cur.level] ?: 9)) best[key] = f
        }
        return best
    }
}
