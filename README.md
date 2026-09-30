# jrs-hotfix

jrs-hotfix applies, verifies, records and rolls back official Jaspersoft cumulative
hotfix packages on JasperReports Server 10.x running under Tomcat, on Windows and
Linux. The only input it reads is the ZIP exactly as Jaspersoft Support publishes
it: a `readme.txt` plus one or both of `jasperserver[-pro].zip` (paths under the
webapp) and `js-install.zip` (paths under the installation). It keeps the service
stopped for every change under `WEB-INF`, snapshots what it replaces or deletes so
a hotfix can be rolled back on its own or as part of a cascade, and resumes or
rolls back a run interrupted by a crash, reboot or Ctrl-C.

## Start

Run `jrs-hotfix` at a terminal to open the menu. The first run detects the
installation and asks you to confirm it (the install directory, the Tomcat root,
the webapp, and how the service is controlled) before the menu is shown. Scripts
use the subcommands below instead; every menu entry prints the command line it is
about to run, so using the menu also teaches the scripted form.

## Commands

```
jrs-hotfix                                   menu at a terminal; usage otherwise
jrs-hotfix apply <package.zip> [--plan] [--yes]
jrs-hotfix rollback <id> [--cascade] [--plan] [--yes]
jrs-hotfix verify <package.zip>
jrs-hotfix list
jrs-hotfix record <package.zip>
jrs-hotfix runs [list | show <id> | resume <id> | rollback <id> | prune --older-than <days> [--include-failed]]
jrs-hotfix settings [show | set <key> <value> | detect]
jrs-hotfix --docs | --version | --help
```

Global options: `--home <dir>` picks the home directory (default: detected).
`--yes` answers every confirmation without asking; it implies `--non-interactive`.
`--plan` prints the plan for `apply` or `rollback` and stops; nothing is changed.
`--non-interactive` never prompts and fails closed (exit 2) where a confirmation
would otherwise be needed.

## What apply does

1. preflight - the installed release and edition match the readme; the hotfix
   is not installed already; there is free space for staging and the snapshot;
   the webapp and install tree are writable; no run is pending; an add whose
   target already exists on disk becomes a replace.
2. snapshot - copy every file the package will replace or delete into
   `snapshots/<runId>`, with hashes.
3. stage - extract the payload into `runs/<runId>/staging`, merge the settings
   files below, and hash every file.
4. stop - stop the service and wait for its JVM to end within the timeout;
   force-stop only when configured.
5. swap - move each staged file into place (replace, add) or into the snapshot
   (delete); skip a file already at the target hash; refuse if any target is
   still locked.
6. clear the JSP cache - remove `<tomcatDir>/work/Catalina/localhost/<webappName>`,
   as the vendor's readme requires; Tomcat compiles the pages again on first use.
7. start - start the service, the companion database first when the host has
   one.
8. wait - poll for the server to answer, capped.
9. record - write the ledger entry `INSTALLED` with the file list; remove the
   staging directory.

The service is always stopped for the swap.

A hotfix whose files are all in place already, with nothing left to delete, was
applied by hand or by another tool. `apply` and `verify` refuse it with exit 2,
because the outage would change nothing, and name the command that tells the
ledger: `jrs-hotfix record <package.zip>`. `jrs-hotfix list` and the menu's
first line show the build the webapp states about itself
(`WEB-INF/internal/jasperserver-pro.properties`), which is the hotfix level of
the files on disk whoever put them there.

`apply` and `verify` compare that build with the ledger:

- the webapp states the package's build and the ledger has no installed entry
  for it: refused with exit 2, as above, even when a file or two differ;
- the webapp states an older build than the newest installed entry: refused
  with exit 2, because the webapp was replaced under the ledger;
- the webapp states a newer build than every entry: a warning that a hotfix was
  applied outside jrs-hotfix, and the run goes on;
- the webapp states no build: a warning, and the run goes on.

A library under `WEB-INF/lib` that looks like an older version of one the
package brings, and that the readme's lists do not name, is reported in the
plan. Nothing is deleted on that ground: the rule goes by file names.

## Settings files

The installer writes values for one server into four properties files of the
webapp: `WEB-INF/js.quartz.properties`, `WEB-INF/js.jdbc.properties`,
`WEB-INF/classes/hibernate.properties` and
`WEB-INF/classes/keystore.init.properties`. When a package ships one of them and
the server's file holds other values, jrs-hotfix installs the package's file
with the server's values: a key both have keeps the server's value, a key only
the package has is taken from the package, and a key only the server has is
carried over under a comment at the end. The plan names the keys, never the
values. Where a fix depends on the package's value of a kept key, set it by
hand. The server's comments are not carried; the file as it was is in the
snapshot, and a rollback puts it back byte for byte.

`META-INF/context.xml` and the `META-INF/*-jdbc.xml` files hold the database
connection the installer wrote. When a package ships one and the server has
it, the server's file stays: it is not snapshotted, staged or swapped, and the
ledger does not list it. If the package's copy differs from the server's, the
plan and `notes.txt` show the package's copy, so that what the hotfix changed
in it can be carried over by hand.

Every other `.xml` and `.properties` file the package ships is replaced, and
the plan names the ones in the webapp: settings you changed in them must be
applied again.

## Customized servers

jrs-hotfix can tell what a site changed in the webapp, given the vendor's own
files to compare with. They are kept in the home as baselines:

```
jrs-hotfix baseline add <jasperserver-pro.war>   the release as the vendor shipped it
jrs-hotfix baseline add <package.zip>            a hotfix that is on the server
jrs-hotfix baseline list
jrs-hotfix baseline remove <id>
```

The WAR is the one the server was installed from (the directory that holds it,
or an unpacked copy, will do). It is read as a stream; the baseline keeps a hash
of every file and the content of the files that can be merged: settings, XML
and pages. When a hotfix is on the server, its package is needed too, because
the files it replaced are the vendor's and not the site's.

`jrs-hotfix scan` compares every file of the webapp with the baseline and
changes nothing. Its first line is the answer, `vanilla: no vendor file was
changed` or `customized: N changed, M added, K removed`, and the files follow,
each with its class: `X` reviewed XML, `P` properties, `T` pages, `G` scripts
and stylesheets, `B` binary. Files that differ in line ends only are equal.
Files the installer fills in for one server are listed as `INSTALLER` and are
not customizations; logs and built scripts are counted, not listed; a deployed
`applicationContext-externalAuth*.xml` is listed under its own heading. Exit 0
either way, 2 when there is no baseline that fits this installation.

`jrs-hotfix scan --package <package.zip>` adds what the package would meet:
for each file it ships, whether only the vendor changed it (replaced), only the
site (kept), or both (a collision).

## Rollback

`jrs-hotfix rollback <id>` restores a hotfix's snapshot: replaced files come
back, files the hotfix added are removed, and files it deleted are restored.
Rollback is last-in-first-out: if a newer installed hotfix owns any of the same
files, rollback is refused with the blocking ids unless `--cascade` is given,
which rolls the blocking hotfixes back newest first, then the one asked for.
Restore is per file and idempotent, so a crash mid-restore resumes cleanly. The
JSP cache is removed after the restore, as it is after a swap. An entry with
state `RECORDED` (a hotfix applied by hand) has no snapshot and cannot be rolled
back by this tool.

## Manual steps

Some hotfixes list manual steps in their readme: SQL for a given database,
properties to add, or settings to re-apply in a configuration file the hotfix
overwrites. jrs-hotfix never runs any of this. It carries those sections of
the readme as they are, every line of them: `jrs-hotfix verify <package.zip>`
prints them before the outage, the plan preview shows the first lines of each
section, and a run prints them again when it finishes and saves them to
`runs/<runId>/notes.txt` for you to act on by hand.

## If something goes wrong

| Code | Meaning |
|---|---|
| 0 | ok |
| 1 | usage |
| 2 | precheck or verify failed, nothing mutated |
| 3 | failed and rolled back |
| 4 | failed, rollback incomplete (the message names the files and the snapshot) |
| 5 | cancelled |
| 6 | unsupported input (not an official package, not a 10.x install) |
| 8 | recovery required |
| 9 | lock held |

A run whose journal has no terminal state blocks every mutating command with
exit 8 until `jrs-hotfix runs resume <id>` finishes it or
`jrs-hotfix runs rollback <id>` undoes it; the menu's entry 7 offers both first.
Its snapshot lives under `snapshots/<runId>` in the home (see Files below) and is
never pruned while the hotfix it belongs to is installed; the snapshot of a run
that failed with exit 4 is kept too, unless `runs prune --include-failed`.
`jrs-hotfix runs show <id>` prints the run's record, every step transition, and
the stored plan's steps, so you can see exactly where it stopped.

After Ctrl-C, run `jrs-hotfix runs list`; a run left pending is finished with
`runs resume` or undone with `runs rollback`.

A run interrupted while the service was down, at an installation outside the
default paths, is found again through the home jrs-hotfix remembers it used
last; if that is gone too, pass `--home <installDir>/jrs-hotfix` (or set
`JRS_HOTFIX_HOME`) to `runs resume` or `runs rollback`.

## Files

Everything jrs-hotfix keeps lives under its home, `<installDir>/jrs-hotfix/`
(override with `--home <dir>` or `JRS_HOTFIX_HOME`):

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

## Settings

`settings.json` holds these keys, edited through the menu or
`jrs-hotfix settings set <key> <value>`:

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

## Build

Build through `scripts/mvn.sh verify` (JDK 21 pinned). While iterating:
`scripts/fast.sh test <TestClass[,TestClass]>` compiles with Error Prone and
`-Werror` and runs just those unit tests; `scripts/fast.sh fmt` formats with
google-java-format before committing.

## Install

Each release on GitHub carries one archive per OS, `jrs-hotfix-<version>-linux-x64.tar.gz`
and `jrs-hotfix-<version>-windows-x64.zip`, with its own Java runtime inside: the server's
Java is neither used nor changed, and no JDK or `JAVA_HOME` is needed.

1. Download the archive for the OS and `SHA256SUMS` from the release page, and check the
   download: `sha256sum -c --ignore-missing SHA256SUMS` (`SHA256SUMS` lists every file of
   the release; on Windows without Git Bash, compare `Get-FileHash <archive>` with its line).
2. Unpack it anywhere, next to the installation or not:
   `tar -xzf jrs-hotfix-<version>-linux-x64.tar.gz` on Linux, Extract All (or
   `Expand-Archive`) on Windows. The directory `jrs-hotfix-<version>` holds `bin/`, `lib/`,
   `runtime/`, this README and the licence.
3. Run `bin/jrs-hotfix` (Windows: `bin\jrs-hotfix.cmd`) as the account that owns the
   installation: the user that installed JasperReports Server and runs its Tomcat on Linux
   (root when a systemd unit controls it), an Administrator prompt on Windows.

The release also carries `jrs-hotfix.jar` for a host that already has Java 21:
`java -jar jrs-hotfix.jar`. To upgrade, unpack the new archive and use it instead of the
old one; what jrs-hotfix keeps lives under the installation (see Files), not in the archive.

`docs/spec.md` is the design.
