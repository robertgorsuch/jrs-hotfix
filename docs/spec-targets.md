# jrs-hotfix 0.7: customizations wherever a hotfix goes

Status: draft for review, 2026-10-01. Extends `docs/spec-customized-servers.md` (the 0.2
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
- **#30 without a distribution** stays `apply --war <in.war> --out <out.war>`, now with the
  installation files of the package written beside the output into a directory given with
  `--install-out <dir>`, for a buildomatic kept elsewhere (open point 3).

### 2.2 Webapp to WAR (#31)

`apply <package.zip> --war <dir> --out <out.war>`: `--war` (and `scan --war`, `verify --war`,
`merge prepare --war`) also takes a directory, an exploded WAR or a deployed webapp. The
directory is read, never written, and the output WAR holds everything in it: `META-INF/
context.xml`, the `*-jdbc.xml` files and the installer-written properties with this site's
values, merged with the package's as on a server. That is what "keep the `META-INF`
configuration" means.

An environment that deploys one generic WAR with its own database configuration usually gives
Tomcat that configuration outside the WAR (`conf/Catalina/localhost/<webapp>.xml` overrides
the WAR's `META-INF/context.xml`), so the site's copy inside does no harm. Whether a
`--generic` output, with the vendor's copies of those files instead, is wanted too is open
point 4.

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
- **Exit codes**: 0 when the comparison ran, whatever it found; 1 for usage; 2 when an input
  cannot be read; 6 when an input is not a JasperReports Server webapp or package. A
  difference is a finding, not a failure (open point 5).

`compare` is what a site uses to find its customizations between two environments, or to see
before an upgrade what its changes meet. The hotfix commands keep their own, narrower forms
(`scan`, `verify`).

## 4. Commands

| 0.6 | 0.7 |
|---|---|
| `baseline add <war \| dir \| package.zip>` | also `<distribution.zip \| distribution dir>`, which records both areas |
| `scan`, `verify`, `merge prepare` | cover the installation area too, when a baseline has it |
| `--war <file.war>` | also `--war <dir>` (#31) |
| `apply --war <in> --out <out.war>` | also `--install-out <dir>` (#30, open point 3) |
| `settings detect` | also finds a distribution root, service kind `none` (#32) |
| (none) | `compare <a> <b> [<c>] [--out <dir>] [--show <path>]` (#33, #34) |

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

Each phase is a release (0.7.0 to 0.7.3) or the four are one 0.7.0 (open point 6).

## Open points

1. **The installation's installer-written and generated files**, measured on a real 10.0.0
   distribution after `js-install` has run: which files buildomatic writes from
   `default_master.properties`, and whether any shipped file holds site values the way the
   webapp's four properties files do (0.2 design 4.4).
2. **That buildomatic deploys the distribution's own `jasperserver-pro.war`**, and from which
   path, measured on the same distribution. If it unpacks the WAR somewhere first, that copy
   is the target instead.
3. **#30 without a distribution:** whether `--install-out <dir>` is wanted, or whether a build
   host is always a full distribution, in which case #30 is section 2.1 alone.
4. **#31's `--generic`:** whether an output WAR with the vendor's `META-INF/context.xml` and
   installer-written files, instead of the site's, is wanted.
5. **`compare`'s exit code** when inputs differ: 0 as above, or a distinct code for scripts
   that test for equality.
6. **One release or four.**
