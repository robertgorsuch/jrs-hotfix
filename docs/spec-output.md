# jrs-hotfix 0.9 design: less on screen, the rest a command away

Status: **proposal for review** by support engineering. Nothing here is built. It answers the
field note on 0.8.0: "The app still puts out a LOT of text, we should probably start thinking
about pruning it and output only the most important info."

## 1. What prints today

An `apply` on a server prints, before it asks anything:

- `home:` and `Plan  <operation>  <target>`;
- a **Summary** table: the files touched (counted by area and action), resources, service,
  strategy, backups, and one rollback point per phase;
- every **warning**, one `!` line each: the package's SHA-256 to compare with the portal, the
  build check, what the merge keeps, merges and replaces, superseded libraries, files under
  `buildomatic/`, and the package readme's own lines (up to a limit, then where the rest is);
- **Steps**: every step, grouped by phase, with its id, title and a dimmed detail (about fifteen
  lines for an apply);
- `Fingerprint  <hash>` and `nothing has changed`.

Then `Run this plan? [y/N]`, then a line per step as it runs, then the outcome and what to do
next. `verify` prints the package's files and the readme's manual steps in full; `scan` lists
every customized file. The menu prints the command line it runs, then all of the above.

All of it is correct, and an engineer reading a run after the fact wants every line. An operator
in an outage window does not: what decides "y" is buried among lines that only describe how the
tool works.

## 2. Principle

On screen, by default, only what an operator **acts on or must know before saying yes**.
Everything else stays exactly as it is, **one command or one keystroke away**, and always in the
run's log. Nothing is dropped; it moves.

## 3. Proposal

### 3.1 The plan preview (apply, rollback, build host, WAR)

Default:

```
Apply JRSHF-10.0.0-20260730-0457 to C:\Jaspersoft\jasperreports-server  (release 10.0.0 PRO)
  Changes   212 files replaced, 14 added, 3 deleted; 5 site files kept, 2 merged
  Outage    the service is stopped for the swap and started again
  Undo      `jrs-hotfix rollback` puts back every file, from a snapshot taken first
  Check     package sha256 4f1c...9a2e: compare it with the support portal
  ! 1 library older than the package's is deleted: WEB-INF/lib/log4j-core-2.24.3.jar
  ! After the apply, by hand: 2 steps from the readme (shown again at the end, saved to notes.txt)
Full plan: `--details` (or d at the prompt); always in runs/<id>/run.log
Run this plan? [y/N/d]
```

- **Always shown:** what is applied to what; the change counts; whether there is an outage; how
  to undo; the checksum question; and every warning that is a **decision or a risk** (a refusal,
  a file that waits for a decision, a superseded library deleted, an older package allowed, the
  service left as it was, files not compared).
- **Moved behind `--details` / `d`:** the step table, the fingerprint, the rollback points, the
  home line, backups by path, and warnings that only describe normal behaviour (installer files
  kept, the count of generated files, files under `buildomatic/` merged as usual).
- **The readme's manual steps:** counted before the run and **printed in full after it**,
  when the operator has to do them, rather than before it, when they cannot.

### 3.2 Progress while it runs

One line per **phase** that updates in place at a terminal (`Stopping the service... done`),
instead of one per step; a step's own lines only when it warns or fails. Without a terminal, or
with `--details`, one line per step as today, since logs and CI read those.

### 3.3 The outcome

Two to four lines: done or not, the build the server states now, the undo, and the next action
(start the server, the manual steps, `runs resume`). The failure path keeps everything it prints
today: a failure is when detail matters most.

### 3.4 `verify` and `scan`

- `verify`: the verdict line first ("applies here", or why not), then the counts and the
  decisions, as the plan does; the full file list with `--details`.
- `scan`: `vanilla`, or `customized: N changed, M added, K removed`, then the files grouped by
  class with **at most 20 shown** and where the rest are; the full list with `--details`.

### 3.5 The menu

The menu passes nothing new: at its prompt `d` shows the full plan, as above. It keeps printing
the command line it runs (that is how the menu teaches the scripted form), on one line.

## 4. What does not change

- Every line written to `runs/<id>/run.log` and `plan.json`, and `runs show <id>`.
- Exit codes, refusals and their remediation, and everything printed on a failure.
- Scripts: `--yes` runs read the exit code, not the text. A `--details` run prints what 0.8 prints.

## 5. Open questions for support engineering

1. Which of today's lines do you actually read before answering "y"? Anything in 3.1's
   "moved" list you would miss?
2. Should the manual steps from the readme really move to after the run, or must they be read
   before the outage starts (for example a SQL change that has to be prepared)?
3. Is a terminal line that updates in place (3.2) welcome, or do you copy the screen into a case,
   where a line per step reads better?
4. Should brief be the default for everyone, or only in the menu, with the command line staying
   as it is?

## 6. Plan

After the answers: build 3.1 and 3.3 first (the preview and the outcome carry most of the text),
then 3.4, then 3.2. Each lands behind `--details` keeping today's output, so nothing is lost while
the brief form is tried on real servers.
