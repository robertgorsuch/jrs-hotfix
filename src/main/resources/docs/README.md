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
jrs-hotfix apply <package.zip> [--merge <mergeId>] [--on-conflict <rule>] [--plan] [--yes]
jrs-hotfix apply <package.zip> --war <in.war> --out <out.war> [--merge <mergeId>]
jrs-hotfix rollback <id> [--cascade] [--plan] [--yes]
jrs-hotfix verify <package.zip>
jrs-hotfix scan [--package <package.zip>] [--war <file.war>]
jrs-hotfix baseline [list | add <war | dir | package.zip> | remove <id>]
jrs-hotfix merge [prepare <package.zip> [--on-conflict <rule>] [--war <file.war>] | status [<mergeId>]
                  | show <mergeId> <path> | edit <mergeId> <path>
                  | resolve <mergeId> <path> --merged [<file>] | --mine | --theirs
                  | discard <mergeId>]
jrs-hotfix list
jrs-hotfix record <package.zip>
jrs-hotfix forget <id>
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
   is not on the server already, by the ledger, by the build the webapp states
   or by its files; there is free space for staging and the snapshot; the
   webapp and install tree are writable; no run is pending.
2. snapshot - copy every file the package will replace or delete into
   `snapshots/<runId>`, with hashes.
3. stage - extract the payload into `runs/<runId>/staging`, put the merged
   files in the place of the package's copies, and hash every file.
4. stop - stop the service and wait for its JVM to end within the timeout;
   force-stop only when configured.
5. swap - move each staged file into place (replace, add) or into the snapshot
   (delete); skip a file already at the target hash; refuse if any target is
   still locked.
6. clear the JSP cache - remove `<tomcatDir>/work/Catalina/localhost/<webappName>`,
   as the vendor's readme requires.
7. start - start the service, the companion database first when the host has
   one.
8. wait - poll for the server to answer, capped.
9. record - write the ledger entry `INSTALLED` with the file list, and the
   package's files as the hotfix's baseline; remove the staging directory.

The service is always stopped for the swap.

The ledger lives in the home, beside the installation, not in the webapp. After
the webapp is redeployed from a WAR, the ledger still lists the hotfixes it had;
preflight notices (the webapp states an older build than the ledger's newest
entry) and refuses. `jrs-hotfix forget <id>` takes such an entry out.

The installer's files keep this server's values: `js.quartz.properties`,
`js.jdbc.properties`, `hibernate.properties` and `keystore.init.properties`
are merged by key, and `META-INF/context.xml` and `META-INF/*-jdbc.xml` are
never replaced.

## Customized servers

Give jrs-hotfix the vendor's own files and it keeps what this site changed:

```
jrs-hotfix baseline add <jasperserver-pro.war>   the WAR the server was installed from
jrs-hotfix baseline add <package.zip>            a hotfix applied before there was a baseline
jrs-hotfix scan                                  vanilla, or customized: what differs
jrs-hotfix scan --package <package.zip>          where the site and a hotfix changed the same file
```

With a baseline, `apply` compares every file the package ships under the
webapp with the vendor's and the server's:

- only the vendor changed it: replaced;
- only the site changed it: kept;
- both changed a properties file: merged by key;
- both changed a page or an XML file under `WEB-INF`: merged by line; an XML
  file is always confirmed by you;
- both changed a script, a stylesheet or a binary file: replaced, and listed
  in the plan so that you carry the change over by hand.

A key or a line both changed differently waits for you, and `apply` refuses
with exit 2 until it is resolved. Nothing on the server is touched meanwhile:

```
jrs-hotfix merge prepare <package.zip>       do the comparing ahead of the outage
jrs-hotfix merge status <mergeId>            every file and its state; exit 0 when none waits
jrs-hotfix merge show <mergeId> <path>       what the site changed, what the hotfix changed
jrs-hotfix merge resolve <mergeId> <path> --merged [<file>] | --mine | --theirs
jrs-hotfix apply <package.zip> --merge <mergeId>
```

`--merged` installs `merges/<mergeId>/files/<path>/merged` from the home, which
you edit, or the file you name; it is refused while a conflict marker is left
in it or an XML file fails its checks. `--on-conflict ask|mine|theirs|fail`
says what happens to a properties key both changed; the default is `ask` at a
terminal and `fail` otherwise.

A rollback brings the site's files back byte for byte. Without a baseline
every file the package ships is replaced, as before.

`apply <package.zip> --war <in.war> --out <out.war>` does the same to a WAR
instead of a server: no service, no snapshot; the input is never modified, the
output is written beside its record `<out.war>.jrs-hotfix.json`, and the home
is `jrs-hotfix` beside the WAR unless `--home` says otherwise. `scan --war` and
`merge prepare --war` read the site's files from the WAR. Files of
`js-install.zip` are not part of a WAR and are left out.

## Rollback

`jrs-hotfix rollback <id>` restores a hotfix's snapshot: replaced files come
back, files the hotfix added are removed, and files it deleted are restored.
Rollback is last-in-first-out: if a newer installed hotfix owns any of the same
files, rollback is refused with the blocking ids unless `--cascade` is given,
which rolls the blocking hotfixes back newest first, then the one asked for.
Restore is per file and idempotent, so a crash mid-restore resumes cleanly. The
JSP cache is removed after the restore, as it is after a swap. An entry that
was recorded (a hotfix applied by hand) has no snapshot and cannot be rolled
back by this tool.

## Manual steps

Some hotfixes list manual steps in their readme: SQL for a given database,
properties to add, or settings to re-apply in a configuration file the hotfix
overwrites. jrs-hotfix never runs any of this. The readme's manual steps are
printed in the plan preview, printed again after the run finishes, and saved to
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
baselines/<id>/manifest.json  the vendor's files: a hash for every file of a release's WAR or of a hotfix
baselines/<id>/payload/...    the content of the mergeable ones (settings, XML, pages)
merges/<mergeId>/merge.json   a prepared merge: what an apply does with every file the package ships
merges/<mergeId>/report.txt   the same, as `merge status` prints it
merges/<mergeId>/files/<path>/base|mine|theirs|merged   the three sides of a file that needed a merge, and the result
wars/webapps/<name>/          the unpacked copy of the WAR being worked on (--war); may be deleted at any time
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
| `merge.onConflict` | a properties key both the site and a hotfix changed: `ask`, `mine`, `theirs`, `fail` | absent: `ask` at a terminal, `fail` otherwise |
| `merge.tool` | the command `merge edit` runs, with `{base}`, `{mine}`, `{theirs}`, `{merged}` | absent |
