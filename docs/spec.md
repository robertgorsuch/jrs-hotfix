# jrs-hotfix: a lean hotfix tool split out of jrsctl

Design, 2026-09-28. Status: approved for planning.

## Problem

jrsctl (final release v2.3.0) does five jobs in one tool: hotfixes, customizations,
export/import, upgrades, and diagnostics. The maintainer is splitting it into four
separate applications, each in its own repository, starting with hotfixes. The hotfix
job today carries the whole tool with it: a REST client and credentials it does not need,
a SQLite state store with migrations, a signed bundle format with key management that
field testers called "another layer of complexity", JSON output schemas, five embedded
documents, and a guided menu that fronts eight operations.

jrs-hotfix keeps what makes a hotfix safe (snapshot first, stop before touching WEB-INF,
idempotent steps, resumable runs, per-file rollback across stacked hotfixes) and drops
everything else.

## Scope

- JasperReports Server 10.x, Tomcat deployments, on Windows and Linux.
- Input: official Jaspersoft cumulative hotfix packages only. A package is a ZIP holding
  `readme.txt` and at least one of `jasperserver[-pro].zip` (paths relative to the webapp)
  and `js-install.zip` (paths relative to the installation). No manifest, no signature.
  The jrsctl bundle format, `hotfix build`, Ed25519 signing and trusted keys are gone.
- Menu-driven at a terminal, subcommands for scripts. No `--json`.
- Not in scope: customizations, export/import, upgrades, doctor and smoke, support
  bundles, JBoss/WildFly, containers, the jrsctl web console.

## Scenarios the tool must handle

1. Apply a package to a clean 10.x install: files under the webapp and under the install
   tree, plus the deletions the readme lists.
2. Apply a second package over an earlier one, including the readme's "Important" globs
   that remove leftovers of earlier hotfixes.
3. Roll back the latest hotfix from its snapshot: replaced files come back, added files
   are removed, deleted files are restored.
4. Roll back an older hotfix when a newer one owns some of the same files: refuse with the
   blocking ids, or cascade newest first with `--cascade`.
5. Resume or roll back a run interrupted by a crash, reboot or Ctrl-C, from whatever step
   it reached.
6. Record a hotfix applied by hand so the ledger and later overlap checks know about it.
7. Preview a plan without touching anything, and verify a package's integrity and
   applicability before an outage window.
8. Control the service on both OSes: Windows service, systemd, the vendor scripts
   (`ctlscript`, `catalina`), or manual, with a locked-file check before the swap.
9. Show the readme's manual steps (SQL for a given database, properties, settings to
   re-apply in overwritten configuration files) as warnings. Never execute them.

## Approach

Copy and trim. The proven jrsctl code for the step engine, the platform layer, the
snapshot store, the official-package reader, the apply and rollback steps, and the guided
menu is copied into a new single-module project and everything it does not need is
removed. Two alternatives were rejected: a from-scratch, readme-literal tool would lose
resumable runs and per-file rollback and relearn the Windows service and locked-file
lessons that took three field tests; consuming jrsctl as a library would ship every
subsystem the split is meant to remove, from a codebase that is frozen.

## 1. Project shape

- Repository `jrs-hotfix`, GPL-3 (the copied code is GPL-3 under jrsctl ADR-0010).
- One Maven module. Package root `com.jaspersoft.jrshotfix`. Java 21; records, sealed
  interfaces, pattern-matching `switch` with no `default` over sealed types; no `null`
  from public APIs; one-paragraph Javadoc stating invariants on every public class.
- Runtime dependencies: picocli, Jackson databind, JLine (terminal, reader, JNI
  provider). Dropped: sqlite-jdbc, logback and slf4j, logstash encoder, commons-compress,
  commons-lang3, json-schema-validator, semver4j. ZIPs are read with `java.util.zip`.
  Version comparison is three dotted integers, hand-parsed.
- Build: Error Prone with `-Werror`, google-java-format through Spotless, Jacoco line
  floor, Surefire for unit tests, Failsafe for acceptance tests against the shaded jar.
  Scripts: `scripts/mvn.{cmd,sh}` pin JDK 21; `scripts/fast.{cmd,sh} test <Class>` runs
  one class; `fast fmt` formats; `fast accept` runs the acceptance suite.
- Release: on a tag, CI builds `jrs-hotfix.jar` (shaded) and portable `zip` and `tar.gz`
  archives with a jlink Temurin 21 runtime for Windows x86-64 and Linux x86-64, with
  SHA-256 checksums beside them. No SBOM, no archive signing.
- Logging: the tool writes `runs/<runId>/run.log` itself through one small writer. No
  logging framework. Every stream the tool writes passes a redaction filter for the few
  secrets it can see (none by design; the filter stays so a future path cannot leak).

## 2. Settings, home and detection

Settings (`settings.json` in the home, edited through the menu or `settings set`):

| Key | Meaning | Default |
|---|---|---|
| `installDir` | JRS installation root | detected |
| `tomcatDir` | Tomcat root | `<installDir>/apache-tomcat` |
| `webappName` | `jasperserver-pro` or `jasperserver` | detected from `webapps/` |
| `service.kind` | `windows-service`, `systemd`, `ctlscript`, `catalina`, `manual` | detected |
| `service.name` | service name (windows-service, systemd) | detected |
| `service.scriptPath` | script path (ctlscript, catalina) | detected |
| `service.stopTimeoutSeconds` | how long a stop may take | 180 |
| `service.forceStopAfterSeconds` | end the JVM if the script outlives this (scripts only, off when absent) | absent |
| `baseUrl` | used only by the wait-for-server probe | `http://localhost:<port from server.xml>/<webappName>` |

There is no server URL for REST, no credentials, no database section, no secrets store,
no proxy. The only network call the tool ever makes is an unauthenticated GET of
`<baseUrl>/rest_v2/serverInfo` while waiting for the server to come back.

Home: `<installDir>/jrs-hotfix/`. One home per installation, so the ledger is keyed to
the server it describes, snapshots sit on the same volume as the webapp so restores are
renames, and the tool needs no permissions beyond the ones it already needs to write the
webapp. `--home <dir>` or `JRS_HOTFIX_HOME` overrides it; the override is for tests and
for an installation on a read-only volume.

Detection (first run, or `settings detect`): candidate install directories come from the
command lines of running Tomcat processes, then the vendor's default paths (the Windows
uninstall registry keys were a third source until 0.4.0); the operator confirms one. The release is read from the
`jasperserver-*-X.Y.Z.jar` names under `WEB-INF/lib`, the edition from the webapp name and
the presence of the pro jars. When the process scan is blind (a JVM whose command line
this account cannot read), the tool says so in one sentence rather than reporting no
server.

The companion PostgreSQL service the bundled installer registers is started before Tomcat
when the host lists one, following the vendor's start order as jrsctl does (ADR-0032).

## 3. Ledger, journal and snapshots

Layout under the home (corrected after implementation: the run record and the stop marker live in
the run directory, and each snapshot sits in a directory named after the step that took it):

```
settings.json
ledger.json
lock                          pid + start time of the running command
runs/<runId>/run.json         the run's record: operation, start, end, state, exit code
runs/<runId>/plan.json        the fingerprinted plan as built
runs/<runId>/journal.jsonl    one line per step transition, appended, fsynced
runs/<runId>/run.log
runs/<runId>/notes.txt        the readme's manual steps for this package
runs/<runId>/staging/         payload extracted before the outage; removed at the end
runs/<runId>/stop-service.stopped   marker: this run stopped the service
snapshots/<runId>/snapshot/manifest.json              apply: the files before the hotfix
snapshots/<runId>/snapshot/payload/...                replaced and deleted files, at their relative paths
snapshots/<rollbackRunId>/pre-rollback-<id>/manifest.json   rollback: the files before the rollback
snapshots/<rollbackRunId>/pre-rollback-<id>/payload/...
```

`ledger.json` is the only registry. One entry per hotfix:

```json
{ "id": "JRSHF-10.0.0-20260730-0457", "release": "10.0.0", "edition": "PRO",
  "build": "20260730_0457", "title": "...", "state": "INSTALLED",
  "origin": "TOOL", "runId": "...", "snapshot": "snapshots/<runId>",
  "installedAt": "...", "files": [
    { "path": "apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib/x.jar",
      "action": "replace", "before": "<sha256>", "after": "<sha256>" } ] }
```

States: `INSTALLED`, `ROLLED_BACK`, `RECORDED` (applied by hand; no snapshot; refused by
rollback). The file list gives overlap and LIFO checks what they need. The ledger is
written by temp file, fsync, rename; it is read whole, and it stays small (a few hundred
paths per hotfix).

`journal.jsonl` replaces SQLite's `step_transitions`: `{ts, stepId, state, detail}` per
line, appended with fsync. Recovery reads `plan.json` and the journal, verifies the plan
fingerprint against the settings and the installed release, and re-executes from the
first step without a terminal state. Every step re-checks the disk before acting, so
re-execution converges. Audit facts (an operator confirmed the checksum, a rollback was
cascaded) are journal lines with their own step id.

Snapshots are verified as they are written and again before and after a restore. A
snapshot referenced by an `INSTALLED` entry is never pruned; `runs prune` removes run
directories and snapshots of ended, unreferenced runs older than a given age.

`lock` refuses a second concurrent command with exit 9. A run whose journal has no
terminal state blocks every mutating command with exit 8 until `runs resume` or
`runs rollback` ends it; the menu offers those two first.

## 4. Plans

### 4.1 Apply

Planning reads the package as a stream, parses the outer and inner readmes, derives the
id `JRSHF-<release>-<date>-<time>` from the readme's release and build, prints the
package's SHA-256 for the operator to confirm against the support portal (at a terminal;
`--yes` skips the prompt and the journal records that), and builds eight steps:

| # | Step | Does | Compensation |
|---|---|---|---|
| 1 | preflight | installed release and edition match the readme; free space for staging plus snapshot; write access to webapp and install tree; no pending run; service state readable; an `add` whose target already exists on disk becomes `replace` | none (read-only) |
| 2 | snapshot | copy every file the package replaces or deletes into `snapshots/<runId>`, with hashes | none (a snapshot is never harmful) |
| 3 | stage | extract the payload to `runs/<runId>/staging`, hash every file | delete staging |
| 4 | stop | stop the service; wait for the JVM to end within the timeout; force-stop only when configured | start the service |
| 5 | swap | per file: rename staged into place (replace, add) or move into the snapshot (delete); skip a file already at the target hash; refuse if any target is still locked | restore the snapshot; remove files this run added |
| 6 | start | start the service (companion database first when present) | none |
| 7 | wait | GET serverInfo until 200, capped | none |
| 8 | record | ledger entry `INSTALLED` with the file list; remove staging | none (last step) |

Actions per file come from this installation, not the readme's lists: a file that exists
now is `replace`, otherwise `add`. Deletions come from the readme's "Deleted files" and
the "Important" section's globs, expanded against this installation and never covering a
file the package itself lays down. A later cumulative package may own files an earlier one
owns; the ledger records both, and LIFO rules apply at rollback.

The service is always stopped: every official package touches `WEB-INF/lib`. There is no
replace-on-restart path.

The readme's manual steps are printed in the plan preview, printed again after the run,
and written to `notes.txt`. Nothing in them is executed.

Corrected before 0.1.0, after the tool met the real package and the real server
(2026-09-29). The table above stays as designed; the implementation differs in these:

- Nine steps. `clear-jsp-cache` runs between swap and start, and between restore and start
  in a rollback: it removes `<tomcatDir>/work/Catalina/localhost/<webappName>`, which the
  vendor's readme requires. It is `irreversible()`: the cache is derived from the pages
  and Tomcat builds it again.
- Preflight and `verify` refuse (exit 2) a package whose files are all in place at the
  package's hashes with nothing left to delete and no `INSTALLED` ledger entry, and name
  `jrs-hotfix record`. Such a run would stop the service to change nothing.
- The build the webapp states in `WEB-INF/internal/jasperserver-pro.properties` is read
  (`InstalledBuild`) and shown by `list` and in the menu's first line.
- The four properties files the installer fills in for one server (`js.quartz.properties`,
  `js.jdbc.properties`, `hibernate.properties`, `keystore.init.properties`) are merged by
  key when the server's values differ: the package's file, the server's value for every
  key the server has, the server's own keys at the end. The merged file is planned (its
  hash is the entry's), written over the staged payload in `stage`, and moved by `swap`
  like any other file; the snapshot holds the server's file. The merge of its own result
  with the same package file gives the same bytes, so a plan rebuilt after the swap
  matches. `docs/spec-customized-servers.md` section 4.4 is the design this is the first
  part of.
- The readme's Important and Additional Notes sections are carried verbatim and whole. A
  line is never dropped because an equal line came before it (SQL repeats its lines); a
  section both inner readmes hold is carried once. The preview shows the first eight lines
  of each section and says where the rest is.
- The warning about replaced settings files names the webapp's files and counts the
  installation's templates. The preview counts the files by area and action instead of
  listing the first twenty paths.

### 4.2 Rollback

`rollback <id> [--cascade]`: the entry must be `INSTALLED` with origin `TOOL`
(`RECORDED` is refused with exit 2 and a sentence saying why). If a newer `INSTALLED`
entry owns any of the same paths, rollback is refused with the blocking ids unless
`--cascade`, which runs one rollback plan per blocking hotfix, newest first, then this
one. Each plan: stop, restore the snapshot (verify hashes before and after; replaced and
deleted files come back, added files are removed), start, wait, mark `ROLLED_BACK`.
Restore is per file and idempotent, so a crash mid-restore resumes.

### 4.3 Record

`record <package.zip>`: parse the readme, list the package's files, hash what is on disk
now at each path, and write a `RECORDED` entry with that list. Nothing on the server is
touched. Later overlap checks and rollback refusals see the entry.

### 4.4 Verify

`verify <package.zip>`: ZIP integrity, package shape, readme parse, applicability against
the installed release and edition, the change list (adds, replaces, deletes), the manual
notes. Exit 0 when applicable, 2 otherwise. Nothing is written except the run log.

### 4.5 Recovery

`runs resume <id>` re-executes the pending run from its journal. `runs rollback <id>`
compensates the steps that ran, newest first. Both refuse when the plan fingerprint no
longer matches the installation (exit 2) and say what changed.

## 5. Commands, menu and docs

```
jrs-hotfix                                   menu at a terminal; usage otherwise
jrs-hotfix apply <package.zip> [--plan] [--yes]
jrs-hotfix rollback <id> [--cascade] [--plan] [--yes]
jrs-hotfix verify <package.zip>
jrs-hotfix list
jrs-hotfix record <package.zip>
jrs-hotfix runs [list | show <id> | resume <id> | rollback <id> | prune --older-than <days>]
jrs-hotfix settings [show | set <key> <value> | detect]
jrs-hotfix --docs | --version | --help
```

Global options: `--home <dir>`, `--no-color` (`--no-pager` and `--ascii` existed until 0.4.0:
the pager went with them, and the ASCII fallback is automatic when the output encoding
cannot carry the glyphs). `--plan` prints the plan and
exits 0 without touching anything. `--yes` answers every confirmation.

Menu:

```
jrs-hotfix - JasperReports Server hotfix tool   (release 10.0.0 PRO at C:\Jaspersoft\...)
  1) Apply a hotfix
  2) Roll back a hotfix
  3) Verify a hotfix package
  4) List installed hotfixes
  5) Record a hotfix applied by hand
  6) Recent runs and recovery
  7) Settings
  q) Quit
```

When no settings exist the settings wizard runs before the menu. When a run is pending
the menu says so on its first line and entry 7 (6 before 0.2) is the only mutating entry offered. Each
entry prints the command it runs before running it, so an operator learns the scripted
form by using the menu. Path prompts complete file names (JLine). Confirmations default
to no.

Documentation: one embedded page of about 150 lines, printed by `--docs` and shipped as
`README.md`, covering what the tool does, the eight steps, rollback rules, recovery, and
where the files live. Help text is one line per option and one example per command. The
plan preview is a table of steps with the manual notes beneath it. There are no other
documents. Design notes live in `docs/decisions/` as short ADRs only when a choice was
not obvious.

## 6. Errors and exit codes

| Code | Meaning |
|---|---|
| 0 | ok |
| 1 | usage |
| 2 | precheck or verify failed, nothing mutated |
| 3 | failed and rolled back |
| 4 | failed, rollback incomplete (the message names the files and the snapshot) |
| 5 | cancelled |
| 6 | unsupported input (not an official package, not a 10.x install); corrected after implementation, which detects neither containers nor other servers |
| 8 | recovery required |
| 9 | lock held |

Every failure message says what happened and the one thing to do next. A locked file
names the holding process when the scan can see it and says the scan was blind when it
cannot.

## 7. Testing

- Unit tests with a fake platform (in-memory file ops, scripted service controller,
  scripted process scan) for the readme parser, the plan builder, each step's execute and
  compensate, the ledger, LIFO and cascade rules, and recovery.
- A fixture generator builds a sample official package (both inner zips, a readme with
  deletions and an "Important" glob, manual SQL notes) and a fake installation with the
  jar names version detection needs, a `server.xml`, and stub start and stop scripts.
- Acceptance tests run the shaded jar against that fixture and cover all nine scenarios,
  including a crash injected mid-swap followed by resume and, separately, by rollback.
  Each test has its own temp home and a stub script that records what it was asked to do.
- CI: Ubuntu and Windows, `verify` on every push, release archives on a tag.
- Before the first release: a live apply, rollback and re-apply on the real JRS 10.0.0
  on this machine (Windows service) and on the Linux laptop (script kind), with a real
  support package.

## 8. What is copied from jrsctl and what is cut

Copied and trimmed: `core.engine` (Plan, Step, Runner, Recovery, RunLock, retry, cancel),
`core.platform` (file ops, Trees, disk space, the four service controllers, Tomcat process
finding and lock detection, code page handling, install scan), `core.snapshot`,
`ops.hotfix` (OfficialPackage and its readme parser, FileTarget, the apply, rollback and
record steps, RollbackChain, PriorState, OwnerRestore), `jrs.service` (stop, start, wait,
companion database), the guided menu, prompter, path completer, pager (dropped in 0.4.0), plan printer and
progress renderer from `app`.

Cut: `jrs.rest`, `jrs.api` except the unauthenticated probe, `jrs.strategy`,
`jrs.vendor`, `jrs.keystore`, `core.state` (SQLite), `core.secrets`, `core.keys`,
`core.crypto`, `core.compat`, `core.config` (replaced by the settings file), the JSON
output layer and schemas, `ops.exim`, `ops.upgrade`, `ops.customizations`, `ops.doctor`,
`ops.smoke`, `ops.init` (replaced by detection), retention beyond `runs prune`, the
support bundle, the signed bundle format, `hotfix build`, `keys`, `Explain`, and four of
the five embedded documents.

## Open points settled during design

- Inputs: official packages only.
- Approach: copy and trim, single module, JSON ledger and journal instead of SQLite.
- Home inside the installation; JSON settings; no YAML.
- The service is always stopped; no `--json`.
