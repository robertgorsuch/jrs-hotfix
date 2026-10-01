# jrs-hotfix 0.6: no ledger, one undo

Status: draft for review, 2026-10-01. Changes `docs/spec.md` (the 0.1 design; cited as "spec
3", "spec 4.2") and `docs/spec-customized-servers.md` where they read the ledger. Two rulings of
the maintainer, 2026-10-01, are its starting point:

- **A rollback undoes the latest apply, and only that.**
- **A snapshot is kept for the run that took it, and the latest apply's snapshot until the
  next run replaces it.** One level of undo; no history.

## Problem

Support engineering finds the tool clunky, and the ledger is why. jrs-hotfix is run by hand,
once, in an outage window; it is not a service that watches a server. Yet it keeps a registry
of every hotfix it has seen (`ledger.json`, spec 3) and refuses whenever the server and that
registry disagree, which is every time the server was changed by anything else:

| The server was... | 0.5 says | and sends the operator to |
|---|---|---|
| given a hotfix by hand, or by another tool | "already on this server ... the ledger does not list it" (exit 2) | `jrs-hotfix record <package.zip>` |
| redeployed from a WAR | "the webapp states build X, older than Y which the ledger lists as installed" (exit 2) | `jrs-hotfix forget <id>`, for it and every later entry |
| given a hotfix while the tool was not looking | a warning that the ledger has no entry for that build | `record` again |

`record` and `forget` exist only to keep the registry true. The refusals are about the tool's
bookkeeping, not about the server, and an operator in an outage window has no reason to care
about either.

What the ledger is used for, and what replaces it:

| The ledger answers | Used by | Instead |
|---|---|---|
| Is this hotfix installed? Is the webapp older than what was installed? | `applicability`, `BuildCheck` | the build the webapp states (section 1) |
| Which hotfixes own a file, in which order, so rollback can refuse or cascade | `RollbackChain`, `filesOwnedBy` | nothing: only the latest apply can be undone (section 3) |
| The hashes each file had after the swap, to verify a rollback | `OwnedFile.after` | the undo record of the latest apply (section 2) |
| Which webapp files are the vendor's, for superseded libraries | `vendorFiles` | the baselines and the undo record (section 5) |
| Which merge an installed hotfix was applied with; what `list` shows; which snapshots `runs prune` keeps | `installedWith`, `ListCommand`, `RunService.prune` | the undo record (section 5) |

## 1. The server is the inventory

Every package is cumulative, and every webapp a package has touched states its build in
`WEB-INF/internal/jasperserver-pro.properties` (`InstalledBuild`, spec 4.1). Builds compare as
text, which is their order in time (`yyyymmdd_hhmm`). So "what is installed" is read, never
remembered:

| The webapp states | `verify` and `apply` | Exit |
|---|---|---|
| the package's build | "`<id>` is already installed (the webapp states build `<b>`)" | 2 |
| a newer build than the package's | "the webapp states build `<w>`, newer than this package's `<b>`: applying it would take the server back" | 2 |
| an older build | applicable | 0 |
| no build (the file is absent or unreadable) | the 0.5 fallback: every file of the package in place at the package's hashes with nothing to delete is "already installed" (exit 2); otherwise applicable, with the 0.5 warning that a hotfix applied by hand would not be noticed | 2 or 0 |

The release, edition and merge checks of `applicability` stay as they are. Gone: the "applied
by hand or by another tool, run `record`" refusal, the "replaced under the ledger, run `forget`"
refusal, and the warning that a build has no ledger entry. A server that was given a hotfix by
hand is simply at that build.

## 2. The undo directory

The home loses `ledger.json` and `snapshots/`, and gains one directory:

```
settings.json
lock
undo/                          the latest apply, until the next apply or rollback
undo/undo.json                 what that apply did, written once, never edited
undo/manifest.json             the snapshot: the files before the hotfix (unchanged format)
undo/payload/...               replaced and deleted files, at their relative paths
runs/<runId>/run.json          as in 0.5
runs/<runId>/plan.json         as in 0.5
runs/<runId>/journal.jsonl     as in 0.5
runs/<runId>/run.log
runs/<runId>/notes.txt
runs/<runId>/staging/          removed at the end of the run, as in 0.5
runs/<runId>/snapshot/         the run's own snapshot while it runs (section 3)
baselines/ merges/             as in 0.2 to 0.5
```

`undo.json` holds what a ledger entry held, for one hotfix:

```json
{ "id": "JRSHF-10.0.0-20260730-0457", "release": "10.0.0", "edition": "PRO",
  "build": "20260730_0457", "title": "...", "runId": "...", "appliedAt": "...",
  "files": [ { "path": "...", "action": "replace", "before": "<sha256>", "after": "<sha256>",
               "vendor": "<sha256>" } ],
  "kept": [ { "path": "...", "vendor": "<sha256>", "reason": "..." } ],
  "mergeId": "...", "baselines": [ "..." ] }
```

There is no state field. Whether the undo is still good is read from the server when it is
needed (section 3), not stored.

## 3. Apply, rollback and the one snapshot

**Apply.** The snapshot step (spec 4.1, step 2) writes to `runs/<runId>/snapshot/` instead of
`snapshots/<runId>/snapshot/`. The compensations are unchanged: a failed or cancelled apply
restores from the run's own snapshot and leaves `undo/` as it was, so the previous apply can
still be undone. The last step, `record` (step 8), becomes `promote`:

1. write `undo.json` into `runs/<runId>/snapshot/`;
2. rename `undo/` to `undo.old/`, if it exists;
3. rename `runs/<runId>/snapshot/` to `undo/`;
4. delete `undo.old/`; sync the home.

The renames are on one volume (both are under the home). `promote` is idempotent, so recovery
converges from a crash between any two of the four: `undo/` already naming this run means
done; `undo.old/` present means finish steps 3 and 4. `promote` has no compensation, as
`record` had none.

**Rollback.** `rollback` takes no argument and undoes what `undo/` holds. `rollback <id>` is
still accepted, so a script that names the hotfix keeps working, and is refused (exit 2) unless
`<id>` is the one in `undo/`; `--cascade` is removed. The plan:

| # | Step | Does | Compensation |
|---|---|---|---|
| 1 | preflight | `undo/` exists and its snapshot verifies; every file of `undo.json` is at its `after` hash and every file it deleted is absent; service state readable | none (read-only) |
| 2 | snapshot | the files about to be restored, into `runs/<runId>/snapshot/` | none |
| 3 | stop | as in 0.5 | start |
| 4 | restore | from `undo/`, per file, verified before and after (spec 4.2) | restore from step 2's snapshot |
| 5 | clear-jsp-cache | as in 0.5 | none (irreversible) |
| 6 | start, wait | as in 0.5 | none |
| 7 | discard | delete `undo/` | none (last step) |

A preflight that finds a file changed since the apply refuses with exit 2, names the files, and
says the server was changed after the hotfix was applied. The files the hotfix replaced are
still in `undo/payload/` for whoever puts the server back by hand. There is no `--force`.

After a rollback there is nothing to undo, and the hotfix before it cannot be taken off with
jrs-hotfix: one level of undo is the ruling. ADR-0001 (one outage per cascaded hotfix) is
superseded: there is no cascade.

**Snapshots outside `undo/`.** A run's own snapshot is deleted when the run ends: moved to
`undo/` by `promote`, or deleted after a clean compensation (exit 3) or a cancellation (exit
5). The one exception is a run that ended with exit 4 (rollback incomplete). Its snapshot is
what the operator needs to finish by hand, so it stays until `runs prune --include-failed`, as
in 0.5.

## 4. Recovery

Unchanged (spec 4.5). A pending run blocks every mutating command with exit 8;
`runs resume <id>` and `runs rollback <id>` finish or undo it from its journal and its own
snapshot. `runs rollback` undoes an interrupted run, not a hotfix, and is unaffected by the
latest-only ruling.

## 5. What else read the ledger

- **Superseded libraries** (spec-customized-servers 6). The webapp files known to be the
  vendor's are every baseline's files plus the files `undo.json` lists under the webapp. A
  cumulative package supersedes the libraries of the hotfix just before it, which is the one
  `undo.json` describes when that hotfix was applied with jrs-hotfix. That covers the field
  test that made this rule (log4j 2.25.3 left by the previous hotfix). A library from a hotfix
  two or more back, or applied by hand, without a baseline, is reported and left as in 0.5.
  The duplicate web-fragment refusal stays, with `baseline add` as its remedy (`record` is
  gone).
- **`verify` on the installed hotfix.** "Merged with this site's file" and "kept as the site
  has it" are read from `undo.json` when its id is the package's.
- **Merges.** `merge discard` refuses the merge `undo.json` names. `runs prune` keeps that merge
  and removes the others prepared before the cutoff.
- **`list`.** It shows the build the webapp states and the undo:
  ```
  on this server: build 20260730_0457 (10.0.0 PRO)
  can be undone:  JRSHF-10.0.0-20260730-0457, applied 2026-10-01T14:02Z by run 20261001-140211-a3f
  ```
  or `nothing to undo`. When `undo.json`'s build is not the one the webapp states, the second
  line says the server has changed since that apply and a rollback would be refused.
- **`runs prune`.** It removes run directories (logs, journals) of ended runs older than the
  cutoff, the snapshots of exit-4 runs with `--include-failed`, and merges and baselines as in
  0.5. It has no ledger entries to remove.
- **WAR target** (`apply --war`). Unchanged; it never had a ledger entry.

## 6. Commands and menu

| 0.5 | 0.6 |
|---|---|
| `record <package.zip>` | removed: the build the webapp states replaces it |
| `forget <id>` | removed: nothing to forget |
| `rollback <id> [--cascade]` | `rollback [<id>]`: the latest apply only; `--cascade` removed |
| `list` | the build and the undo (section 5) |
| menu entry 6, "Record a hotfix applied by hand" | removed; the entries after it move up |
| menu "Roll back a hotfix" with the cascade question | "Undo the latest hotfix (`<id>`)", shown only while `undo/` exists |

A script that runs `record`, `forget` or `--cascade` gets exit 1 (unknown command or option)
and must drop it. The release notes say so, as 0.4.0's did. The exit codes and their meanings
are unchanged (spec 6).

## 7. Homes written by 0.1 to 0.5

The first mutating command of 0.6 in a home that has `ledger.json` converts it once, under the
lock:

1. The newest entry that is `INSTALLED`, has origin `TOOL`, and has a snapshot that exists and
   verifies becomes `undo/`: `undo.json` from the entry, and the snapshot directory moved into
   place. Nothing becomes `undo/` when there is no such entry.
2. The other directories under `snapshots/` are deleted, except those of pending runs and of
   runs that ended with exit 4, which move to their run's `runs/<runId>/snapshot/`.
3. `ledger.json` is renamed `ledger.json.0.5`. It is kept for support and never read again.

The conversion is idempotent and its steps are journal lines, so a crash part-way is finished
by the next command. A read-only command (`list`, `verify`, `settings show`) on an unconverted
home reads the ledger as 0.5 would, to show the undo, and writes nothing.

## 8. What is given up

- Rolling back a hotfix that is not the latest, or more than one level back. With cumulative
  packages, putting back the previous hotfix's files is what "undo" means. Older states are a
  backup's job.
- Rolling back after the server changed under the hotfix. 0.5 refused that too, in its own way;
  0.6 says which files changed.
- Knowing a hotfix was applied by hand. The server's build says it is installed, which is all
  apply and verify need. `list` shows the build, not who applied it.
- Deleting a superseded library from two or more hotfixes back without a baseline (section 5).

## 9. What goes from the code

`state/Ledger`, `LedgerEntry`, `HotfixState`, `Origin` and `hotfix/RollbackChain`; most of
`BuildCheck` (only the build comparison of section 1 is left); `RecordCommand`,
`ForgetCommand`, `HotfixPlans.record`, `forget` and `resolveRollback`'s chain; the cascade
phases of the rollback plan; the ledger parts of `RunService.prune`, `ListCommand` and the menu.
`OwnedFile` survives as the file row of `undo.json`. That is roughly 1,000 lines with their
tests, against perhaps 300 new (the undo store, `promote`, the conversion).

## 10. Testing

- **Unit.**
  - Applicability by build: equal, newer, older, absent with and without the files in place.
  - `promote`: a crash simulated after each of its four renames converges on the next run.
  - Rollback preflight: refuses a changed file, a re-added deleted file, a missing `undo/`, and
    an id that is not the latest.
  - Snapshot deletion on exit 0, 3 and 5, and kept on exit 4.
  - Conversion of 0.5 homes: an installed entry with its snapshot; rolled-back and recorded
    entries only; a pending run; a crash part-way.
- **Acceptance** (`ScenarioTest`, `CrashRecoveryTest`, `CustomizedServerTest`).
  - Apply A, apply B, rollback: the server is back at A and there is nothing to undo.
  - Apply, then hand-edit a file, then rollback: refused, naming the file.
  - Redeploy the webapp at an older build, then apply: applies, with no `forget`.
  - A kill during `promote` resumes.
  - A 0.5 home with a ledger converts.
  - The cascade scenarios are removed.
- **Live.** The 0.2 sequence on the two real servers: apply, rollback, apply over a hotfix
  applied by hand.

## Open points

1. Refusing a package older than the webapp's build (section 1) is new. 0.5 applied it if the
   ledger did not know better. Taking a server back is what rollback is for, so the refusal
   stands unless the maintainer wants a `--downgrade` escape hatch.
2. Whether `rollback <id>` (accepted when it matches) is worth keeping over a bare `rollback`.
   It costs nothing and keeps scripts that name the hotfix working; it can go in 0.7.
