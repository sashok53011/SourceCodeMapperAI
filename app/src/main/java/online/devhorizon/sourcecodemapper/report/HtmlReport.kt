package online.devhorizon.sourcecodemapper.report

import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.FileReport
import online.devhorizon.sourcecodemapper.model.LineRef
import online.devhorizon.sourcecodemapper.model.ReportBundle
import online.devhorizon.sourcecodemapper.model.ReportRow

/**
 * Builds a self-contained, interactive HTML report:
 *  - 4 columns, each ~25% of the screen width;
 *  - everything is collapsed by default, a click expands it;
 *  - any text that does not fit into a quarter of the screen width is collapsed
 *    behind a small toggle (measured at runtime in JS);
 *  - search, verdict filter, expand/collapse all.
 */
object HtmlReport {

    private fun t(lang: String, key: String) = Strings.get(lang, key)

    private val levelOrder = listOf("vulnerability", "problem", "warning", "ok", "best")

    fun build(b: ReportBundle): String {
        val lang = b.language
        val sb = StringBuilder(1 shl 20)

        sb.append("<!doctype html>\n<html lang=\"").append(lang).append("\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(b.repoName)).append(" — Source Code Mapper AI</title>\n")
        sb.append("<style>\n").append(CSS).append("\n</style>\n</head>\n<body>\n")

        // header
        sb.append("<header>\n")
        sb.append("<h1>").append(esc(b.repoName)).append("</h1>\n")
        sb.append("<div class=\"meta\">")
        sb.append(t(lang, "generated")).append(": ").append(esc(b.generatedAt))
        sb.append(" · ").append(esc(b.source))
        sb.append(" · ").append(t(lang, "files")).append(": ").append(b.totalFiles)
        sb.append(" · ").append(t(lang, "lines")).append(": ").append(b.totalLines)
        sb.append(" · ").append(t(lang, "size")).append(": ").append(humanBytes(b.totalBytes))
        sb.append("</div>\n")
        sb.append("<div class=\"controls\">")
        sb.append("<input id=\"q\" placeholder=\"").append(esc(t(lang, "search"))).append("\" oninput=\"applyFilter()\">")
        sb.append("<select id=\"lvl\" onchange=\"applyFilter()\"><option value=\"all\">").append(esc(t(lang, "all")))
        sb.append("</option>")
        for (l in levelOrder) {
            sb.append("<option value=\"").append(l).append("\">").append(esc(t(lang, "level_$l"))).append("</option>")
        }
        sb.append("</select>")
        sb.append("<button onclick=\"expandAll()\">").append(esc(t(lang, "expand_all"))).append("</button>")
        sb.append("<button onclick=\"collapseAll()\">").append(esc(t(lang, "collapse_all"))).append("</button>")
        sb.append("</div>\n")
        sb.append("<div class=\"legend\">")
        for (l in levelOrder) {
            sb.append("<span class=\"badge level-").append(l).append("\">").append(esc(t(lang, "level_$l"))).append("</span>")
        }
        sb.append("</div>\n</header>\n")

        sb.append("<div class=\"cols-head\">")
        sb.append("<div class=\"colhead c1\">").append(esc(t(lang, "col1"))).append("</div>")
        sb.append("<div class=\"colhead c2\">").append(esc(t(lang, "col2"))).append("</div>")
        sb.append("<div class=\"colhead c3\">").append(esc(t(lang, "col3"))).append("</div>")
        sb.append("<div class=\"colhead c4\">").append(esc(t(lang, "col4"))).append("</div>")
        sb.append("</div>\n")

        sb.append("<main>\n")

        // overview
        sb.append("<details class=\"group sec\"><summary><span class=\"gtitle\">")
            .append(esc(t(lang, "overview"))).append("</span></summary><div class=\"pad\">")
            .append(markdown(b.overview.ifBlank { t(lang, "no_overview") })).append("</div></details>\n")

        if (b.superReport.isNotBlank()) {
            sb.append("<details class=\"group sec\"><summary><span class=\"gtitle\">")
                .append(esc(t(lang, "super_report"))).append("</span>")
            if (b.providers.isNotEmpty()) sb.append("<span class=\"fmeta\">").append(esc(b.providers.joinToString(", "))).append("</span>")
            sb.append("</summary><div class=\"pad\">").append(markdown(b.superReport)).append("</div></details>\n")
        }

        sb.append("<details class=\"group sec\"><summary><span class=\"gtitle\">")
            .append(esc(t(lang, "languages"))).append("</span><span class=\"fmeta\">").append(b.languages.size)
            .append("</span></summary><div class=\"pad\"><table class=\"languages\">")
        for (ls in b.languages.sortedByDescending { it.lines }) {
            sb.append("<tr><td>").append(esc(ls.name)).append("</td><td>").append(ls.files).append(" ").append(esc(t(lang, "files")))
                .append("</td><td>").append(ls.lines).append(" ").append(esc(t(lang, "lines"))).append("</td></tr>")
        }
        sb.append("</table>")
        if (b.entryPoints.isNotEmpty()) {
            sb.append("<h3>").append(esc(t(lang, "entry_points"))).append("</h3><ul>")
            for (ep in b.entryPoints) sb.append("<li><code>").append(esc(ep)).append("</code></li>")
            sb.append("</ul>")
        }
        sb.append("</div></details>\n")

        // findings
        sb.append("<details class=\"group sec\"><summary><span class=\"gtitle\">")
            .append(esc(t(lang, "findings"))).append("</span><span class=\"fmeta\">").append(b.findings.size)
            .append("</span></summary><div class=\"pad\"><table class=\"findings\">")
        for (f in b.findings.sortedBy { levelOrder.indexOf(it.level) }) {
            sb.append("<tr class=\"lvl-").append(f.level).append("\"><td><span class=\"badge level-").append(f.level).append("\">")
                .append(esc(t(lang, "level_${f.level}"))).append("</span></td><td><code>")
                .append(esc(f.filePath)).append(":").append(f.line).append("</code></td><td>")
                .append(esc(f.message)).append("</td></tr>")
        }
        sb.append("</table></div></details>\n")

        // files
        for (fr in b.files) {
            appendFile(sb, fr, lang)
        }

        sb.append("</main>\n<script>\n").append(JS).append("\n</script>\n</body>\n</html>\n")
        return sb.toString()
    }

    private fun appendFile(sb: StringBuilder, fr: FileReport, lang: String) {
        val worst = fr.rows.filter { it.kind != "file" }
            .minByOrNull { levelOrder.indexOf(it.level) }?.level ?: "ok"
        sb.append("<details class=\"group filegroup\" data-level=\"").append(worst).append("\">\n")
        sb.append("<summary><span class=\"fname\">").append(esc(fr.filePath)).append("</span>")
        sb.append("<span class=\"fmeta\">").append(esc(fr.language)).append(" · ")
            .append(fr.rows.count { it.kind != "file" }).append(" ").append(esc(t(lang, "rows"))).append("</span>")
        sb.append("<span class=\"badge level-").append(worst).append("\">").append(esc(t(lang, "level_$worst"))).append("</span>")
        sb.append("</summary>\n<div class=\"rows\">\n")
        for (row in fr.rows) appendRow(sb, row, lang)
        sb.append("</div></details>\n")
    }

    private fun appendRow(sb: StringBuilder, row: ReportRow, lang: String) {
        sb.append("<details class=\"row\" data-level=\"").append(row.level).append("\">\n")
        sb.append("<summary>")
        sb.append("<span class=\"rname\">").append(esc(row.name)).append("</span>")
        sb.append("<span class=\"rkind\">").append(esc(t(lang, "kind_${row.kind}").ifEmpty { row.kind })).append("</span>")
        sb.append("<span class=\"rsum\">").append(esc(row.summary)).append("</span>")
        sb.append("<span class=\"conf conf-").append(row.confidence).append("\">")
            .append(esc(t(lang, "conf_${row.confidence}"))).append("</span>")
        sb.append("<span class=\"badge level-").append(row.level).append("\">").append(esc(t(lang, "level_${row.level}"))).append("</span>")
        sb.append("</summary>\n")

        sb.append("<div class=\"grid\">\n")
        // col 1
        sb.append("<div class=\"cell c1\"><div class=\"chead\">").append(esc(t(lang, "col1"))).append("</div>")
        sb.append("<div class=\"body\">").append(esc(row.name)).append("<div class=\"muted\">").append(esc(row.summary)).append("</div></div></div>")
        // col 2
        sb.append("<div class=\"cell c2\"><div class=\"chead\">").append(esc(t(lang, "col2"))).append("</div>")
        sb.append("<div class=\"body\">").append(esc(row.detail)).append("</div></div>")
        // col 3
        sb.append("<div class=\"cell c3\"><div class=\"chead\">").append(esc(t(lang, "col3"))).append("</div>")
        sb.append("<div class=\"body\">")
        if (row.lines.isEmpty()) {
            sb.append("<span class=\"muted\">—</span>")
        } else {
            sb.append("<pre class=\"code\">")
            for (l in row.lines) {
                sb.append("<span class=\"ln\">").append(l.n).append("</span> ").append(esc(l.text)).append("\n")
            }
            sb.append("</pre>")
        }
        sb.append("</div></div>")
        // col 4
        sb.append("<div class=\"cell c4\"><div class=\"chead\">").append(esc(t(lang, "col4"))).append("</div>")
        sb.append("<div class=\"body\"><span class=\"badge level-").append(row.level).append("\">")
            .append(esc(t(lang, "level_${row.level}"))).append("</span> ")
            .append("<span class=\"conf conf-").append(row.confidence).append("\">")
            .append(esc(t(lang, "conf_${row.confidence}"))).append("</span>")
        if (row.note.isNotBlank()) sb.append("<div class=\"note\">").append(esc(row.note)).append("</div>")
        if (row.evidence.isNotEmpty()) {
            sb.append("<div class=\"evbox\"><b>").append(esc(t(lang, "evidence"))).append(":</b><ul>")
            for (ev in row.evidence) sb.append("<li>").append(esc(ev)).append("</li>")
            sb.append("</ul></div>")
        }
        if (row.variants.size > 1) {
            sb.append("<details class=\"variants\"><summary>")
                .append(esc(t(lang, "models_opinions"))).append(" (").append(row.variants.size)
                .append(")</summary>")
            for (v in row.variants) {
                sb.append("<div class=\"variant\"><div><b>").append(esc(v.provider)).append("</b> ")
                    .append("<span class=\"badge level-").append(v.level).append("\">").append(esc(t(lang, "level_${v.level}"))).append("</span> ")
                    .append("<span class=\"conf conf-").append(v.confidence).append("\">").append(esc(t(lang, "conf_${v.confidence}"))).append("</span></div>")
                if (v.detail.isNotBlank()) sb.append("<div class=\"muted\">").append(esc(v.detail)).append("</div>")
                if (v.note.isNotBlank()) sb.append("<div class=\"note\">").append(esc(v.note)).append("</div>")
                sb.append("</div>")
            }
            sb.append("</details>")
        }
        sb.append("</div></div>")
        sb.append("</div></details>\n")
    }

    // ------------------------------------------------------------------ helpers

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun humanBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }

    private fun markdown(md: String): String {
        val out = StringBuilder()
        var inCode = false
        var inList = false
        for (raw in md.split('\n')) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) { out.append("</code></pre>"); inCode = false } else { out.append("<pre><code>"); inCode = true }
                continue
            }
            if (inCode) { out.append(esc(line)).append("\n"); continue }
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> { closeList(out, inList); inList = false; out.append("<h4>").append(inline(trimmed.removePrefix("### "))).append("</h4>") }
                trimmed.startsWith("## ") -> { closeList(out, inList); inList = false; out.append("<h3>").append(inline(trimmed.removePrefix("## "))).append("</h3>") }
                trimmed.startsWith("# ") -> { closeList(out, inList); inList = false; out.append("<h2>").append(inline(trimmed.removePrefix("# "))).append("</h2>") }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (!inList) { out.append("<ul>"); inList = true }
                    out.append("<li>").append(inline(trimmed.substring(2))).append("</li>")
                }
                trimmed.isEmpty() -> { closeList(out, inList); inList = false }
                else -> { closeList(out, inList); inList = false; out.append("<p>").append(inline(trimmed)).append("</p>") }
            }
        }
        if (inList) out.append("</ul>")
        if (inCode) out.append("</code></pre>")
        return out.toString()
    }

    private fun closeList(out: StringBuilder, inList: Boolean) { if (inList) out.append("</ul>") }

    private fun inline(s: String): String {
        var r = esc(s)
        r = Regex("""\*\*(.+?)\*\*""").replace(r, "<b>\$1</b>")
        r = Regex("""`([^`]+)`""").replace(r, "<code>\$1</code>")
        return r
    }

    // ------------------------------------------------------------------ assets

    private val CSS = """
:root{--bg:#0f1117;--panel:#171a23;--panel2:#1e2230;--fg:#e6e8ef;--muted:#9aa3b2;--line:#2a3040;
--vuln:#e5484d;--prob:#f76808;--warn:#e5a400;--ok:#46c46b;--best:#3b82f6;}
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg);font-family:-apple-system,Roboto,Segoe UI,Helvetica,Arial,sans-serif;font-size:15px;line-height:1.45}
header{padding:14px 12px;background:linear-gradient(180deg,#1b1f2b,#12141c);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:20}
h1{font-size:18px;margin:0 0 4px}
.meta{color:var(--muted);font-size:12px}
.controls{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
input,select,button{background:var(--panel2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:7px 10px;font-size:13px}
button{cursor:pointer}
button:hover{background:#272c3c}
.legend{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
.badge{display:inline-block;font-size:11px;font-weight:700;padding:2px 7px;border-radius:999px;white-space:nowrap}
.level-vulnerability{background:rgba(229,72,77,.18);color:var(--vuln);border:1px solid rgba(229,72,77,.5)}
.level-problem{background:rgba(247,104,8,.18);color:var(--prob);border:1px solid rgba(247,104,8,.5)}
.level-warning{background:rgba(229,164,0,.18);color:var(--warn);border:1px solid rgba(229,164,0,.5)}
.level-ok{background:rgba(70,196,107,.16);color:var(--ok);border:1px solid rgba(70,196,107,.45)}
.level-best{background:rgba(59,130,246,.18);color:var(--best);border:1px solid rgba(59,130,246,.5)}
.cols-head{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--line);position:sticky;top:112px;z-index:15;margin-top:6px}
.colhead{background:var(--panel);padding:8px;font-size:12px;font-weight:700;color:var(--muted)}
main{padding:8px 8px 60px}
details.group{margin:8px 0;border:1px solid var(--line);border-radius:12px;background:var(--panel);overflow:hidden}
details.group>summary{cursor:pointer;padding:10px 12px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;list-style:none;background:var(--panel2)}
details.group>summary::-webkit-details-marker{display:none}
details.group[open]>summary{border-bottom:1px solid var(--line)}
.gtitle{font-weight:700}
.fname{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;word-break:break-all}
.fmeta{color:var(--muted);font-size:12px}
.pad{padding:10px 12px}
.pad pre{background:#0c0e14;border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto}
.languages td,.findings td{padding:4px 8px;border-bottom:1px solid var(--line);font-size:13px;vertical-align:top}
details.row{border:1px solid var(--line);border-radius:10px;margin:8px;background:var(--panel)}
details.row>summary{cursor:pointer;padding:8px 10px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;list-style:none}
details.row>summary::-webkit-details-marker{display:none}
.rname{font-family:ui-monospace,Menlo,Consolas,monospace;font-weight:700;word-break:break-all}
.rkind{font-size:11px;color:var(--muted);border:1px solid var(--line);border-radius:6px;padding:1px 6px}
.rsum{color:var(--muted);font-size:12px;flex:1 1 40%;min-width:0}
.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));border-top:1px solid var(--line)}
.cell{border-right:1px solid var(--line);padding:6px;min-width:0;overflow:hidden}
.cell:last-child{border-right:none}
.chead{font-size:10px;font-weight:700;color:var(--muted);margin-bottom:4px;text-transform:uppercase;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.body{font-size:12px;overflow:hidden;word-break:break-word;max-height:9em}
.body.open{max-height:none}
.rname,.rsum,.fname{overflow:hidden;max-height:1.6em}
.rname.open,.rsum.open,.fname.open{max-height:none}
.note{overflow:hidden;max-height:4.5em}
.note.open{max-height:none}
.fmeta,.gtitle{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.muted{color:var(--muted);font-size:12px}
.code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;white-space:pre-wrap;word-break:break-word;margin:0;color:#cdd3e0}
.code .ln{color:#5b657a;user-select:none;display:inline-block;min-width:2.2em;text-align:right;margin-right:6px}
.note{margin-top:4px;font-size:12px}
.more{margin-top:4px;font-size:11px;padding:1px 7px;border-radius:6px;background:#272c3c;color:var(--fg);display:inline-block}
.conf{display:inline-block;font-size:10px;font-weight:700;padding:1px 6px;border-radius:6px;border:1px solid var(--line);color:var(--muted);white-space:nowrap}
.conf-verified{color:#46c46b;border-color:rgba(70,196,107,.5)}
.conf-likely{color:#e5a400;border-color:rgba(229,164,0,.5)}
.conf-unverified{color:#e5484d;border-color:rgba(229,72,77,.5)}
.conf-conflicting{color:#a855f7;border-color:rgba(168,85,247,.5)}
.evbox{margin-top:6px;font-size:11px;color:var(--muted)}
.evbox ul{margin:4px 0 0 14px;padding:0}
.variants{margin-top:6px;border:1px solid var(--line);border-radius:8px;padding:6px}
.variants>summary{cursor:pointer;font-size:11px;font-weight:700;color:var(--muted)}
.variant{padding:5px 0;border-bottom:1px solid var(--line)}
.variant:last-child{border-bottom:none}
"""

    private val JS = """
function clampEl(el){
  if(!el || el.dataset.clamped==='1') return;
  var over = el.scrollHeight > el.clientHeight + 2 || el.scrollWidth > el.clientWidth + 2;
  if(!over) return;
  el.dataset.clamped='1';
  var btn=document.createElement('button');
  btn.className='more';
  btn.type='button';
  btn.textContent='▸';
  btn.setAttribute('aria-label','toggle');
  btn.onclick=function(ev){ev.preventDefault();ev.stopPropagation();el.classList.toggle('open');btn.textContent=el.classList.contains('open')?'▾':'▸';};
  el.parentNode.insertBefore(btn,el.nextSibling);
}
function clampInside(root){
  root.querySelectorAll('.cell .body,.rname,.rsum,.fname,.note').forEach(clampEl);
}
document.addEventListener('DOMContentLoaded',function(){
  clampInside(document);
  document.querySelectorAll('details.group').forEach(function(d){
    d.addEventListener('toggle',function(){ if(d.open) clampInside(d); });
  });
  document.querySelectorAll('details.row').forEach(function(d){
    d.addEventListener('toggle',function(){ if(d.open){ clampInside(d); d.querySelectorAll('.cell .body').forEach(clampEl); } });
  });
});
function applyFilter(){
  var q=(document.getElementById('q').value||'').toLowerCase();
  var lvl=document.getElementById('lvl').value;
  document.querySelectorAll('details.row').forEach(function(r){
    var txt=r.querySelector('summary').textContent.toLowerCase();
    var ok=(!q||txt.indexOf(q)>=0)&&(lvl==='all'||r.getAttribute('data-level')===lvl);
    r.style.display=ok?'':'none';
  });
  document.querySelectorAll('details.filegroup').forEach(function(g){
    var vis=Array.prototype.filter.call(g.querySelectorAll('details.row'),function(r){return r.style.display!=='none';}).length;
    g.style.display=vis>0?'':'none';
  });
}
function expandAll(){
  document.querySelectorAll('details').forEach(function(d){
    d.open=true;
    d.querySelectorAll('.cell .body,.note').forEach(function(el){clampEl(el);el.classList.add('open');});
  });
}
function collapseAll(){
  document.querySelectorAll('details').forEach(function(d){d.open=false;});
  document.querySelectorAll('.cell .body,.note').forEach(function(el){el.classList.remove('open');});
}
"""
}
