# kmob – mob.sh mit KI-Kontext

`kmob` ist ein Aufsatz für [mob.sh](https://mob.sh). Beim Driver-Wechsel gibt er neben dem Code auch die **Kiro-CLI-Session** an den nächsten Driver weiter. So kann die nächste Person mit dem kompletten KI-Kontext weiterarbeiten.

```
kmob next   →  Session prüfen, mit dem Code auf den WIP-Branch
kmob start  →  Code holen, Hinweis zum Laden der Session
kmob done   →  Session-Dateien entfernen, Code squashen
```

mob.sh selbst wird nicht verändert. Alle anderen Befehle (`status`, `timer`, `reset`, …) reicht kmob unverändert an `mob` weiter.

> Status: in Entwicklung. Diese Doku beschreibt das Zielverhalten von v0.1 laut [Spec](.kiro/specs/kmob/).

### Stand v0.1.0-alpha (MVP)

Die Alpha kann bereits übergeben, übernehmen und aufräumen (`kmob next`/`start`/`done`), mit eingebautem Secret-Scan und Bestätigung vor dem ersten Push. Was in dieser Doku beschrieben ist, aber noch fehlt:

- Session-Auswahl: nur „zuletzt aktive Session“. `--session`, `--pick-session`, `--no-session` und `KIRO_SESSION_ID` kommen später.
- Secret-Scan: nur die eingebauten Grundmuster. gitleaks, `--allow-secrets` und `--require-gitleaks` sind noch nicht eingebaut.
- Findet kmob keine Session, bleibt eine alte `.kmob/` aus einer früheren Runde vorerst liegen und wird weitergegeben.
- Es gibt keine Prüfung auf ignoriertes `.kmob/`, keine Größenwarnung und keinen Hinweis bei abgelehntem Push.
- `kmob done`: Der Done-Modus (`--no-squash`, `--squash-wip`) wird nicht geprüft, und nach einem fehlgeschlagenen `mob done` wird nichts wiederhergestellt.
- Als Optionen gibt es nur `--yes` und `--debug` (bzw. `KMOB_YES`/`KMOB_DEBUG`). Unterstützt wird nur macOS (Apple Silicon); das Linux-Binary ist noch ungetestet.

Für den Teamtest: Binary mit `./gradlew linkReleaseExecutableMacosArm64` bauen (Ergebnis `build/bin/macosArm64/releaseExecutable/kmob.kexe`) und als `kmob` in den `PATH` legen.

## Voraussetzungen

- macOS (Apple Silicon) oder Linux x64. Intel-Macs und Windows werden nicht unterstützt.
- [mob](https://mob.sh) im `PATH` (getestet mit 5.4.2)
- [Kiro CLI](https://kiro.dev) (getestet mit 2.21.1)
- git
- empfohlen: [gitleaks](https://github.com/gitleaks/gitleaks) im `PATH` (getestet mit 8.30.1) für einen gründlichen Secret-Scan, z. B. `brew install gitleaks`

## Installation

Lade das Binary für deine Plattform von den [GitHub Releases](https://github.com/ajdf24/kmob/releases) herunter und lege es in deinen `PATH`:

```bash
tar -xzf kmob-<version>-macosArm64.tar.gz
xattr -d com.apple.quarantine kmob   # nur macOS: Binary ist nicht signiert
mv kmob /usr/local/bin/
kmob version
```

`kmob version` zeigt die eigene Version sowie die von gitleaks und mob. `kmob help` beschreibt die kmob-Optionen und zeigt danach die Hilfe von mob.

## Arbeitsablauf

### 1. Session starten

```bash
kmob start            # wie mob start, z. B. auch: kmob start 10
kiro-cli chat         # im Repo (oder einem Unterverzeichnis) starten
```

### 2. Übergeben

```bash
kmob next
```

kmob sucht deine Kiro-Session zu diesem Repo, prüft sie auf Secrets und schreibt sie nach `.kmob/kiro-session.json` im Git-Root. Danach ruft es `mob next` auf, und die Session landet mit dem Code im WIP-Commit:

```
kmob: handing over Kiro session 7e6dd8a5-…-0c2d "Refactoring Payment-Service" (last activity 2026-10-29T13:18:01Z; most recent, 2 sessions found – use --pick-session to choose)
…Ausgabe von mob next…
```

Beim ersten `kmob next` in einem Clone fragt kmob vorher nach, ob die Session wirklich ins Remote gepusht werden soll (siehe [Sicherheit](#sicherheit-und-datenschutz)).

Du kannst `kmob next` auch von der KI ausführen lassen („führe kmob next aus“). Dann nimmt kmob in der Regel die Session, aus der der Befehl kommt. Ist eine andere Session im Repo aktueller, warnt kmob und nennt deren ID. Im Zweifel `--session <id>` angeben.

`kmob next` ohne laufende mob-Session (z. B. auf dem Basis-Branch) reicht nur an mob durch und schreibt nichts.

### 3. Übernehmen

```bash
kmob start
```

```
…Ausgabe von mob start…
kmob: Kiro session from Anna, handed over 2026-10-29T13:20:00Z: "Refactoring Payment-Service" (78 entries, last activity 2026-10-29T13:18:01Z)
kmob: load it in Kiro with:  /chat load /Users/bob/work/shop/.kmob/kiro-session.json
```

Lade die Session dann in Kiro. Der Pfad ist absolut, der Befehl funktioniert also auch, wenn Kiro in einem Unterverzeichnis läuft:

```
kiro-cli chat
> /chat load /Users/bob/work/shop/.kmob/kiro-session.json
```

Kiro legt daraus eine neue lokale Session mit der kompletten Historie an. Wenn du später `kmob next` ausführst, gibt kmob diese Session inklusive deiner neuen Nachrichten weiter.

### 4. Abschließen

```bash
kmob done
git commit
```

kmob entfernt `.kmob/` und ruft dann `mob done` auf. Im Ergebnis auf dem Basis-Branch landen keine Session-Dateien. Den Remote-WIP-Branch entfernt `mob done` wie gewohnt.

Schlägt `mob done` fehl, bevor mob etwas committet hat (z. B. kein Netz), stellt kmob `.kmob/` wieder her. Hat jemand nach deinem letzten `kmob start` noch übergeben, lehnt das Remote den Push ab. Dann einfach `kmob start` und erneut `kmob done`. kmob hebt eine Sicherung der alten Session-Dateien auf und nennt den Pfad.

## Welche Session wird übergeben?

kmob berücksichtigt nur Sessions, deren Arbeitsverzeichnis das Git-Root oder ein Unterverzeichnis davon ist und die schon Einträge haben. Sessions von Subagents werden ignoriert, ihr Ergebnis steckt ohnehin in der Hauptsession.

Die Auswahl erfolgt in dieser Reihenfolge:

1. `--session <id>`: genau diese Session (volle ID oder eindeutiger Anfang)
2. `--pick-session`: Auswahl aus einer Liste (zeigt auch die vollen IDs)
3. die Session, aus der `kmob next` aufgerufen wurde (wenn die KI den Befehl ausführt)
4. die zuletzt aktive Session

Findet kmob keine Session oder übergibst du mit `--no-session`, gibt kmob nur den Code weiter und **entfernt** eine `.kmob/` aus einer früheren Runde. So lädt niemand versehentlich eine veraltete Session.

kmob liest nur die Sessions der Kiro CLI (`~/.kiro/sessions/cli/`).

## Optionen

| Option | Befehl | Umgebungsvariable | Wirkung |
|---|---|---|---|
| `--session <id>` | next | `KMOB_SESSION` | bestimmte Session übergeben |
| `--pick-session` | next | `KMOB_PICK_SESSION=true` | Session interaktiv wählen (braucht Terminal) |
| `--no-session` | next | `KMOB_NO_SESSION=true` | nur Code übergeben, vorhandenes `.kmob/` entfernen |
| `--allow-secrets` | next | `KMOB_ALLOW_SECRETS=true` | trotz Secret-Fund übergeben |
| `--require-gitleaks` | next | `KMOB_REQUIRE_GITLEAKS=true` | abbrechen, wenn gitleaks fehlt |
| `--yes` | next, done | `KMOB_YES=true` | Rückfragen überspringen (Erst-Hinweis, `done` ohne Squash) |
| `--debug` | next, start, done | `KMOB_DEBUG=true` | Debug-Ausgaben von kmob; das Flag geht auch an mob |
| – | alle | `KMOB_MOB_BIN` | Pfad zum `mob`-Binary |
| – | next, version | `KMOB_GITLEAKS_BIN` | Pfad zum `gitleaks`-Binary |
| – | next | `KMOB_KIRO_SESSIONS_DIR` | Kiro-Session-Verzeichnis (Default `~/.kiro/sessions/cli`) |

Flags haben Vorrang vor Umgebungsvariablen. Boolesche Variablen akzeptieren `true`/`false`/`1`/`0`.

kmob-Optionen stehen nach dem Befehl (`kmob next --yes`). Nur `--debug` darf auch davor stehen. Alle übrigen Argumente gehen unverändert an mob, z. B. `kmob next --message "wip: tests"` oder `kmob start -b feature`. Argumente nach `--` wertet kmob nicht aus. Die mob-Kurzformen `s`, `n` und `d` funktionieren ebenfalls.

kmob-Optionen gelten nur für `next`, `start` und `done`. Bei allen anderen Befehlen (z. B. `kmob status`) gehen sie unverändert an mob. `kmob next --help` zeigt direkt die Hilfe von mob.

## Sicherheit und Datenschutz

Die Session wird **ins Git-Remote gepusht**, und zwar vollständig: deine Prompts, die Antworten und alle Tool-Ausgaben (Dateiinhalte, Kommando-Ausgaben, lokale Pfade wie `/Users/<name>/…`). Beim ersten `kmob next` in einem Clone erklärt kmob das und fragt nach einer Bestätigung. Ohne Terminal (z. B. wenn die KI `kmob next` ausführt) brauchst du dafür einmalig `--yes`.

- **Secret-Scan:** Vor jeder Übergabe prüft kmob die Session (Metadaten und alle Log-Einträge).
  - Mit **gitleaks** gelten dessen rund 200 Regeln. Eine `.gitleaks.toml` im Git-Root wird berücksichtigt, z. B. für eigene Regeln oder deaktivierte Fehlalarme. Ohne `[extend] useDefault = true` ersetzt sie die Standardregeln von gitleaks. Zusätzlich gelten immer die Grundmuster von kmob (siehe nächster Punkt).
  - Ohne gitleaks nutzt kmob nur seine eingebauten Grundmuster für AWS Access Keys, Private Keys, Bearer-Tokens, GitHub- und Slack-Tokens und weist einmal auf gitleaks hin. Mit `--require-gitleaks` bricht kmob stattdessen ab.
  - Bei einem Treffer bricht kmob ab und nennt die Regel und die Nummer des Log-Eintrags. Den Wert selbst zeigt es nicht an.
  - Der Scan ist ein Sicherheitsnetz und keine Garantie. Bei sehr großen Sessions (> 100 MB) dauert er etwa 10 Sekunden.
- **Bei einem Treffer:** Starte eine neue Session (`/chat new`) und übergib mit `--no-session`. Nimm `--allow-secrets` nur, wenn du den Treffer geprüft hast.
- **Nach dem Push:** Auch wenn `mob done` den WIP-Branch löscht, können gepushte Commits beim Hoster weiter erreichbar sein (z. B. über die Commit-SHA, Forks oder Caches). Ein gepushtes Secret gilt deshalb als kompromittiert und muss rotiert werden. Bei `mob done --no-squash`/`--squash-wip` bleibt die Session dauerhaft in der Historie des Basis-Branches.
- **Pfade:** Lokale Pfade und Benutzernamen werden nicht anonymisiert.

## Hinweise und Grenzen

- **`.gitignore`:** `.kmob/` darf nicht ignoriert werden, sonst überträgt mob die Session stillschweigend nicht. kmob prüft das und bricht in diesem Fall ab.
- **Größe:** Sessions mit vielen großen Tool-Ausgaben können auf über 100 MB wachsen. kmob warnt ab 50 MB. GitHub lehnt Dateien über 100 MB ab, dann schlägt der Push von `mob next` fehl. Die Session steckt dann aber schon im lokalen WIP-Commit, ein erneutes `kmob next --no-session` hilft allein nicht. kmob zeigt in diesem Fall den passenden Befehl an, im Kern:
  ```bash
  git reset --soft origin/<wip-branch>   # bzw. origin/<basis-branch>, falls der WIP-Branch remote noch nicht existiert
  kmob next --no-session                 # entfernt .kmob/ und übergibt nur den Code
  ```
  Starte bei einem Themenwechsel lieber eine neue Session (`/chat new`).
- **`mob done --no-squash` / `--squash-wip`:** Die Session-Commits landen dann in der Historie des Basis-Branches. kmob fragt deshalb nach, ebenso wenn sich der Done-Modus nicht ermitteln lässt. Ohne Terminal bricht kmob ab, außer mit `--yes`.
- **Kein automatisches Laden:** `/chat load` ist ein Befehl innerhalb von Kiro, deshalb kann kmob ihn nicht selbst ausführen.
- **Parallele Sessions:** Arbeiten mehrere Personen gleichzeitig in eigenen Kiro-Sessions, gibt kmob immer nur eine Session weiter. Zusammengeführt werden sie nicht.
- **Gemischte Teams:** Wer `mob next` statt `kmob next` benutzt, überträgt keine neue Session, gibt eine vorhandene `.kmob/` aber unverändert weiter. Wichtig ist **`done`**: Ein `mob done` statt `kmob done` bringt `.kmob/` ins Ergebnis. Dann vor dem Commit im Git-Root `git rm -r --cached .kmob && rm -rf .kmob` ausführen. kmob ändert weder die mob-Konfiguration noch die Commit-Messages.
- **Session-Format:** kmob liest Kiros lokales Session-Format. Ändert eine neue Kiro-Version dieses Format, findet kmob die Session womöglich nicht mehr (Warnung „no Kiro session found“ mit Anzahl unlesbarer Dateien) oder `/chat load` scheitert beim Empfänger. Mit `--no-session` kannst du weiterarbeiten.

## Fehlermeldungen

| Meldung | Ursache und Abhilfe |
|---|---|
| `'mob' not found in PATH` / `KMOB_MOB_BIN=… is not an executable file` | mob installieren bzw. `KMOB_MOB_BIN` korrigieren |
| `KMOB_GITLEAKS_BIN=… is not an executable file` | Pfad korrigieren oder Variable entfernen |
| `gitleaks not found (required by --require-gitleaks)` | gitleaks installieren oder ohne `--require-gitleaks` übergeben |
| `secret scan failed: …` | gitleaks ist gescheitert, z. B. an einer ungültigen `.gitleaks.toml`. Datei korrigieren. |
| `.kmob/… is ignored by git` | Ignore-Regel entfernen (die Meldung nennt, wo sie steht) |
| `first handover from this clone needs confirmation` | `kmob next` einmal im Terminal ausführen oder `--yes` angeben |
| `no Kiro session for <repo> found` | Kiro wurde außerhalb des Repos gestartet, oder es gibt noch keine Session. Es wird nur der Code übergeben. |
| `session <id> not found / matches several sessions / belongs to … / is a subagent session / has no log entries` | ID mit `kmob next --pick-session` prüfen |
| `--pick-session needs a terminal` | im Terminal ausführen oder `--session <id>` verwenden |
| `no session picked – handover aborted` / `no Kiro session for <repo> to pick from` | Auswahl abgebrochen bzw. keine Session vorhanden. Erneut wählen oder mit `--no-session` übergeben. |
| `possible secret (<regel>) in log entry #<n>` | siehe [Sicherheit](#sicherheit-und-datenschutz) |
| `could not export session` | Session-Dateien beschädigt oder neues Kiro-Format. Mit `--no-session` weiterarbeiten und ein Issue anlegen. |
| `done mode … may keep .kmob/ in the history … Continue? [y/N]` | siehe Hinweis zu `--no-squash` |
| `mob done failed – restored .kmob/` | Ursache in der mob-Ausgabe beheben, dann erneut `kmob done` |
| `mob done failed – .kmob/ was already removed by mob; backup kept at …` | meist abgelehnter Push: `kmob start`, dann erneut `kmob done` |
| `.kmob/ was staged in the result – removed it from the index` | kmob hat aufgeräumt; mit `git status` prüfen und dann committen |
| `--<option> is only valid for <befehl>` / `--<option> must follow the command` / `… See kmob help.` | falsche Aufrufparameter, z. B. `kmob next --yes` statt `kmob --yes next` |

## Exit-Codes

`0` oder der Exit-Code von mob · `1` kmob-Fehler (Übergabe oder `done` abgebrochen, Rückfrage abgelehnt oder ohne Terminal, `.kmob/` im Ergebnis gefunden, mob fehlt, gitleaks fehlt bei `--require-gitleaks`) · `2` falsche Aufrufparameter

## Entwicklung

```bash
./gradlew build          # bauen + Unit-Tests (Host-Plattform)
./e2e/run.sh             # End-to-End gegen lokales Bare-Repo (mob erforderlich, gitleaks optional)
```

Spezifikation: [requirements](.kiro/specs/kmob/requirements.md) · [design](.kiro/specs/kmob/design.md) · [spike](.kiro/specs/kmob/spike.md)
