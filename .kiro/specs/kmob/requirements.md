# Requirements: kmob

Status: v4 – Windows-Unterstützung (R9) und Schichtenarchitektur (R10) ergänzt; v3: Spec-Audit, gitleaks-Spike (S8) und Nachtests (S9) aus [spike.md](spike.md)

## Einleitung

Teams, die mit mob.sh im Mob programmieren, verlieren beim Driver-Wechsel (`mob next` → `mob start`) den Kontext der KI-Session (Kiro CLI): Code wandert über den WIP-Branch, die Konversation bleibt lokal beim bisherigen Driver.

`kmob` ist ein Aufsatz (Wrapper) für mob.sh, geschrieben in Kotlin/Native. Er reicht Befehle an `mob` durch und transportiert dabei die Kiro-Session über den WIP-Branch zum nächsten Driver. mob.sh selbst wird nicht verändert.

## Glossar

- **mob**: das mob.sh-CLI (Go-Binary, getestet mit v5.4.2), muss im `PATH` liegen.
- **Kiro-Session**: von Kiro CLI automatisch gespeicherte Konversation unter `~/.kiro/sessions/cli/`: `{id}.json` (Metadaten inkl. `cwd`, `updated_at`, `title`), `{id}.jsonl` (Log, eine Zeile pro Eintrag), `{id}.lock` (solange aktiv).
- **Subagent-Session**: von Kiro für einen Subagent angelegte Session; erkennbar am Feld `parent_session_id` (Spike S4).
- **Session-Artefakt**: von kmob erzeugte Datei `.kmob/kiro-session.json` im Format `kiro-session-export-v1`, ladbar mit `/chat load`.
- **Handover-Info**: von kmob erzeugte Datei `.kmob/handover.json` mit Kurzinfos (Session-ID, Titel, Stand, Anzahl Einträge, Absender) für die Anzeige bei `kmob start`.
- **Artefakt-Verzeichnis**: `<git-root>/.kmob/`. Alle `.kmob`-Pfade sind relativ zum Git-Root, nie zum aktuellen Verzeichnis. (Nicht `.mob/` – `.mob` ist die Projekt-Konfigurationsdatei von mob.sh.)
- **WIP-Branch**: Branch, dessen Name mit dem mob-Präfix beginnt (`MOB_WIP_BRANCH_PREFIX`, Default `mob/`).
- **Erweiterte Befehle**: `next`, `start`, `done` (inkl. Aliase `n`, `s`, `d`). Alle anderen Befehle sind **Passthrough-Befehle**.
- **Driver**: Person, die gerade tippt und die KI-Session führt.

## Rahmenbedingungen

- Kotlin/Native, Ausgabe als natives Binary ohne JVM-Abhängigkeit.
- Zielplattformen: macOS arm64, Linux x64, **Windows x64** (Windows 10/11, R9). **Kein Intel-Mac** (Kotlin/Native-Target `macosX64` ist deprecated, im Team nicht genutzt), kein Windows ARM64.
- Keine Änderung an mob.sh; Integration ausschließlich über Aufruf des `mob`-Binaries, von `git` und optional `gitleaks`.
- Keine Netzwerkzugriffe durch kmob selbst (Transport ausschließlich über Git via mob).
- Kompatibel getestet mit kiro-cli 2.21.1, mob 5.4.2 und gitleaks 8.30.1.

## Requirements

### R1 – Transparentes Durchreichen an mob

**User Story:** Als Teammitglied möchte ich `kmob` wie `mob` benutzen, damit ich keinen neuen Workflow lernen muss.

1. WENN ein Passthrough-Befehl aufgerufen wird (z. B. `status`, `timer`, `reset`, `branch`, `config`), DANN SOLL kmob `mob` mit **identischen** Argumenten aufrufen. kmob-Optionen und `KMOB_*`-Variablen (außer `KMOB_MOB_BIN`) werden dabei weder ausgewertet noch entfernt.
2. kmob SOLL stdin, stdout und stderr von `mob` unverändert durchreichen (inkl. interaktiver Ausgaben).
3. kmob SOLL mit dem Exit-Code von `mob` beenden.
4. kmob SOLL die mob-Aliase `s`, `n`, `d` wie `start`, `next`, `done` behandeln.
5. WENN `mob` nicht gefunden wird, DANN SOLL kmob mit verständlicher Fehlermeldung und Exit-Code ≠ 0 abbrechen; ein ungültiger `KMOB_MOB_BIN` SOLL eine eigene Meldung erhalten.
6. kmob SOLL die mob-Konfiguration nicht verändern, insbesondere `MOB_CLI_NAME` nicht setzen (Spike S6).
7. Bei erweiterten Befehlen SOLL kmob seine eigenen Optionen (R2, R4, R5) vor der Übergabe an `mob` entfernen; alle übrigen Argumente gehen in unveränderter Reihenfolge an `mob`. Argumente nach `--` SOLLEN nicht als kmob-Optionen interpretiert werden. Optionswerte von mob-Optionen (z. B. `--message <text>`) SOLLEN nicht als kmob-Optionen interpretiert werden.
8. WENN einem erweiterten Befehl `-h` oder `--help` folgt, DANN SOLL kmob ohne Vorlauf an `mob` durchreichen.

### R2 – Session übergeben bei `kmob next`

**User Story:** Als Driver möchte ich, dass bei `kmob next` meine KI-Session automatisch mitgegeben wird, damit der nächste Driver mit vollem Kontext weiterarbeiten kann.

1. WENN `kmob next` ausgeführt wird, DANN SOLL kmob vor dem Aufruf von `mob next` das Session-Artefakt und die Handover-Info nach `<git-root>/.kmob/` schreiben.
2. Das Session-Artefakt SOLL aus `{id}.json` (als `metadata`, unverändert) und den Zeilen von `{id}.jsonl` (als `log_entries`, unverändert, in Reihenfolge) bestehen (Spike S1).
3. kmob SOLL nur vollständige JSONL-Zeilen übernehmen; eine unvollständige letzte Zeile (laufende Session) SOLL verworfen werden (Spike S3).
4. Kandidaten SIND Sessions, deren `cwd` dem Git-Root oder einem Unterverzeichnis davon entspricht und die mindestens einen vollständigen Log-Eintrag haben. Der Vergleich SOLL auf aufgelösten Pfaden (Symlinks, z. B. `/tmp` → `/private/tmp`) und segmentweise erfolgen (`/repo` matcht nicht `/repo-x`) und Pfade mit Leerzeichen und Sonderzeichen unterstützen (Spike S5).
5. Subagent-Sessions (Feld `parent_session_id` vorhanden und ≠ null) SOLLEN keine Kandidaten sein (Spike S4).
6. Die Session SOLL in dieser Reihenfolge bestimmt werden:
   1. explizit per Option `--session <id>` (volle ID oder eindeutiges Präfix);
   2. interaktiv per Option `--pick-session` (nummerierte Liste der Kandidaten mit Titel, Stand und voller ID);
   3. die Session aus `KIRO_SESSION_ID`, wenn sie ein Kandidat ist (Aufruf aus der KI heraus, Spike S3);
   4. sonst der Kandidat mit der jüngsten Aktualität = max(`updated_at`, Änderungszeit von `{id}.jsonl`) (Spike S3).
   WENN nach Regel 3 gewählt wird und ein anderer Kandidat aktueller ist, DANN SOLL kmob warnen und beide Sessions nennen (Spike S9, offener Punkt `/chat load`).
7. kmob SOLL die gewählte Session vor dem Handover ausgeben (volle ID, Titel, Stand) und angeben, nach welcher Regel aus R2.6 sie gewählt wurde. WENN nach Regel 3 oder 4 gewählt wurde und es mehr als einen Kandidaten gab, SOLL der Hinweis auf `--pick-session` erscheinen.
8. WENN eine per `--session` angegebene Session nicht existiert, kein Kandidat ist, keine Log-Einträge hat oder das Präfix mehrdeutig ist, ODER WENN `--pick-session` angegeben ist und es keine Kandidaten gibt, DANN SOLL kmob mit einer Meldung, die den Grund nennt, abbrechen, ohne `mob next` auszuführen.
9. Option `--no-session` SOLL die Übergabe für diesen Aufruf überspringen; ein vorhandenes `.kmob/` SOLL dabei entfernt werden, damit keine veraltete Session weitergegeben wird.
10. WENN keine passende Session gefunden wird, DANN SOLL kmob eine Warnung ausgeben, ein vorhandenes `.kmob/` entfernen und `mob next` trotzdem ausführen. Wurden Session-Dateien wegen Lesefehlern übersprungen, SOLL die Warnung deren Anzahl nennen.
11. WENN das Erzeugen des Artefakts fehlschlägt, DANN SOLL kmob `mob next` NICHT ausführen und mit Fehler abbrechen. Mit `--no-session` kann der Driver trotzdem übergeben.
12. Vorhandene Dateien in `.kmob/` SOLLEN überschrieben werden; das Schreiben SOLL atomar erfolgen (temporäre Datei außerhalb des Working Trees + Umbenennen; liegt das Git-Verzeichnis auf einem anderen Dateisystem, eine temporäre Datei unter `.kmob/`, die per `.kmob/.gitignore` von Git ignoriert wird), damit nie ein halbes Artefakt committet wird. Artefakt und Handover-Info SOLLEN nie in inkonsistentem Zustand (alte Info zu neuem Artefakt) zurückbleiben.
13. WENN eine Session gewählt wurde und `.kmob/kiro-session.json` oder `.kmob/handover.json` von Git ignoriert wird, DANN SOLL kmob mit Fehler abbrechen und erklären, dass mob das Artefakt sonst stillschweigend nicht überträgt (Spike S6).
14. Es gibt kein Größenlimit für das Artefakt. Ab 50 MB SOLL kmob warnen (GitHub lehnt Dateien > 100 MB ab).
15. WENN der aktuelle Branch kein WIP-Branch ist, DANN SOLL kmob ohne Vorlauf an `mob next` durchreichen (Spike S9: mob gibt dann nur einen Hinweis aus, ein `.kmob/` bliebe untracked liegen).

### R3 – Session übernehmen bei `kmob start`

**User Story:** Als nächster Driver möchte ich nach `kmob start` sofort sehen, wie ich die Session des Vorgängers lade.

1. WENN `mob start` erfolgreich war (Exit-Code 0) UND `<git-root>/.kmob/kiro-session.json` existiert, DANN SOLL kmob ausgeben: Titel, Stand, Anzahl Log-Einträge, Absender (Git-`user.name` des vorherigen Drivers), Zeitpunkt der Übergabe und den Befehl `/chat load <git-root>/.kmob/kiro-session.json` mit **absolutem** Pfad (funktioniert auch, wenn Kiro in einem Unterverzeichnis läuft).
2. Die Angaben aus R3.1 SOLLEN aus der Handover-Info gelesen werden; WENN sie fehlt, unlesbar ist oder nicht zum Artefakt passt (Größe des Artefakts weicht ab), SOLL nur der Lade-Befehl ausgegeben werden.
3. WENN kein Artefakt existiert oder `mob start` fehlschlug, DANN SOLL kmob nichts zusätzlich ausgeben.
4. kmob SOLL das Artefakt beim Start nicht verändern oder löschen.

5. WENN `kmob start` außerhalb einer Kiro-Session läuft (`KIRO_SESSION_ID` nicht gesetzt), `mob start` erfolgreich war und ein Artefakt existiert, DANN SOLL kmob anbieten, Kiro direkt mit der übergebenen Session zu starten: `kiro-cli chat "/chat load <absoluter pfad>"` (Spike S10).
   - Mit Terminal fragt kmob `Start Kiro with this session? [Y/n]` (Enter = ja).
   - Option `--kiro` bzw. `KMOB_START_KIRO=true` startet ohne Frage; `--no-kiro` bzw. `KMOB_START_KIRO=false` verhindert den Start. `--kiro` und `--no-kiro` zusammen sind ein Usage-Fehler.
   - Ohne Terminal startet kmob Kiro nicht (auch nicht mit `--kiro`) und gibt nur den Lade-Befehl aus.
   - kmob wartet, bis Kiro beendet ist, und endet mit dem Exit-Code von `mob start`.
   - WENN `kiro-cli` nicht gefunden wird, DANN SOLL kmob warnen und nur den Lade-Befehl ausgeben (kein Fehler).
6. WENN `kmob start` innerhalb einer Kiro-Session läuft, DANN SOLL kmob Kiro nicht starten, sondern den Lade-Befehl ausgeben und darauf hinweisen, dass er im Chat eingegeben werden muss. Ein `--kiro` wird dann mit Hinweis ignoriert.

Hinweis: In eine **laufende** Kiro-Session kann kmob nicht laden, da `/chat load` ein In-Chat-Befehl ist und Kiro keine Schnittstelle dafür bietet (Spike S10). Nach dem Laden entsteht lokal eine neue Session mit voller Historie; diese wird beim nächsten `kmob next` weitergegeben (Spike S2, Kette A→B→C).

### R4 – Aufräumen bei `kmob done`

**User Story:** Als Team möchten wir, dass Session-Artefakte nie im Ergebnis auf dem Basis-Branch landen.

1. WENN `kmob done` ausgeführt wird, DANN SOLL kmob vor dem Aufruf von `mob done` das Verzeichnis `<git-root>/.kmob/` aus dem Working Tree entfernen.
2. WENN `mob done` im Squash-Modus läuft (Default), DANN SOLL das Ergebnis auf dem Basis-Branch keine Dateien aus `.kmob/` enthalten (Spike S6, S9).
3. WENN der effektive Done-Modus `--no-squash` oder `--squash-wip` ist (Argument oder `MOB_DONE_SQUASH`) **oder nicht ermittelt werden kann**, DANN SOLL kmob warnen, dass Session-Artefakte in der Commit-Historie des Basis-Branches verbleiben können, und eine Bestätigung verlangen. Option `--yes` überspringt die Bestätigung; ohne Terminal (kein TTY) und ohne `--yes` SOLL kmob abbrechen.
4. kmob SOLL nach erfolgreichem `mob done` prüfen, ob Pfade unter `.kmob/` im Index liegen. WENN ja, DANN SOLL kmob sie aus Index und Working Tree entfernen, dies melden und mit Exit-Code ≠ 0 enden. Liegt `.kmob/` nur noch in `HEAD` und ist im Index bereits gelöscht (Rest eines früheren `mob done`), SOLL kmob nur informieren, dass der nächste Commit es entfernt.
5. WENN `mob done` fehlschlägt und `.kmob/` noch in `HEAD` des WIP-Branches liegt (Löschung nicht committet), DANN SOLL kmob `.kmob/` wiederherstellen. Andernfalls (mob hat die Löschung bereits committet, Spike S9) SOLL kmob nicht wiederherstellen, die Sicherung behalten und deren Pfad nennen; der Weg `kmob start` → `kmob done` SOLL dann funktionieren.

### R5 – Sicherheit und Datenschutz

**User Story:** Als Team möchten wir nicht versehentlich Secrets über den WIP-Branch ins Remote pushen.

1. Beim ersten `kmob next` mit Session je lokalem Clone (gemeinsam für alle Worktrees) SOLL kmob **vor** dem Push darauf hinweisen, dass die Session (inkl. Tool-Outputs) ins Remote gepusht wird, und eine Bestätigung verlangen. `--yes` gilt als Bestätigung; ohne TTY und ohne `--yes` SOLL kmob abbrechen. Erst nach Bestätigung SOLL der Merker gesetzt werden; er liegt lokal im Git-Verzeichnis (nicht committet).
2. kmob SOLL Session-Metadaten (`{id}.json`) und Log (`{id}.jsonl`) vor dem Schreiben des Artefakts auf Secrets prüfen und bei Treffern mit Angabe von Regel und Fundort (`metadata` bzw. Log-Eintrag-Nummer), ohne den Wert auszugeben, abbrechen. Mindestens erkannt werden SOLLEN: AWS Access Key IDs, Private-Key-Blöcke, `Bearer`-Tokens, GitHub-Tokens, Slack-Tokens. Reine Schlüsselnamen (z. B. `aws_secret_access_key`) und bekannte Dokumentations-Beispielschlüssel SOLLEN keinen Treffer auslösen (Spike S7, S8).
3. Option `--allow-secrets` SOLL den Abbruch aus R5.2 übersteuern; die Treffer werden dann als Warnung ausgegeben.
4. Bei Abbruch nach R5.2 SOLL kein Artefakt in `.kmob/` zurückbleiben bzw. ein vorher vorhandenes unverändert bleiben.
5. kmob SOLL lokale Pfade nicht umschreiben oder anonymisieren (Out of Scope v1); die Doku SOLL darauf hinweisen.
6. WENN `gitleaks` verfügbar ist (`KMOB_GITLEAKS_BIN` oder `PATH`), DANN SOLL kmob damit scannen (Spike S8). Eine `.gitleaks.toml` im Git-Root SOLL dabei gelten; die Mindestmuster aus R5.2 SOLL kmob immer als eigene Regeln ergänzen, damit sie auch gelten, wenn die Repo-Config die Default-Regeln ersetzt (Spike S9).
7. WENN `gitleaks` nicht verfügbar ist, DANN SOLL kmob einen eingebauten Scanner mit den Mustern aus R5.2 verwenden und einmal je Clone auf die Installation von gitleaks hinweisen. Mit `--require-gitleaks` SOLL kmob stattdessen abbrechen.
8. WENN `gitleaks` mit einem Fehler endet (z. B. ungültige `.gitleaks.toml`), DANN SOLL kmob abbrechen (kein stiller Fallback).

### R6 – Konfiguration

1. Jede kmob-Option SOLL zusätzlich über eine Umgebungsvariable `KMOB_*` setzbar sein (inkl. `KMOB_DEBUG`); CLI-Flags haben Vorrang. Umgebungsvariablen SOLLEN nur für Befehle ausgewertet und validiert werden, auf die sie wirken.
2. Der Pfad zum Kiro-Session-Verzeichnis SOLL konfigurierbar sein (`KMOB_KIRO_SESSIONS_DIR`, Default `~/.kiro/sessions/cli/`).
3. Der Pfad zum `mob`-Binary SOLL konfigurierbar sein (`KMOB_MOB_BIN`, Default: Suche im `PATH`).
4. Der Pfad zum `gitleaks`-Binary SOLL konfigurierbar sein (`KMOB_GITLEAKS_BIN`, Default: Suche im `PATH`).
5. Der Pfad zum `kiro-cli`-Binary SOLL konfigurierbar sein (`KMOB_KIRO_BIN`, Default: Suche im `PATH`).

### R7 – Robustheit gegenüber dem Kiro-Format

1. kmob SOLL unbekannte Felder in Session-Dateien tolerieren und unverändert übernehmen.
2. WENN eine Session-Datei nicht lesbar oder nicht parsebar ist, DANN SOLL kmob sie bei der Kandidatensuche überspringen, dies im Debug-Modus ausgeben und die Anzahl in der Warnung nach R2.10 nennen.
3. kmob SOLL ein `--debug`-Flag (vor oder nach dem Befehl) unterstützen; es wird zusätzlich an `mob` weitergereicht und SOLL die Erkennung des Befehls nicht beeinflussen. Andere kmob-Optionen SOLLEN nach dem Befehl stehen; davor sind sie ein Usage-Fehler.

### R8 – Verteilung und Qualität

1. Der Build SOLL Binaries für alle Zielplattformen erzeugen (CI: GitHub Actions, macOS-Runner für macOS-Targets).
2. Kernlogik (Session-Suche und -Auswahl, Pfadvergleich, Export, Secret-Scan, Argument-Parsing) SOLL mit Unit-Tests abgedeckt sein.
3. Das End-to-End-Verhalten (`next` → `start` → `done`, inkl. Aufruf aus Unterverzeichnis) SOLL mit einem Test gegen ein lokales Bare-Repo und das echte `mob` geprüft werden.
4. `kmob version` SOLL die eigene Version und die Ausgabe von `mob version` ausgeben, dazu die gitleaks-Version oder „gitleaks not found“.
5. `kmob help` SOLL die kmob-Optionen beschreiben und anschließend die Hilfe von `mob` ausgeben.

### R9 – Windows

**User Story:** Als Teammitglied unter Windows möchte ich kmob genauso nutzen wie meine Kolleg:innen unter macOS und Linux.

1. kmob SOLL unter Windows 10/11 x64 (PowerShell, cmd, Windows Terminal) dasselbe Verhalten zeigen wie unter macOS und Linux (R1–R8), soweit dieser Abschnitt nichts anderes festlegt. Unter WSL gilt das Linux-Binary.
2. Programmsuche (mob, git, gitleaks, kiro-cli): `PATH` mit `;` getrennt, Endungen laut `PATHEXT`, davon nur `.exe` und `.com`. Das aktuelle Verzeichnis SOLL nicht durchsucht werden (anders als `CreateProcess`), damit kein Programm aus dem Repo gestartet wird. `KMOB_*_BIN` SOLL auf eine `.exe`/`.com` zeigen; `.cmd`/`.bat` werden nicht unterstützt.
3. Argumente SOLLEN unverändert beim Kindprozess ankommen (R1.1), auch mit Leerzeichen, Anführungszeichen, Backslashes, leeren Argumenten und Nicht-ASCII-Zeichen (Quoting nach den Regeln der Microsoft-C-Runtime / `CommandLineToArgvW`).
4. Pfade mit Laufwerksbuchstaben, `\` oder `/` als Trenner und UNC-Pfade SOLLEN unterstützt werden. Der Pfadvergleich aus R2.4 SOLL auf vom Betriebssystem aufgelösten Pfaden erfolgen (Symlinks, Junctions, kanonische Groß-/Kleinschreibung).
5. Home-Verzeichnis ist `%USERPROFILE%`; das Default-Session-Verzeichnis ist damit `%USERPROFILE%\.kiro\sessions\cli` (zu bestätigen, Spike S11). `KMOB_KIRO_SESSIONS_DIR` gilt wie auf den anderen Plattformen.
6. Interaktiv (R5.1, R2.6.2, R3.5, R4.3) ist kmob, wenn stdin und stderr Konsolen sind. In Terminals ohne Windows-Konsole (z. B. mintty/Git Bash ohne winpty) gilt kmob als nicht interaktiv; dann gelten die Regeln „ohne Terminal“ (`--yes`, Lade-Befehl).
7. Meldungen SOLLEN in der Konsole unabhängig von der eingestellten Codepage korrekt erscheinen (z. B. `–`, Umlaute). Umgeleitete Ausgabe ist UTF-8.
8. Ctrl+C während `mob` oder `kiro-cli` läuft SOLL das Kind beenden; kmob wartet und endet mit dessen Exit-Code (unverändert, z. B. `0xC000013A`).
9. Umgebungsvariablen, Pfade und Argumente mit Nicht-ASCII-Zeichen SOLLEN korrekt verarbeitet werden (Unicode-APIs).
10. Das Release SOLL `kmob.exe` als `kmob-<version>-mingwX64.zip` enthalten.

### R10 – Architektur und Wartbarkeit

**User Story:** Als Entwickler:in möchte ich den Code schnell verstehen und gefahrlos ändern können, und neue Plattformen sollen den fachlichen Kern nicht berühren.

1. Der Code SOLL in Schichten gegliedert sein: Domäne, Anwendung (Use Cases und Ports), Adapter, CLI und Plattform (design §3). Abhängigkeiten zeigen nur nach innen zur Domäne.
2. Alle Schichten außer der Plattform-Implementierung („Kern“, Source-Set `commonMain`) SOLLEN frei von Betriebssystem-Logik sein: keine Plattform-APIs (POSIX, Win32, cinterop), keine Logik zu Pfadtrennern, `PATH`, Home-Verzeichnis, Terminal oder Signalen und keine Fallunterscheidung nach Betriebssystem. Der Kern erreicht das Betriebssystem nur über die Ports `Platform` und `ProcessRunner`.
3. Die Regeln aus R10.1 und R10.2 SOLLEN im Build automatisch geprüft werden (`./gradlew checkArchitecture`, Teil von `./gradlew check`).
4. Ein Refactoring SOLL das fachliche Verhalten nicht ändern. Vorher SOLLEN Charakterisierungstests das bestehende Verhalten (Ausgaben, Exit-Codes, Dateien, Prozessaufrufe) festhalten; ihre Assertions bleiben beim Refactoring unverändert, nur der Aufbau der Testobjekte darf sich anpassen.
5. Der Code SOLL den Clean-Code-Regeln aus design §3.4 folgen.

## Out of Scope (v1)

- Windows ARM64; `.cmd`/`.bat` als mob, git, gitleaks oder kiro-cli (R9.2).
- Eigene E2E-Läufe unter Windows (Unit-Tests der Windows-Plattformschicht laufen in CI, E2E folgt nach Spike S11).
- Andere KI-Tools als Kiro CLI (Architektur soll Erweiterung aber nicht verbauen).
- Das zweite Kiro-Session-Format unter `~/.kiro/sessions/<hash>/sess_*/` (Spike S9).
- Automatisches Laden der Session in eine bereits laufende Kiro-Session.
- Zusammenführen paralleler Sessions mehrerer Personen.
- Übergabe-Notiz (`handoff.md`) – möglicher späterer Ausbau.
- Größenlimits, Kompression oder Kürzen von Sessions.
- Eigene Allowlist/Ignore-Mechanik für Secret-Treffer (Team nutzt `.gitleaks.toml`).
- Mitliefern von gitleaks im kmob-Release.
- Änderungen an mob.sh oder Upstream-Beiträge.

## Entscheidungen aus dem Review

| Frage | Entscheidung |
|---|---|
| Windows | ja, x64 (Entscheidung 2026-10-30, R9); vorher „nein“ |
| Intel-Mac (`macosX64`) | nein (deprecated in Kotlin 2.4, nicht genutzt) |
| Exportformat | per Spike verifiziert (S1, S2) |
| Fortgeführte Session weitergeben | ja (S2) |
| Größenlimit | nein; Warnung ab 50 MB (S7, S9) |
| Sessions außerhalb des Repos | nicht unterstützt; Pfadvergleich robust (S5) |
| Subagent-Sessions | ausschließen (S4) |
| Altes `.kmob/` bei `--no-session` / keiner Session (Audit K5-01) | löschen |
| Erst-Hinweis (Audit K1-08) | Bestätigung vor dem ersten Push, je Clone |
| kmob-Optionen bei Passthrough-Befehlen (Audit K1-10) | nicht auswerten |
| Secret-Scanner | gitleaks, falls vorhanden; sonst eingebauter Minimal-Scanner (S8) |
| Architektur | Schichten mit OS-freiem Kern, Plattformschicht je Betriebssystem, im Build geprüft (R10) |
| Kiro direkt starten bei `kmob start` | ja, außerhalb von Kiro mit Rückfrage `[Y/n]`; `--kiro`/`--no-kiro`, `KMOB_START_KIRO` (S10) |
