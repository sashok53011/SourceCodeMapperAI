# Source Code Mapper AI

[English](README.md) | [Русский](README.ru.md) | [Deutsch](README.de.md)

[![Android CI](https://github.com/sashok53011/SourceCodeMapperAI/actions/workflows/android.yml/badge.svg)](https://github.com/sashok53011/SourceCodeMapperAI/actions/workflows/android.yml)

An Android app that opens a local repository or clones one from GitHub, builds a
"full picture" of the code and produces an interactive **4-column HTML report** with
collapsible blocks.

## What it does

1. **Open a local repository** — system folder picker (SAF, read-only access).
2. **Clone from GitHub** — `owner/repo` or a full URL; `git clone` via JGit by default,
   with an automatic fallback to ZIP download (`codeload.github.com`) if it fails.
3. **Build the report** — a hybrid pipeline:
   - on device: file walk → static analysis (files, classes, functions, methods,
     properties, UI elements, triggers, manifest components, routes) → security heuristics;
   - via AI: column 2 (description) and column 4 (assessment), plus an architecture overview;
   - HTML generation and display in a WebView with export/share.
4. **Express report** — runs the same code through every configured model in turn,
   then compares the results into a **super-report** (model agreement/disagreement,
   per-element opinions, AI reconciliation of conflicts).
5. **AI providers** — add, edit and delete OpenAI-compatible providers, restore defaults.

### Analysis rules

- **README, `*.md`, `*.txt`, licences, changelogs and other documentation are not analysed** —
  only real, executing code is taken into account.
- **Code comments are ignored**: elements are built from meaningful lines only; comments and
  blank lines never appear in the report, while every line of code still finds its place.
- If the AI is unsure what a fragment does, it must mark the row with a confidence level
  (`Verified / Likely / Unverified`) and give a short justification or the reason why it
  could not be confirmed (column 4).

## Report: 4 columns

| Column | Content |
|---|---|
| 1. Name / designation | Every element: files, classes, functions, methods, properties, UI elements, triggers, etc. Name + short explanation. When collapsed, only this summary is visible. |
| 2. Method / technology / type / class | Detailed description (AI + static analysis). |
| 3. Concrete lines | Line numbers and full content. Every line of a file lands either in its own element or in the "Other lines" block — 100% coverage. |
| 4. Code assessment | `Vulnerability / Problem / Warning / OK / Best practice` + explanation. Baseline from static rules, refined by AI. |

Everything is collapsed by default (`<details>`): file group → element row → 4-column grid.
Any text that does not fit within a quarter of the screen width (a ~25% cell) gets a
`▸ / ▾` toggle. There is search, a verdict filter, and "Expand all / Collapse all".

## Interface and report languages

**English is the primary language and the default.** Russian and German are fully supported:
the UI, the HTML report (headings, columns, levels, super-report), static element
descriptions, messages, and provider names. The selected language also drives the language
of the model's answers. The technical analysis journal stays in English (debug information).

## AI providers (Settings)

| Provider | Base URL | Default model | Key |
|---|---|---|---|
| Primary (OpenAI-compatible) | `https://llm.devhorizon.online/v1` | `gemma4-12b-qat-uncensored-hauhaucs-balanced` | none required |
| Ollama Cloud | `https://ollama.com/v1` | user-defined | API key or OAuth device flow |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `space-bunny-free` | `sk-...` |
| OpenCode Zen | `https://opencode.ai/zen/v1` | `big-pickle` | `sk-...` |

The "Test connection" button calls `/models`. For Ollama OAuth the app opens
`ollama.com/connect?...` and polls the API until the key becomes active.

## Build

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.15.6-hotspot"
$env:ANDROID_HOME="C:\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install on an emulator:

```powershell
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## Stack

Kotlin (built into AGP 9.1.1), Jetpack Compose (BOM 2024.09.00, Material3),
coroutines, kotlinx-serialization-json, OkHttp, JGit, DocumentFile,
Gradle 9.3.1, compileSdk 36 / minSdk 26.

## Source layout

```
app/src/main/java/online/devhorizon/sourcecodemapper/
  MainActivity.kt, MainViewModel.kt
  model/Models.kt
  i18n/Strings.kt                 # EN / RU / DE
  data/Settings.kt                # providers, settings, cache
  data/RepoAccess.kt              # SAF + JGit + ZIP fallback
  analysis/Language.kt
  analysis/Comments.kt            # comment/blank-line filter
  analysis/StaticIndexer.kt       # element extraction + line coverage
  analysis/SecurityRules.kt       # column 4 rules
  analysis/Enricher.kt            # AI for columns 2 and 4
  analysis/ExpressMerger.kt       # multi-model super-report
  ai/AiClient.kt                  # 4 providers + Ollama OAuth
  report/HtmlReport.kt            # HTML: 4 columns, collapsing, search
  ui/                             # Compose screens
```

## Limitations

- Default limits: 2000 files, 320 KB per file, 25 MB of text (configurable).
- AI speed depends on the selected model; results are cached by file hash.
- Private repositories require a GitHub token.

## CI

GitHub Actions builds the debug APK on every push to `main` and on pull requests
(`.github/workflows/android.yml`). Pushing a `v*` tag attaches the APK to a GitHub Release.
