package online.devhorizon.sourcecodemapper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import online.devhorizon.sourcecodemapper.MainViewModel
import online.devhorizon.sourcecodemapper.UiState
import online.devhorizon.sourcecodemapper.i18n.Strings
import online.devhorizon.sourcecodemapper.model.ProviderConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val lang = state.settings.language
    val settings = state.settings
    val active = settings.providers.firstOrNull { it.id == settings.activeProviderId }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel {
                SectionTitle(txt(lang, "settings_title"))
                Text(txt(lang, "lang_label"), style = MaterialTheme.typography.labelMedium)
                LangChips(lang) { vm.setLanguage(it) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item {
            Panel {
                SectionTitle(txt(lang, "providers"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.providers.forEach { p ->
                        FilterChip(
                            selected = p.id == settings.activeProviderId,
                            onClick = { vm.setActiveProvider(p.id) },
                            label = { Text(Strings.providerTitle(lang, p.id, p.title).substringBefore(" (")) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.addProvider() }) { Text(txt(lang, "add_provider")) }
                    OutlinedButton(onClick = { vm.restoreDefaultProviders() }) { Text(txt(lang, "restore_defaults")) }
                }
            }
        }

        if (active != null) {
            item {
                Panel {
                    SectionTitle(Strings.providerTitle(lang, active.id, active.title))
                    LabeledField(txt(lang, "base_url"), active.baseUrl, { vm.updateProvider(active.copy(baseUrl = it)) })
                    LabeledField(txt(lang, "protocol"), active.protocol, { vm.updateProvider(active.copy(protocol = it)) }, placeholder = "openai | ollama")
                    LabeledField(txt(lang, "model"), active.model, { vm.updateProvider(active.copy(model = it)) })
                    LabeledField(txt(lang, "api_key"), active.apiKey, { vm.updateProvider(active.copy(apiKey = it)) })

                    if (active.freeModels.isNotEmpty()) {
                        Text(txt(lang, "free_models"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            active.freeModels.forEach { m ->
                                FilterChip(
                                    selected = active.model == m,
                                    onClick = { vm.updateProvider(active.copy(model = m)) },
                                    label = { Text(m, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.testProvider(active) }) { Text(txt(lang, "test")) }
                        OutlinedButton(onClick = { vm.deleteProvider(active.id) }) { Text(txt(lang, "delete_provider")) }
                    }
                    state.testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (active.id == "ollama") {
                        Text(txt(lang, "ollama_oauth"), style = MaterialTheme.typography.labelMedium)
                        Text(txt(lang, "oauth_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            enabled = !state.oauthBusy,
                            onClick = {
                                vm.startOllamaOAuth { url ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            }
                        ) { Text(txt(lang, "oauth_open")) }
                    }
                }
            }
        }

        item {
            Panel {
                SectionTitle(txt(lang, "ai_enabled"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.enableAi,
                        onCheckedChange = { vm.updateSettings(settings.copy(enableAi = it)) }
                    )
                    Text(txt(lang, "ai_enabled"), modifier = Modifier.padding(start = 8.dp))
                }
                NumberField(txt(lang, "max_files"), settings.maxFiles) { vm.updateSettings(settings.copy(maxFiles = it)) }
                NumberField(txt(lang, "max_file_kb"), settings.maxFileKb) { vm.updateSettings(settings.copy(maxFileKb = it)) }
                NumberField(txt(lang, "max_total_mb"), settings.maxTotalMb) { vm.updateSettings(settings.copy(maxTotalMb = it)) }
                NumberField(txt(lang, "concurrency"), settings.aiConcurrency) { vm.updateSettings(settings.copy(aiConcurrency = it)) }
                NumberField(txt(lang, "timeout"), settings.requestTimeoutSec) { vm.updateSettings(settings.copy(requestTimeoutSec = it)) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    LabeledField(
        label = label,
        value = value.toString(),
        onChange = { s -> s.toIntOrNull()?.let(onChange) }
    )
}
