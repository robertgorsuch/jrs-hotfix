# jrs-hotfix

jrs-hotfix applies, verifies, records and rolls back official Jaspersoft cumulative
hotfix packages on JasperReports Server 10.x running under Tomcat, on Windows and
Linux. The only input it reads is the ZIP exactly as Jaspersoft Support publishes
it: a `readme.txt` plus one or both of `jasperserver[-pro].zip` (paths under the
webapp) and `js-install.zip` (paths under the installation). It keeps the service
stopped for every change under `WEB-INF`, snapshots what it replaces or deletes so
a hotfix can be rolled back on its own or as part of a cascade, and resumes or
rolls back a run interrupted by a crash, reboot or Ctrl-C.

On a customized server it keeps what the site changed: given the vendor's own
WAR as a baseline, it tells which files the site changed, keeps the ones the
hotfix does not touch, merges settings files by key and pages and XML by line,
and leaves what it cannot merge cleanly for you to resolve before the outage
(see Customized servers). It can also make a hotfixed WAR from a WAR, without
a server. A library an earlier hotfix left behind in an older version is
deleted with the next hotfix, and a duplicate that would keep Tomcat from
starting is refused before anything changes.

The current release is [v0.3.1](https://github.com/robertgorsuch/jrs-hotfix/releases/latest);
each release page carries the archives, `SHA256SUMS` and the release notes, and
`docs/releases/` in this repository holds every release's notes. Install is at
the end of this page.

## Start

Run `jrs-hotfix` at a terminal to open the menu. The first run detects the
installation and asks you to confirm it (the install directory, the Tomcat root,
the webapp, and how the service is controlled) before the menu is shown. Scripts
use the subcommands below instead; every menu entry prints the command line it is
about to run, so using the menu also teaches the scripted form.

## Commands

```
jrs-hotfix                                   menu at a terminal; usage otherwise
jrs-hotfix apply <package.zip> [--merge <mergeId>] [--on-conflict <rule>] [--keep-superseded] [--plan] [--yes]
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
  with exit 2, because the webapp was replaced under the ledger. After a
  redeploy from a WAR that is expected: `jrs-hotfix forget <id>` takes the
  entry out of the ledger (the snapshot stays until `runs prune`), and the
  package applies again;
- the webapp states a newer build than every entry: a warning that a hotfix was
  applied outside jrs-hotfix, and the run goes on;
- the webapp states no build: a warning, and the run goes on.

A library under `WEB-INF/lib` that is an older version of one the package
brings, and that the readme's lists do not name, is deleted as superseded
when the ledger or a baseline knows it as the vendor's (a library an earlier
hotfix brought, or the release's). The plan lists them under their own
heading, they go into the snapshot, and a rollback puts them back;
`--keep-superseded` leaves them. A library nothing knows may be the site's
own: it is reported and left, with one exception. When such a library is a
web fragment (it holds `META-INF/web-fragment.xml`, as `log4j-jakarta-web`
does), leaving it beside the newer one makes Tomcat refuse to deploy the
whole webapp ("More than one fragment with the name"), so `apply` and
`verify` refuse with exit 2 and say what to do: `record` or `baseline add`
the hotfix that brought it, and it is deleted as superseded; or remove it by
hand if it is the site's. This came from a field test where a hotfix brought
log4j 2.25.4 and deleted the release's 2.24.3 while the 2.25.3 of the hotfix
before it stayed, named by no list, and the server would not start.

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
applied again. That is so on a server without a baseline; with one, see
"Customized servers" below.

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

### Applying a hotfix to a customized server

With a baseline that fits, `apply` no longer replaces what the site changed. It
first prepares a merge (or you prepare one ahead of the outage with `jrs-hotfix
merge prepare <package.zip>`), which decides every file the package ships under
the webapp:

| The file | What happens |
|---|---|
| only the vendor changed it, or it is new | the package's copy lands |
| only the site changed it | it is kept: not written, not snapshotted, listed in the ledger as kept |
| both changed a properties file | merged by key: the vendor's value where only the vendor changed a key, the site's where only the site did |
| both changed a page (`.jsp`, `.tag`, `.html`) | merged by line |
| both changed an XML file under `WEB-INF` | merged by line, then checked, and always confirmed by you |
| both changed a script, a stylesheet or a binary file | the package's copy lands; the plan lists the file so that you carry the change over by hand |
| the installer wrote it (section Settings files) | properties keep this server's values; `context.xml` and the `*-jdbc.xml` files stay |
| the site added it and a pattern of the readme would delete it | it stays: only a file a baseline or an earlier hotfix knows is the vendor's leftover |

A key, or a line, that both sides changed differently is a conflict. For a
properties key the rule is `--on-conflict` (or the setting `merge.onConflict`):
`ask` leaves the file for you, `mine` and `theirs` take one side and write the
other beside it as a comment, `fail` is `ask` with exit 2. The default is `ask`
at a terminal and `fail` otherwise: neither side wins silently. Two changes on
neighbouring lines of a page or an XML file count as a conflict too.

While a file waits for you, `apply` refuses with exit 2 and nothing on the
server is touched. The workspace is `merges/<mergeId>/` in the home:

```
jrs-hotfix merge status <mergeId>            every file and its state; exit 0 when none waits
jrs-hotfix merge show <mergeId> <path>       what the site changed, what the hotfix changed
jrs-hotfix merge resolve <mergeId> <path> --merged [<file>]   install the merged text
jrs-hotfix merge resolve <mergeId> <path> --mine | --theirs   keep the server's file, or take the hotfix's
jrs-hotfix merge edit <mergeId> <path>       run the tool of the setting merge.tool, then resolve
jrs-hotfix apply <package.zip> --merge <mergeId>
```

`--merged` takes the workspace's `files/<path>/merged`, which you edit with
your own editor, or the file you name. It is refused while it holds a conflict
marker, and an XML file must be well-formed, define no bean, filter, servlet or
listener twice, and neither bring back a bean the site removed nor lose one the
site added. `merge.tool` is a command line with `{base}`, `{mine}`, `{theirs}`
and `{merged}`, for example `code --wait --merge {mine} {theirs} {base}
{merged}`.

The merged files are staged, swapped, snapshotted and rolled back like every
other file, so a rollback brings the site's files back byte for byte. The plan
is built from the package and the merge alone: a file edited on the server
after the merge was prepared stops the apply (exit 2, "changed since the merge
was prepared"), and a run that was interrupted resumes with the same merged
files. `jrs-hotfix verify <package.zip>` on an installed hotfix says which
files were merged and which were kept.

Every apply writes the package's files as the hotfix's baseline, so the next
hotfix is compared with them and you supply nothing. `jrs-hotfix runs prune`
removes hotfix baselines older than the newest two and merges no installed
hotfix was applied with.

Without a baseline nothing of this applies: the package is applied as before,
every file it ships is replaced, and only the installer-written files are
spared. With baselines that do not fit the build the webapp states (a hotfix
applied by hand whose package was never added), `apply` is refused with exit 2
rather than run blind; add that package with `jrs-hotfix baseline add`, or
remove the baselines.

### A WAR as the target

`jrs-hotfix apply <package.zip> --war <in.war> --out <out.war>` makes a hotfixed
WAR from a WAR and touches no server: no service, no snapshot, no rollback. The
input is never modified; the output is written beside its record,
`<out.war>.jrs-hotfix.json`, which says what was applied, from what, and what
every file became. The same merge applies: `scan --war <in.war>` and
`merge prepare <package.zip> --war <in.war>` read the site's files from the WAR,
and a merge prepared for a WAR is what `apply --war` uses, by itself or with
`--merge <mergeId>`.

The home is `--home` (or `JRS_HOTFIX_HOME`), else `jrs-hotfix` beside the WAR.
The baseline of the release goes into that home as for a server; the hotfix's
baseline is written by every `apply --war`, so the next hotfix on the output
WAR is compared with it. The WAR is unpacked under `<home>/wars/` while it is
worked on, one WAR at a time; that copy may be deleted at any time.

Files of `js-install.zip` (`buildomatic`, `samples`) are not part of a WAR:
the plan says how many were left out, and they are applied on the server the
WAR is deployed to. A WAR is not a server's inventory: `apply --war` writes no
ledger entry, and the server that deploys the output knows the hotfix through
`jrs-hotfix record <package.zip>` there.

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

`docs/spec.md` is the design of 0.1; `docs/spec-customized-servers.md` the design of
0.2 and 0.3, with a section on where the built tool differs from it and why;
`docs/decisions/` the decisions taken along the way.
