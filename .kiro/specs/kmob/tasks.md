# Tasks: kmob

Status: v1 – basiert auf [requirements.md](requirements.md) v3 und [design.md](design.md) v2

## Vorgehen

Inkrementell in Meilensteinen. Jeder Meilenstein endet mit einem **lauffähigen, nutzbaren Stand**: Build und alle Tests grün, E2E erweitert, README-Status aktualisiert, Commit. Am Ende jedes Meilensteins steht ein **Checkpoint**: Erst nach Review und OK geht es mit dem nächsten Meilenstein weiter.

| Meilenstein | Ergebnis | Nutzbar als |
|---|---|---|
| M0 | Projekt baut, Binary läuft | – |
| M1 | kmob reicht alles an mob durch | Drop-in für `mob` |
| M2 | **MVP**: Session-Übergabe im Happy Path, eingebauter Secret-Scan | erster Teamtest |
| M3 | gitleaks-Scan | sicherer Teamtest |
| M4 | vollständige Session-Auswahl und Optionen | Alltagseinsatz |
| M5 | robustes `next` (Fehlerfälle, Größe, Ignore) | Alltagseinsatz |
| M6 | robustes `done` (Done-Modus, Wiederherstellung) | Alltagseinsatz |
| M7 | vollständiger Parser und Konfiguration | Feature-komplett |
| M8 | CI, Release v0.1.0 | Verteilung |

Konventionen für alle Tasks:
- Jede Komponente bekommt ihre Unit-Tests im selben Task (R8.2). Der Task ist erst erledigt, wenn `./gradlew build` grün ist.
- Verhalten, das nur mit echtem mob prüfbar ist, kommt in `e2e/run.sh` (R8.3).
- Komponenten hängen nur von den Interfaces aus design.md §3 ab (`ProcessRunner`, `Platform`, Okio `FileSystem`). So bleiben Fakes in Tests möglich.
- Was ein Meilenstein bewusst weglässt, steht unter „Grenzen“. Das README beschreibt den tatsächlichen Stand.

---

## M0 – Projekt-Setup

- [x] **0.1 Gradle-Projekt anlegen**
  - Gradle-Wrapper (aktuelle stabile Version, läuft mit dem lokalen JDK 25; lokal `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, solange `xcode-select` auf die Command Line Tools zeigt), Kotlin-Multiplatform-Plugin, Targets `macosArm64`, `linuxX64`, gemeinsames Source-Set `nativeMain` (design §3).
  - `gradle/libs.versions.toml` mit gepinnten Versionen: Kotlin, kotlinx-serialization-json, Okio (inkl. `okio-fakefilesystem` für Tests).
  - Package `de.kmob`, `Main.kt` gibt `kmob <version>` aus.
  - Version aus `gradle.properties`, generierte `BuildInfo.kt` (design §9).
  - `.gitignore` für `build/`, `.gradle/`, `.kotlin/`, `local.properties`.
  - Fertig: `./gradlew build` grün, `build/bin/macosArm64/releaseExecutable/kmob.kexe` gibt die Version aus.
- [x] **0.2 Test-Gerüst**
  - kotlin.test in `commonTest`, ein Beispieltest.
  - `e2e/run.sh`: baut das Binary, legt ein Temp-Verzeichnis mit Bare-Repo und den Clones A und B an, `MOB_TIMER_LOCAL=false`, räumt per `trap` auf. Bricht mit Hinweis ab, wenn `mob` fehlt. Erster Fall: `kmob` gibt die Version aus.
  - Fertig: `./e2e/run.sh` grün.
- [x] **0.3 Minimal-CI**
  - `.github/workflows/ci.yml`: `macos-latest`, `./gradlew build`. Linux und E2E kommen in M8.

**Checkpoint M0:** Struktur und Versionen reviewen.

---

## M1 – Transparenter Wrapper

Ziel: `alias mob=kmob` ändert für niemanden etwas.

- [x] **1.1 Platform (posix)**
  - `env`, `realPath`, `isInteractive`, `readLine`, `findInPath`, `isExecutable`, `homeDir`, `now` (design §3).
  - Tests: `realPath` mit Symlink (`/tmp` → `/private/tmp` auf macOS), `findInPath` mit Temp-`PATH`.
- [x] **1.2 ProcessRunner (posix)** – design §5.9
  - `runInherit` über `posix_spawnp`, SIGINT/SIGQUIT im Kind per `POSIX_SPAWN_SETSIGDEF` auf Default, kmob ignoriert beide nur, solange es wartet. Exit-Code, bei Signal `128 + n`.
  - `runCapture` mit zwei Pipes und `poll`, stdin aus `/dev/null`.
  - Tests: Exit-Codes (`sh -c 'exit 3'`), Ausgabe > 64 KB auf stdout und stderr gleichzeitig (Deadlock-Test), Signal-Exit-Code.
- [x] **1.3 Mob finden und durchreichen** – R1.1–R1.3, R1.5, R1.6
  - `KMOB_MOB_BIN` (ungültig → eigene Meldung) oder `mob` im `PATH`. Fehlt mob → Meldung und Exit 1 (design §7).
  - Minimaler Parser: Kommando = erstes Argument außer `--debug`. `next`/`n`, `start`/`s` und `done`/`d` werden erkannt (in M1 noch ohne Vorlauf), alles geht unverändert an mob.
  - `Output.kt`: Präfix `kmob:`, stderr.
  - Tests: Aliase, Argumente identisch inkl. Reihenfolge, fehlendes mob.
- [x] **1.4 `version` und `help`** – R8.4 (ohne gitleaks), R8.5
  - `version`: `kmob <v>`, danach `mob version`. `help` (auch ohne Kommando, `-h`, `--help`): kurzer kmob-Abschnitt, danach `mob help`.
- [x] **1.5 E2E Passthrough**
  - Fake-mob über `KMOB_MOB_BIN`, das seine Argumente protokolliert: `kmob status --yes -- x` → identische Argumente, gleicher Exit-Code.
  - Echtes mob: `kmob start`, `kmob next`, `kmob done` verhalten sich wie mob.
  - Signal: Fake-mob, das schläft, erhält SIGINT → kmob endet mit 130 (K5-02).

**Grenzen:** noch keine Session-Übergabe.
**Checkpoint M1:** einige Tage als `alias mob=kmob` im Alltag nutzen.

---

## M2 – MVP: Session-Übergabe im Happy Path

Ziel: Ein Team kann die Übergabe ausprobieren. Bewusst schlank, aber **ohne Abstriche bei Sicherheit und Datenschutz**: Secret-Scan, Bestätigung vor dem ersten Push und keine Session-Dateien auf dem Basis-Branch sind von Anfang an dabei.

- [x] **2.1 Git** – design §5.2
  - `root`, `gitDir`, `commonDir`, `currentBranch`, `inIndex`, `inHead`, `removeFromIndex`, `userName`. Alles außer `root` mit `git -C <root>`.
  - Tests mit Fake-`ProcessRunner`.
- [x] **2.2 SessionStore** – R2.4, R2.5, R7.1, R7.2, design §5.3
  - Verzeichnis aus `KMOB_KIRO_SESSIONS_DIR` (R6.2, nötig für E2E), sonst `~/.kiro/sessions/cli`.
  - `*.json` lesen, `SessionInfo`, `freshness`, `hasCompleteEntry`, `skipped`-Zähler. Pfadvergleich `isWithin` (segmentweise, realpath).
  - Tests: Subagent, fehlende/leere/nur unvollständige `.jsonl`, kaputtes JSON, Präfix-Falle `/repo` vs. `/repo-x`, Leerzeichen, Symlink, null-Zeiten.
- [x] **2.3 Auswahl (vereinfacht)** – R2.6.4, R2.7
  - Nur Regel 4 (zuletzt aktiv). Keine Kandidaten → Warnung, nur Code übergeben.
  - Auswahl-Meldung mit voller ID, Titel und Stand (design §6.1).
- [x] **2.4 Snapshot** – R2.2, R2.3, R2.11, design §5.5
  - Kopie nach `<git-dir>/kmob/tmp/`: Metadaten validiert, nur vollständige Zeilen, jede Zeile gültiges JSON. `tmp/` zu Beginn leeren.
  - Tests: unvollständige letzte Zeile, Leerzeilen, ungültige Zeile, Zeilennummer = Eintrag, große Zeile (> 10 MB) mit konstantem Speicher.
- [x] **2.5 MandatoryRules + BuiltinScanner** – R5.2–R5.4, R5.7 (ohne Hinweis), design §5.6
  - Eine Regeldefinition (fünf Regeln mit Vorfilter, Regex und Allowlist), Scan von `meta.json` und `log.jsonl`, Meldung ohne Wert, max. 10 Treffer.
  - Tests: Positivfall je Regel, Negativfälle (`aws_secret_access_key`, `AKIAIOSFODNN7EXAMPLE`, „use a Bearer token“, `Bearer xxxx…`, „github“), Treffer in den Metadaten.
  - Messung: Scan der größten lokalen Session dauert < 15 s. Sonst Vorfilter auf Byte-Ebene (design §11).
    Ergebnis: 138 MB, Release-Build: Snapshot 2,8 s + Scan 1,9 s (`PerfTest`, `KMOB_PERF_SESSION`).
- [x] **2.6 ArtifactWriter + HandoverInfo** – R2.1, R2.12, R3.2, design §5.7, §5.8
  - Streamen nach `tmp/`, Rename-Reihenfolge, `artifact_bytes`. `EXDEV`-Fallback erst in M5. Bis dahin klare Fehlermeldung.
  - Tests: Byte-Gleichheit mit dem Format aus Spike S1, vorhandenes Artefakt bleibt bei Fehler unverändert.
- [x] **2.7 LocalState + Erst-Bestätigung** – R5.1, design §5.10
  - Merker `notice-accepted` im git-common-dir. Mit TTY Frage `[y/N]`, ohne TTY Abbruch mit Hinweis.
  - In M2 ist `--yes` die einzige kmob-Option, damit die KI und E2E-Tests `next` ausführen können (einfache Erkennung, der vollständige Parser kommt in M7).
- [x] **2.8 `kmob next`** – R2.1, R2.15, design §6.1 (Teilmenge)
  - Ablauf: Git-Root → WIP-Branch? (Präfix `mob/` fest, `mob config` erst in M6) → Auswahl → Bestätigung → Snapshot → Scan → Writer → `mob next`.
  - Kein Repo oder kein WIP-Branch → nur durchreichen.
- [x] **2.9 `kmob start`** – R3.1–R3.4, design §6.2
  - Nach erfolgreichem `mob start`: Info-Zeile und `/chat load <absoluter Pfad>`.
- [x] **2.10 `kmob done` (einfach)** – R4.1, R4.2, R4.4
  - `.kmob/` löschen → `mob done` → `.kmob` im Index? → entfernen, melden, Exit 1. Wiederherstellung und Done-Modus kommen in M6.
- [x] **2.11 E2E Happy Path** – design §8 E2E 1, 2, 4, 5
  - A: `kmob next` aus Unterverzeichnis → Artefakt im WIP-Branch, Inhalt wie erwartet. B: `kmob start` zeigt den Pfad. B: `kmob done` → nichts aus `.kmob/` im Index. `kmob next` auf dem Basis-Branch → kein `.kmob/`. Secret-Fixture → Exit 1.
- [~] **2.12 README-Status + Pre-Release** (README erledigt; Tag `v0.1.0-alpha.1` erst nach Freigabe)
  - README: Abschnitt „Stand v0.1.0-alpha“ mit den Grenzen. Tag `v0.1.0-alpha.1`, lokal gebautes Binary für den Teamtest.
- [ ] **2.13 Manueller Test mit echtem Kiro (mit dem Pre-Release-Binary)**
  - Installation genau nach README aus 2.12, dann zwei Clones auf einem Rechner: `kmob next` → `kmob start` → `/chat load` → Kontextfrage beantworten lassen (wie Spike S2).
  - Dabei den offenen Punkt aus Spike S9 klären: `KIRO_SESSION_ID` nach `/chat load` und `/chat new`. Das Ergebnis in spike.md eintragen, es beeinflusst M4.
  - Schlägt der Test fehl: beheben, `v0.1.0-alpha.2` taggen, Test wiederholen. Erst danach geht das Binary ans Team.

**Grenzen (dokumentiert):** nur „zuletzt aktive Session“, nur eingebaute Muster, keine Prüfung auf `.gitignore`, alte `.kmob/` wird bei fehlender Session **nicht** entfernt (kommt in 4.4), kein Hinweis bei abgelehntem Push, `done` ohne Wiederherstellung und ohne Done-Modus-Prüfung.
**Checkpoint M2 (wichtigster):** Teamtest über ca. 1–2 Wochen. Auswerten: Wird die geladene Session genutzt? Wie groß werden die Sessions? Wo hakt es? Danach die Reihenfolge von M3–M7 neu priorisieren.

---

## M3 – gitleaks

- [ ] **3.1 GitleaksScanner** – R5.6, R5.8, design §5.6
  - Config-Generierung (`[extend]` mit Repo-Config oder `useDefault`, Pflichtregeln aus `MandatoryRules` als TOML), Aufruf je Datei, Exit 0/99/sonst, Parsen nur von `RuleID`/`StartLine`, doppelte Treffer zusammenfassen, Fortschrittshinweis ab 10 MB.
  - Tests: TOML-Generierung inkl. Sonderzeichen im Pfad, JSON-Parsing, Exit-Code-Abbildung (Fake-`ProcessRunner`).
- [ ] **3.2 Scanner-Auswahl** – R5.7, R6.4, design §5.6 (Diagramm)
  - `KMOB_GITLEAKS_BIN` oder PATH, sonst Builtin mit einmaligem Hinweis (`gitleaks-hint-shown`). Option `--require-gitleaks` (vorerst einfache Erkennung wie `--yes`).
  - Bestätigungstext nennt den Scanner (design §5.10).
- [ ] **3.3 `--allow-secrets`** – R5.3
- [ ] **3.4 `version` mit gitleaks** – R8.4
- [ ] **3.5 Paritätstest MandatoryRules**
  - Dieselben Fixtures gegen BuiltinScanner (Unit) und gitleaks (E2E) → gleiche Treffer.
- [ ] **3.6 E2E** – design §8 E2E 6, 10
  - Läuft mit und ohne gitleaks im `PATH`. Repo-`.gitleaks.toml` ohne `useDefault` + Secret → trotzdem Exit 1. Kaputte `.gitleaks.toml` → `secret scan failed`, Exit 1.

**Checkpoint M3**

---

## M4 – Session-Auswahl vollständig

Reihenfolge innerhalb des Meilensteins je nach Ergebnis von 2.13.

- [ ] **4.1 `--session <id>`** – R2.6.1, R2.8
  - Volle ID oder eindeutiges Präfix, Präfix-Suche über alle Sessions, Fehlergründe (not found / ambiguous / belongs to / subagent / no log entries).
- [ ] **4.2 `KIRO_SESSION_ID`** – R2.6.3, R2.6 (Warnung `newer`)
- [ ] **4.3 `--pick-session`** – R2.6.2, R2.8, design §5.4
  - Liste mit voller ID und `active`, Eingabe Nummer/Enter/`q`/EOF/ungültig, ohne TTY → Fehler, 0 Kandidaten → Fehler, 1 Kandidat → ohne Frage.
- [ ] **4.4 `--no-session` und „keine Session“ räumen `.kmob/` weg** – R2.9, R2.10
  - Meldung mit dem bisherigen Absender, Anzahl übersprungener Dateien in der Warnung.
- [ ] **4.5 E2E** – design §8 E2E 3
  - `--no-session` → `.kmob/` auf dem WIP-Branch gelöscht. `--session <präfix>` wählt die richtige Session.

**Checkpoint M4**

---

## M5 – Robustes `next`

- [ ] **5.1 Ignore-Prüfung** – R2.13
  - Nach der Auswahl beide Dateien prüfen, die Quelle aus `git check-ignore -v` in der Meldung nennen.
- [ ] **5.2 Größenwarnung** – R2.14
- [ ] **5.3 Hinweis bei fehlgeschlagenem `mob next`** – design §6.1 (K4-01)
  - `<ref>`-Ermittlung (`origin/<wip>` vorhanden?).
  - E2E mit Pre-Receive-Hook wie Spike S9: Hinweis erscheint, und der beschriebene Ausweg funktioniert.
- [ ] **5.4 `EXDEV`-Fallback + `.kmob/.gitignore`** – R2.12, design §5.7 Schritt 4
  - Unit-Test über Fake-FileSystem, das beim Rename `EXDEV` liefert.
- [ ] **5.5 Aufräumen von Resten** – `tmp/` und `.kmob/*.tmp` zu Beginn von `next`.
- [ ] **5.6 E2E Negativfälle** – design §8 E2E 6
  - `.gitignore` mit `.kmob/` → Exit 1, kein WIP-Commit.

**Checkpoint M5**

---

## M6 – Robustes `done`

- [ ] **6.1 `mob config` auswerten** – design §5.1
  - `wipPrefix` (ersetzt das feste `mob/` aus 2.8), `doneMode` inkl. `UNKNOWN`.
  - Tests: Werte mit Anführungszeichen, fehlende Zeilen, Fehler-Exit.
- [ ] **6.2 Bestätigung bei Non-Squash/Unbekannt** – R4.3
- [ ] **6.3 Backup und bedingte Wiederherstellung** – R4.5, design §6.3 (N-01)
  - Verschieben nach `done-backup/`, vorhandenes Backup mit Zeitstempel ablegen, Restore nur auf dem WIP-Branch und wenn `.kmob` in HEAD liegt, sonst Pfad nennen.
- [ ] **6.4 Index und HEAD getrennt bewerten** – R4.4 (K1-07)
- [ ] **6.5 E2E** – design §8 E2E 6, 7a, 7b
  - `--no-squash` ohne TTY → Exit 1. Fake-mob mit Fehler → wiederhergestellt. Veralteter Stand → kein Restore, danach `kmob start` + `kmob done` erfolgreich.

**Checkpoint M6**

---

## M7 – Parser und Konfiguration vollständig

- [ ] **7.1 Parser nach design §4.1**
  - Ersetzt die einfache Erkennung aus M2/M3: Optionen vor dem Kommando (nur `--debug`), `--`, mob-Optionen mit Wert, `--session=<id>`, `<cmd> --help` → Passthrough, Option beim falschen Befehl, Widersprüche.
  - Tests: alle Fälle aus design §8 „Args“.
- [ ] **7.2 Config: Flags vor Env** – R6.1
  - Alle `KMOB_*` inkl. `KMOB_DEBUG`, Validierung nur je Befehl, Quellen-Regel bei Widersprüchen.
- [ ] **7.3 Debug-Ausgaben** – R7.2, R7.3
  - Übersprungene Dateien, Auswahlgründe, ignoriertes `KIRO_SESSION_ID`, Prozessaufrufe.
- [ ] **7.4 `help` vollständig** – R8.5
  - Alle Optionen und Env-Variablen aus §4.1.
- [ ] **7.5 Meldungen abgleichen**
  - Alle Texte aus design §7 einmal in einem Test (Snapshot der Meldungen) festhalten. README-Tabelle „Fehlermeldungen“ dagegen prüfen.
- [ ] **7.6 E2E** – design §8 E2E 9, 11
  - Passthrough mit kmob-Optionen, zweiter Worktree ohne erneute Bestätigung.

**Checkpoint M7:** Feature-Abgleich gegen requirements.md, jedes Akzeptanzkriterium abhaken (Tabelle in design §10).

---

## M8 – CI und Release v0.1.0

- [ ] **8.1 CI vollständig** – R8.1, design §9
  - macOS: Tests `macosArm64`, E2E mit mob und gitleaks (Homebrew). Ubuntu: Tests `linuxX64`, E2E (mob-Install-Skript, gitleaks-Release, Versionen gepinnt). E2E läuft zusätzlich einmal ohne gitleaks.
- [ ] **8.2 Release-Workflow**
  - Tag `v*` → zwei `kmob-<version>-<target>.tar.gz` + SHA-256 als GitHub Release.
- [ ] **8.3 Manuelle Release-Checkliste** – design §8 „Manuell“
  - `/chat load` mit Leerzeichen im Pfad, Kette A→B→C mit drei Personen, Ctrl+C während `kmob next` und während `mob`.
- [ ] **8.4 README finalisieren**
  - Status-Hinweis entfernen, Installationsweg anhand des echten Release-Artefakts prüfen (inkl. `xattr`).
- [ ] **8.5 Release v0.1.0**

---

## Später (nicht v0.1)

Aus der Stabilitätsbewertung, priorisiert nach dem Teamtest in M2:
- Zusammenfassung statt voller Historie ab einer bestimmten Session-Größe (Kontextlimit beim Empfänger).
- Feature-Request bei Kiro: offizieller Export und Import außerhalb des Chats (`kiro-cli chat --export`). Das würde die Abhängigkeit vom internen Format beseitigen.
- Unterstützung des zweiten Kiro-Session-Formats (`sess_*`, Spike S9), falls Kiro dorthin migriert.
- Signierte und notarisierte macOS-Binaries, Homebrew-Tap.
