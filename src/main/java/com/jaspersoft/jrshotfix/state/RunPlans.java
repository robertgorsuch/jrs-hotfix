package com.jaspersoft.jrshotfix.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrshotfix.engine.Plan;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The plan each run was started with, {@code runs/<runId>/plan.json} under the home: its id, the
 * operation and arguments it was built from, its fingerprint and the fingerprint's inputs, its step
 * ids and the summary the operator confirmed. Invariants: a write replaces the file atomically
 * after an fsync; an absent file is "no plan stored"; the arguments round-trip, so recovery can
 * rebuild the plan with {@code HotfixPlans.rebuild} and compare fingerprints.
 */
public final class RunPlans {
  private static final String FILE = "plan.json";

  private final Home home;

  public RunPlans(Home home) {
    this.home = Objects.requireNonNull(home, "home");
  }

  /**
   * What was stored for one run; {@code argsJson} is compact JSON; {@code inputs} are the
   * fingerprint's inputs, so recovery can compare the stable ones with a rebuilt plan's.
   */
  public record Stored(
      String planId,
      String operation,
      String argsJson,
      String fingerprint,
      Map<String, String> inputs,
      List<String> stepIds) {
    public Stored {
      Objects.requireNonNull(planId, "planId");
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(argsJson, "argsJson");
      Objects.requireNonNull(fingerprint, "fingerprint");
      inputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
      stepIds = List.copyOf(stepIds);
    }
  }

  public void store(String runId, Plan plan, String operation, String argsJson) {
    Path file = file(runId);
    try {
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("planId", plan.planId());
      doc.put("operation", operation);
      doc.put("args", Json.mapper().readTree(argsJson));
      doc.put("fingerprint", plan.fingerprint().value());
      doc.put("fingerprintInputs", plan.fingerprint().inputs());
      doc.put("stepIds", plan.steps().stream().map(Step::id).toList());
      doc.put("summary", plan.summary());
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(FILE + ".tmp");
      Files.writeString(tmp, Json.writePretty(doc), StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(
          tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Durability.syncDirectory(file.toAbsolutePath().getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  public Optional<Stored> load(String runId) {
    Path file = file(runId);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      JsonNode n = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
      List<String> stepIds = new ArrayList<>();
      for (JsonNode id : n.path("stepIds")) {
        stepIds.add(id.asText());
      }
      Map<String, String> inputs = new LinkedHashMap<>();
      n.path("fingerprintInputs")
          .properties()
          .forEach(e -> inputs.put(e.getKey(), e.getValue().asText()));
      return Optional.of(
          new Stored(
              n.path("planId").asText(),
              n.path("operation").asText(),
              Json.write(n.path("args")),
              n.path("fingerprint").asText(),
              inputs,
              stepIds));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private Path file(String runId) {
    return home.runDir(runId).resolve(FILE);
  }
}
