# jrs-hotfix 0.2: hotfixes on customized servers

Status: draft for review, 2026-09-29; the maintainer's rulings of 2026-09-30 are folded in
(see "Decided 2026-09-30" at the end). Extends `docs/spec.md` (the 0.1 design); section
numbers there are cited as "spec 4.1". Source of the requirements: the hotfix flow diagram
(vanilla check, three-way collisions, merge by file kind, library cleanup, deployed or
zipped WAR) compared with jrs-hotfix at 56c242f.

**Phase 1 (section 11) landed in v0.1.0**, on 2026-09-29, after the tool met the real package
and two real servers: the installed build is read and shown (section 1), the four
installer-written properties files are merged (4.4), the JSP cache is cleared (5a), and a
hotfix already in place is refused. The three parts of phase 1 that v0.1.0 left open landed
on 2026-09-30: the preflight `build` table of section 1, `META-INF/context.xml` never
replaced (4.4), and the superseded-library warning (6). The measurements of 2026-09-29 are
folded in where they change the design; each is marked "measured 2026-09-29".

## Problem

The vendor's readme ends its install steps with one sentence that carries most of the
risk: "If any settings or customizations were previously applied to the files listed under
'Added files' or 'Modified files', make sure to reapply them to the newly copied files."
jrs-hotfix 0.1 does the copy safely and leaves that sentence to the operator: it prints one
warning naming the `.xml` and `.properties` files it is about to replace, and replaces
them.

Three things follow, all observed on the JRS 10.0.0 PRO install on this machine with the
real package `hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip`:

1. The package ships nine text files under the webapp: four `applicationContext*.xml`,
   `web.xml`, `jasperreports.properties`, `js.quartz.properties`, a bundle, and
   `WEB-INF/internal/jasperserver-pro.properties`. On the server all nine are byte-identical
   to the package. Whatever the site had in `web.xml` (the 2026-09-02 inventory lists
   edits) and whatever the installer wrote into `js.quartz.properties` is gone.
2. The server carries the hotfix (`BUILD_DATE_STAMP=20260730`, `BUILD_TIME_STAMP=0457` in
   `WEB-INF/internal/jasperserver-pro.properties`) but there is no ledger. 0.1 reads the
   hotfix level from its ledger only, so it would plan this package as not installed.
3. The readme tells the operator to remove the application server's JSP cache. 0.1 does
   not.

The diagram describes the missing half: decide whether the server is customized, find the
files where the site's change and the vendor's change collide, merge by file kind, and only
then copy. 0.2 adds that half in front of the 0.1 apply, without weakening it.

## Scope

- Same as spec "Scope": JRS 10.x, Tomcat, Windows and Linux, official packages only.
- Commercial (PRO) edition only. The Community edition is disregarded: nothing here reads
  its files, and detection (spec 2) already refuses it.
- New: reading the installed build, a vendor baseline, a customization scan, a three-way
  merge workspace prepared before the outage, merged files carried through the existing
  swap, superseded-library cleanup, the JSP cache, and a WAR file as a target.
- Merging covers files under the webapp only. Files from `js-install.zip` (buildomatic,
  samples) are replaced as in 0.1 and keep the 0.1 warning.
- Not in scope: registering customizations for upgrades (that stays with the future
  customizations tool), Tomcat-side files (`setenv`, `server.xml`, `lib/*.jar`), themes in
  the repository, clusters, and any graphical merge tool of our own.

## Scenarios the tool must handle

10. Tell the operator whether the server is customized, and list what differs from the
    vendor's files, before any package is chosen.
11. Notice a hotfix that was applied by hand: the installed build is newer than the
    ledger says.
12. Apply a package to a customized server where no customized file is touched by the
    package: behave exactly as 0.1.
13. Apply a package where the site changed a file the vendor did not change: keep the
    site's file.
14. Apply a package where both changed a properties file: merge by key, report keys both
    changed.
15. Apply a package where both changed a Spring context or `web.xml`: show the three-way
    diff, let the operator resolve it before the outage, refuse to apply while any file
    is unresolved.
16. Roll back a hotfix applied with merged files: the site's files come back exactly.
17. Keep installer-written files (`js.quartz.properties` and the others in section 4.4)
    from losing the site's values on every server, customized or not.
18. Remove older versions of a library the package brings in a newer version, when the
    readme's lists missed them, and never remove a jar the site added.
19. Produce a hotfixed WAR from a WAR, with the same merge, without a server.

## Approach

Merge before the outage, swap once. The merge is prepared and resolved while the server is
running, into a workspace under the home. At apply time the merged files replace the
package's copies in staging, so the existing `swap` step puts them in place and the
existing snapshot and rollback cover them. There is no second write pass and no new
compensation.

Rejected:

- A post-swap overlay step. It writes every merged file twice, leaves a window where the
  stock file is live in a started-then-stopped server, and needs its own compensation.
- A separate merge tool that emits a rebuilt package. jrs-hotfix refuses anything that is
  not an official package (exit 6), and should keep doing so: the checksum the operator
  confirms against the support portal is the package's.
- Merging during the outage. A conflict in `applicationContext.xml` found with the service
  down turns a ten-minute window into an open-ended one.

## 1. Installed build

`WEB-INF/internal/jasperserver-pro.properties` (PRO) states `PRO_VERSION`,
`BUILD_DATE_STAMP` (`yyyymmdd`) and `BUILD_TIME_STAMP` (`hhmm`). Every cumulative package
ships this file, so after an apply the stamps equal the package's build, which is also the
tail of the ledger id `JRSHF-<release>-<date>-<time>`.

`InstalledBuild` reads the file as ISO-8859-1 text, tolerating the leading whitespace the
vendor's build leaves before the two stamp keys. It returns release, date and time, or
empty when the file or a key is absent. It never throws. (In v0.1.0: read, and shown by
`list` and the menu header.)

Preflight (spec 4.1 step 1) gains one check, `build` (`BuildCheck`, landed 2026-09-30;
v0.1.0 covered the second row another way, by refusing a package whose files are all in place
at their target hashes with no `INSTALLED` entry, and that test stays):

| Installed build | Ledger | Result |
|---|---|---|
| equals newest `INSTALLED` or `RECORDED` entry | any | ok |
| equals the package being applied | no entry for it | refuse, exit 2: "this hotfix is already on the server but not in the ledger; run `jrs-hotfix record <package.zip>`" |
| newer than every entry, not the package's | any | warn: "a hotfix with build X was applied outside jrs-hotfix"; refuse with exit 2 when a merge is requested, because the base cannot be established without that package (section 2) |
| older than the newest `INSTALLED` entry | any | refuse, exit 2: the webapp was replaced under the ledger; say which entry, and name `jrs-hotfix forget <id>`, the command that takes a stale entry out (added 2026-09-30 after a redeploy from a WAR met this row with no way out but editing `ledger.json`) |
| unreadable | any | warn once, continue as 0.1 |

As built, two limits keep the check from refusing a healthy server:

- Only an `INSTALLED` entry whose hotfix shipped the build file is compared. A package
  without `jasperserver-pro.properties` leaves the stamps as they were, and its entry says
  nothing about them.
- "Newer than every entry" is a warning only when there is such an entry, or a release
  baseline (section 2) that says which build the release itself has. With an empty ledger
  and no baseline, a release's own build cannot be told from a hotfix applied by hand, and
  nothing is said.

`list` and the menu header print the installed build beside the release. Detection (spec
2) is unchanged: the release still comes from the jar names, and the two must agree or
`settings detect` says which file disagrees.

The Community edition is out of scope (decided 2026-09-30): its file is
`jasperserver.properties` and nothing reads it.

## 2. Baseline

A three-way merge needs three copies of a file:

| Name | What | Where it comes from |
|---|---|---|
| base | the vendor's file at the server's current level | the baseline store |
| mine | the file on disk | the webapp |
| theirs | the vendor's file in the package being applied | the package |

The baseline store lives in the home:

```
baselines/release-<version>-<edition>-<build>/manifest.json     path, sha256, size per file
baselines/release-<version>-<edition>-<build>/payload/...       mergeable files only (section 4.1)
baselines/<hotfixId>/manifest.json
baselines/<hotfixId>/payload/...
```

A release baseline is keyed by build as well as by release and edition (measured
2026-09-29: the 10.0.0 PRO WAR the installer on this machine laid down is build
`20260121_2317`, while the package readme's "Important" section names `20251112_1144` as a
10.0.0 build; one release has more than one base build, and the build is in
`WEB-INF/internal/jasperserver-pro.properties` inside the WAR).

- `baseline add <war | unpacked webapp | distribution dir>` fills the release baseline.
  A WAR is read as a stream and never unpacked to disk whole. The manifest records a
  SHA-256 for every file in the source, so the scan can classify binaries too; the payload
  keeps only mergeable files, a few megabytes. The manifest also records which files carry
  the installer's `@@BITROCK_*@@` placeholders: that list is what section 4.4 uses.
- `baseline add <package.zip>` fills a hotfix baseline from an official package, for a
  hotfix that was applied by hand. The package must parse as in spec 4.1.
- The `record` step of every apply (spec 4.1 step 8) writes the hotfix baseline from the
  package it just applied. From the second hotfix on, the operator supplies nothing.
- Base for a path = the hotfix baseline of the installed build when it holds the path, else
  the release baseline. Packages are cumulative, so one hotfix baseline is enough. When the
  installed build is a hotfix and its baseline is absent (pruned, or applied by hand), a
  merge is refused with exit 2 and the message names `baseline add <package.zip>`: an older
  base would show the vendor's own changes as the site's.
- `runs prune` also removes hotfix baselines older than the newest two (decided
  2026-09-30). Two are kept so that one rollback still finds its base. Release baselines
  are never pruned; `baseline remove <id>` removes any of them by hand.
- A release baseline is verified against the installation before use: at least 95 percent
  of the jars under `WEB-INF/lib` that the baseline lists must exist on disk with the same
  hash, or the baseline is refused as belonging to another release or edition.

Without a release baseline the tool cannot say what the site changed. It then behaves as
0.1 with two differences: installer-written files are still merged (section 4.4, which
needs no base), and the warning names the command that would enable the rest.

As built (2026-09-30, `BaselineStore`, `BaseView`):

- A manifest row also holds the hash of the file with line ends normalised (`textSha256`),
  for the text classes X, P, T and G. It is what lets a script or stylesheet, which has no
  payload, be compared without its line ends (section 3).
- A hotfix manifest also holds `deleted`: every path and glob the package's readmes list
  for deletion. Seen through the hotfix, a release file its readme deletes is absent, so
  the scan does not report it as removed by the site.
- The 95 percent test is made through the hotfix: a library the hotfix ships is compared
  with the hotfix's hash, and one its readme deletes is not expected. Without that, a
  cumulative hotfix that replaces a good part of `WEB-INF/lib` would make every release
  baseline look like another release.
- The build the webapp states selects the base. It must be a release baseline's build, or a
  hotfix baseline's; any other build has no base, and `scan` exits 2 naming
  `baseline add <package.zip>`.
- Measured on the JRS 10.0.0 PRO on this machine: the release baseline has 5819 files,
  1093 of them mergeable and kept, five installer-written as listed in 4.4; the baseline
  of the package of 2026-07-30 has 299 files under the webapp, 9 of them mergeable. Adding
  each takes about 20 seconds.

## 3. Scan

`jrs-hotfix scan [--package <package.zip>]` is read-only and answers the diagram's first
question. It compares every file under the webapp with the base and prints:

| Class | Meaning |
|---|---|
| `CHANGED` | in both, different: the site edited a vendor file |
| `ADDED` | only on disk: the site added it (a JDBC driver, a custom jar, a JSP) |
| `REMOVED` | only in the base: the site deleted a vendor file |
| `INSTALLER` | a file the installer writes with site values (section 4.4); always listed, never counted as a customization |
| `GENERATED` | built output and caches (section 4.1 class G); listed as a count, not by path |

The first line is the answer: "vanilla: no vendor file was changed" or "customized: N
changed, M added, K removed". Exit 0 either way; exit 2 when there is no baseline.

With `--package`, each `CHANGED` or `REMOVED` path gains the package's view, which is the
diagram's collision test:

| mine vs base | theirs vs base | Verdict | Plan action |
|---|---|---|---|
| same | same | untouched | none |
| same | different | vendor change only | `replace` (0.1) |
| different | same or absent from package | site change only | `keep`: the file is not written |
| different | different, mine equals theirs | already applied | none |
| different | different | collision | merge by class (section 4) |
| removed | different | collision | operator decides: stay removed, or take theirs |

Line endings are normalised to LF before comparison for text classes, so a file saved by
a Windows editor is not a customization.

As built (2026-09-30, `Scan`, `jrs-hotfix scan`):

- A class G file the vendor ships and the site changed is listed by path as `CHANGED`, with
  its class beside it: it is the file the package will overwrite, and a count would hide
  it. `GENERATED` counts only files the vendor does not ship: anything under
  `WEB-INF/logs/`, and scripts or stylesheets the base does not have.
- A file the installer writes whole, which the vendor's WAR therefore does not hold
  (`WEB-INF/classes/keystore.init.properties`), is `INSTALLER`, not `ADDED`.
- The verdict table has three more rows than the design's: `new in the package` (neither
  the base nor the server has the file), `removed by the site` with the package shipping
  the base's copy (it stays removed), and `installer-written` (section 4.4).
- A class G or B file that both changed is a collision the package wins: the row's action
  is "replace (the site's change is lost)".
- Measured on the JRS 10.0.0 PRO on this machine, with the hotfix of 2026-07-30 applied:
  `customized: 1 changed, 4 added, 0 removed` (`js.config.properties`, a backup copy of
  it, `iijdbc.jar`, `actian-chart-customizers.jar`, and `keystore.init.properties` before
  the rule above). The scan takes about 15 seconds once the files are in the file cache
  and 100 seconds when they are not.

## 4. Merge

### 4.1 File classes

| Class | Paths | How it is merged | Who resolves a collision |
|---|---|---|---|
| P properties | `*.properties` | by key (4.2) | automatic; keys both changed follow the conflict policy |
| T text templates | `*.jsp`, `*.jspf`, `*.tag`, `*.tld`, `*.html` outside `scripts/` and `optimized-scripts/` | by line (4.3) | automatic when clean; operator when not |
| X reviewed XML | `WEB-INF/applicationContext*.xml`, `WEB-INF/web.xml`, `WEB-INF/*-servlet.xml`, `META-INF/context.xml`, other `*.xml` under `WEB-INF` | by line (4.3), then checked (4.3) | operator always confirms, clean or not |
| G generated | `scripts/**`, `optimized-scripts/**`, `*.js`, `*.js.map`, `*.css` | never merged: theirs wins | nobody; a site change is reported and kept in the snapshot |
| B binary | `*.jar`, `*.class`, images, fonts, everything else | never merged: theirs wins | nobody; a site change is reported and kept in the snapshot |

Class G is the diagram's "identify JavaScript changes and exclude them". The package
ships 101 JavaScript files, 35 of them under `scripts/_chunks`: they are build output, a
line merge of them means nothing, and a site that patched one must rebuild its patch
against the new bundle. The plan lists such files under their own heading so the operator
sees them before the outage, and the snapshot holds the site's copy.

Webapp CSS is class G (decided 2026-09-30): themes live in the repository, not the webapp,
so a stylesheet under the webapp is vendor output and is replaced.

Classes are decided by path, in the order X, P, G, T, B; the first match wins.

### 4.2 Properties, by key

The parser keeps lines: comments, blank lines, continuation lines, key order and the
separator each key used. `java.util.Properties` is not used for writing, because it drops
all of that.

The result starts as theirs. Then, for every key:

| base | mine | theirs | Result |
|---|---|---|---|
| v | v | anything | theirs (the vendor's change, or no change) |
| v | w | v | w in place (the site's change) |
| absent | w | absent | w appended in a block headed `# site settings re-applied by jrs-hotfix <runId>` |
| v | absent | v | removed (the site removed it) |
| v | w | x, x differs from w | both changed: conflict policy |
| v | absent | x | both changed: conflict policy |
| absent | w | x, x differs from w | both changed: conflict policy |

Conflict policy (`--on-conflict`, also a setting `merge.onConflict`):

| Value | Meaning | Default |
|---|---|---|
| `ask` | the file is marked `CONFLICT`; the operator resolves it as in 4.5 | at a terminal |
| `mine` | the site's value wins; the vendor's value is written above it as a comment | never |
| `theirs` | the vendor's value wins; the site's value is written above it as a comment | never |
| `fail` | preparing the merge fails, exit 2 | with `--non-interactive` |

Neither side wins silently by default: this package's closed issues include class-filter
and path-validation settings (JSSEC-163 to JSSEC-182), and a site value that silently
overrides one of those is a security regression nobody chose.

### 4.3 Text and XML, by line

A diff3 over lines: Myers diff of base to mine and base to theirs, hunks that do not
overlap merge, hunks that overlap and differ are a conflict written with markers:

```
<<<<<<< mine (on the server)
...
||||||| base (vendor, build 20251112_1144)
...
=======
...
>>>>>>> theirs (hotfix JRSHF-10.0.0-20260730-0457)
```

The implementation is in-house, about 300 lines, with `UnifiedDiff` copied from jrsctl
`ops.customizations` for display. No dependency is added (spec 1).

Class X files are checked after the merge, clean or resolved, and a failed check keeps the
file unresolved:

- the file is well-formed XML and has no conflict marker left;
- in a Spring context, no `bean` `id` or `name` appears twice;
- every bean the base had that mine removed is still absent, and every bean mine added is
  still present (a resolution that drops the site's bean by accident is caught);
- in `web.xml`, no `filter-name`, `servlet-name` or `listener-class` appears twice.

As built (2026-09-30): "twice" is relative to the two sides. The vendor's own 10.0.0
`web.xml` declares the same two listeners ten times each, and fourteen of its contexts
define a bean twice, so a merged file is refused only when a name stands more often in it
than in the site's file and in the hotfix's: a repeat the vendor ships passes, one the merge
doubled does not. Found by the dry run against the real WAR and package, where the first
rule refused every merged `web.xml`.

A class X file always needs the operator's confirmation, even when the merge was clean: a
clean line merge of a Spring context can still wire two beans where one was meant.

### 4.4 Installer-written files

These hold values the installer writes for this site. They differ from the vendor's copy
on every server, including a server nobody customized. Which files they are comes from the
baseline, not from a list in the code: the files whose vendor copy carries an
`@@BITROCK_*@@` placeholder. Measured 2026-09-29 in the 10.0.0 PRO WAR, five files:

```
META-INF/context.xml                @@BITROCK_DB_HOSTNAME@@ @@BITROCK_DB_PORT@@ @@BITROCK_DB_USER@@ ...
META-INF/foodmartDS-jdbc.xml        @@BITROCK_DB_USER@@
META-INF/jasperserverDS-jdbc.xml    @@BITROCK_DB_USER@@
META-INF/sugarcrmDS-jdbc.xml        @@BITROCK_DB_USER@@
WEB-INF/js.quartz.properties        @@BITROCK_MACHINE_HOSTNAME@@ @@BITROCK_TOMCAT_PORT@@
```

(The list jrsctl `customizations scan` marks `INSTALLER` names `js.jdbc.properties`,
`hibernate.properties` and `keystore.init.properties` instead of the three `*DS-jdbc.xml`
files; those three hold no placeholder in the WAR, the installer writes them whole.)

When a package replaces one:

- a properties file is merged by key with mine as the authority for every key mine has,
  and theirs supplying keys mine lacks. No base is needed, so this works without a
  baseline. **In v0.1.0** for `js.quartz.properties`, `js.jdbc.properties`,
  `hibernate.properties` and `keystore.init.properties`, by a fixed list (`SiteSettings`),
  on every server; run live on both test servers, the scheduler address survived. With a
  baseline the three-way rule of 4.2 applies instead, so a vendor change to a key the site
  did not touch is taken;
- an XML file among them (`context.xml`, the `*DS-jdbc.xml`) is never replaced; the plan
  says so and shows the vendor's copy in `notes.txt`. Landed 2026-09-30 by a fixed rule,
  `META-INF/context.xml` and `META-INF/*-jdbc.xml`, on every server: such a file gets no
  plan entry (`PackageContents.kept`), so nothing snapshots, stages, swaps or records it,
  and a readme deletion never removes it. When the package's copy equals the server's,
  nothing is said. The package of 2026-07-30 ships none of them.

This is a behaviour change from 0.1, which replaces them with a warning.

### 4.5 The workspace and the operator

`jrs-hotfix merge prepare <package.zip>` runs the scan with the package, merges what it
can, and writes:

```
merges/<mergeId>/merge.json                 package sha256 and id, installed build, baseline ids,
                                            conflict policy, one record per file
merges/<mergeId>/files/<path>/base
merges/<mergeId>/files/<path>/mine
merges/<mergeId>/files/<path>/theirs
merges/<mergeId>/files/<path>/merged        the proposal, with markers when it conflicts
merges/<mergeId>/report.txt                 the plan-style summary, also printed
```

`mergeId` is `m-<yyyyMMdd>-<HHmmss>-<4 hex>`, as run ids are. One record per file:

```json
{ "path": "WEB-INF/applicationContext-security-web.xml", "class": "X",
  "verdict": "collision", "state": "CONFLICT",
  "base": "<sha256>", "mine": "<sha256>", "theirs": "<sha256>", "merged": null,
  "resolvedBy": null, "resolvedAt": null, "checks": [] }
```

States: `AUTO` (merged, nothing to do), `REVIEW` (clean merge of a class X file, waiting
for confirmation), `CONFLICT`, `RESOLVED`, `KEPT_MINE`, `TOOK_THEIRS`.

The operator resolves with their own editor or merge tool. The tool opens nothing by
itself unless `merge.tool` is set, a command line with `{base}`, `{mine}`, `{theirs}` and
`{merged}` placeholders, for example `code --wait --merge {mine} {theirs} {base} {merged}`.

```
jrs-hotfix merge status [<mergeId>]              every file and its state; exit 0 when none is
                                                 CONFLICT or REVIEW, 2 otherwise
jrs-hotfix merge show <mergeId> <path>           the three-way diff, paged
jrs-hotfix merge edit <mergeId> <path>           run merge.tool, then the checks
jrs-hotfix merge resolve <mergeId> <path> --merged [<file>] | --mine | --theirs
jrs-hotfix merge discard <mergeId>
```

`resolve --merged` takes the workspace's `merged` file, or the file given, runs the checks
of 4.3, stores the hash and marks the record `RESOLVED`. Nothing under the installation is
touched by any `merge` command.

### 4.6 External authentication

The readme's external-authentication step (port the changes of
`samples/externalAuth-sample-config` into the deployed
`applicationContext-externalAuth-*.xml`) is the operator's: the deployed file is the site's
own, made from a sample, and no package replaces it. The tool flags it (decided
2026-09-30). When a deployed `WEB-INF/applicationContext-externalAuth*.xml` exists:

- `scan` lists it under its own heading, "External authentication", not as `ADDED`;
- with a package (`scan --package`, the apply plan, `merge prepare`), when the package
  changes any file under `samples/externalAuth-sample-config`, a warning names each deployed
  file and each sample the package changed, and says that the changes must be ported by
  hand.

It is a warning only: nothing is merged, nothing is refused.

## 5. Apply with a merge

`jrs-hotfix apply <package.zip> [--merge <mergeId>]`. At a terminal, when the scan finds a
collision and no merge is given, apply stops before the plan and offers to prepare one.
With `--non-interactive` it exits 2 and names the command.

The eight steps of spec 4.1 stay. Changes:

| # | Step | Change |
|---|---|---|
| 1 | preflight | the `build` check (section 1). With a merge: the package hash equals the merge's; no record is `CONFLICT` or `REVIEW`; every file on disk still has the `mine` hash the merge recorded, else exit 2 "changed since the merge was prepared: <path>" |
| 2 | snapshot | unchanged. A `keep` file is not snapshotted because it is not written |
| 3 | stage | after extraction, every merged file replaces the package's copy in staging and is hashed again. `keep` files are removed from staging |
| 5 | swap | unchanged: it moves what staging holds. Superseded jars (section 6) are deleted here with the readme's deletions |
| 5a | clear-work | new: delete `<tomcatDir>/work/Catalina/localhost/<webappName>`. No compensation; Tomcat rebuilds it. Skipped with a note when the directory is absent or `service.kind` is `manual` and the path is not under `tomcatDir`. **In v0.1.0** as `clear-jsp-cache`, `irreversible()`, after the swap and after a rollback's restore too, since restored pages are older than the compiled classes |
| 8 | record | the ledger entry gains `mergeId` and `baseline`; each file gains `vendor` (the package's hash) beside `after` (what was written) and may have the action `keep`. The hotfix baseline is written (section 2). The merge workspace is kept while the entry is `INSTALLED` |

The plan fingerprint (spec 3) gains the merge id, the hash of `merge.json` and the hash of
every merged file, so `runs resume` refuses a merge edited after the run began.

The plan preview gains three headings under the step table: "Merged (N)", with each file's
class and how it was resolved; "Kept as the site has it (N)"; and "Replaced although the
site changed it (N)", the class G and B files, which is the list the operator must act on
by hand.

`verify <package.zip>` reports a file whose hash equals `after` but not `vendor` as
"merged by jrs-hotfix", not as a mismatch.

Rollback (spec 4.2) is unchanged. The snapshot holds mine, so a rollback brings the site's
files back exactly; `keep` files were never touched. The hotfix baseline of a rolled-back
entry is removed with it.

## 6. Superseded libraries

The readme's "Deleted files" covers the step from the release to this package, and its
"IMPORTANT" globs cover leftovers of earlier hotfixes. Both are lists somebody maintains,
and a jar outside them stays beside its newer version.

After the readme's deletions are expanded, planning adds a `delete (superseded)` for a jar
under `WEB-INF/lib` when all of these hold:

- the package lays down a jar with the same artifact name and a higher version, where the
  name is everything before the last `-` that is followed by a digit;
- the jar on disk is not laid down by the package;
- the jar on disk is known to the baseline or to a ledger entry, so a jar the site added
  is never a candidate.

Superseded deletions are listed under their own heading in the plan, snapshotted and
rolled back like every other deletion, and turned off with `--keep-superseded`. When there
is no baseline the third condition cannot be checked; the candidates are then reported as
a warning and nothing is deleted.

Measured 2026-09-29: after the package of 2026-07-30 on a pristine 10.0.0 PRO server, no
artifact under `WEB-INF/lib` is present in two versions; the readme's lists were complete.
There is no evidence yet that this section deletes anything a readme did not name, and a
name-based heuristic can be wrong. Until a second package shows a leftover, this section
is **report-only**: the candidates are a warning in the plan, with or without a baseline,
and nothing is deleted. The `delete (superseded)` action stays designed, not built.

The warning landed 2026-09-30 (`JarName`, `OfficialPackage.superseded`). The name rule is
taken literally, so `jasperreports-spring-hotfix-7.0.5-JS-79557-SNAPSHOT.jar` splits into
the artifact `jasperreports-spring-hotfix-7.0.5-JS` and the version `79557-SNAPSHOT`: a
7.0.6 of that jar is another artifact to the rule and is not reported. That errs towards
saying nothing, which is the right side for a rule that goes by names.

## 7. A WAR as the target

`jrs-hotfix apply <package.zip> --war <in.war> --out <out.war> [--merge <mergeId>]`
produces a hotfixed WAR and touches no server. `scan` and `merge prepare` take the same
`--war` and read mine from it.

| # | Step | Does |
|---|---|---|
| 1 | preflight | the WAR's release, edition and build (sections 1 and spec 2, read from inside the WAR) fit the package; free space; `--out` does not exist |
| 2 | stage | as spec 4.1 step 3, with the merge substitution of section 5 |
| 3 | assemble | stream `in.war` to `out.war.tmp`: drop deleted and replaced entries, copy the rest unchanged, append staging. Only webapp paths apply; `js-install.zip` is skipped with a note |
| 4 | verify | reopen the result, hash every entry the plan wrote, check the entry count |
| 5 | record | rename to `--out`; write `<out.war>.jrs-hotfix.json`, a ledger entry with origin `WAR` |

There is no service, no snapshot and no rollback: the input is never modified, and the
output is a new file. The home is `--home`, else a directory beside `--out`. The sidecar
is what `record` reads on the server the WAR is deployed to, so that server's ledger
learns the hotfix and its merge.

As built (2026-09-30, `WarFile`, `WarSteps`, `Bootstrap.forWar`):

- The WAR is unpacked under `<home>/wars/webapps/<name>/` (one WAR at a time, keyed by the
  WAR's hash and reused while it matches), and the scan, the merge and the plan read it as
  they read a webapp. The design's "read as a stream" holds for the baseline; for the target
  the code that judges, merges and stages files is the server's, unchanged, which is worth
  a copy on disk. The output is assembled as designed: the input streamed to a temporary
  file without the entries the package replaces or deletes, the staged files appended,
  every written and dropped entry checked, then the rename.
- The home is `--home` (or the environment), else `jrs-hotfix` beside the input WAR, not
  beside `--out`: `scan --war` and `merge prepare --war` have no `--out`, and the three
  commands must share one home. A home that has no settings gets settings written that
  name the unpacked copy as the webapp, with no service, so `baseline add` works in it.
- No ledger entry, origin `WAR` or otherwise. The ledger is a server's inventory, one
  entry per hotfix id; a home used for several WARs would refuse the second. The sidecar
  is the record. `record` on the deploying server does not read it yet: it records from
  the files as before, which gives the same hashes.
- The hotfix baseline is written by `apply --war` as by an apply on a server, so the
  output WAR, given as the next input, is compared with the hotfix's files.
- Tested: unit (unpack, assemble, check; the commands over a site's WAR with and without a
  baseline, with a merge prepared by `apply` and by `merge prepare`, the home beside the
  WAR, the refusals) and acceptance scenario 19 through the shaded jar. Run against the
  real pristine 10.0.0 WAR and the package of 2026-07-30, no server involved: a site WAR
  (the scheduler filled in, one key added to `jasperreports.properties`), the release
  baseline added, `scan --war --package` naming the one collision, `apply --war --out`
  in two minutes with both properties files merged automatically, the output holding
  5844 entries (5819 less 149 deleted plus 174 added, as the check step counts), the
  site's key and the vendor's new keys in it, and a 116 KB sidecar with 448 files.

## 8. Commands and menu

```
jrs-hotfix scan [--package <package.zip>] [--war <file.war>]
jrs-hotfix baseline [list | add <war|dir|package.zip> | remove <id>]
jrs-hotfix merge [prepare <package.zip> | status [<id>] | show <id> <path> | edit <id> <path>
                  | resolve <id> <path> --merged [<file>] | --mine | --theirs | discard <id>]
jrs-hotfix apply <package.zip> [--merge <id>] [--on-conflict ask|mine|theirs|fail]
                 [--keep-superseded] [--war <in.war> --out <out.war>] [--plan] [--yes]
```

Menu: entry 1 becomes "Apply a hotfix" with the scan run first and the merge offered when
it finds a collision; a new entry "Check the server for customizations" sits after
"Verify a hotfix package". The header line gains the installed build.

New settings: `merge.onConflict` (`ask`), `merge.tool` (absent).

## 9. Errors and exit codes

No new code. New conditions under the existing ones:

| Code | New condition |
|---|---|
| 2 | a merge has a file in `CONFLICT` or `REVIEW`; a file changed since the merge was prepared; the merge belongs to another package; a collision exists and no baseline; the installed build contradicts the ledger (section 1); `--on-conflict fail` met a conflict; a class X check failed |
| 6 | `--war` names a file that is not a JRS 10.x webapp |

Every message names the file and the one command that moves things forward.

## 10. Testing

- Unit: the properties merge table of 4.2 row by row, including continuation lines,
  escaped separators and ISO-8859-1 characters; diff3 on clean, overlapping, identical
  and empty inputs; the class X checks; path classification; artifact and version
  splitting for section 6 (`log4j-1.2-api-2.25.4.jar`, `jersey-spring6-4.0.2.jar`,
  `jasperreports-spring-hotfix-7.0.5-JS-79557-SNAPSHOT.jar`); `InstalledBuild` on the
  vendor's indented stamp lines.
- Fixture generator: a release baseline, a package that changes a properties file, a
  context and a generated script, and an installation that changed each of them in a way
  that merges cleanly, and again in a way that collides.
- Acceptance, one per scenario 10 to 19, plus: a crash after `stage` with a merge, then
  resume; a file edited on the server between `merge prepare` and `apply` (exit 2);
  rollback after a merged apply restores the site's bytes.
- Live, before release, on the JRS 10.0.0 on this machine: `baseline add` from the 10.0.0
  WAR, `record` the hotfix already on the server, `scan`, then re-create the 2026-09-02
  `web.xml` edits, prepare a merge against the real package, apply, verify the scheduler
  and login, roll back.

## 11. Phases

| Phase | Contents | Why first |
|---|---|---|
| 1 | section 1 (installed build), 4.4 (installer-written files), 5a (JSP cache), 6 with baseline-free warning | each fixes something 0.1 does wrong on every server; none needs a baseline. **Landed in v0.1.0**; the preflight `build` table, `context.xml` kept, and the superseded warning landed after it, on 2026-09-30 |
| 2 | sections 2, 3, 4, 5, full section 6 | the customized path of the diagram. **Landed 2026-09-30**; where it differs from the text above, section 13 says how and why |
| 3 | section 7 | the WAR target; reuses everything above. Wanted for 0.2 (decided 2026-09-30). **Landed 2026-09-30** for 0.3; see the note under section 7 |

## 12. The diagram, box by box

| Diagram | Here |
|---|---|
| JasperServer vanilla? | `scan`, first line (section 3) |
| Figure out the hotfix version from `WEB-INF/internal/jasperserver-pro.properties` | section 1 |
| Diff JRS files with the files from the hotfix; are files different? | `scan --package`, the verdict table (section 3) |
| Identify the list of changed files and changed properties | `scan` classes; per-key rows of 4.2 |
| Identify JavaScript changes and exclude them | class G (section 4.1) |
| Diff the customized files with the target hotfix and the hotfixed WAR without customizations | base, mine, theirs (section 2) |
| Are there three-way collisions? | verdict `collision` (section 3) |
| Property files, JSPs: upsert or merge into the target | classes P and T (4.2, 4.3). Differs: a key or hunk both sides changed is not merged silently |
| applicationContexts: signify the three-way diff, the user resolves it | class X and the workspace (4.3, 4.5) |
| Follow the readme to delete and copy files | spec 4.1, unchanged |
| Delete older versions of existing libraries | the readme's lists (spec 4.1) plus section 6 |
| Port customizations | merged files carried through staging (section 5); class G and B changes listed for the operator |
| Propagated into the deployed or the zipped WAR | spec 4.1 for the deployed webapp; section 7 for a WAR |

## 13. Phase 2 as built

Built on 2026-09-30 in the design's order: baseline and scan, then the merge by key and the
keep and replace verdicts, then the workspace. The sections above stay as designed; these are
the places where the implementation differs, each with its reason.

**The merge document decides everything, not only collisions.** `merge.json` holds one
record for every file the package ships under the webapp, with the hash the server's file
had when the merge was prepared, and three more states than 4.5 lists: `PLAIN` (the
package's copy lands, nothing of the site's is lost), `KEPT` (only the site changed it, or
the installer wrote it: not written) and `OVERWRITTEN` (a class G or B file both changed:
the package's copy lands). An apply on a server with a baseline always runs with a merge,
and its plan is built from the package and `merge.json` alone, never from the baseline
again. The reason is recovery: a plan is rebuilt from its stored arguments in the middle of
a run, when some files are already swapped and the webapp states the new build. A plan that
asked the baseline again at that point would judge the swapped files as site changes. With
the decisions recorded, the rebuilt plan is the plan that was started, and the preflight
rule of section 5 ("every file on disk still has the `mine` hash") becomes: every file is
as the merge found it, or already as the apply leaves it.

**`apply` prepares the merge itself.** Without `--merge`, `apply` uses the newest merge
that is still valid (same package, same baselines, every file as it found it) and prepares
one otherwise, so a server where no file collides needs no extra command, and a second
attempt meets the merge the operator has been resolving. `apply --plan` therefore writes a
workspace under the home; it still changes nothing on the server. While a record is
`CONFLICT` or `REVIEW`, `apply` exits 2 and names the merge and the three commands. The
terminal does not "offer to prepare": there is nothing to ask.

**Kept files are not rows of `files`.** The ledger entry gains `mergeId`, `baselines` and
`kept` (path, the vendor's hash, the reason); a file of `files` gains `vendorSha256` when
what was written is a merge. There is no action `keep`: every row of `files` is something a
rollback restores, and a kept file was never touched.

**The hotfix baseline outlives a rollback.** The build the webapp states selects the base,
so the baseline of a rolled-back hotfix is unused, not wrong, and the step that would
remove it would need a compensation that writes it back. `runs prune` removes hotfix
baselines older than the newest two, and always keeps the one of the build the webapp
states. `record` writes the hotfix baseline too, so a hotfix applied by hand and then
recorded is the vendor's level for the next merge. In an apply the baseline is written by
the stage step, not the record step: staging reads the package before the outage anyway,
and a record step that failed on a missing package, with the service up again, would roll
a good apply back. The record step finds the baseline there; if it has to write it and
cannot, it says so and does not fail.

**Baselines that do not fit refuse the apply.** When a release baseline exists and the
build the webapp states has no baseline (a hotfix applied by hand, or by 0.1), `apply` is
refused with exit 2 and names `baseline add <package.zip>` and `baseline remove`. The
design's "behaves as 0.1" holds only when there is no release baseline at all: an operator
who added one expects the site's files to be compared, and replacing them silently is the
unsafe reading.

**The installer's files under a merge.** A properties file the installer wrote is merged by
key with the base, the site's value standing in a conflict and nothing said in the file,
exactly as 0.1 merged it without a base; the gain is that a key only the vendor changed now
takes the vendor's value. This settles the first open point for these files and leaves it
open for every other file. The heading over carried keys stays the fixed one of 0.1, not
"re-applied by jrs-hotfix <runId>": a line that changed with every run would make the
merged file differ from itself.

**`--on-conflict` is for properties keys.** `mine` and `theirs` settle a key both changed
and write the other value above it as a comment. A line both changed in a page or an XML
file always waits for the operator. `fail` prepares the same workspace as `ask` and makes
`merge prepare` exit 2.

**Neighbouring lines conflict.** The line merge treats changes on adjacent lines as one
place, as git does. Two edits need an unchanged line between them to merge cleanly.

**A readme pattern never deletes the site's file.** The last open point is closed for
patterns: a file a readme glob matches is deleted only when a baseline or a ledger entry
knows it; otherwise it gets a `KEPT` record and a note in the plan. A path the readme lists
by name is deleted as before.

**Not built.** `--keep-superseded` and the `delete (superseded)` action (section 6 is
report-only), and `--war` (section 7, phase 3). `verify` reports merged and kept files of
an installed hotfix from the ledger; it does not yet compare every owned file with the
disk.

**Menu.** Eight entries: "Check the server for customizations" is entry 4 and runs `scan`;
when there is no baseline it asks for the WAR and adds it. Recovery moved from entry 6 to 7.

**Tests.** Unit: the 4.2 table row by row, the three styles, continuation lines, escaped
separators and ISO-8859-1 bytes; diff3 on clean, overlapping, identical, empty and random
inputs; the XML checks; path classes; the workspace and its states; the apply plan with a
merge, its rebuild after the swap, and a second cumulative hotfix over the first.
Acceptance, through the shaded jar (`CustomizedServerTest`): scenarios 10 to 18, a run
killed after staging and resumed with the merged files, and a file edited between `merge
prepare` and `apply`. Scenario 19 is phase 3.

**Live.** The sequence of section 10 ran on the JRS 10.0.0 PRO on this machine on
2026-09-30 (`jrs-hotfix-live/live-0.2.ps1`, 120 checks, all green, four outages): baselines
from the real WAR and the real package, `scan`, the installed hotfix rolled back, two site
edits (a listener in `web.xml` right where the hotfix adds its three, the session timeout; in
`jasperreports.properties` a key the hotfix adds too with the other value, and a key of the
site's own), `apply` refused with both files as CONFLICT, the two resolved with `--merged`,
`apply` with the merge, `verify` naming `web.xml` as merged, `rollback` giving the site's
files back byte for byte, the edits taken out and the hotfix applied again. Two things the
run taught: the site's listener must be a class the webapp has, because Tomcat redeploys the
webapp the moment `web.xml` changes (a class that does not exist took the server to 404
until the file was put back); and a `web.xml` edit while the server runs is itself an
outage of a minute or two, which the script now waits for. The same sequence ran on the
Linux laptop's installation (`ctlscript`) the same day, 80 checks green.

## Decided 2026-09-30

- The Community edition is disregarded (Scope, section 1).
- Webapp `*.css` is class G: vendor output, replaced, a site change reported (4.1).
- `runs prune` removes hotfix baselines older than the newest two (section 2).
- A deployed `applicationContext-externalAuth*.xml` is flagged (4.6).
- A WAR as the target (section 7) is wanted for 0.2; it is phase 3.
- The order of 0.2 work: the three leftovers of phase 1, then phase 2 as `baseline add` and
  `scan` first (read-only, no outage, every later section needs them), then 4.2 and the
  keep/replace verdicts, then the class X workspace.

## Open points

- Whether `merge.onConflict` should ever default to `mine` for installer-written keys
  outside the five files of 4.4. Until decided it does not: the defaults of 4.2 stand.
  (Inside those files the site's value stands, as in 0.1; section 13.)
- The 95 percent threshold of section 2 is a guess until it has met a second server.
- Whether a class G or B file both changed should be resolvable with `--mine`. Today the
  package's copy always wins and the plan lists the file.
