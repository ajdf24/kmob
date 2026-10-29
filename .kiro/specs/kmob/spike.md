# Spike: Kiro-Sessionformat und mob-Verhalten

Datum: 2026-10-29 · Umgebung: macOS, kiro-cli 2.21.1, mob v5.4.2
Nachtrag (S8, S9): gitleaks 8.30.1 und Nachtests aus dem Spec-Audit, gleicher Tag.
Methode: echte Kiro-Sessions per `expect` gesteuert (`/chat save`, `/chat load`), mob gegen lokales Bare-Repo mit zwei Clones (A, B). Alle Test-Sessions wurden danach mit `kiro-cli chat --delete-session` entfernt.

## S1 – Exportformat `kiro-session-export-v1`

Ergebnis von `/chat save` verglichen mit den Dateien in `~/.kiro/sessions/cli/`:

```json
{ "format": "kiro-session-export-v1",
  "metadata":    <Inhalt von {id}.json, unverändert>,
  "log_entries": [ <jede Zeile von {id}.jsonl als JSON-Objekt, in Reihenfolge> ] }
```

- `metadata` ist identisch mit `{id}.json` (inkl. `session_state`). Einzige Abweichung: `updated_at` (Save schreibt die Session vorher neu).
- `log_entries` ist 1:1 der Inhalt von `{id}.jsonl` (`diff` ohne Unterschied).
- Log-Zeilen: `{"version":"v1","kind":"Prompt"|"AssistantMessage"|"ToolResults","data":{…}}`.

**Folgerung:** kmob kann das Artefakt ohne Kiro selbst zusammensetzen; ein Parsen der Log-Einträge ist nicht nötig (rohe Zeilen genügen).

## S2 – Selbstgebautes Artefakt laden

| Test | Ergebnis |
|---|---|
| Aus `.json` + `.jsonl` gebaut, in **anderem Repo/cwd** per `/chat load` geladen | ✅ Kontext vorhanden (Codewort korrekt wiedergegeben) |
| Aus einer **laufenden** Session gebaut (letzter Eintrag: `AssistantMessage` mit offenem `toolUse`, ohne `ToolResults`) | ✅ lädt, Folgefrage korrekt beantwortet |
| Geladene Session | neue UUID, `cwd` = aktuelles Verzeichnis, `created_at` bleibt, neues Feld `imported_from`, komplette Historie |
| Weitergabe einer geladenen Session (Kette A→B→C) | ✅ Log enthält alte + neue Einträge; kann erneut exportiert werden |
| Geladene Session übernimmt `session_state` | ✅ (u. a. Todo-Liste wurde mit übernommen) |

Hinweis: `kiro-cli chat --no-interactive --resume-id …` lädt den Gesprächskontext **nicht** (auch bei der Originalsession). Taugt nicht als Test- oder Ladeweg.

## S3 – Session-Auswahl

- **`KIRO_SESSION_ID`**: Kiro setzt die Variable für alle Kindprozesse. Ruft der Driver `kmob next` über die KI (Shell-Tool) auf, ist die Session eindeutig bekannt.
- **`updated_at` ist bei laufenden Sessions veraltet**: `{id}.json` wurde zuletzt 13:58 geschrieben, `{id}.jsonl` 14:18. → Aktualität = max(`updated_at`, mtime `.jsonl`).
- **`.lock`** existiert während eine Session aktiv ist (zum Testzeitpunkt 4 parallel). Kein Ausschlusskriterium, aber Information.
- **JSONL-Ende**: Datei endet mit `\n`; bei gleichzeitigem Schreiben kann die letzte Zeile unvollständig sein → nur vollständige Zeilen übernehmen.

## S4 – Subagent-Sessions

- `session_created_reason` ist bei **allen** Sessions `"subagent"`, auch bei interaktiven Hauptsessions → **kein** Merkmal.
- Ein echter Subagent legt eine eigene Session an (`cwd` = Repo der Hauptsession, Titel = Subagent-Prompt) mit zusätzlichem Feld **`parent_session_id`** (zeigt auf die Hauptsession) und `agent_name: null`.
- Das Ergebnis des Subagents steht bereits als Tool-Result im Log der Hauptsession.

**Folgerung:** Subagent-Sessions werden ausgeschlossen (`parent_session_id` vorhanden und ≠ null). Sie werden nicht gebraucht, weil ihr Ergebnis in der Hauptsession enthalten ist, sie würden aber sonst als „neueste Session“ gewinnen.

## S5 – Pfade / cwd

- Kiro speichert den **aufgelösten** Pfad: Start in `/tmp/…` → `cwd: /private/tmp/…`.
- `git rev-parse --show-toplevel` liefert ebenfalls den aufgelösten Pfad.
- Pfade können Leerzeichen und `~` enthalten (z. B. `Library/Mobile Documents/com~apple~CloudDocs/…`).
- Sessions im Unterverzeichnis kommen vor (`…/bestdata/bestdata-backend`).

**Folgerung:** Vergleich nur nach `realpath` auf beiden Seiten und segmentweise (`/repo` darf nicht `/repo-other` matchen).

## S6 – mob-Verhalten mit `.kmob/`

| Test | Ergebnis |
|---|---|
| `mob next` mit neuem `.kmob/kiro-session.json` | ✅ wird im WIP-Commit mitgenommen (`git add --all`) |
| `mob start` bei B | ✅ Artefakt liegt im Working Tree |
| `.kmob/` löschen, dann `mob done` (squash) | ✅ Basis-Branch-Index enthält keine `.kmob/`-Dateien |
| `.kmob/` in `.gitignore` | ⚠️ `mob next` committet das Artefakt **stillschweigend nicht** → kmob muss das prüfen |
| `MOB_CLI_NAME=kmob` | ⚠️ ändert auch die WIP-Commit-Message zu `kmob next […]`. mob erkennt WIP-Commits am Präfix (`--squash-wip`) → gemischte Teams (mob + kmob) würden sich stören. **kmob setzt `MOB_CLI_NAME` nicht.** |

## S7 – Größe und Secrets

- Größte lokale `.jsonl`: 132 MB, 117 MB, 99 MB. Treiber sind `ToolResults` (einzelne Einträge bis 13 MB).
  GitHub lehnt Dateien > 100 MB beim Push ab und warnt ab 50 MB. Laut Entscheidung gibt es kein Größenlimit in kmob; das Risiko wird dokumentiert.
- Secret-Muster in 70 lokalen Sessions: `AKIA…` 0, Private Keys 0, `Bearer <token>` 1 Datei, Feldname `aws_secret_access_key` 5 Dateien (Erwähnungen, keine Werte).
  **Folgerung:** nur wertbasierte Muster prüfen, keine reinen Schlüsselnamen (sonst Fehlalarme).

## S8 – gitleaks als Secret-Scanner

Umgebung: gitleaks 8.30.1 (Homebrew, MIT). Testdatei: JSONL mit je einem AWS-Key, GitHub-PAT, RSA-Private-Key, Slack-Bot-Token, `curl -H 'Authorization: Bearer …'`, dem AWS-Doku-Key `AKIAIOSFODNN7EXAMPLE` und einer reinen Erwähnung von `aws_secret_access_key`.

Aufruf: `gitleaks dir <datei> --redact --no-banner -l error -f json -r - --exit-code 99 -c <config>`

| Test | Ergebnis |
|---|---|
| Erkennung (Default-Regeln) | ✅ `aws-access-token`, `github-pat`, `private-key`, `slack-bot-token`, `curl-auth-header`; Doku-Key und reine Schlüsselnamen **kein** Treffer |
| Zeilennummer | `StartLine` = Zeile in der JSONL = Nummer des Log-Eintrags (auch bei mehrzeiligem Private Key, da in JSON escaped) |
| `--redact` | `Secret` und `Match` enthalten `REDACTED`; `Match` kann umgebenden Text enthalten → kmob gibt nur `RuleID` und Zeile aus |
| `stdin` statt `dir` | gleiche Treffer und Zeilen, `File` leer |
| Exit-Codes mit `--exit-code 99` | 0 = sauber, 99 = Treffer, 1 = Fehler (z. B. kaputte Config) → eindeutig unterscheidbar |
| Mehrere Dateien in einem `dir`-Aufruf | ⚠️ unzuverlässig (je nach cwd wird nur ein Teil gescannt) → **ein Aufruf pro Datei** |
| Repo-`.gitleaks.toml` | ⚠️ wird bei `dir <datei>` außerhalb des Repos **nicht** automatisch gefunden. Mit `-c` und generierter Config `[extend] path = "<root>/.gitleaks.toml"` gelten die Team-Regeln (z. B. `disabledRules`) ✅ |
| Repo-Config ohne `[extend] useDefault` | ersetzt die Default-Regeln vollständig (gitleaks-Semantik; Team-Entscheidung) |
| `.gitleaksignore` | wird aus dem **cwd** gelesen (Fingerprint `<pfad>:<regel>:<zeile>`), Pfad ist Session-spezifisch → für kmob praktisch wirkungslos, kein Feature |
| Einfaches `Authorization: Bearer <token>` (ohne `curl`) | ⚠️ von den Default-Regeln **nicht** erkannt → kmob ergänzt eigene Regel `kmob-bearer-token` (`(?i)\bbearer\s+([a-z0-9_\-.=~+/]{20,})`, Entropie ≥ 3.0); erkennt Token, ignoriert „use a Bearer token“ und `xxxx…` |
| Performance | 133 MB: 11 s, 118 MB: 10 s, 100 MB: 8 s (`dir` und `stdin` gleich; M-Serie Mac) |
| Echte Sessions (70) | 0 Treffer mit Default-Regeln; `kmob-bearer-token`: 1 Treffer (die bekannte Datei aus S7) |

**Folgerung:** gitleaks ist der primäre Scanner (≈ 200 gepflegte Regeln, Entropie, Allowlists). kmob scannt `{id}.json` und `{id}.jsonl` getrennt, wertet nur `RuleID`/`StartLine` aus, übergibt immer eine generierte Config (Extend der Repo-Config oder der Defaults + Pflichtregeln `kmob-*`, s. S9). Ohne gitleaks greift ein eingebauter Minimal-Scanner mit denselben Pflichtmustern (R5.2). ~10 s für sehr große Sessions sind akzeptabel; kmob zeigt ab 10 MB einen Fortschrittshinweis.

## S9 – Nachtests aus dem Audit

| Test | Ergebnis |
|---|---|
| `mob next` auf dem Basis-Branch (keine mob-Session) | Exit 0, nur Hinweis „use mob start“; ein vorher geschriebenes `.kmob/` bleibt **untracked** liegen → kmob muss vorher prüfen, ob ein WIP-Branch aktiv ist |
| `mob done` nach lokalem `rm -rf .kmob` | mob führt `git add --all` + `git commit` auf dem WIP-Branch aus, pusht, dann `merge --squash` → Löschung ist committet, Squash-Ergebnis enthält `.kmob/` nicht |
| `mob done` mit veraltetem lokalem WIP-Stand (anderer hat danach gepusht) | Push wird abgelehnt (non-fast-forward), Exit 1, **kein** Merge → Artefakt kann nicht unbemerkt ins Ergebnis gelangen; Abhilfe `mob start` (holt Stand), dann `done` |
| `mob config`-Ausgabe (5.4.2) | Zeilen `KEY=value`, z. B. `MOB_DONE_SQUASH=squash`, `MOB_WIP_BRANCH_PREFIX="mob/"` (Werte teils in Anführungszeichen) |
| Push-Ablehnung (Pre-Receive-Hook simuliert GitHub > 100 MB) | `mob next` Exit 1, lokaler WIP-Commit **enthält** die Datei. Erneutes `mob next` nach `rm -rf .kmob` scheitert ebenfalls (alter Commit wird mitgepusht). Abhilfe: `git reset --soft origin/<wip-branch>` (bzw. `origin/<base>`, wenn der WIP-Branch remote noch nicht existiert), `.kmob/` löschen, erneut übergeben ✅ |
| `kiro-cli chat --list-sessions` | zeigt nur Sessions mit **exakt** dem aktuellen cwd (kein Unterverzeichnis), zusätzlich Sessions eines anderen Formats (`sess_…`, „v3“, unter `~/.kiro/sessions/<hash>/`) → für die Doku ungeeignet; Doku verweist auf `kmob next --pick-session` (zeigt die Kandidaten mit voller ID) |
| `mob help` (5.4.2) | keine Option kollidiert mit kmob-Optionen; Aliase `s`/`n`/`d` bestätigt |
| `mob done` mit veraltetem lokalem WIP-Stand **und** lokal gelöschtem `.kmob/` | mob committet die Löschung lokal (`git add --all`, `commit`), **dann** wird der Push abgelehnt → `.kmob` nicht mehr in HEAD. Wird `.kmob/` jetzt wiederhergestellt, ist es untracked und `mob start` verweigert den Start („discarding uncommitted changes“). **Ohne** Wiederherstellung: `mob start` holt den neuen Stand (inkl. neuerem `.kmob/` von B), danach `done` ✅ → Wiederherstellung nur, solange `.kmob` noch in HEAD liegt |
| gitleaks auf Dateien unter `<git-dir>/kmob/tmp/` mit `-i <tmp>`, aus Repo-Root und Unterverzeichnis | ✅ alle Treffer wie in S8 (Default-Allowlist für `.git` greift nicht auf Dateien darin) |
| Repo-`.gitleaks.toml` **ohne** `[extend] useDefault` | ⚠️ ersetzt alle Default-Regeln, still → kmob hängt seine Pflichtregeln (`kmob-*`) immer an; mit einer Repo-Config, die selbst `useDefault` erweitert, gelten Defaults + Repo-Regeln ✅ |

**Offen:** `KIRO_SESSION_ID` nach `/chat load` bzw. `/chat new` (zeigt die Variable im Shell-Tool dann auf die neue Session?). Automatisierter Test scheiterte am Bestätigungsdialog von `--trust-all-tools`; wird manuell nachgeholt. Risiko, falls die Variable auf die alte ID zeigt: Regel 3 würde die kurze Ausgangssession statt der geladenen wählen. Absicherung im Design unabhängig vom Ergebnis: Sessions ohne Log-Einträge sind keine Kandidaten, und weicht die Session aus `KIRO_SESSION_ID` von der zuletzt aktiven ab, warnt kmob und nennt beide.

**Neu entdeckt:** Neben `~/.kiro/sessions/cli/` existiert ein zweites Session-Format (`~/.kiro/sessions/<hash>/sess_<uuid>/session.json`, Felder `workspacePaths`, `lastModifiedAt`), vermutlich von einer anderen Kiro-Oberfläche. kmob v0.1 unterstützt nur das CLI-Format (`cli/`); in der Doku vermerkt.
