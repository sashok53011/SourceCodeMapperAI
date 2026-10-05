package online.devhorizon.sourcecodemapper.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.devhorizon.sourcecodemapper.analysis.Language
import online.devhorizon.sourcecodemapper.model.RepoFile
import java.io.File
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

class RepoAccess(private val context: Context) {

    data class Limits(val maxFiles: Int, val maxFileBytes: Int, val maxTotalBytes: Int)

    private val http = OkHttpClient()

    // ---------------------------------------------------------------- local SAF

    suspend fun openLocalTree(
        uri: Uri,
        limits: Limits,
        log: (String) -> Unit
    ): Pair<String, List<RepoFile>> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot open the selected folder")
        val name = root.name ?: "local-repo"
        val out = ArrayList<RepoFile>()
        var total = 0L
        var hitLimit = false

        fun walk(dir: DocumentFile, prefix: String) {
            if (out.size >= limits.maxFiles || hitLimit) return
            val children = runCatching { dir.listFiles() }.getOrElse { emptyArray() }
            for (child in children) {
                if (out.size >= limits.maxFiles || hitLimit) return
                val childName = child.name ?: continue
                if (child.isDirectory) {
                    if (childName in Language.ignoredDirs) continue
                    walk(child, "$prefix$childName/")
                } else {
                    val rel = "$prefix$childName"
                    if (Language.isDoc(rel)) continue
                    val size = child.length()
                    if (size > limits.maxFileBytes) { log("skip (big): $rel"); continue }
                    if (Language.isBinaryExt(rel)) continue
                    val text = readText(child.uri) ?: continue
                    if (text.indexOf('\u0000') >= 0) continue
                    if (total + text.toByteArray().size > limits.maxTotalBytes) {
                        log("total text limit reached, stopping at $rel")
                        hitLimit = true
                        return
                    }
                    total += text.toByteArray().size
                    out.add(toRepoFile(rel, text))
                }
            }
        }

        walk(root, "")
        log("collected ${out.size} files from $name")
        name to out
    }

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // ---------------------------------------------------------------- GitHub

    suspend fun cloneGithub(
        urlOrSlug: String,
        branch: String,
        token: String,
        useJgit: Boolean,
        limits: Limits,
        log: (String) -> Unit
    ): Pair<String, List<RepoFile>> = withContext(Dispatchers.IO) {
        val (owner, repo) = parseOwnerRepo(urlOrSlug)
        val slug = "$owner-$repo"
        val dest = File(File(context.filesDir, "repos"), "$slug-${System.currentTimeMillis()}")
        dest.mkdirs()
        log("target: ${dest.absolutePath}")

        var ok = false
        if (useJgit) {
            ok = runCatching {
                log("JGit clone https://github.com/$owner/$repo.git …")
                val cmd = Git.cloneRepository()
                    .setURI("https://github.com/$owner/$repo.git")
                    .setDirectory(dest)
                    .setCloneAllBranches(false)
                if (branch.isNotBlank()) cmd.setBranch(branch)
                if (token.isNotBlank()) cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                cmd.call().use { }
                true
            }.getOrElse {
                log("JGit clone failed: ${it.message}; falling back to ZIP")
                false
            }
        }

        if (!ok) {
            dest.deleteRecursively()
            dest.mkdirs()
            downloadZip(owner, repo, branch, token, dest, log)
        }

        val files = collectFromDir(dest, limits, log)
        repo to files
    }

    private fun downloadZip(owner: String, repo: String, branch: String, token: String, dest: File, log: (String) -> Unit) {
        val ref = if (branch.isBlank()) "HEAD" else "refs/heads/$branch"
        val zipUrl = "https://codeload.github.com/$owner/$repo/zip/$ref"
        log("downloading $zipUrl")
        val req = Request.Builder().url(zipUrl)
            .header("User-Agent", "SourceCodeMapperAI/1.0")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ZIP download failed: HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            ZipInputStream(body.byteStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val slash = entryName.indexOf('/')
                    val rel = if (slash >= 0) entryName.substring(slash + 1) else entryName
                    if (rel.isNotBlank() && !entry.isDirectory) {
                        val target = File(dest, rel)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        log("ZIP extracted")
    }

    private fun collectFromDir(root: File, limits: Limits, log: (String) -> Unit): List<RepoFile> {
        val out = ArrayList<RepoFile>()
        var total = 0L
        var hit = false
        fun walk(dir: File, prefix: String) {
            if (out.size >= limits.maxFiles || hit) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (out.size >= limits.maxFiles || hit) return
                if (child.isDirectory) {
                    if (child.name in Language.ignoredDirs) continue
                    walk(child, "$prefix${child.name}/")
                } else {
                    val rel = "$prefix${child.name}"
                    if (Language.isDoc(rel)) continue
                    if (child.length() > limits.maxFileBytes) continue
                    if (Language.isBinaryExt(rel)) continue
                    val text = runCatching { child.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                    if (text.indexOf('\u0000') >= 0) continue
                    if (total + text.toByteArray().size > limits.maxTotalBytes) { hit = true; return }
                    total += text.toByteArray().size
                    out.add(toRepoFile(rel, text))
                }
            }
        }
        walk(root, "")
        log("collected ${out.size} files")
        return out
    }

    private fun toRepoFile(rel: String, text: String): RepoFile {
        val lines = text.count { it == '\n' } + 1
        return RepoFile(
            path = rel,
            name = rel.substringAfterLast('/'),
            language = Language.detect(rel),
            sizeBytes = text.toByteArray().size.toLong(),
            lineCount = lines,
            text = text
        )
    }

    private fun parseOwnerRepo(input: String): Pair<String, String> {
        var s = input.trim()
        s = s.removeSuffix(".git")
        s = s.removePrefix("https://github.com/")
        s = s.removePrefix("http://github.com/")
        s = s.removePrefix("git@github.com:")
        s = s.trim('/')
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) error("Enter a GitHub URL like https://github.com/owner/repo or owner/repo")
        return parts[0] to parts[1]
    }
}
