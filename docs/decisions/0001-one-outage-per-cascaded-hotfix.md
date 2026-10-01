# 0001: One outage per cascaded hotfix

**Superseded in 0.6.0** by `docs/spec-no-ledger.md`: a rollback undoes the latest apply only,
so there is no cascade.

**Context.** Rolling back a hotfix whose files a later installed hotfix also owns needs `--cascade`: the later hotfixes come off first, newest first, and the target last. Each hotfix's rollback restores its own snapshot, and the files under `WEB-INF/lib` and `WEB-INF/classes` may only change while the service is stopped. The plan could stop the service once for the whole chain, or once per hotfix.

**Decision.** A rollback stops and starts the service once per hotfix: each hotfix is its own phase (`rollback:<id>`) of stop, restore the snapshot, start, wait for the server, mark rolled back. The first stop checks that every snapshot of the chain is present and intact before the service goes down.

**Consequence.** A cascade of N hotfixes costs N outages instead of one. A cascade is rare, and the per-hotfix phases keep each step's compensation local: a failure puts back only the hotfix being rolled back and leaves the server running on a consistent, recorded set of hotfixes. Merging the phases into one outage can come later without changing the ledger or the snapshots.
