# jrs-hotfix

**The safe way to install a Jaspersoft hotfix.** For JasperReports Server 10.x on Apache Tomcat.

jrs-hotfix applies, verifies, records and rolls back the cumulative hotfix packages Jaspersoft Support publishes, on Windows and Linux. Before it changes anything it shows you exactly what it will do and asks you to confirm. It stops the server for every change under `WEB-INF`, keeps a copy of every file it replaces or deletes so a hotfix can be taken out again, and finishes or undoes a job that a crash, a reboot or Ctrl-C interrupted.

On a customized server it keeps what the site changed: given the vendor's own WAR to compare with, it tells which files the site changed, keeps the ones the hotfix does not touch, merges settings files by key and pages and XML by line, and leaves what it cannot merge cleanly for you to resolve before the outage. It can also make a hotfixed WAR from a WAR, without a server.

It comes as one download with everything it needs inside. There is nothing else to install, and it works on servers with no internet access. The current release is [v0.5.0](https://github.com/robertgorsuch/jrs-hotfix/releases/latest).

---

## What you can do with it

| You want to… | Command |
|---|---|
| See what a hotfix package would do to this server | `jrs-hotfix verify <package.zip>` |
| Install a hotfix | `jrs-hotfix apply <package.zip>` |
| Take the latest hotfix out again | `jrs-hotfix rollback` |
| See the build this server is at, and what `rollback` would undo | `jrs-hotfix list` |
| Find out what the site changed in the webapp | `jrs-hotfix scan` |
| Install a hotfix without losing the site's changes | `jrs-hotfix baseline add <vendor.war>`, then `jrs-hotfix apply <package.zip>` |
| Make a hotfixed WAR from a WAR or a deployed webapp, with no server | `jrs-hotfix apply <package.zip> --war <in.war \| dir> --out <out.war>` |
| Hotfix a build host, which deploys with buildomatic and runs no server | `jrs-hotfix settings detect` in the distribution, then `jrs-hotfix apply <package.zip>` |
| Compare two WARs or webapps, or see what two of them each changed from a third | `jrs-hotfix compare <a> <b>` / `jrs-hotfix compare <base> <mine> <theirs>` |
| Finish or undo an interrupted job | `jrs-hotfix runs resume <id>` / `jrs-hotfix runs undo <id>` |

---

## Before you start

- **Run jrs-hotfix on the JasperReports Server machine itself**, except when the target is a WAR file (`--war`) or a build host, which need no server at all.
- **Use the account that owns the installation:** on Linux, the user that installed JasperReports Server and runs its Tomcat (`root` when a systemd unit controls it); on Windows, open **Command Prompt** with **Run as administrator**.
- **Have the hotfix package as Jaspersoft Support published it:** a `.zip` holding `readme.txt` plus one or both of `jasperserver-pro.zip` (paths under the webapp) and `js-install.zip` (paths under the installation). Do not unpack or repack it. Have the package's checksum from the support portal at hand: `apply` prints the SHA-256 of the file and asks whether it matches.
- **No Java to install.** The download includes its own Java, used only by jrs-hotfix. The server's Java is neither used nor changed, and no `JAVA_HOME` is needed.
- **Supported:** JasperReports Server 10.x, Commercial edition (`jasperserver-pro`), on Apache Tomcat, on Windows or Linux (x86-64), controlled as a Windows service, a systemd unit, `ctlscript`, `catalina` or by hand. Nothing changes on an unsupported input: a package for another release than the server is refused with exit 2, a file that is not an official package or a WAR that is not a 10.x webapp with exit 6, and another OS or architecture at start. The Community edition is out of scope.

---

## Quick start

### Step 1: Download and unpack

Download the archive for your system and `SHA256SUMS` from the [releases page](https://github.com/robertgorsuch/jrs-hotfix/releases):

- Windows: `jrs-hotfix-<version>-windows-x64.zip`
- Linux: `jrs-hotfix-<version>-linux-x64.tar.gz`

Check the download, then unpack it anywhere, next to the installation or not, and open a terminal in the unpacked folder:

```bash
# Linux
sha256sum -c --ignore-missing SHA256SUMS
tar -xzf jrs-hotfix-0.5.0-linux-x64.tar.gz -C /opt
cd /opt/jrs-hotfix-0.5.0
```

```bat
:: Windows (Command Prompt, run as administrator)
certutil -hashfile jrs-hotfix-0.5.0-windows-x64.zip SHA256    (compare with the line in SHA256SUMS)
tar -xf jrs-hotfix-0.5.0-windows-x64.zip -C C:\Jaspersoft
cd C:\Jaspersoft\jrs-hotfix-0.5.0
```

The folder holds `bin/`, `lib/`, `runtime/`, this README and the licence. To upgrade later, unpack the new archive and use it instead of the old one: everything jrs-hotfix keeps lives under the installation (see [Where jrs-hotfix keeps its files](#where-jrs-hotfix-keeps-its-files)), not in the archive.

> **How to type the commands.** This guide writes every command as `jrs-hotfix …`.
> On **Windows** type `bin\jrs-hotfix.cmd …` and on **Linux** type `bin/jrs-hotfix …`.
> A host that already has Java 21 can run the release's `jrs-hotfix.jar` instead: `java -jar jrs-hotfix.jar …`.

### Step 2: Connect jrs-hotfix to your server

Type `jrs-hotfix` on its own. The first run finds the installation and shows what it found: the install directory, the Tomcat root, the webapp, and how the service is controlled. Confirm each value (press Enter to keep it, or type another), and the settings are written. Then the menu opens.

For scripts, the same step without the menu is `jrs-hotfix settings detect`. `jrs-hotfix settings show` prints what was saved.

### Step 3: Look before you change anything

```bash
jrs-hotfix verify C:\Downloads\hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip
```

`verify` reads the package and the server and changes nothing. It says whether the package applies here (release, edition, build), which files it would replace, add and delete, which settings files keep this server's values, and prints the readme's manual steps in full. If it says the package cannot be applied, the message says why and what to do.

### Step 4: Do the job

Every command that changes something works the same way:

1. **It shows you the plan:** every step, the files it will touch, the warnings, and that the service will be stopped.
2. **It asks** `Run this plan? [y/N]`. Type `y` to go ahead. Anything else stops, and nothing has changed.
3. **It runs the steps**, one line per step, then says what happened and what to do next.

Want to see the plan without being asked to run it? Add `--plan` to `apply` or `rollback`. `--yes` answers every question for scripted runs. Without a terminal jrs-hotfix never prompts: where a confirmation is needed and `--yes` was not given, it stops with exit 2.

The examples below show the most common jobs.

---

## Common jobs

The examples use Windows paths. On Linux, use paths such as `/tmp/hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip` instead.

### Install a hotfix

```bash
jrs-hotfix apply C:\Downloads\hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip
```

jrs-hotfix prints the package's SHA-256 and asks you to confirm it matches the checksum on the support portal, shows the plan, and on `y` does this:

1. **preflight**: the installed release and edition match the readme; the build the webapp states is older than the package's (see [What is installed](#what-is-installed)); there is free space for staging and the snapshot; the webapp and install tree are writable; no run is pending; an add whose target already exists on disk becomes a replace.
2. **snapshot**: copy every file the package will replace or delete into `runs/<runId>/snapshot`, with hashes.
3. **stage**: extract the payload into `runs/<runId>/staging`, merge the settings files (see [Settings files](#settings-files-keep-this-servers-values)), and hash every file.
4. **stop**: stop the service and wait for its JVM to end within the timeout; force-stop only when configured.
5. **swap**: move each staged file into place (replace, add) or into the snapshot (delete); skip a file already at the target hash; refuse if any target is still locked.
6. **clear the JSP cache**: remove `<tomcatDir>/work/Catalina/localhost/<webappName>`, as the vendor's readme requires; Tomcat compiles the pages again on first use.
7. **start**: start the service, the companion database first when the host has one.
8. **wait**: poll for the server to answer, capped.
9. **keep the undo**: the snapshot, with a record of every file the hotfix wrote or deleted, becomes `undo/` in the home, replacing the previous one; remove the staging directory.

The service is always stopped for the swap. Unattended, `--yes` skips the checksum question after you have checked it yourself; the run log says whether it was confirmed or skipped.

**Manual steps.** Some hotfixes list manual steps in their readme: SQL for a given database, properties to add, or settings to re-apply in a file the hotfix overwrites. jrs-hotfix never runs any of this. It carries those sections of the readme as they are, every line of them: `verify` prints them before the outage, the plan shows the first lines of each section, and the run prints them again when it finishes and saves them to `runs/<runId>/notes.txt` for you to act on by hand.

**Libraries an earlier hotfix left behind.** A library under `WEB-INF/lib` that is an older version of one the package brings, and that the readme's lists do not name, is deleted as superseded when the latest apply or a baseline knows it as the vendor's (a library the hotfix before brought, or the release's). The plan lists them under their own heading, they go into the snapshot, and a rollback puts them back; `--keep-superseded` leaves them. A library nothing knows may be the site's own: it is reported and left, with one exception. When such a library is a web fragment (it holds `META-INF/web-fragment.xml`, as `log4j-jakarta-web` does), leaving it beside the newer one makes Tomcat refuse to deploy the whole webapp (`More than one fragment with the name`), so `apply` and `verify` refuse with exit 2 and say what to do: `baseline add` the hotfix that brought it, and it is deleted as superseded; or remove it by hand if it is the site's. This came from a field test where a hotfix brought log4j 2.25.4 and deleted the release's 2.24.3 while the 2.25.3 of the hotfix before it stayed, named by no list, and the server would not start.

### What is installed

```bash
jrs-hotfix list
```

```
on this server: 10.0.0 PRO, build 20260730_0457
can be undone:  JRSHF-10.0.0-20260730-0457, applied 2026-10-01T14:02:11Z by run 20261001-140211-a3f
```

The hotfix packages are cumulative, so the build the webapp states about itself (`WEB-INF/internal/jasperserver-pro.properties`) is the hotfix level of the files on disk, whoever put them there: jrs-hotfix, an operator following the readme, or a redeploy. jrs-hotfix keeps no list of hotfixes beside it. `apply` and `verify` compare the package's build with it:

- the webapp states the package's build: the hotfix is installed already, and the package is refused with exit 2;
- the webapp states a newer build: applying the package would take the server back, and it is refused with exit 2 (taking it back is what `rollback` is for);
- the webapp states an older build: the package applies;
- the webapp states no build: a warning, and a package whose files are all in place already, with nothing left to delete, is refused with exit 2, because the outage would change nothing.

`jrs-hotfix verify <package.zip>` on the hotfix the latest apply installed says which of its files were merged and which were kept.

### Take the latest hotfix out again

```bash
jrs-hotfix rollback
```

There is one level of undo: the latest apply. Replaced files come back exactly as they were, files the hotfix added are removed, and files it deleted are restored (on a build host, the WAR too, with no service to stop); the JSP cache is cleared after it as after a swap, and the restore is per file and idempotent, so a crash mid-restore resumes cleanly. Before the service is stopped, every file is checked against what the apply left: if the server changed since (a redeploy, a file edited by hand), the rollback is refused with exit 2 and names the files, and the files the hotfix replaced are under `undo/payload` in the home for whoever puts the server back by hand.

The undo is used up by the rollback, and replaced by the next apply: after either, the hotfix before cannot be taken out with jrs-hotfix. An apply that fails and is undone leaves the previous undo as it was. A hotfix applied by hand has no undo; the server is simply at its build.

### Keep the site's changes on a customized server

jrs-hotfix can tell what a site changed in the webapp, and in the installation's `buildomatic` and `samples`, given the vendor's own files to compare with. They are kept in the home as baselines:

```bash
jrs-hotfix baseline add C:\Jaspersoft\jasperreports-server-pro-10.0.0-bin.zip    # the release: the vendor's distribution, both areas
jrs-hotfix baseline add C:\Jaspersoft\jasperserver-pro.war    # or the release's WAR alone: the webapp only
jrs-hotfix baseline add C:\Downloads\hotfix_....zip           # a hotfix that is already on the server
jrs-hotfix baseline list
jrs-hotfix baseline remove <id>
```

The release is best given as the vendor's distribution the server was installed from: its ZIP, or the directory it was unpacked into (`buildomatic/` beside `jasperserver-pro.war`). Its WAR gives the webapp, and its `buildomatic/` and `samples/` the installation, so what the site changed in buildomatic is kept or merged too. The WAR alone (the directory that holds it, or an unpacked copy, will do) gives the webapp only; the installation's files are then replaced as without a baseline, and the plan says so. Either is read as a stream; the baseline keeps a hash of every file and the content of the files that can be merged: settings, XML, pages, and buildomatic's scripts and SQL. When a hotfix is on the server, its package is needed too, because the files it replaced are the vendor's and not the site's. A hotfix's baseline holds its `js-install.zip` files as well.

In the installation, `buildomatic/default_master.properties` is the site's own and is never compared or touched, and what buildomatic builds from it (`buildomatic/build_conf/`) is counted, not listed. (These two rules are to be confirmed against a real 10.0.0 distribution.)

Then see what the site changed:

```bash
jrs-hotfix scan
jrs-hotfix verify C:\Downloads\hotfix_....zip
```

`scan` compares every file of the webapp with the baseline and changes nothing. Its first line is the answer, `vanilla: no vendor file was changed` or `customized: N changed, M added, K removed`, and the files follow, each with its class: `X` reviewed XML, `P` properties, `T` pages, `G` scripts and stylesheets, `B` binary. Files that differ in line ends only are equal. Files the installer fills in for one server are listed as `INSTALLER` and are not customizations; logs and built scripts are counted, not listed; a deployed `applicationContext-externalAuth*.xml` is listed under its own heading. Exit 0 either way, 2 when there is no baseline that fits this installation. `verify <package.zip>` adds what the package would meet, whenever a baseline fits: for each file it ships, whether only the vendor changed it (replaced), only the site (kept), or both (a collision). (Before 0.6 that was `scan --package`.)

With a baseline that fits, `apply` no longer replaces what the site changed. It first prepares a merge (or you prepare one ahead of the outage with `jrs-hotfix merge prepare <package.zip>`), which decides every file the package ships under the webapp:

| The file | What happens |
|---|---|
| only the vendor changed it, or it is new | the package's copy lands |
| only the site changed it | it is kept: not written, not snapshotted, listed in the undo record as kept |
| both changed a properties file | merged by key: the vendor's value where only the vendor changed a key, the site's where only the site did |
| both changed a page (`.jsp`, `.tag`, `.html`) | merged by line |
| both changed an XML file under `WEB-INF` | merged by line, then checked, and always confirmed by you |
| both changed a script, a stylesheet or a binary file | the package's copy lands and the plan lists the file, so that you carry the change over by hand; or `merge resolve <mergeId> <path> --mine` keeps the site's copy, and the hotfix's change in that file is skipped |
| the installer wrote it (see [Settings files](#settings-files-keep-this-servers-values)) | properties keep this server's values; `context.xml` and the `*-jdbc.xml` files stay |
| the site added it and a pattern of the readme would delete it | it stays: only a file a baseline or an earlier hotfix knows is the vendor's leftover |

A key, or a line, that both sides changed differently is a conflict. For a properties key the rule is `--on-conflict` (or the setting `merge.onConflict`): `ask` leaves the file for you, `mine` and `theirs` take one side and write the other beside it as a comment, `fail` is `ask` with exit 2. The default is `ask` at a terminal and `fail` otherwise: neither side wins silently. Two changes on neighbouring lines of a page or an XML file count as a conflict too.

While a file waits for you, `apply` refuses with exit 2 and nothing on the server is touched. The workspace is `merges/<mergeId>/` in the home:

```bash
jrs-hotfix merge status <mergeId>                              # every file and its state; exit 0 when none waits
jrs-hotfix merge show <mergeId> <path>                         # what the site changed, what the hotfix changed
jrs-hotfix merge resolve <mergeId> <path> --merged [<file>]    # install the merged text
jrs-hotfix merge resolve <mergeId> <path> --mine | --theirs    # keep the server's file, or take the hotfix's (also for a script, stylesheet or binary file)
jrs-hotfix apply <package.zip> --merge <mergeId>
```

`--merged` takes the workspace's `files/<path>/merged`, which you edit with your own editor, or the file you name. It is refused while it holds a conflict marker, and an XML file must be well-formed, define no bean, filter, servlet or listener twice, and neither bring back a bean the site removed nor lose one the site added. The three sides are plain files under `files/<path>/` (`base`, `mine`, `theirs`), so any merge tool can be pointed at them.

The merged files are staged, swapped, snapshotted and rolled back like every other file, so a rollback brings the site's files back byte for byte. The plan is built from the package and the merge alone: a file edited on the server after the merge was prepared stops the apply (exit 2, "changed since the merge was prepared"), and a run that was interrupted resumes with the same merged files.

Every apply writes the package's files as the hotfix's baseline, so the next hotfix is compared with them and you supply nothing. `jrs-hotfix runs prune` removes hotfix baselines older than the newest two and merges other than the one the latest apply was made with.

Without a baseline nothing of this applies: the package is applied as described under [Install a hotfix](#install-a-hotfix), every file it ships is replaced, and only the installer-written files are spared. With baselines that do not fit the build the webapp states (a hotfix applied by hand whose package was never added), `apply` is refused with exit 2 rather than run blind; add that package with `jrs-hotfix baseline add`, or remove the baselines.

### Make a hotfixed WAR from a WAR

```bash
jrs-hotfix apply C:\Downloads\hotfix_....zip --war C:\build\jasperserver-pro.war --out C:\build\jasperserver-pro-hotfixed.war
jrs-hotfix apply C:\Downloads\hotfix_....zip --war C:\Jaspersoft\apache-tomcat\webapps\jasperserver-pro --out C:\build\jasperserver-pro-hotfixed.war
```

`--war` takes a WAR file, or a deployed or exploded webapp directory, which is read and never written. A deployed webapp gives a WAR that carries its configuration: `META-INF/context.xml`, the `META-INF/*-jdbc.xml` files and the installer-written settings files with this site's values, merged with the package's as on a server.

For an environment that deploys one generic WAR with its own database configuration, add `--generic`: those files are written as the vendor ships them instead, the package's copy where the package has one, else the release baseline's, and a `*-jdbc.xml` the vendor has none of is left out. Every other change of the site is kept or merged as without it. `--generic` needs a release baseline (exit 2 without one), works with `--war` only, and the plan lists the files it takes from the vendor.

This touches no server: no service, no snapshot, no rollback. The input is never modified, and the output is the one file written: what it carries is stated by its own `WEB-INF/internal/jasperserver-pro.properties`, as on a server, and the run's record is under `runs/<runId>/` in the home. The same merge applies: `scan --war <in.war>` and `merge prepare <package.zip> --war <in.war>` read the site's files from the WAR, and a merge prepared for a WAR is what `apply --war` uses, by itself or with `--merge <mergeId>`.

The home is `--home` (or `JRS_HOTFIX_HOME`), else `jrs-hotfix` beside the WAR, and for a deployed webapp beside Tomcat's `webapps/`, never inside it. The baseline of the release goes into that home as for a server; the hotfix's baseline is written by every `apply --war`, so the next hotfix on the output WAR is compared with it. The WAR is unpacked, or the directory copied, under `<home>/wars/` while it is worked on, one at a time; that copy may be deleted at any time.

Files of `js-install.zip` (`buildomatic`, `samples`) are not part of a WAR: the plan says how many were left out, and they are applied on the server the WAR is deployed to. A WAR has no undo: `apply --war` leaves the home's `undo/` alone, and the server that deploys the output states the hotfix's build like any other.

To patch a buildomatic kept elsewhere at the same time, add `--install-out <dir>`: the package's `buildomatic` and `samples` files are applied to that installation tree, merged with the site's as on a server when the home has the distribution as a baseline, after a snapshot, and `jrs-hotfix rollback --home <home>` puts them back (the output WAR stays; it is yours). An empty or absent directory receives them as the package has them. `--install-out` needs a home of its own: the home of a server or a build host is refused, since its undo is that installation's.

### Hotfix a build host

```bash
cd C:\build\jasperreports-server-pro-10.0.0      # buildomatic\, samples\, jasperserver-pro.war
jrs-hotfix settings detect
jrs-hotfix baseline add C:\Downloads\jasperreports-server-pro-10.0.0.zip
jrs-hotfix apply C:\Downloads\hotfix_....zip
js-ant deploy-webapp-pro                         # from buildomatic, as always: jrs-hotfix never runs it
```

A build host holds the unpacked distribution, `buildomatic/`, `samples/` and `jasperserver-pro.war`, and deploys to application servers elsewhere; it runs no server. `settings detect` run in the distribution's directory (or with `--home <distribution>\jrs-hotfix`) recognizes one and writes settings with `service.kind` `none`. Then `apply` does to the distribution what it does to a server, without a service: the WAR is hotfixed as `--war` does it, beside itself, checked entry by entry and only then put in place of the old one, and the `buildomatic` and `samples` files are swapped in place. With the distribution as a baseline, the site's changes in both, the webapp's configuration files inside the WAR and buildomatic's, are kept and merged as on a server.

The old WAR is moved into the home, not copied, and is the undo with the installation files: `jrs-hotfix rollback` puts both back, after checking that nothing changed since the apply, the WAR included. Deploying is yours: the plan ends by naming `js-ant deploy-webapp-pro`, and after a rollback the earlier WAR is deployed the same way. `list`, `scan` and `verify` read the WAR the distribution holds.

### Compare WARs, webapps and distributions

```bash
jrs-hotfix compare C:\Jaspersoft\jasperserver-pro.war server               # what this site changed
jrs-hotfix compare test\jasperserver-pro.war prod\jasperserver-pro.war      # two environments
jrs-hotfix compare vendor.war site.war hotfix.zip --out C:\merged           # three-way, with the result
jrs-hotfix compare vendor.war site.war --show WEB-INF/web.xml               # one file's differences
```

`compare` reads any of: a WAR, a webapp directory (exploded or deployed), the vendor's distribution (its ZIP or directory, which brings the webapp and buildomatic), an official hotfix package (its files), or `server`, this home's server. It needs no settings unless one input is `server`, and it changes nothing.

With two inputs it lists the files that differ or are in only one, each with its class; files that differ in line ends only are the same, and logs and buildomatic's generated configuration are counted, not listed. With three (base, mine, theirs) it says for each file whether mine changed it, theirs did, both alike, or both differently, which is merged as `merge` merges (properties by key, pages and XML by line) or is a conflict. `--out <dir>` writes that result into a new or empty directory, one directory per area, with conflict markers where a conflict is. An area is compared only when every input has it.

It exits 0 when the inputs are the same, and 7, a warning, when they differ or a three-way comparison has conflicts.

---

## If something goes wrong

jrs-hotfix finishes every job by saying what happened and what to do next. The number it ends with tells you the result:

| Result | What it means | What to do |
|:---:|---|---|
| **0** | Done | Nothing |
| **1** | The command line was wrong | `jrs-hotfix --help` |
| **2** | A check failed before anything changed (preflight, `verify`, a merge that waits for you) | Read the message, fix it, run the command again |
| **3** | A step failed, and jrs-hotfix put everything back | Read the message, fix the cause, run it again |
| **4** | A step failed, and jrs-hotfix could not put everything back | The message names the files and the snapshot; restore them from `runs/<runId>/` in the home |
| **5** | You cancelled it | Nothing |
| **6** | Not an official package, or not a supported installation (not 10.x, not Tomcat) | Nothing changed. Check [Before you start](#before-you-start) |
| **7** | `compare` only: the inputs differ, or a three-way comparison has conflicts | Read the report; it is a finding, not a failure |
| **8** | An earlier job was interrupted (a crash, a reboot, Ctrl-C) | Run `jrs-hotfix runs resume <id>` to finish it, or `jrs-hotfix runs undo <id>` to undo it |
| **9** | Another jrs-hotfix job is already running | Wait for it to finish |

A run whose journal has no terminal state blocks every mutating command with exit 8 until `runs resume` finishes it or `runs undo` undoes it; the menu's entry 6 offers both first. A run's snapshot lives in its run directory for as long as the run does, and goes when it ends: an apply's becomes the undo, any other is deleted. A run that failed with exit 4 keeps its snapshot, which its message told you to restore from, until `runs prune --include-failed`.

```bash
jrs-hotfix runs list                                   # every job that has run, and how it ended
jrs-hotfix runs show <id>                              # the run's record, every step transition and the stored plan: exactly where it stopped
jrs-hotfix runs resume <id>                            # finish an interrupted run
jrs-hotfix runs undo <id>                              # undo an interrupted run (`runs rollback` before 0.6)
jrs-hotfix runs prune --older-than <days> [--include-failed]
```

A run interrupted while the service was down, at an installation outside the default paths, is found again through the home jrs-hotfix remembers it used last; if that is gone too, pass `--home <installDir>/jrs-hotfix` (or set `JRS_HOTFIX_HOME`) to `runs resume` or `runs undo`.

---

## Getting help

Not sure which command you need? Type `jrs-hotfix` on its own: a menu walks you through the common jobs (apply, undo the latest hotfix, verify, check for customizations, the installed build, recent runs and recovery, settings) and prints the command it runs for each, so using the menu also teaches the scripted form.

Everything is built in and works without internet access:

```bash
jrs-hotfix --help                 # every command
jrs-hotfix apply --help           # the options of one command
jrs-hotfix --docs                 # the documentation page, in the terminal
```

The full command list:

```
jrs-hotfix                                   menu at a terminal; usage otherwise
jrs-hotfix apply <package.zip> [--merge <mergeId>] [--on-conflict <rule>] [--keep-superseded] [--plan] [--yes]
jrs-hotfix apply <package.zip> --war <in.war | dir> --out <out.war> [--install-out <dir>] [--merge <mergeId>] [--generic]
jrs-hotfix rollback [--plan] [--yes]
jrs-hotfix verify <package.zip> [--war <file.war | dir>]
jrs-hotfix scan [--war <file.war | dir>]
jrs-hotfix baseline [list | add <war | dir | package.zip> | remove <id>]
jrs-hotfix merge [prepare <package.zip> [--on-conflict <rule>] [--war <file.war | dir>] | list | status <mergeId>
                  | show <mergeId> <path>
                  | resolve <mergeId> <path> --merged [<file>] | --mine | --theirs
                  | discard <mergeId>]
jrs-hotfix list
jrs-hotfix compare <a> <b> [<c>] [--out <dir>] [--show <path>]
jrs-hotfix runs [list | show <id> | resume <id> | undo <id> | prune --older-than <days> [--include-failed]]
jrs-hotfix settings [show | set <key> <value> | detect]
jrs-hotfix --docs | --version | --help
```

Global options: `--home <dir>` picks the home directory (default: detected). `--yes` answers every confirmation without asking. `--plan` prints the plan for `apply` or `rollback` and stops; nothing is changed. Without a terminal nothing prompts, and a confirmation that `--yes` did not give fails closed (exit 2). (`--non-interactive`, which forced that at a terminal, is still accepted but no longer listed.)

---

## Good to know

### Settings files keep this server's values

The installer writes values for one server into four properties files of the webapp: `WEB-INF/js.quartz.properties`, `WEB-INF/js.jdbc.properties`, `WEB-INF/classes/hibernate.properties` and `WEB-INF/classes/keystore.init.properties`. When a package ships one of them and the server's file holds other values, jrs-hotfix installs the package's file with the server's values: a key both have keeps the server's value, a key only the package has is taken from the package, and a key only the server has is carried over under a comment at the end. The plan names the keys, never the values. Where a fix depends on the package's value of a kept key, set it by hand. The server's comments are not carried; the file as it was is in the snapshot, and a rollback puts it back byte for byte.

`META-INF/context.xml` and the `META-INF/*-jdbc.xml` files hold the database connection the installer wrote. When a package ships one and the server has it, the server's file stays: it is not snapshotted, staged or swapped, and the undo record does not list it. If the package's copy differs from the server's, the plan and `notes.txt` show the package's copy, so that what the hotfix changed in it can be carried over by hand.

Every other `.xml` and `.properties` file the package ships is replaced, and the plan names the ones in the webapp: settings you changed in them must be applied again. That is so on a server without a baseline; with one, see [Keep the site's changes on a customized server](#keep-the-sites-changes-on-a-customized-server).

### Where jrs-hotfix keeps its files

Everything jrs-hotfix keeps lives under its home, `<installDir>/jrs-hotfix/` (override with `--home <dir>` or `JRS_HOTFIX_HOME`). Back this folder up along with the server.

```
settings.json
lock                          pid + start time of the running command
runs/<runId>/run.json         the run's record: operation, start, end, state, exit code
runs/<runId>/plan.json        the fingerprinted plan as built
runs/<runId>/journal.jsonl    one line per step transition, appended, fsynced
runs/<runId>/run.log
runs/<runId>/notes.txt        the readme's manual steps for this package
runs/<runId>/staging/         payload extracted before the outage; removed at the end
runs/<runId>/stop-service.stopped   marker: this run stopped the service
runs/<runId>/<step>/manifest.json   the run's snapshot while it runs (an apply's: snapshot/, a rollback's: pre-rollback-<id>/)
runs/<runId>/<step>/payload/...     replaced and deleted files, at their relative paths
undo/undo.json                what the latest apply did: every file it wrote or deleted, with its hash before and after
undo/manifest.json            the latest apply's snapshot, until the next apply or a rollback
undo/payload/...
baselines/<id>/manifest.json  the vendor's files: a hash for every file of a release's WAR or of a hotfix
baselines/<id>/payload/...    the content of the mergeable ones (settings, XML, pages)
merges/<mergeId>/merge.json   a prepared merge: what an apply does with every file the package ships
merges/<mergeId>/report.txt   the same, as `merge status` prints it
merges/<mergeId>/files/<path>/base|mine|theirs|merged   the three sides of a file that needed a merge, and the result
wars/webapps/<name>/          the copy of the WAR or webapp directory being worked on (--war); may be deleted at any time
```

### Settings

`settings.json` holds these keys, edited through the menu or `jrs-hotfix settings set <key> <value>`:

| Key | Meaning | Default |
|---|---|---|
| `installDir` | JRS installation root | detected |
| `tomcatDir` | Tomcat root | `<installDir>/apache-tomcat` |
| `webappName` | `jasperserver-pro` or `jasperserver` | detected from `webapps/` |
| `service.kind` | `windows-service`, `systemd`, `ctlscript`, `catalina`, `manual`, or `none` on a build host | detected |
| `service.name` | service name (windows-service, systemd) | detected |
| `service.scriptPath` | script path (ctlscript, catalina) | detected |
| `service.stopTimeoutSeconds` | how long a stop may take | 180 |
| `service.forceStopAfterSeconds` | end the JVM if the script outlives this (scripts only, off when absent) | absent |
| `baseUrl` | used only by the wait-for-server probe | `http://localhost:<port from server.xml>/<webappName>` |
| `merge.onConflict` | a properties key both the site and a hotfix changed: `ask`, `mine`, `theirs`, `fail` | absent: `ask` at a terminal, `fail` otherwise |

### Checking your download

`SHA256SUMS` on each release page lists every file of the release:

- Linux: `sha256sum -c --ignore-missing SHA256SUMS`
- Windows: `certutil -hashfile jrs-hotfix-0.5.0-windows-x64.zip SHA256`, or `Get-FileHash` in PowerShell, and compare with the archive's line

Each release page also carries the release notes; `docs/releases/` in this repository holds every release's notes.

---

## For developers

You do not need to build jrs-hotfix to use it: download the release archive above. Building from source needs JDK 21: `scripts/mvn.sh verify` (JDK 21 pinned) builds, tests and runs the acceptance suite against the shaded jar. While iterating, `scripts/fast.sh test <TestClass[,TestClass]>` compiles with Error Prone and `-Werror` and runs just those unit tests, and `scripts/fast.sh fmt` formats with google-java-format before committing.

The design is in [`docs/spec.md`](docs/spec.md) (0.1) and [`docs/spec-customized-servers.md`](docs/spec-customized-servers.md) (0.2 and 0.3, with a section on where the built tool differs from it and why); the decisions taken along the way are in [`docs/decisions/`](docs/decisions/).

## Licence

jrs-hotfix is free software, licensed under the GNU General Public License, version 3 only (SPDX `GPL-3.0-only`). See [`LICENSE`](LICENSE). The libraries bundled in the download keep their own licences.
