package online.devhorizon.sourcecodemapper.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import online.devhorizon.sourcecodemapper.MainViewModel
import online.devhorizon.sourcecodemapper.UiState

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val lang = state.settings.language

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.openLocalRepo(uri)
        }
    }

    var url by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var useJgit by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(txt(lang, "home_title"), style = MaterialTheme.typography.headlineSmall)
                Text(txt(lang, "home_subtitle"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(txt(lang, "lang_label"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LangChips(lang) { vm.setLanguage(it) }
            }
        }

        item {
            Panel {
                SectionTitle(txt(lang, "open_local"))
                Text(txt(lang, "open_local_desc"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { picker.launch(null) }) { Text(txt(lang, "open_local")) }
            }
        }

        item {
            Panel {
                SectionTitle(txt(lang, "clone_github"))
                LabeledField(txt(lang, "repo_url"), url, { url = it }, placeholder = "https://github.com/owner/repo")
                LabeledField(txt(lang, "branch"), branch, { branch = it }, placeholder = "main")
                LabeledField(txt(lang, "github_token"), token, { token = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useJgit, onCheckedChange = { useJgit = it })
                    Text(txt(lang, "use_jgit"), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.cloneGithub(url, branch, token, useJgit) }) { Text(txt(lang, "clone")) }
            }
        }

        if (state.recent.isNotEmpty()) {
            item { SectionTitle(txt(lang, "recent")) }
            items(state.recent) { r ->
                Panel(Modifier.clickable { url = r }) {
                    Text(r, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            item {
                Text(txt(lang, "no_recent"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
