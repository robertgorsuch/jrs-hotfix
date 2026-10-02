# jrs-hotfix 0.7: customizations wherever a hotfix goes

Status: decided 2026-10-01, for 0.7.0; phases 1 to 4 built, phase 2 on the assumptions of
the open points, which are still to be measured before the release. Extends `docs/spec-customized-servers.md` (the 0.2
design, cited as "0.2 design 4.2") and `docs/spec-no-ledger.md` (the 0.6 design). Source:
issues #30 to #34, and the maintainer's ruling of 2026-10-01:

- **Full support for customizations during every hotfix operation.** What a site changed is
  kept or merged, never silently replaced, whatever the hotfix is applied to.
- **#32's target is a build host with only buildomatic**: a JasperReports Server distribution
  directory, with no Tomcat and no running server, from which buildomatic deploys.

## Problem

jrs-hotfix 0.6 keeps a site's customizations in one place only: the files under the webapp,
on a server or in a WAR file. The five issues ask for the places it does not reach:

| Issue | Asks for | 0.6 today |
|---|---|---|
| #30 | Patch a WAR that is not deployed, and deploy the result with buildomatic | `apply --war <in.war> --out <out.war>` writes a new WAR; buildomatic's own files are left out |
| #31 | Patch a deployed webapp and get a WAR that keeps its `META-INF` configuration | `--war` takes a WAR file only |
| #32 | Patch buildomatic | Only on a server, as part of an apply, and its files are replaced: 0.2 design, Scope, "Merging covers files under the webapp only" |
| #33 | Compare two unrelated WARs, archives or directories | `scan` compares one webapp with a vendor baseline, nothing else |
| #34 | A three-way diff of three unrelated WARs | The merge engine does three-way, in one fixed shape: vendor, site, hotfix |

## Principle: one engine, every target

Every hotfix operation already runs the same pipeline: a **baseline** says what the vendor
shipped, a **scan** says what the site changed, a **merge** decides every file the package
ships (replace, keep, merge by key, merge by line, or wait for the operator), and the
**stage, swap and undo** of 0.6 write the result with a way back. 0.7 does not add a second
engine. It gives that pipeline two more areas and two more targets, and opens the comparing
part as a command of its own.

- **Areas** are what a hotfix package has two of: the webapp (`jasperserver-pro.zip`) and the
  installation (`js-install.zip`: `buildomatic/`, `samples/`). 0.6 handles customizations in
  the first only. 0.7 handles both, by the same classes and rules (section 1).
- **Targets** are where the areas live (section 2).

## 1. Customizations in the installation area

Today a package's `js-install.zip` files are replaced, and the plan only counts the settings
templates among them (0.2 design, Scope). 0.7 treats them as it treats webapp files:

- **Baselines get an installation area.** A release baseline added from the vendor's
  distribution (`baseline add jasperreports-server-pro-10.0.0-bin.zip`, or its unpacked
  directory) records the webapp from the distribution's `jasperserver-pro.war` and the
  installation from its `buildomatic/` and `samples/` trees. A hotfix baseline records the
  package's `js-install.zip` beside its webapp files, as it already records the latter. A
  baseline written by 0.2 to 0.6 has no installation area: until one is added, installation
  files keep the 0.6 behaviour and its warning, which names `baseline add <distribution>`.
- **Scan, verify and merge cover both areas.** Paths stay as the package writes them, so the
  areas never collide (`WEB-INF/...` against `buildomatic/...`). The file classes of 0.2
  design 4.1 apply unchanged: properties merged by key, XML and pages by line (XML confirmed
  by the operator), scripts and binaries replaced or, with `merge resolve --mine`, kept.
- **The installation has its own installer-written and generated files**, to be measured on
  a real 10.0.0 distribution before this is built (open point 1). Expected: the site's
  `buildomatic/default_master.properties` (never shipped by the vendor, so site-only and never
  touched), and the configuration buildomatic generates from it (`buildomatic/build_conf/`),
  which is the GENERATED class of a scan: listed, never merged, rebuilt by buildomatic.
- **Tomcat's own files stay out of scope** (`setenv`, `server.xml`, `lib/*.jar`), as in 0.2.

On a server this changes nothing for a site without customizations, and for one with them it
turns "replaced; apply your settings again" into the same keep-or-merge the webapp gets.

## 2. Targets

| Target | Webapp area | Installation area | Service | Writes | Undo |
|---|---|---|---|---|---|
| **Server** (0.1) | the deployed webapp | the installation directory | stopped and started | in place | yes (0.6) |
| **WAR to WAR** (0.3) | an unpacked copy of the WAR | none | none | `--out`, a new file | no: the input is untouched |
| **Webapp to WAR** (#31, new) | the deployed or exploded directory, read only | none | none | `--out`, a new file | no: the input is untouched |
| **Build host** (#30, #32, new) | the distribution's `jasperserver-pro.war` | the distribution's `buildomatic/` and `samples/` | none | in place | yes |

### 2.1 Build host (#30, #32)

A build host holds an unpacked JasperReports Server distribution: `buildomatic/`, `samples/`
and `jasperserver-pro.war` at its root, from which `js-install` or `js-ant deploy-webapp-pro`
deploys to one or more application servers elsewhere (open point 2 confirms that buildomatic
reads that WAR). There is no Tomcat, and jrs-hotfix never runs buildomatic.

- **Detection.** `settings detect` recognises a distribution root (both `buildomatic/` and
  `jasperserver-pro.war`, no `webapps/`) and writes settings with a new service kind, `none`,
  and the WAR as the webapp. The home is `<distribution>/jrs-hotfix`, as on a server.
- **Apply.** The server's plan without the service steps:

  | # | Step | Does |
  |---|---|---|
  | 1 | preflight | the build stated inside the WAR fits the package (0.6 design 1); free space; the distribution writable |
  | 2 | snapshot | the installation files the package replaces or deletes, into the run |
  | 3 | stage | both areas, with the merge, as on a server |
  | 4 | assemble | the new WAR beside the old one, as `apply --war` assembles its output, then checked entry by entry |
  | 5 | swap | the installation files into place; the old WAR renamed into the run's snapshot and the new one into its place |
  | 6 | keep the undo | as 0.6: the run's snapshot, the old WAR in it, becomes `undo/` |

  The old WAR is moved, not copied: the home is on the distribution's volume by default, so
  the undo costs no second copy. A home on another volume is refused unless there is room for
  the copy.
- **Rollback** puts back the old WAR and the installation files, after the 0.6 check that
  nothing changed since the apply.
- **Deploying is the operator's.** The plan ends with a note naming the buildomatic command
  that deploys the patched WAR, which jrs-hotfix never runs, as it never runs a readme's
  manual steps.
- **#30 without a distribution** stays `apply --war <in.war> --out <out.war>`, and takes
  `--install-out <dir>` for a buildomatic kept elsewhere. That directory is the installation
  area of the run: an installation tree (`buildomatic/`, `samples/`) is patched in place,
  with the same merge as on a build host and with an undo of its own in the home; an empty or
  absent directory receives the package's installation files as they are. Without the option
  the installation files are left out and counted, as in 0.6.

### 2.2 Webapp to WAR (#31)

`apply <package.zip> --war <dir> --out <out.war>`: `--war` (and `scan --war`, `verify --war`,
`merge prepare --war`) also takes a directory, an exploded WAR or a deployed webapp. The
directory is read, never written, and the output WAR holds everything in it: `META-INF/
context.xml`, the `*-jdbc.xml` files and the installer-written properties with this site's
values, merged with the package's as on a server. That is what "keep the `META-INF`
configuration" means.

For an environment that deploys one generic WAR with its own database configuration,
`--generic` writes the vendor's copies instead of the site's: `META-INF/context.xml`, the
`META-INF/*-jdbc.xml` files and the installer-written properties of 0.2 design 4.4 come from
the release baseline and the package, as if no installer had run. Every other customization
is kept or merged as without the option. `--generic` needs a release baseline (exit 2
without one, naming `baseline add`), and the plan lists the files it takes from the vendor.
It applies to the WAR targets only (`--war`), never to a server, whose own configuration
those files are.

## 3. Compare (#33, #34)

`compare` is the scan and the merge as a read-only command over any inputs. It needs no
settings and no home, and it changes nothing unless told where to write.

```
jrs-hotfix compare <a> <b>                       what differs between two
jrs-hotfix compare <base> <mine> <theirs>        what each changed from base, and where they meet
                   [--out <dir>]                 write the merged result into dir (three-way only)
                   [--show <path>]               print one file's differences
```

- **Inputs** are any of: a WAR file, a directory (exploded WAR, deployed webapp, or a
  distribution, which brings both areas), a distribution ZIP, a hotfix package (its files),
  or `server`, the webapp of this home's settings. Inputs of different kinds compare as long
  as they hold the same area.
- **Two-way** prints the files that differ, are only in one, or differ in line ends only
  (equal, as in a scan), each with its class; generated and installer-written files are
  marked as a scan marks them.
- **Three-way** prints, per file, who changed it: neither, only mine, only theirs, both alike,
  or both differently, which is merged by its class or listed as a conflict, exactly as a
  merge workspace decides. `--out <dir>` writes the merged files, with conflict markers where
  a conflict is, and a report beside them; `merge show`'s side-by-side is `--show`.
- **Exit codes**: 0 when the inputs are the same, **7 when they differ**, a warning rather
  than a failure: the comparison ran and its report is the result. 1 for usage; 2 when an
  input cannot be read; 6 when an input is not a JasperReports Server webapp or package. A
  three-way comparison with conflicts is 7 too; the report counts them. Exit 7 is new, and
  only `compare` uses it.

`compare` is what a site uses to find its customizations between two environments, or to see
before an upgrade what its changes meet. The hotfix commands keep their own, narrower forms
(`scan`, `verify`).

## 4. Commands

| 0.6 | 0.7 |
|---|---|
| `baseline add <war \| dir \| package.zip>` | also `<distribution.zip \| distribution dir>`, which records both areas |
| `scan`, `verify`, `merge prepare` | cover the installation area too, when a baseline has it |
| `--war <file.war>` | also `--war <dir>` (#31) |
| `apply --war <in> --out <out.war>` | also `--install-out <dir>` (#30) and `--generic` (#31) |
| `settings detect` | also finds a distribution root, service kind `none` (#32) |
| (none) | `compare <a> <b> [<c>] [--out <dir>] [--show <path>]` (#33, #34); exit 0 same, 7 different |

Nothing is removed or renamed; every 0.6 command line keeps working.

## 5. Not in scope

- Running buildomatic, or deploying to an application server.
- Tomcat-side files, repository themes, clusters (0.2 design, Scope).
- A graphical merge tool.
- Comparing two servers over the network: `compare` reads files that are on this host.

## 6. Testing

- **Unit.** Installation-area classes, merges and kept files; a distribution read as a
  baseline with both areas; a baseline without an installation area falling back with its
  warning; detection of a distribution root; the build-host plan without service steps; the
  WAR swapped by rename and put back by rollback; `--war <dir>` read and never written;
  `compare` two-way and three-way over each input kind, `--out` and `--show`.
- **Acceptance.** A build host: a fixture distribution with a customized
  `buildomatic/` file and a site-changed webapp file in its WAR, a hotfix applied with both
  merged, rolled back to the bytes it started from. A deployed webapp to a WAR with its
  `META-INF/context.xml` kept. `compare` over a WAR and a directory.
- **Live.** The real 10.0.0 distribution and the package of 2026-07-30 on a build host, then
  the output deployed with buildomatic to a server, which starts and states the hotfix's
  build.

## 7. Phases

| Phase | Contents | Why in this order |
|---|---|---|
| 1 | The installation area: baselines from a distribution, scan, verify, merge, on a server | Every later phase needs it, and servers gain from it at once |
| 2 | The build-host target (#30, #32) | The ruling's case; it needs phase 1 for buildomatic's customizations |
| 3 | `--war <dir>` (#31) | Small: the WAR target already works from an unpacked copy |
| 4 | `compare` (#33, #34) | Read-only; it reuses phases 1 to 3's readers |

The four phases are one release, 0.7.0 (decided 2026-10-01); each is its own pull request,
merged into `main` in this order.

## Phase 1 as built (#42)

The installation area is `baseline/Area`, `BaselineManifest.installFiles` and `installDeleted`,
`BaseView.installation()`, `Scan` over both areas, and `MergeDoc.Item.area`. A release baseline
from the distribution's directory or ZIP records both areas; a hotfix baseline records its
`js-install.zip`; `scan` prints the installation as a second section; `verify` and `merge
prepare` judge both. The installation is compared only where the installation directory holds
`buildomatic/` or `samples/`, so a WAR target, whose settings name a scratch directory, is
unaffected. The site files and generated directories of open point 1 are a provisional list in
`Area`, the one place to change once measured.

## Phase 2 as built

A build host is settings with `service.kind` `none` (`ServiceConfig.Kind.NONE`), which `settings
detect` writes when the home's parent or the working directory holds `buildomatic/` and
`jasperserver-pro.war` and no `webapps/` (`Detection.distribution`): the installation directory
is the distribution, and the webapp is the WAR's copy under `<home>/wars/`, unpacked again by
every command whose runtime it needs once the WAR has changed. `HotfixPlans.planApplyBuildHost`
is the section 2.1 plan: preflight, a snapshot of the installation files only, staging, the WAR
assembled beside itself and checked (in the apply phase, so the phases stay contiguous), the
installation files swapped, `swap-war`, and `promote-undo`. `swap-war` records both hashes in
the snapshot's `war/war.json` before it moves anything, moves the old WAR into `war/`, and its
compensation moves it back; `UndoRecord.war` lists the WAR beside the installation files, so the
rollback's check refuses a WAR changed since the apply. The rollback without a service is
`check-undo`, `restore-snapshot`, `restore-war` and `discard-undo`.

`--install-out <dir>` turns the WAR plan's runtime towards that tree (`HotfixRuntime.
withInstallDir`, also on a resumed run, whose home's settings never name it) and adds the
snapshot, the swap and the undo of the installation files. It is refused in a home whose
settings are a server's or a build host's, whose undo it would replace. Homes made for WARs now
have `service.kind` `none` too, and settings written for WARs before (a manual service) are
rewritten so, so their rollback has no service step.

Built on the open points' assumptions: `Area`'s provisional lists for point 1, and for point 2
that buildomatic deploys `<distribution>/jasperserver-pro.war` itself. If it deploys from a copy,
`Settings.distributionWar` is the one place that names the target.

## Decided 2026-10-01

1. **#30 without a distribution:** `apply --war ... --install-out <dir>` is wanted (section
   2.1).
2. **#31:** a `--generic` output, with the vendor's `META-INF` and installer-written files,
   is wanted beside the default that keeps the site's (section 2.2).
3. **`compare`'s exit code** is a warning when the inputs differ: exit 7, distinct from
   success (0) and failure (2) (section 3). Reading of the ruling "as warn": a code a script
   can test, not a failure.
4. **One release**, 0.7.0, with the four phases as its pull requests (section 7).

## Open points

These are facts to measure on a real JasperReports Server 10.0.0 distribution before phase 1
and phase 2 are built, not choices:

1. **The installation's installer-written and generated files**, after `js-install` has run:
   which files buildomatic writes from `default_master.properties`, and whether any shipped
   file holds site values the way the webapp's four properties files do (0.2 design 4.4).
2. **That buildomatic deploys the distribution's own `jasperserver-pro.war`**, and from which
   path. If it unpacks the WAR somewhere first, that copy is the target instead.
