# Source Code Mapper AI

[English](README.md) | [Русский](README.ru.md) | [Deutsch](README.de.md)

[![Android CI](https://github.com/sashok53011/SourceCodeMapperAI/actions/workflows/android.yml/badge.svg)](https://github.com/sashok53011/SourceCodeMapperAI/actions/workflows/android.yml)

Eine Android-App, die ein lokales Repository öffnet oder eines von GitHub klont, ein
„Gesamtbild" des Codes erstellt und einen interaktiven **4-Spalten-HTML-Bericht** mit
einklappbaren Blöcken erzeugt.

## Was sie macht

1. **Lokales Repository öffnen** — System-Ordnerauswahl (SAF, nur Lesen).
2. **Von GitHub klonen** — `owner/repo` oder vollständige URL; standardmäßig `git clone`
   über JGit, bei Fehler automatischer Rückfall auf ZIP-Download (`codeload.github.com`).
3. **Bericht erstellen** — hybride Pipeline:
   - auf dem Gerät: Dateidurchlauf → statische Analyse (Dateien, Klassen, Funktionen,
     Methoden, Eigenschaften, UI-Elemente, Trigger, Manifest-Komponenten, Routen) →
     Sicherheitsheuristiken;
   - per KI: Spalte 2 (Beschreibung) und Spalte 4 (Bewertung), plus Architekturüberblick;
   - HTML-Erzeugung und Anzeige im WebView mit Export/Teilen.
4. **Express-Bericht** — lässt denselben Code nacheinander durch alle konfigurierten Modelle
   laufen und vergleicht die Ergebnisse in einem **Super-Bericht** (Übereinstimmung/Abweichung
   der Modelle, Meinungen je Element, KI-Ausgleich von Konflikten).
5. **KI-Anbieter** — OpenAI-kompatible Anbieter hinzufügen, bearbeiten und löschen,
   Standard wiederherstellen.

### Analyseregeln

- **README, `*.md`, `*.txt`, Lizenzen, Changelogs und sonstige Dokumentation werden nicht
  analysiert** — es zählt nur echter, ausführbarer Code.
- **Code-Kommentare werden ignoriert**: Elemente entstehen nur aus aussagekräftigen Zeilen;
  Kommentare und Leerzeilen erscheinen nie im Bericht, dennoch findet jede Codezeile ihren Platz.
- Ist die KI bei einem Abschnitt unsicher, muss sie die Zeile mit einem Sicherheitsgrad
  (`Bestätigt / Wahrscheinlich / Unbestätigt`) markieren und eine kurze Begründung oder den
  Grund angeben, warum keine Bestätigung möglich war (Spalte 4).

## Bericht: 4 Spalten

| Spalte | Inhalt |
|---|---|
| 1. Name / Bezeichnung | Alle Elemente: Dateien, Klassen, Funktionen, Methoden, Eigenschaften, UI-Elemente, Trigger usw. Name + kurze Erklärung. Im eingeklappten Zustand ist nur diese Zusammenfassung sichtbar. |
| 2. Methode / Technologie / Typ / Klasse | Ausführliche Beschreibung (KI + Statik). |
| 3. Konkrete Zeilen | Zeilennummern und vollständiger Inhalt. Jede Dateizeile landet entweder in ihrem Element oder im Block „Sonstige Zeilen" — 100 % Abdeckung. |
| 4. Code-Bewertung | `Schwachstelle / Problem / Warnung / OK / Best Practice` + Erklärung. Basis sind statische Regeln, verfeinert durch die KI. |

Alles ist standardmäßig eingeklappt (`<details>`): Dateigruppe → Elementzeile → 4-Spalten-Raster.
Jeder Text, der nicht in ein Viertel der Bildschirmbreite passt (Zelle ~25 %), erhält einen
`▸ / ▾`-Schalter. Es gibt Suche, Bewertungsfilter und „Alles ausklappen / Alles einklappen".

## Sprachen der Oberfläche und des Berichts

**Englisch ist die Hauptsprache und die Standardeinstellung.** Russisch und Deutsch werden
vollständig unterstützt: Oberfläche, HTML-Bericht (Überschriften, Spalten, Stufen, Super-Bericht),
statische Elementbeschreibungen, Meldungen und Anbieternamen. Die gewählte Sprache bestimmt auch
die Sprache der Modellantworten. Das technische Analyseprotokoll bleibt auf Englisch
(Debug-Informationen).

## KI-Anbieter (Einstellungen)

| Anbieter | Basis-URL | Standardmodell | Schlüssel |
|---|---|---|---|
| Primär (OpenAI-kompatibel) | `https://llm.devhorizon.online/v1` | `gemma4-12b-qat-uncensored-hauhaucs-balanced` | nicht erforderlich |
| Ollama Cloud | `https://ollama.com/v1` | benutzerdefiniert | API-Schlüssel oder OAuth Device Flow |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `space-bunny-free` | `sk-...` |
| OpenCode Zen | `https://opencode.ai/zen/v1` | `big-pickle` | `sk-...` |

Die Schaltfläche „Verbindung testen" ruft `/models` auf. Bei Ollama OAuth öffnet die App
`ollama.com/connect?...` und fragt die API ab, bis der Schlüssel aktiv ist.

## Build

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.15.6-hotspot"
$env:ANDROID_HOME="C:\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Installation auf einem Emulator:

```powershell
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## Technologie-Stack

Kotlin (in AGP 9.1.1 integriert), Jetpack Compose (BOM 2024.09.00, Material3),
Coroutines, kotlinx-serialization-json, OkHttp, JGit, DocumentFile,
Gradle 9.3.1, compileSdk 36 / minSdk 26.

## Quellstruktur

```
app/src/main/java/online/devhorizon/sourcecodemapper/
  MainActivity.kt, MainViewModel.kt
  model/Models.kt
  i18n/Strings.kt                 # EN / RU / DE
  data/Settings.kt                # Anbieter, Einstellungen, Cache
  data/RepoAccess.kt              # SAF + JGit + ZIP-Fallback
  analysis/Language.kt
  analysis/Comments.kt            # Filter für Kommentare und Leerzeilen
  analysis/StaticIndexer.kt       # Elementextraktion + Zeilenabdeckung
  analysis/SecurityRules.kt       # Regeln für Spalte 4
  analysis/Enricher.kt            # KI für Spalten 2 und 4
  analysis/ExpressMerger.kt       # Super-Bericht über mehrere Modelle
  ai/AiClient.kt                  # 4 Anbieter + Ollama OAuth
  report/HtmlReport.kt            # HTML: 4 Spalten, Einklappen, Suche
  ui/                             # Compose-Screens
```

## Einschränkungen

- Standardlimits: 2000 Dateien, 320 KB pro Datei, 25 MB Text (konfigurierbar).
- Die KI-Geschwindigkeit hängt vom gewählten Modell ab; Ergebnisse werden per Datei-Hash gecacht.
- Private Repositorys erfordern ein GitHub-Token.

## CI

GitHub Actions baut das Debug-APK bei jedem Push auf `main` und bei Pull Requests
(`.github/workflows/android.yml`). Ein `v*`-Tag hängt das APK an ein GitHub-Release.
