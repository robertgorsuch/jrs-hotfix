package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.state.Ledger;
import com.jaspersoft.jrshotfix.state.LedgerEntry;
import com.jaspersoft.jrshotfix.state.OwnedFile;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which hotfixes have to come off, and in what order, to roll one of them back. Invariants: the
 * order is strictly last-installed-first (the ledger's order of installed entries), because a file
 * owned by a later hotfix must be restored by that hotfix before the earlier one can put its own
 * version back; a hotfix blocked by a later one is refused unless {@code --cascade} was given, and
 * the refusal names every direct blocker so the operator can decide; the walk is transitive, so a
 * blocker's own blockers are included, and a cycle cannot loop because a hotfix is only visited
 * once.
 */
final class RollbackChain {

  private RollbackChain() {}

  /**
   * The ids to roll back, newest first, the target last.
   *
   * @param cascade whether later hotfixes owning the same files may be taken off too
   * @throws HotfixException when the target is blocked and {@code cascade} is false
   */
  static List<String> of(Ledger ledger, LedgerEntry target, boolean cascade) {
    List<LedgerEntry> installed = ledger.installed();
    Map<String, Integer> order = new LinkedHashMap<>();
    for (int i = 0; i < installed.size(); i++) {
      order.put(installed.get(i).id(), i);
    }
    Set<String> selected = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(List.of(target.id()));
    List<String> directBlockers = new ArrayList<>();
    while (!pending.isEmpty()) {
      String id = pending.pop();
      if (!selected.add(id)) {
        continue;
      }
      int position = order.getOrDefault(id, -1);
      List<Path> paths = ledger.files(id).stream().map(OwnedFile::path).toList();
      Set<String> blockers = new LinkedHashSet<>();
      for (Map.Entry<String, OwnedFile> owned : ledger.filesOwnedBy(paths)) {
        if (!owned.getKey().equals(id) && order.getOrDefault(owned.getKey(), -1) > position) {
          blockers.add(owned.getKey());
        }
      }
      if (id.equals(target.id())) {
        directBlockers.addAll(blockers);
      }
      pending.addAll(blockers);
    }
    if (!directBlockers.isEmpty() && !cascade) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "rollback of "
              + target.id()
              + " is blocked by later hotfixes owning the same files: "
              + String.join(", ", directBlockers),
          "roll those back first, or re-run with --cascade");
    }
    List<String> ordered = new ArrayList<>(selected);
    ordered.sort((a, b) -> Integer.compare(order.getOrDefault(b, -1), order.getOrDefault(a, -1)));
    return List.copyOf(ordered);
  }
}
