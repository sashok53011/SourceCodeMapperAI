package online.devhorizon.sourcecodemapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import online.devhorizon.sourcecodemapper.MainViewModel
import online.devhorizon.sourcecodemapper.UiState

@Composable
fun RepoScreen(state: UiState, vm: MainViewModel) {
    val lang = state.settings.language

    if (state.files.isEmpty()) {
        Column(Modifier.padding(16.dp)) {
            Text(txt(lang, "no_report"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel {
                SectionTitle(state.repoName)
                Text(state.repoSource, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatBox(txt(lang, "files"), state.stats.totalFiles.toString())
                    StatBox(txt(lang, "lines"), state.stats.totalLines.toString())
                    StatBox(txt(lang, "size"), human(state.stats.totalBytes))
                }
            }
        }

        if (state.busy || state.progress.running) {
            item {
                Panel {
                    SectionTitle(txt(lang, "progress_title") + ": " + txt(lang, state.progress.phase.ifBlank { "phase_idle" }))
                    Text("${state.progress.done} / ${state.progress.total}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = {
                            if (state.progress.total > 0) state.progress.done.toFloat() / state.progress.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { vm.cancelAnalysis() }) { Text(txt(lang, "cancel")) }
                }
            }
        }

        item {
            Panel {
                Button(
                    onClick = { vm.startAnalysis() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.busy) txt(lang, "building") else txt(lang, "build_report")) }
                OutlinedButton(
                    onClick = { vm.startExpressAnalysis() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(txt(lang, "express_report")) }
                Text(txt(lang, "express_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Panel {
                SectionTitle(txt(lang, "languages"))
                state.stats.languages.take(15).forEach { ls ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ls.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${ls.files} · ${ls.lines}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.stats.entryPoints.isNotEmpty()) {
            item {
                Panel {
                    SectionTitle(txt(lang, "entry_points"))
                    state.stats.entryPoints.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (state.findings.isNotEmpty()) {
            item {
                Panel {
                    SectionTitle("${txt(lang, "findings")} (${state.findings.size})")
                    val top = state.findings.sortedWith(compareBy { rank(it.level) }).take(40)
                    top.forEach { f ->
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LevelBadge(f.level, lang)
                                Text("${f.filePath}:${f.line}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                            Text(f.message, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }

        item {
            Panel {
                SectionTitle("${txt(lang, "structure")} (${state.files.size})")
            }
        }
        items(state.files.take(400)) { f ->
            Text(
                "${f.path}   · ${f.language} · ${f.lineCount}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LevelBadge(level: String, lang: String) {
    val color = when (level) {
        "vulnerability" -> MaterialTheme.colorScheme.error
        "problem" -> androidx.compose.ui.graphics.Color(0xFFF76808)
        "warning" -> androidx.compose.ui.graphics.Color(0xFFE5A400)
        "best" -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
        else -> MaterialTheme.colorScheme.secondary
    }
    Text(
        txt(lang, "level_$level"),
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

private fun rank(level: String) = when (level) {
    "vulnerability" -> 0; "problem" -> 1; "warning" -> 2; "best" -> 3; else -> 4
}

private fun human(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
