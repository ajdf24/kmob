# Design: kmob

Status: v3 – basiert auf [requirements.md](requirements.md) v4 und [spike.md](spike.md) (S1–S11). Neu in v3: Schichtenarchitektur (§3), Windows (§5.9, R9). Audit-Befunde (K…) sind in den betroffenen Abschnitten eingearbeitet.

## 1. Überblick

kmob ist ein natives CLI (Kotlin/Native), das zwischen Nutzer und `mob` sitzt. Es erweitert genau drei Befehle (`next`, `start`, `done`) und reicht alles andere unverändert durch. Zusätzlich bietet es `version` und `help` (R8.4, R8.5), die jeweils die mob-Ausgabe einschließen.

```mermaid
sequenceDiagram
    autonumber
    actor A as Driver A
    participant KA as kmob (A)
    participant R as Git-Remote<br/>(WIP-Branch mob/…)
    participant KB as kmob (B)
    actor B as Driver B

    A->>KA: kmob next
    KA->>KA: Session wählen (~/.kiro/sessions/cli)
    KA->>KA: Secret-Scan (gitleaks / eingebaut)
    KA->>KA: #lt;root#gt;/.kmob/ schreiben
    KA->>R: mob next (WIP-Commit inkl. .kmob/)
    B->>KB: kmob start
    KB->>R: mob start (pull)
    KB-->>B: /chat load #lt;root#gt;/.kmob/kiro-session.json
    Note over B: arbeitet weiter, ggf. kmob next …
    B->>KB: kmob done
    KB->>KB: .kmob/ sichern + entfernen
    KB->>R: mob done (squash, löscht WIP-Branch)
    KB->>KB: Prüfung: nichts aus .kmob/ im Index
```

## 2. Technologie-Entscheidungen

| Thema | Entscheidung | Begründung |
|---|---|---|
| Sprache/Build | Kotlin Multiplatform-Plugin, nur Native-Targets `macosArm64`, `linuxX64`, `mingwX64`; Gradle mit Version Catalog | Anforderung (R9); kein Intel-Mac (`macosX64` deprecated). `mingwX64` lässt sich auf macOS cross-kompilieren, Tests laufen nur unter Windows |
| Argumente | **eigener kleiner Parser**, kein Clikt | kmob muss unbekannte Argumente unverändert und in Reihenfolge an mob weitergeben (R1.1, R1.7) |
| Dateisystem | Okio (`FileSystem.SYSTEM`, in Tests `FakeFileSystem`) | Streaming großer Dateien (bis 133 MB, Spike S7), testbar |
| JSON | kotlinx.serialization-json, nur `JsonElement`-API | Felder unbekannt/unverändert übernehmen (R7.1) |
| Zeit | `kotlin.time.Instant` | ISO-Timestamps vergleichen |
| Prozesse | eigene Abstraktion: `posix_spawnp` (macOS, Linux), `CreateProcessW` (Windows), je ein kleiner C-Shim | nicht in der Stdlib (s. 5.9) |
| Pfade | Okio-`Path` im Kern (trennerneutral); Auflösung in der Plattformschicht: `realpath(3)` bzw. `GetFinalPathNameByHandleW` | Symlink-Auflösung wie Kiro und Git (Spike S5); Windows: Junctions, kanonische Schreibweise |
| Secret-Scan | **gitleaks** (externes Binary, optional) + eingebauter Minimal-Scanner als Fallback | gepflegte Regeln, Entropie, Allowlists, Team-Config; Go-Regex schnell auf großen Zeilen (Spike S8). Keine K/N-Bibliothek verfügbar, Portierung der Regeln (RE2 → K/N-Regex) nicht wartbar |
| Tests | kotlin.test (Unit), Bash-Skript für E2E gegen echtes mob | E2E testet das echte Binary |

Bibliotheksversionen werden beim Projekt-Setup auf die jeweils aktuelle stabile Version festgelegt und in `gradle/libs.versions.toml` gepinnt. gitleaks wird **nicht** mitgeliefert (Out of Scope); kmob ruft es nur auf.

## 3. Architektur

### 3.1 Schichten (R10.1)

```mermaid
flowchart TB
    subgraph Kern["Kern – commonMain, frei von Betriebssystem-Logik (R10.2)"]
        CLI["cli<br/>Args, HelpText, KmobCli"]
        APP["application<br/>Use Cases + Ports"]
        DOM["domain<br/>Fachmodell, Regeln"]
        ADP["adapter<br/>git, mob, kiro, secrets, state, console"]
        PORT["platform (Ports)<br/>Platform, ProcessRunner"]
        ROOT["App<br/>Verdrahtung"]
    end
    subgraph Plat["Plattformschicht – je Betriebssystem"]
        NAT["nativeMain<br/>Main, ExecutableSearch, WindowsCommandLine"]
        POSIX["posixMain<br/>PosixPlatform, PosixProcessRunner, posix.def"]
        WIN["mingwMain<br/>WindowsPlatform, WindowsProcessRunner, windows.def"]
    end
    CLI --> APP --> DOM
    ADP --> APP
    ADP --> PORT
    ROOT --> CLI & ADP
    NAT --> ROOT
    POSIX & WIN -. implementieren .-> PORT
    NAT --> POSIX & WIN
```

| Schicht | Paket / Source-Set | Inhalt | darf abhängen von |
|---|---|---|---|
| Domäne | `de.kmob.domain.*` | `SessionInfo`, Kandidatenregel, `isWithin`, `SessionSelector`, `quotedTitle`, `isWipBranch`, `HandoverInfo`, `Snapshot`, `SecretRule`/`MandatoryRules`, `SecretMatcher`, `Hit`, Fehlerklassen | nur Domäne, Kotlin-Stdlib, kotlinx.serialization; von Okio nur der Werttyp `Path` |
| Anwendung | `de.kmob.application.*` | Use Cases `HandOverSession` (next), `TakeOverSession` (start), `FinishMob` (done), `FirstHandoverNotice`, `KmobPaths`; Ports `MobCli`, `GitRepositories`/`GitRepository`, `SessionRepository`, `SecretScanner`, `ArtifactStore`, `LocalState`, `Console` (Zeit über `kotlin.time.Clock`) | Domäne; von Okio nur der Werttyp `Path` |
| Adapter | `de.kmob.adapter.*` | `GitCli`, `MobBinary`, `SessionStore`, `SnapshotWriter`, `ArtifactFiles` + `ArtifactWriter`, `BuiltinScanner`, `FileLocalState`, `PlatformConsole` | Anwendung, Domäne, Plattform-Ports, Okio |
| CLI | `de.kmob.cli` | `Args` (Parser), `HelpText`, `KmobCli` (Befehlsauswahl, Exit-Codes) | Anwendung, Domäne |
| Plattform-Ports | `de.kmob.platform` (commonMain) | `Platform`, `ProcessRunner`, `Captured`, `ProcessStartException` | Domäne (Fehlerbasis) |
| Verdrahtung | `de.kmob.App` (commonMain) | baut Adapter und Use Cases aus `Platform`, `ProcessRunner`, `FileSystem` | alles im Kern |
| Plattformschicht | `nativeMain`, `posixMain`, `mingwMain` | Einstieg `main`, Implementierungen der Plattform-Ports, C-Shims | Kern, platform.posix / platform.windows, cinterop |

`posixMain` ist ein eigenes Zwischen-Source-Set für `macosArm64` und `linuxX64`, `mingwMain` gilt für `mingwX64`. `nativeMain` enthält plattformneutrale Hilfen der Plattformschicht in reinem Kotlin (PATH-Suche mit Parametern, Windows-Kommandozeilen-Quoting). Sie lassen sich so auf dem Entwicklungsrechner testen, obwohl sie nur unter Windows gebraucht werden.

### 3.2 Ports zum Betriebssystem

```kotlin
/** Startet externe Programme (mob, git, gitleaks, kiro-cli). */
interface ProcessRunner {
    /** Erbt stdin/stdout/stderr. Ctrl+C beendet das Kind, nicht kmob. Liefert den Exit-Code (POSIX: bei Signal 128 + n). */
    fun runInherit(cmd: List<String>): Int
    /** stdin leer, stdout und stderr eingesammelt (ohne Deadlock). */
    fun runCapture(cmd: List<String>): Captured
}
data class Captured(val exitCode: Int, val stdout: String, val stderr: String)
class ProcessStartException(message: String) : KmobException(message)   // "cannot start <prog>: <grund>"

interface Platform {
    val pathSeparator: Char                    // Trenner in Pfaden von realPath ('/' bzw. '\\'), für isWithin
    fun env(name: String): String?
    fun realPath(path: String): String?        // aufgelöst und kanonisch, null wenn nicht existent
    fun isInteractive(): Boolean               // stdin und stderr sind ein Terminal / eine Konsole
    fun readLine(): String?                    // null bei EOF
    fun findInPath(binary: String): String?    // Suchregeln der Plattform (R9.2)
    fun isExecutable(path: String): Boolean
    fun homeDir(): String
    fun now(): Instant
    fun printOut(line: String)
    fun printErr(line: String)
}
```

Der Kern kennt keine Betriebssysteme, nur diese Fähigkeiten. Pfade baut er ausschließlich mit Okio (`root.toPath() / ".kmob"`). Der Pfadvergleich `isWithin` arbeitet auf den Zeichenketten von `realPath` mit dem Trenner `Platform.pathSeparator` (gleiches Verhalten wie M2 auf POSIX). Ein `cwd`-Parameter existiert bewusst nicht (K5-12): Git wird immer mit `-C <root>` aufgerufen, mob und gitleaks laufen im aktuellen Verzeichnis von kmob.

### 3.3 Architekturprüfung (R10.3)

Gradle-Task `checkArchitecture` (abhängig von `check`), ein einfacher Quelltext-Scan über `src/commonMain`:

- Abhängigkeiten je Schicht nur zu den erlaubten Schichten laut Tabelle 3.1 – als Import und vollqualifiziert im Code (`de.kmob.<schicht>.…`).
- Verboten im ganzen Kern (Import und vollqualifiziert): `platform.posix`, `platform.windows`, `kotlinx.cinterop`, `de.kmob.cinterop`, die Plattform-Pakete `de.kmob.platform.{posix,windows,support}`.
- Okio außerhalb von Adaptern und Verdrahtung nur `okio.Path` (kein `FileSystem`, keine Streams, keine Wildcard-Imports).
- Tokens im Code (Kommentare ausgenommen): `OsFamily`, `osFamily`, `"/dev/null"`, `"HOME"`, `"PATH"`, `"USERPROFILE"`, `'/'`, `'\\'`.
- Verstöße werden mit Datei, Zeile und Regel gemeldet; der Build schlägt fehl.

Selbsttest: `checkArchitectureSelfTest` lässt dieselben Regeln über `src/archTest/` laufen (bewusst verletzende Dateien, nicht kompiliert). Jede verletzende Zeile trägt `// expect: <regel>`; der Test schlägt fehl, wenn eine markierte Zeile nicht oder eine unmarkierte Zeile gemeldet wird.

### 3.4 Clean-Code-Regeln (R10.5)

- Eine Funktion erledigt einen Schritt; Abläufe (design §6) lesen sich als Folge benannter Schritte.
- Namen aus der Fachsprache dieser Spec (Handover, Artefakt, Kandidat, WIP-Branch). Keine Abkürzungen außer `id`.
- Pfade und Dateinamen (`.kmob`, `<git-dir>/kmob/tmp`, Merker) stehen an genau einer Stelle (`KmobPaths`).
- Keine Nebenwirkung über Property-Zugriffe (`mob.ensureAvailable()` statt `mob.binary`).
- Meldungstexte bleiben wörtlich wie in §7; Formatierungen (z. B. Titel auf erste Zeile, 80 Zeichen) gibt es nur einmal.
- Fehler als `KmobException`-Unterklassen mit Exit-Code; kein stilles Verschlucken außer an den in der Spec genannten toleranten Stellen (R3.2, R7.2).
- Kotlin-Namenskonventionen (camelCase); JSON-Feldnamen über `@SerialName`.

### 3.5 Projektstruktur

```
kmob/
├─ build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
├─ gradle/architecture.gradle.kts       checkArchitecture + Selbsttest (§3.3)
├─ src/
│  ├─ commonMain/kotlin/de/kmob/
│  │  ├─ App.kt                         Verdrahtung
│  │  ├─ cli/                           Args, HelpText, KmobCli
│  │  ├─ application/                   HandOverSession, TakeOverSession, FinishMob, FirstHandoverNotice, KmobPaths
│  │  ├─ application/port/Ports.kt      MobCli, GitRepositories, SessionRepository, SecretScanner, ArtifactStore, LocalState, Console
│  │  ├─ domain/Errors.kt               KmobException, UsageException
│  │  ├─ domain/session/                SessionInfo, SessionScan, isWithin, Selection, SessionSelector, quotedTitle
│  │  ├─ domain/handover/               HandoverInfo, Snapshot, ExportException
│  │  ├─ domain/secrets/                SecretRule, MandatoryRules, SecretMatcher, Hit, ScanOutcome, describeHits, SecretsFoundException
│  │  ├─ domain/mob/                    isWipBranch
│  │  ├─ adapter/{console,git,kiro,mob,secrets,state}/
│  │  └─ platform/Ports.kt              Platform, ProcessRunner, Captured, ProcessStartException (3.2)
│  ├─ nativeMain/kotlin/de/kmob/        Main.kt (expect hostPlatform/…), platform/support/{ExecutableSearch,WindowsCommandLine}
│  ├─ posixMain/kotlin/de/kmob/         Host.kt (actual), platform/posix/{PosixPlatform,PosixProcessRunner}
│  ├─ mingwMain/kotlin/de/kmob/         Host.kt (actual), platform/windows/{WindowsPlatform,WindowsProcessRunner}
│  ├─ nativeInterop/cinterop/           posix.def (de.kmob.cinterop.posix), windows.def (de.kmob.cinterop.windows)
│  ├─ commonTest/                       Unit- und Charakterisierungstests mit Fakes (Pakete wie commonMain)
│  ├─ nativeTest/                       Tests der neutralen Plattformhilfen (laufen auf jedem Host)
│  ├─ posixTest/, mingwTest/            Tests der echten Plattform-Implementierungen
│  └─ archTest/                         bewusst verletzende Beispiele für den Selbsttest (nicht kompiliert)
├─ e2e/run.sh                           End-to-End gegen Bare-Repo + echtes mob
└─ .github/workflows/ci.yml, release.yml
```

## 4. Kommandozeile

### 4.1 Parser (R1.1, R1.4, R1.7, R1.8, R7.3)

```mermaid
flowchart TD
    A[rohe Argumente] --> B["Kommando = erstes Argument außer --debug<br/>(führendes --debug: kmob-Debug an, bleibt für mob, K2-01)"]
    B --> K{"kmob-Option<br/>vor dem Kommando?"}
    K -->|ja, N-05| U([Usage-Fehler, Exit 2])
    K -->|nein| C{Kommando?}
    C -->|keins, -h, --help, help| H[Help]
    C -->|version| V[Version]
    C -->|next/n, start/s, done/d| E{"-h / --help<br/>nach dem Kommando?"}
    C -->|sonst| P["Passthrough:<br/>mob #lt;Argumente identisch#gt;<br/>keine KMOB_*-Auswertung außer KMOB_MOB_BIN"]
    E -->|ja| P
    E -->|nein| F["kmob-Optionen erkennen + entfernen<br/>bis '--' (Rest unverändert)<br/>Werte von mob-Optionen überspringen"]
    F --> G[Config: Flags > Env, Gültigkeit je Kommando]
```

Regeln für erweiterte Befehle:

0. Vor dem Kommando ist nur `--debug` erlaubt (beliebig oft). Steht dort eine andere kmob-Option (z. B. `kmob --yes next`, `kmob --session abc next`), ist das ein Usage-Fehler; so wird nie ein Optionswert als Kommando erkannt (N-05). Andere Argumente mit `-` vor dem Kommando (außer `-h`/`--help`) gelten als Kommando und gehen per Passthrough an mob.
1. Durchlauf von links nach rechts über die Argumente nach dem Kommando. Ab `--` wird nichts mehr interpretiert; `--` und alles danach geht an mob.
2. Bekannte mob-Optionen mit Wert (`--message`/`-m`, `--branch`/`-b`, `--room`) werden **samt Wert** unverändert weitergereicht, damit `kmob next -m --yes` den Text `--yes` als Commit-Message übergibt (K1-10).
3. kmob-Optionen (auch in der Form `--session=<id>`):

   | Option | Wert | Env-Variable | gültig bei |
   |---|---|---|---|
   | `--session <id>` | ID oder eindeutiges Präfix | `KMOB_SESSION` | next |
   | `--pick-session` | – | `KMOB_PICK_SESSION` | next |
   | `--no-session` | – | `KMOB_NO_SESSION` | next |
   | `--allow-secrets` | – | `KMOB_ALLOW_SECRETS` | next |
   | `--require-gitleaks` | – | `KMOB_REQUIRE_GITLEAKS` | next |
   | `--yes` | – | `KMOB_YES` | next (Erst-Bestätigung), done |
   | `--kiro` / `--no-kiro` | – | `KMOB_START_KIRO` (`true`/`false`, nicht gesetzt = fragen) | start |
   | `--debug` | – | `KMOB_DEBUG` | alle erweiterten; **bleibt** in den mob-Argumenten (R7.3). `KMOB_DEBUG` wird nicht an mob gegeben |
   | – | – | `KMOB_KIRO_SESSIONS_DIR` | next |
   | – | – | `KMOB_GITLEAKS_BIN` | next, version |
   | – | – | `KMOB_KIRO_BIN` | start |
   | – | – | `KMOB_MOB_BIN` | alle |

4. Eine kmob-Option bei einem erweiterten Befehl, für den sie nicht gilt (z. B. `kmob start --yes`) → Usage-Fehler, Exit 2.
5. Pro Option gewinnt das Flag gegen die Env-Variable (R6.1). Widersprüche (`--no-session` mit `--session`/`--pick-session`, `--session` mit `--pick-session`) werden **nach** dieser Auflösung geprüft und nur dann als Usage-Fehler gemeldet, wenn beide aus derselben Quelle stammen. Stammt eine Seite aus der Umgebung, gewinnt das Flag und die Env-Seite wird ignoriert (Debug-Hinweis) (K2-05).
6. Boolesche Env-Variablen: `true`/`false`/`1`/`0` (Groß/klein egal), sonst Usage-Fehler – aber nur bei Befehlen, für die die Variable gilt (K2-05).
7. `--session` ohne Wert → Usage-Fehler.

Kollisionen mit künftigen mob-Optionen gleichen Namens werden in Kauf genommen; mob 5.4.2 hat keine (Spike S9).

### 4.2 Exit-Codes

| Code | Bedeutung |
|---|---|
| Code von mob | bei Passthrough und nach erfolgreichem kmob-Vorlauf (R1.3) |
| 1 | kmob-Fehler: Übergabe abgebrochen (Session ungültig, Secret, Export-/Scan-Fehler, Bestätigung abgelehnt oder kein TTY), `done` abgebrochen (Bestätigung), `.kmob/` nach `done` im Index, mob/gitleaks nicht gefunden |
| 2 | Usage-Fehler |

Ausgaben von kmob gehen nach **stderr** mit Präfix `kmob:`, damit stdout von mob unverfälscht bleibt. Meldungen sind englisch (wie mob); die Nutzer-Doku ist deutsch.

## 5. Komponenten

### 5.1 Mob (R1.2, R1.5, R1.6, R4.3)

- Binary: `KMOB_MOB_BIN` (muss existieren und ausführbar sein, sonst eigene Meldung, K5-11), sonst `mob` im `PATH`.
- Aufruf per `runInherit`: Umgebung **unverändert** (kein `MOB_CLI_NAME`, Spike S6).
- `config()`: `mob config` per `runCapture`; Zeilen `KEY=value`, Wert ohne umschließende `"` (Spike S9). Wird von `next` und `done` je einmal aufgerufen.
  - `wipPrefix` = `MOB_WIP_BRANCH_PREFIX`, Fallback `mob/`.
  - `doneMode(args)`: letztes von `--squash`/`--no-squash`/`--squash-wip` in den Argumenten; sonst `MOB_DONE_SQUASH` ∈ {`squash`, `no-squash`, `squash-wip`}; sonst **`UNKNOWN`** (fail-closed, K1-06).

### 5.2 Git (K1-01)

`root()` läuft im aktuellen Verzeichnis, alle anderen Aufrufe mit `git -C <root>`. Alle `.kmob`-Pfade sind `<root>/.kmob/…`.

| Funktion | Befehl |
|---|---|
| `root()` | `git rev-parse --show-toplevel` → `realPath` |
| `gitDir()` | `git rev-parse --absolute-git-dir` (pro Worktree; für temporäre Dateien) |
| `commonDir()` | `git rev-parse --path-format=absolute --git-common-dir` (pro Clone; für Merker, K1-08) |
| `currentBranch()` | `git branch --show-current` (leer = detached) |
| `isIgnored(rel)` | `git check-ignore -q -- <rel>` (Exit 0 = ignoriert) |
| `inIndex(rel)` | `git ls-files -- <rel>` nicht leer |
| `inHead(rel)` | `git ls-tree -r --name-only HEAD -- <rel>` nicht leer |
| `removeFromIndex(rel)` | `git rm -r -q --cached -- <rel>` |
| `hasRef(ref)` | `git rev-parse --verify -q <ref>` |
| `userName()` | `git config user.name` (leer → `unknown`) |

Kein Git-Repo → `next`/`start`/`done` reichen ohne Vorlauf an mob durch.

### 5.3 SessionStore (R2.4, R2.5, R7.1, R7.2)

Liest alle `*.json` im Session-Verzeichnis (Default `<home>/.kiro/sessions/cli`). Andere Kiro-Formate (`~/.kiro/sessions/<hash>/sess_*`) werden nicht gelesen (Spike S9).

```kotlin
data class SessionInfo(
    val id: String,               // Dateiname ohne .json
    val cwd: String,              // aufgelöst (realPath, Fallback: Rohwert)
    val title: String?,
    val updatedAt: Instant?,      // aus JSON
    val logModified: Instant?,    // mtime von {id}.jsonl, null wenn fehlt
    val hasCompleteEntry: Boolean,// mind. eine nicht-leere, mit \n abgeschlossene Zeile (N-02)
    val isSubagent: Boolean,      // parent_session_id vorhanden und != null
    val isActive: Boolean,        // {id}.lock existiert (nur Anzeige)
) {
    /** null zählt als ältester Wert (K5-09) */
    val freshness: Instant get() = maxOf(updatedAt ?: DISTANT_PAST, logModified ?: DISTANT_PAST)
}
data class ScanResult(val sessions: List<SessionInfo>, val skipped: Int)
```

- Datei nicht lesbar / kein JSON-Objekt / kein `cwd`-String → überspringen, `skipped++`, Debug-Ausgabe (R7.2).
- **Pfadvergleich** `isWithin(cwd, root)`: beide aufgelöst, abschließende `/` entfernt; wahr, wenn `cwd == root` oder `cwd.startsWith(root + "/")`.
- `hasCompleteEntry` wird beim Lesen geprüft: Datei streamen, bis die erste nicht-leere Zeile mit `\n` gefunden ist (meist nach wenigen KB).
- **Kandidat** = `isWithin(cwd, root) && !isSubagent && hasCompleteEntry` (R2.4).

### 5.4 SessionSelector (R2.6–R2.8)

```kotlin
sealed interface Selection {
    data class Chosen(val session: SessionInfo, val reason: Reason,
                      val candidates: Int, val newer: SessionInfo?) : Selection
    data object None : Selection
    data class Error(val message: String) : Selection
}
enum class Reason { EXPLICIT, PICKED, KIRO_SESSION_ID, MOST_RECENT }
```

```mermaid
flowchart TD
    S([Start]) --> O1{--session?}
    O1 -->|ja| X1{"ID/Präfix in allen<br/>Sessions eindeutig?"}
    X1 -->|nein| E1["Error: not found /<br/>ambiguous prefix"]
    X1 -->|ja| X2{Kandidat?}
    X2 -->|nein| E2["Error: belongs to #lt;cwd#gt; /<br/>is a subagent session / has no log"]
    X2 -->|ja| C1[Chosen EXPLICIT]
    O1 -->|nein| O2{--pick-session?}
    O2 -->|ja| KP{Kandidaten?}
    KP -->|0, N-06| E5[Error: no sessions to pick from]
    KP -->|≥1| T{TTY?}
    T -->|nein| E3[Error: needs a terminal]
    T -->|ja| PK["Liste (nach freshness)<br/>1 Kandidat: ohne Frage"]
    PK -->|Nummer / Enter| C2[Chosen PICKED]
    PK -->|q / EOF| E4[Error: no session picked]
    O2 -->|nein| K{Kandidaten?}
    K -->|0| N[None]
    K -->|≥1| O3{"KIRO_SESSION_ID<br/>ist Kandidat?"}
    O3 -->|ja| C3["Chosen KIRO_SESSION_ID<br/>newer = aktuellerer Kandidat, falls vorhanden"]
    O3 -->|nein| C4["Chosen MOST_RECENT<br/>(Gleichstand: ID lexikographisch)"]
```

- Präfix-Suche (K2-06) läuft über **alle** gelesenen Sessions, damit die Fehlermeldung den Grund nennen kann.
- Picker: Format `[n] <title, 60 Zeichen> · <relative Zeit> · <volle ID>` (+ ` · active` bei Lock, K2-04). Eingabe Nummer, Enter = 1, `q` = Abbruch (`Error`, kein Handover); ungültige Eingabe → erneut fragen; EOF → Abbruch (K5-09).
- `KIRO_SESSION_ID` gesetzt, aber kein Kandidat → ignorieren, Debug-Hinweis.
- `newer` (R2.6, K3-01): bei Regel 3 der jüngste andere Kandidat mit größerer `freshness`. Absicherung für den offenen Punkt aus Spike S9 (`KIRO_SESSION_ID` nach `/chat load`).

### 5.5 Snapshot (R2.2, R2.3, R2.11)

Laufende Sessions wachsen während des Handovers (Spike S3). Damit Scan und Artefakt garantiert denselben Inhalt sehen, arbeitet kmob auf einer Kopie im Temp-Verzeichnis `<git-dir>/kmob/tmp/` (liegt nie im Working Tree, K5-05):

1. `{id}.json` lesen, als JSON-Objekt validieren, Originaltext nach `tmp/meta.json` kopieren.
2. `{id}.jsonl` zeilenweise streamen. Nur Zeilen, die mit `\n` enden, sind vollständig; ein Rest am Ende wird verworfen (R2.3). Leere Zeilen werden übersprungen. Jede Zeile muss gültiges JSON sein (sonst Fehler, R2.11) und wird nach `tmp/log.jsonl` geschrieben.
3. Ergebnis: `entries` (Anzahl). Zeile *n* in `log.jsonl` ist Log-Eintrag *n* (wichtig für Fundstellen).
4. `entries == 0` → Fehler (sollte durch R2.4 nicht vorkommen; Datei kann aber inzwischen geleert sein).

Konstanter Speicher (≈ größte Einzelzeile, bis 13 MB laut Spike S7). Zu Beginn jedes `next` wird `tmp/` geleert (Reste abgebrochener Läufe).

### 5.6 SecretScanner (R5.2–R5.4, R5.6–R5.8)

```kotlin
interface SecretScanner {
    val name: String                                     // "gitleaks 8.30.1" / "built-in"
    fun scan(file: Path, location: Location): ScanOutcome
}
enum class Location { METADATA, LOG }
sealed interface ScanOutcome {
    data object Clean : ScanOutcome
    data class Findings(val hits: List<Hit>) : ScanOutcome   // Hit(ruleId, line)
    data class Failed(val reason: String) : ScanOutcome
}
```

Gescannt werden `tmp/meta.json` (Fundort „session metadata“, K1-04) und `tmp/log.jsonl` (Fundort „log entry #n“), **je ein Aufruf pro Datei** (Spike S8: mehrere Dateien pro Aufruf unzuverlässig).

**Auswahl:**

```mermaid
flowchart TD
    A{"KMOB_GITLEAKS_BIN gesetzt?"} -->|ja| B{"ausführbar?"}
    B -->|nein| F1[Fehler, Exit 1]
    B -->|ja| G[GitleaksScanner]
    A -->|nein| C{"gitleaks im PATH?"}
    C -->|ja| G
    C -->|nein| D{"--require-gitleaks?"}
    D -->|ja| F2["Fehler: gitleaks not found, Exit 1"]
    D -->|nein| BI["BuiltinScanner<br/>+ einmaliger Hinweis je Clone<br/>(Merker gitleaks-hint-shown)"]
```

**GitleaksScanner** (Spike S8):

- Generierte Config `tmp/gitleaks.toml`:
  ```toml
  [extend]
  path = "<root>/.gitleaks.toml"   # wenn vorhanden, sonst: useDefault = true

  # danach die fünf Pflichtregeln aus MandatoryRules (s. u.), z. B.:
  [[rules]]
  id = "kmob-bearer-token"
  description = "Bearer token"
  regex = '''(?i)\bbearer\s+([a-z0-9_\-.=~+/]{20,})'''
  secretGroup = 1
  entropy = 3.0
  keywords = ["bearer"]
  ```
  Pfad als TOML-Basic-String mit escapten `\` und `"`. Die Pflichtregeln werden **immer** angehängt (R5.6, N-03): Ersetzt eine Repo-Config die Default-Regeln (kein `useDefault`, Spike S9), gelten die Mindestmuster aus R5.2 trotzdem. Doppelte Treffer (z. B. `aws-access-token` und `kmob-aws-access-key-id` in derselben Zeile) werden je Fundort zu einer Meldung mit allen Regel-IDs zusammengefasst.
- Gescannte Dateien liegen unter `<git-dir>/kmob/tmp/`; die Default-Allowlist von gitleaks für `.git` greift dort nicht (Spike S9).
- Aufruf: `gitleaks dir <datei> -c tmp/gitleaks.toml --redact --no-banner -l error -f json -r - --exit-code 99 -i tmp/` (`-i` zeigt auf ein Verzeichnis ohne `.gitleaksignore`, damit das Ergebnis nicht vom cwd abhängt).
- Auswertung: Exit 0 → `Clean`; 99 → JSON-Array parsen, nur `RuleID` und `StartLine` übernehmen (`Match` kann trotz `--redact` Umgebungstext enthalten); jeder andere Code → `Failed(stderr)` → Abbruch, kein Fallback (R5.8).
- Ab 10 MB Gesamtgröße Hinweis `kmob: scanning <n> MB for secrets…` (~10 s bei 133 MB).

**MandatoryRules** (`secrets/MandatoryRules.kt`): eine Definition der fünf Pflichtregeln, aus der sowohl die gitleaks-Regeln (TOML: `regex`, `keywords`, `entropy`, `[[rules.allowlists]]`) als auch der BuiltinScanner erzeugt werden. Die Regexe nutzen nur den gemeinsamen Teil von RE2 (Go) und der Kotlin/Native-Regex (keine Lookarounds, keine Backreferences); ein Unit-Test prüft dieselben Fixtures gegen beide Implementierungen (gitleaks im E2E).

**BuiltinScanner** (Fallback, K5-10): zeilenweise, Vorfilter per `String.contains`, Regex nur bei Vorfilter-Treffer.

| Regel (`kmob-…`) | Vorfilter | Regex | Allowlist |
|---|---|---|---|
| aws-access-key-id | `AKIA`, `ASIA` | `\b(AKIA\|ASIA)[0-9A-Z]{16}\b` | Wert endet auf `EXAMPLE` |
| private-key | `PRIVATE KEY-----` | vollständiger Block `-----BEGIN … PRIVATE KEY-----` + ≥ 64 Zeichen Base64 + `-----END … PRIVATE KEY-----` (wie gitleaks; reine Erwähnung des Headers ist kein Treffer) | – |
| bearer-token | `earer` (case-insensitive) | `(?i)\bbearer\s+([A-Za-z0-9._~+/=-]{20,})` | Shannon-Entropie < 3.0 |
| github-token | `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_` | `gh[pousr]_[A-Za-z0-9]{36}\|github_pat_[A-Za-z0-9_]{22,}` | – |
| slack-token | `xox` | `xox[abprs]-[A-Za-z0-9-]{10,}` | – |

Gescannt wird der Rohtext (inkl. JSON-Escapes; für die Muster unkritisch). Nur wertbasierte Muster (Spike S7).

**Ergebnis:** Treffer → Meldung je Treffer (max. 10, dann `… and <n> more`), Abbruch, Exit 1, `.kmob/` unverändert (R5.4). Mit `--allow-secrets` dieselben Zeilen als Warnung, Handover läuft weiter (R5.3).

### 5.7 ArtifactWriter (R2.1, R2.12, R2.14, K5-04)

Erst wenn Scan bestanden ist:

1. `tmp/kiro-session.json` aus den Snapshots streamen:
   `{"format":"kiro-session-export-v1","metadata":<meta.json getrimmt>,"log_entries":[<Zeile 1>,<Zeile 2>,…]}`
2. `tmp/handover.json` schreiben (5.8), inkl. `artifact_bytes` = Größe aus Schritt 1.
3. Commit-Reihenfolge nach `<root>/.kmob/`:
   1. alte `.kmob/handover.json` löschen,
   2. `tmp/kiro-session.json` → `.kmob/kiro-session.json` (rename),
   3. `tmp/handover.json` → `.kmob/handover.json` (rename).
   Bricht kmob zwischen 3.1 und 3.3 ab, fehlt höchstens die Handover-Info → `start` zeigt nur den Lade-Befehl (R3.2). Eine alte Info zu einem neuen Artefakt ist ausgeschlossen; zusätzlich prüft `start` `artifact_bytes`.
4. `rename` scheitert mit `EXDEV` (Worktree auf anderem Dateisystem als git-dir, N-07) → Kopie nach `.kmob/<name>.tmp`, dann rename. Damit eine solche Datei nie committet wird, schreibt kmob bei jedem Handover `.kmob/.gitignore` mit dem Inhalt `*.tmp` (wird mitcommittet, ist harmlos; R2.13 prüft nur die beiden Nutzdateien). Reste werden zu Beginn von `next` gelöscht.
5. Artefakt > 50 MB → Warnung mit Größe und Hinweis auf `/chat new` (R2.14).

### 5.8 HandoverInfo (R3.1, R3.2)

```json
{ "schema": 1, "kmob_version": "0.1.0", "session_id": "…", "title": "…",
  "freshness": "2026-10-29T13:18:01Z", "log_entries": 78, "artifact_bytes": 423885,
  "from": "<git user.name>", "handed_over_at": "2026-10-29T13:20:00Z" }
```

Lesen tolerant: fehlende/ungültige Datei, fehlende Felder oder `artifact_bytes` ≠ tatsächliche Größe → nur Lade-Befehl ausgeben.

### 5.9 Plattformschicht (K5-02, R9)

**POSIX (macOS, Linux):**

- `runInherit`: `posix_spawnp` ohne File-Actions (erbt Deskriptoren 0–2), `environ` unverändert. Mit `posix_spawnattr_setsigdefault({SIGINT, SIGQUIT})` und `POSIX_SPAWN_SETSIGDEF` startet mob mit Default-Signalbehandlung; kmob selbst ignoriert SIGINT/SIGQUIT nur, solange es auf mob wartet. Ctrl+C trifft mob (gleiche Prozessgruppe), kmob übernimmt dessen Exit-Code; bei Signal `128 + Signal`.
- `runCapture`: `posix_spawnp` mit zwei Pipes (`posix_spawn_file_actions_adddup2`), stdin aus `/dev/null`, beide Pipes per `poll` lesen (kein Deadlock bei voller Pipe). Gleiche Signal-Attribute.
- Während kmob selbst arbeitet (Snapshot, Scan), beendet Ctrl+C kmob normal. Es bleibt höchstens `<git-dir>/kmob/tmp/` zurück, das nicht committet wird und beim nächsten Lauf geleert wird.

**Windows (`mingwX64`, R9):**

| Port-Funktion | Umsetzung |
|---|---|
| `env` | `GetEnvironmentVariableW` (Unicode, R9.9) |
| `realPath` | `CreateFileW` mit `FILE_FLAG_BACKUP_SEMANTICS` + `GetFinalPathNameByHandleW`; Präfix `\\?\` entfernen, `\\?\UNC\` → `\\` |
| `isInteractive` | `GetConsoleMode` auf stdin und stderr erfolgreich (R9.6) |
| `readLine` | Konsole: `ReadConsoleW`; sonst Bytes bis `\n` als UTF-8, `\r` entfernen |
| `printOut`/`printErr` | Konsole: `WriteConsoleW` (UTF-16, unabhängig von der Codepage); sonst `WriteFile` mit UTF-8 (R9.7) |
| `findInPath` | `ExecutableSearch` mit Trenner `;`, Endungen aus `PATHEXT` ∩ {`.COM`, `.EXE`} (Default `.COM;.EXE`), Anführungszeichen in `PATH`-Einträgen entfernt, kein aktuelles Verzeichnis (R9.2) |
| `isExecutable` | reguläre Datei mit Endung `.exe`/`.com` (Groß/klein egal) |
| `homeDir` | `USERPROFILE`, sonst `HOMEDRIVE` + `HOMEPATH`, sonst `C:\` |
| `runInherit` | Programm über `findInPath` auflösen (kein Suchen im cwd), Kommandozeile mit `WindowsCommandLine.quote`, `CreateProcessW` mit vererbten Duplikaten der Standard-Handles (`STARTF_USESTDHANDLES`), warten, `GetExitCodeProcess`. Während des Wartens ein Konsolen-Handler, der Ctrl+C/Ctrl+Break für kmob verschluckt (nur Handler-Routinen werden nicht vererbt, das Kind behält die Default-Behandlung, R9.8) |
| `runCapture` | wie oben, aber `CREATE_NO_WINDOW` und ohne Ctrl+C-Handler (Ctrl+C beendet kmob und git gemeinsam, wie unter POSIX); stdin = `NUL`, stdout/stderr über zwei anonyme Pipes, gelesen von zwei Threads im C-Shim (kein Deadlock). Scheitert ein Thread oder Speicher, bricht der Aufruf als Startfehler ab, ohne zu hängen |

`WindowsCommandLine.quote(args)` (R9.3): Argument ohne Leerzeichen, Tab, `"` und nicht leer → unverändert; sonst in `"` einschließen, `n` Backslashes vor `"` → `2n+1` Backslashes + `"`, `n` Backslashes am Ende → `2n`. Unit-Test: Roundtrip gegen eine Referenz-Implementierung von `CommandLineToArgvW` (in `nativeTest`), unter Windows zusätzlich echt über einen Kindprozess (`mingwTest`).

`ExecutableSearch` ist reines Kotlin mit zwei Konfigurationen, `ExecutableSearch.POSIX` (Trenner `:`, keine Endungen) und `ExecutableSearch.windows(pathExt)`; `find(name, pathValue, isExecutable)`. Name mit Pfadtrenner oder Laufwerk → nur dieser Pfad.

Argumente von kmob selbst (R9.3, R9.9): unter Windows aus `GetCommandLineW` + `CommandLineToArgvW` statt aus `main(args)`, damit Nicht-ASCII unabhängig von der Codepage ankommt. `mingwTest` prüft `WindowsCommandLine.quote` per Roundtrip gegen das echte `CommandLineToArgvW`.

Exit-Codes unter Windows: unverändert vom Kind (32 Bit, z. B. `0xC000013A` nach Ctrl+C). `exitProcess` gibt sie unverändert weiter.

### 5.10 LocalState (R5.1, R5.7, K1-08)

Merker unter `<git-common-dir>/kmob/` (ein Clone, alle Worktrees; liegt in `.git/`, nie committet):

| Datei | Bedeutung |
|---|---|
| `notice-accepted` | Erst-Hinweis bestätigt |
| `gitleaks-hint-shown` | Hinweis „install gitleaks“ wurde gezeigt |

**Erst-Hinweis** (vor jedem Schreiben und Pushen):

```
kmob: kmob next pushes your Kiro session to the git remote (branch <wip-branch>):
kmob:   prompts, answers and all tool output (file contents, command output, local paths).
kmob:   Secret scan: <gitleaks 8.30.1 | built-in patterns only>. It is a safety net, not a guarantee.
kmob: Continue and don't ask again for this clone? [y/N]
```

`--yes` → Hinweistext wird einmal angezeigt, bestätigt ohne Frage. Kein TTY und kein `--yes` → Abbruch mit Hinweis auf `--yes`, Exit 1. „Nein“ → Exit 1, kein Merker.

## 6. Abläufe

### 6.1 `kmob next [mob-args]`

```mermaid
flowchart TD
    A[Args parsen] -->|Usage-Fehler| X2([Exit 2])
    A --> B{mob vorhanden?}
    B -->|nein| X1([Exit 1])
    B -->|ja| C{Git-Root?}
    C -->|nein| M[[mob next]]
    C -->|ja| D["mob config → wipPrefix<br/>aktueller Branch ist WIP-Branch?"]
    D -->|nein, R2.15 / K5-03| M
    D -->|ja| T[tmp/ leeren, Reste .kmob/.*.tmp löschen]
    T --> NS{--no-session?}
    NS -->|ja| RM["vorhandenes .kmob/ löschen<br/>(Info-Meldung, R2.9)"] --> M
    NS -->|nein| SEL[SessionStore + Selector]
    SEL -->|Error| X1
    SEL -->|None| W["Warnung no session<br/>(+ skipped-Anzahl)"] --> RM2[".kmob/ löschen (R2.10)"] --> M
    SEL -->|Chosen| P["Auswahl ausgeben (R2.7)<br/>ggf. Warnung newer"]
    P --> IG{"kiro-session.json oder<br/>handover.json ignoriert?"}
    IG -->|ja, R2.13 / K5-08| X1
    IG -->|nein| SC[Scanner wählen 5.6]
    SC -->|Fehler| X1
    SC --> NO{"Erst-Hinweis<br/>bestätigt?"}
    NO -->|nein| X1
    NO -->|ja| SN[Snapshot 5.5]
    SN -->|Fehler| X1
    SN --> SCAN[Scan meta + log]
    SCAN -->|Treffer ohne --allow-secrets<br/>oder Scan-Fehler| X1
    SCAN --> WR[ArtifactWriter 5.7 + Größenwarnung]
    WR -->|Fehler| X1
    WR --> M
    M --> R{Exit-Code mob}
    R -->|0| X0([Exit 0])
    R -->|≠ 0 und Artefakt geschrieben| HF["Hinweis Push-Ablehnung (K4-01)"] --> XM([Exit-Code von mob])
    R -->|≠ 0| XM
```

Auswahl-Meldung (R2.7, K1-03), eine Zeile; der Zusatz in Klammern hängt von der Regel ab:

```
kmob: handing over Kiro session 7e6dd8a5-…-0c2d "Refactoring Payment-Service" (last activity 2026-10-29T13:18:01Z; <regel>)
```

`<regel>`: `--session` · `picked` · `current session (KIRO_SESSION_ID)` · `most recent`. Bei `KIRO_SESSION_ID`/`most recent` und mehr als einem Kandidaten zusätzlich `, <n> sessions found – use --pick-session to choose`.

**Hinweis bei fehlgeschlagenem `mob next`** (Spike S9: der lokale WIP-Commit enthält das Artefakt bereits, ein erneutes `kmob next --no-session` hilft nicht):

```
kmob: mob next failed. If the push was rejected (e.g. file too large), the session is already in your local WIP commit. To hand over without it:
kmob:   git reset --soft <ref> && kmob next --no-session
```

`<ref>` = `origin/<wip-branch>`, wenn diese Ref existiert, sonst wörtlich `origin/<base-branch>` (Platzhalter; kmob kennt den Basis-Branch nicht zuverlässig). Nach dem Reset liegt `.kmob/` noch im Index; `--no-session` löscht das Verzeichnis (R2.9), und `git add --all` von mob nimmt die Löschung mit. Ein `rm` mit (aus Unterverzeichnissen falschem) relativem Pfad ist deshalb nicht nötig (N-04).

### 6.2 `kmob start [mob-args]`

```mermaid
flowchart TD
    A[[mob start]] --> B{Exit 0?}
    B -->|nein| XM([Exit-Code von mob, keine Ausgabe R3.3])
    B -->|ja| C{"Git-Root und<br/>#lt;root#gt;/.kmob/kiro-session.json?"}
    C -->|nein| X0([Exit 0])
    C -->|ja| D{"handover.json gültig<br/>und artifact_bytes passt?"}
    D -->|ja| E[Info-Zeile]
    D -->|nein| K
    E --> K{"KIRO_SESSION_ID gesetzt?<br/>(innerhalb von Kiro)"}
    K -->|ja, R3.6| IN["Lade-Befehl +<br/>'type it in the chat'"] --> X0
    K -->|nein| N{"--no-kiro /<br/>KMOB_START_KIRO=false?"}
    N -->|ja| LC[nur Lade-Befehl] --> X0
    N -->|nein| T{TTY?}
    T -->|nein| LC
    T -->|ja| Y{"--kiro / KMOB_START_KIRO=true?"}
    Y -->|ja| KB
    Y -->|nein| Q{"Start Kiro with this session? [Y/n]"}
    Q -->|n| LC
    Q -->|Y / Enter| KB{"kiro-cli gefunden?<br/>(KMOB_KIRO_BIN / PATH)"}
    KB -->|nein| W[Warnung + Lade-Befehl] --> X0
    KB -->|ja| RUN[["kiro-cli chat '/chat load #lt;pfad#gt;'<br/>(inherit, warten)"]] --> XS([Exit-Code von mob start])
```

Kiro direkt starten (R3.5, Spike S10): kmob ruft `kiro-cli chat "/chat load <root>/.kmob/kiro-session.json"` per `runInherit` auf, im aktuellen Verzeichnis. Der Slash-Befehl ist ein einzelnes Argument, Leerzeichen im Pfad werden also nicht von einer Shell zerlegt. Der Exit-Code von Kiro beeinflusst den von kmob nicht. Die Frage erscheint nach der Ausgabe von mob, damit sie nicht in dessen Meldungen untergeht.

Ausgabe (R3.1, absoluter Pfad K1-02):

```
kmob: Kiro session from Anna, handed over 2026-10-29T13:20:00Z: "Refactoring Payment-Service" (78 entries, last activity 2026-10-29T13:18:01Z)
kmob: load it in Kiro with:  /chat load /Users/bob/work/shop/.kmob/kiro-session.json
```

Innerhalb von Kiro (R3.6):

```
kmob: load it in Kiro with:  /chat load /Users/bob/work/shop/.kmob/kiro-session.json
kmob: (kmob runs inside a Kiro session and cannot load it for you – type the command in the chat)
```

Dateien werden nicht verändert (R3.4).

### 6.3 `kmob done [mob-args]`

```mermaid
flowchart TD
    A[Args parsen] --> B{"Git-Root und<br/>WIP-Branch?"}
    B -->|nein| M0[[mob done]] --> XM0([Exit-Code von mob])
    B -->|ja| C{"doneMode = squash?"}
    C -->|nein / UNKNOWN| Q{"--yes / TTY?"}
    Q -->|--yes| D
    Q -->|TTY: y| D
    Q -->|TTY: N / kein TTY| X1([Exit 1])
    C -->|ja| D[".kmob/ → #lt;git-dir#gt;/kmob/done-backup/<br/>(verschieben, R4.1; altes Backup vorher<br/>nach done-backup-#lt;zeit#gt;)"]
    D --> M[[mob done]]
    M -->|≠ 0| RC{"noch WIP-Branch und<br/>.kmob in HEAD?"}
    RC -->|ja| RS["Backup zurück nach .kmob/<br/>Meldung restored"] --> XM([Exit-Code von mob])
    RC -->|nein, N-01| KB["Backup behalten, Pfad nennen<br/>Hinweis: kmob start, dann kmob done"] --> XM
    M -->|0| DEL[alle Backups löschen]
    DEL --> I{".kmob im Index?"}
    I -->|ja| FIX["git rm -r --cached .kmob<br/>+ Verzeichnis löschen, Meldung"] --> X1b([Exit 1])
    I -->|nein| H{".kmob nur in HEAD?"}
    H -->|ja| INF["Info: next commit removes .kmob/<br/>(Rest aus früherem done)"] --> X0
    H -->|nein| X0([Exit 0])
```

- Verschieben statt Löschen (K5-06, R4.5): scheitert `mob done`, bevor es die Löschung committet (z. B. `fetch` ohne Netz), liegt `.kmob` noch in `HEAD` des WIP-Branches. Dann stellt kmob es wieder her; der Working Tree ist danach sauber. Meldung: `kmob: mob done failed – restored .kmob/.`
- Hat mob die Löschung schon committet (typisch: abgelehnter Push bei veraltetem lokalem Stand, Spike S9) oder den Branch gewechselt, würde eine Wiederherstellung untracked Dateien erzeugen, und `mob start` verweigert dann den Start. kmob stellt deshalb **nicht** wieder her (N-01). Meldung: `kmob: mob done failed – .kmob/ was already removed by mob; backup kept at <pfad>. If the push was rejected, run kmob start, then kmob done again.` `kmob start` holt danach den aktuellen Stand inklusive der neueren Session (Spike S9).
- Liegt von einem abgebrochenen Lauf noch ein `done-backup/`, wird es vor dem Verschieben in `done-backup-<UTC-Zeit>/` umbenannt. Nach erfolgreichem `done` löscht kmob alle Backups.
- Squash-Mechanismus (Spike S9): mob committet die Löschung auf dem WIP-Branch, bevor es squasht; im Ergebnis taucht `.kmob/` weder als Zufügen noch als Löschen auf.
- Index/HEAD getrennt (K1-07): ein `.kmob/` aus einem früheren `mob done` (gemischtes Team) liegt in `HEAD`; nach dem Squash ist seine Löschung im Index vorgemerkt → nur Info, kein Fehler.
- `rename` mit `EXDEV` → kopieren, dann löschen.

### 6.4 `kmob version`, `kmob help`, Passthrough

- `version`: `kmob <version>`, `gitleaks <version>` bzw. `gitleaks: not found (built-in secret patterns)`, danach `mob version` (inherit). Exit-Code von mob.
- `help` (auch `--help`, `-h`, ohne Kommando): kmob-Abschnitt (Optionen und Env-Variablen aus 4.1), danach `mob help`.
- Passthrough: `mob <args>` mit identischen Argumenten (R1.1). `<cmd> -h/--help` bei erweiterten Befehlen ebenfalls (R1.8).

## 7. Fehlerbehandlung und Meldungen

| Situation | Meldung (stderr) | Exit |
|---|---|---|
| mob fehlt | `kmob: 'mob' not found in PATH. Install from https://mob.sh or set KMOB_MOB_BIN.` | 1 |
| `KMOB_MOB_BIN` ungültig | `kmob: KMOB_MOB_BIN=<pfad> is not an executable file.` | 1 |
| `KMOB_GITLEAKS_BIN` ungültig | `kmob: KMOB_GITLEAKS_BIN=<pfad> is not an executable file.` | 1 |
| gitleaks fehlt + `--require-gitleaks` | `kmob: gitleaks not found (required by --require-gitleaks). Install: https://github.com/gitleaks/gitleaks` | 1 |
| gitleaks fehlt (einmal je Clone) | `kmob: gitleaks not found – using built-in secret patterns only. Install gitleaks for better detection.` | – |
| Usage | `kmob: <grund>. See kmob help.` | 2 |
| `kiro-cli` fehlt bei `start` | `kmob: warning: kiro-cli not found – start Kiro yourself and load the session.` (danach Lade-Befehl) | mob |
| `--kiro` innerhalb von Kiro / ohne TTY | `kmob: --kiro ignored: <already inside a Kiro session \| no terminal>.` | mob |
| Option beim falschen Befehl | `kmob: --<option> is only valid for <befehl>.` | 2 |
| `.kmob/` ignoriert | `kmob: .kmob/<datei> is ignored by git (<quelle aus check-ignore -v>). mob would not transfer the session. Remove the ignore rule or use --no-session.` | 1 |
| `--session` ungültig | `kmob: session <id> not found` / `matches several sessions: <ids>` / `belongs to <cwd>, not <root>` / `is a subagent session` / `has no log entries` | 1 |
| `--pick-session` ohne TTY | `kmob: --pick-session needs a terminal.` | 1 |
| Picker abgebrochen | `kmob: no session picked – handover aborted.` | 1 |
| `--pick-session` ohne Kandidaten | `kmob: no Kiro session for <root> to pick from.` | 1 |
| kmob-Option vor dem Kommando | `kmob: --<option> must follow the command (e.g. kmob next --<option>).` | 2 |
| `mob done` gescheitert | `kmob: mob done failed – restored .kmob/.` bzw. `kmob: mob done failed – .kmob/ was already removed by mob; backup kept at <pfad>. If the push was rejected, run kmob start, then kmob done again.` | mob |
| Regel 3, neuere Session vorhanden | `kmob: warning: session <id2> "<titel>" is more recent than the current one. Use --session <id2> if you meant that one.` | – |
| keine Session | `kmob: warning: no Kiro session for <root> found[ (<n> session files unreadable)] – handing over code only.` | mob |
| `.kmob/` entfernt (none/`--no-session`) | `kmob: removed previous .kmob/ (session from <from>) – it would be outdated.` | – |
| Erst-Hinweis ohne TTY | `kmob: first handover from this clone needs confirmation. Run in a terminal or pass --yes.` | 1 |
| Secret | `kmob: possible secret (<regel>) in log entry #<n>` bzw. `in session metadata`, danach `kmob: handover aborted. Start a new session (/chat new) or use --no-session; use --allow-secrets only after checking.` | 1 |
| Scan-Fehler | `kmob: secret scan failed: <grund>.` | 1 |
| Export-Fehler | `kmob: could not export session <id>: <grund>. Use --no-session to hand over without it.` | 1 |
| Größe | `kmob: warning: session artifact is <n> MB. GitHub rejects files over 100 MB – consider /chat new.` | – |
| done non-squash/unbekannt | `kmob: done mode <modus|unknown> may keep .kmob/ in the history of the base branch. Continue? [y/N]` (ohne TTY: `… Pass --yes to continue.`) | 1 bei Nein |
| `.kmob/` nach done im Index | `kmob: .kmob/ was staged in the result – removed it from the index. Check with git status before committing.` | 1 |

## 8. Teststrategie (R8.2, R8.3)

**Unit (commonTest, `FakeFileSystem`, Fake-`ProcessRunner`, Fake-`Platform`):**
- Args: Kommando nach führendem `--debug` (K2-01), kmob-Option vor dem Kommando → Usage (N-05), Aliase, Passthrough identisch inkl. `--yes` (K1-10), `--`, `-m --yes`, `--session=<id>`, `<cmd> --help`, Option beim falschen Befehl, Flag vs. Env, Widersprüche gleiche/verschiedene Quelle, Env-Validierung nur je Befehl.
- Pfadvergleich: gleich, Unterverzeichnis, Präfix-Falle, Trailing Slash, Leerzeichen, Symlink.
- SessionStore: Subagent, fehlende/leere `.jsonl`, `.jsonl` nur mit unvollständiger Zeile (kein Kandidat, N-02), kaputtes JSON (skipped-Zähler), `freshness` mit null-Werten.
- Selector: alle Regeln, Präfix eindeutig/mehrdeutig, Gründe der Fehler, Picker (Nummer, Enter, `q`, ungültig → erneut, EOF, 1 Kandidat, ohne TTY), `newer` bei Regel 3.
- Snapshot: unvollständige letzte Zeile, leere Zeilen, ungültige Zeile, Zeilennummer = Eintrag.
- ArtifactWriter: Byte-Gleichheit mit Spike-S1-Format, Reihenfolge der Renames, alter Zustand bei Fehler (R5.4), `EXDEV`-Fallback.
- GitleaksScanner: Config-Generierung (mit/ohne Repo-Config, Pflichtregeln immer enthalten, Sonderzeichen im Pfad), Zusammenfassen doppelter Treffer, Exit 0/99/1, JSON-Parsing nur `RuleID`/`StartLine`.
- BuiltinScanner: Positivfall je Regel; Negativ: `aws_secret_access_key`, `AKIAIOSFODNN7EXAMPLE`, „use a Bearer token“, `Bearer xxxx…`, „github“/„high“ ohne Token.
- Mob: `mob config`-Parsing mit Anführungszeichen, `doneMode` inkl. `UNKNOWN`.
- HandoverInfo: tolerant lesen, `artifact_bytes`-Abgleich.
- Start mit Kiro: alle Zweige aus 6.2 (innerhalb Kiro, `--no-kiro`, Env true/false, kein TTY, Frage Y/Enter/n/EOF, kiro-cli fehlt), Argument genau `["chat", "/chat load <abs>"]`, Exit-Code = mob start.

**Charakterisierung vor Refactorings (R10.4):** Für jeden Ablauf aus §6 gibt es Tests über die öffentliche Kante `App(platform, runner, fs).run(args)` mit Fakes: vollständige stderr-/stdout-Zeilen, Exit-Code, Prozessaufrufe (Reihenfolge und Argumente), Dateien in `.kmob/` und im git-dir (inkl. exaktem Inhalt von `handover.json`). Beim Refactoring dürfen sich nur Konstruktion und Imports der Tests ändern.

**Architektur:** `checkArchitecture` (§3.3) läuft in `./gradlew check`.

**Plattform:** `nativeTest`: `ExecutableSearch` (beide Konventionen), `WindowsCommandLine` (Leerzeichen, `"`, Backslashes vor `"` und am Ende, leere Argumente, Unicode; Roundtrip). `posixTest`: wie bisher (Symlinks, Exit-Codes, Signale, 64-KB-Deadlock). `mingwTest` (nur Windows, CI): `realPath` mit Junction und anderer Schreibweise, `findInPath` mit `PATHEXT`, Argumente per Kindprozess verbatim, Exit-Codes, großer Output auf beiden Pipes, stdin `NUL`, Startfehler.

**E2E (`e2e/run.sh`, echtes mob + gebautes Binary):** Bare-Repo + Clones A/B, Fake-Session-Verzeichnis über `KMOB_KIRO_SESSIONS_DIR`, `MOB_TIMER_LOCAL=false`, `KMOB_YES=true` außer in den Bestätigungstests. Läuft einmal mit gitleaks im `PATH` und einmal ohne.
1. A: `kmob start`, Fixture mit `cwd` = Clone A, `kmob next` **aus einem Unterverzeichnis** → `<root>/.kmob/` im WIP-Branch, Inhalt = erwarteter Export (K1-01).
2. B: `kmob start` → Ausgabe enthält absoluten `/chat load`-Pfad und Titel.
3. B: `kmob next --no-session` → `.kmob/` im WIP-Branch gelöscht (K5-01).
4. A: `kmob start`, `kmob next` mit neuer Fixture, B: `kmob start`, `kmob done` → `git ls-files .kmob` leer, Index enthält Code.
5. `kmob next` auf dem Basis-Branch → kein `.kmob/` (K5-03).
6. Negativ: `.gitignore` mit `.kmob/` → Exit 1, kein WIP-Commit; Secret-Fixture (in Log und in Metadaten) → Exit 1; `kmob done --no-squash` ohne TTY → Exit 1; Erst-Hinweis ohne TTY und ohne `--yes` → Exit 1.
7. `mob done` scheitert:
   a. Fake-mob über `KMOB_MOB_BIN`, das sofort mit 1 endet → `.kmob/` wiederhergestellt, `git status` sauber (R4.5).
   b. Remote-WIP neuer (Spike S9) → kein Restore, Backup-Pfad gemeldet; danach `kmob start` und `kmob done` erfolgreich, Index ohne `.kmob/` (N-01).
10. Secret im Log mit einer Repo-`.gitleaks.toml` ohne `useDefault` → trotzdem Exit 1 (Pflichtregeln, N-03).
11. Worktree: zweiter Worktree desselben Clones → Erst-Hinweis wird nicht erneut verlangt (`git-common-dir`).
8. Signal: `kmob timer 1` bzw. ein Fake-mob, das schläft, mit SIGINT → Kind endet, kmob Exit 130 (K5-02).
9. Passthrough: `kmob status --yes` → mob erhält `--yes` (Fake-mob über `KMOB_MOB_BIN`, protokolliert Argumente).

**Manuell vor Release** (braucht Kiro-Login): echtes `/chat load` des Artefakts mit absolutem Pfad (auch mit Leerzeichen im Pfad), Kontextfrage wie Spike S2; `KIRO_SESSION_ID` nach `/chat load` und `/chat new` (offener Punkt Spike S9).

## 9. Build, CI, Release (R8.1)

- `./gradlew build` baut und testet für den Host; Release-Binaries über `linkReleaseExecutable<Target>`.
- Version aus `gradle.properties` (`version=0.1.0`), per Gradle-Task als `BuildInfo.kt` generiert.
- **CI** (`.github/workflows/ci.yml`, bei Push/PR):
  - `macos-latest`: Tests `macosArm64`, E2E (mob und gitleaks über Homebrew).
  - `ubuntu-latest`: Tests `linuxX64`, E2E (mob über offizielles Install-Skript, gitleaks-Release-Binary; Versionen gepinnt).
  - `windows-latest`: `./gradlew mingwX64Test checkArchitecture` und Release-Binary bauen. E2E unter Windows folgt nach Spike S11.
  - Zusätzlich auf macOS: `linkReleaseExecutableMingwX64` (Cross-Build), damit Windows-Kompilierfehler auch lokal auffallen.
- **Release** (`release.yml`, bei Tag `v*`): Binaries für macOS und Linux als `kmob-<version>-<target>.tar.gz`, Windows als `kmob-<version>-mingwX64.zip` (R9.10), jeweils + SHA-256 an GitHub Release. Das macOS-Binary wird mit „Developer ID Application“ signiert (Hardened Runtime, Zeitstempel, Identifier `de.kmob.kmob`) und notarisiert, damit es nach einem Browser-Download ohne `xattr` startet (K4-06, Entscheidung 2026-10-30). Ein Ticket lässt sich an ein einzelnes Binary nicht anheften; Gatekeeper prüft es beim ersten Start online. Bis zum Release-Workflow lokal mit `scripts/sign-macos.sh` (Schlüsselbund + Notary-Profil `kmob-notary`); im Workflow später über GitHub-Secrets (`.p12` + Passwort, Notary-Zugang) in einem temporären Schlüsselbund.

## 10. Nachverfolgung Requirements → Design

| Requirement | Abschnitt |
|---|---|
| R1.1–R1.4, R1.7, R1.8 | 4.1, 6.4 |
| R1.5, R1.6 | 5.1, 7 |
| R2.1, R2.12, R2.14 | 5.7, 6.1 |
| R2.2, R2.3, R2.11 | 5.5 |
| R2.4, R2.5 | 5.3 |
| R2.6–R2.8 | 5.4, 6.1 |
| R2.9, R2.10, R2.13, R2.15 | 6.1, 7 |
| R3.1–R3.6 | 5.8, 6.2 |
| R4.1–R4.5 | 5.1 (doneMode), 6.3 |
| R5.1 | 5.10 |
| R5.2–R5.4, R5.6–R5.8 | 5.6 |
| R5.5 | Nutzer-Doku |
| R6.1–R6.4 | 4.1, 5.1, 5.3, 5.6 |
| R7.1–R7.3 | 4.1, 5.3, 5.5 |
| R8.1–R8.5 | 6.4, 8, 9 |
| R9.1–R9.10 | 3.2, 5.9, 9 |
| R10.1–R10.5 | 3, 8 |

## 11. Risiken

| Risiko | Auswirkung | Umgang |
|---|---|---|
| Kiro ändert Session-Layout/Exportformat | Sessions werden übersprungen (Warnung mit Anzahl) oder das Artefakt scheitert erst bei `/chat load` | Format zentral in `SessionStore`/`ArtifactWriter`; getestete Kiro-Version in Doku; `--no-session` als Ausweg |
| `KIRO_SESSION_ID` zeigt nach `/chat load` auf alte Session | falsche (kurze) Session übergeben | Sessions ohne Log keine Kandidaten; Warnung bei neuerer Session (5.4); manueller Test vor Release |
| Artefakte > 100 MB | Push abgelehnt, lokaler WIP-Commit enthält Datei | Warnung ab 50 MB; Hinweis mit `git reset --soft` nach fehlgeschlagenem `mob next` |
| Secret-Regeln unvollständig, besonders ohne gitleaks | Secrets im Remote | gitleaks empfohlen, Hinweis bei Fallback, `--require-gitleaks`; Doku: Scan ist kein Garant, gepushte Secrets gelten als kompromittiert |
| gitleaks nur noch Security-Patches (Autor wechselt zu Betterleaks) | Regeln veralten langfristig | Scanner hinter Interface; Wechsel auf kompatiblen Nachfolger ohne Design-Änderung |
| Scan-Dauer bei großen Sessions (~10 s / 130 MB) | langsames `next` | Fortschrittshinweis ab 10 MB |
| Gemischtes Team: jemand nutzt `mob done` | `.kmob/` im Ergebnis | Doku; nächstes `kmob done` meldet bzw. erkennt den Rest (6.3) |
| Windows-Spezifika ungeprüft (Session-Verzeichnis, `cwd`-Format, CRLF, mob unter Windows) | Session nicht gefunden oder Übergabe scheitert | Spike S11 vor Freigabe für Windows; `KMOB_KIRO_SESSIONS_DIR`; Unit-Tests der Plattformschicht in CI |
| Refactoring ändert Verhalten unbemerkt | Regression | Charakterisierungstests vorher (R10.4), E2E unverändert grün |
| mob ändert CLI oder `mob config`-Format | Parser/doneMode falsch | doneMode fail-closed (`UNKNOWN` → Bestätigung); E2E in CI |
