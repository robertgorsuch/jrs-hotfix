package com.jaspersoft.jrshotfix.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrshotfix.engine.Journal;
import com.jaspersoft.jrshotfix.engine.JournalException;
import com.jaspersoft.jrshotfix.engine.RunRecord;
import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.engine.Transition;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The run journal on disk: {@code runs/<id>/run.json} (start, end, state) and {@code
 * runs/<id>/journal.jsonl}, one appended, fsynced line per step transition. Invariants: run.json is
 * replaced atomically; a journal line is never rewritten; a run with no {@code endedAt} is pending;
 * a run directory with no run.json is not a run.
 */
public final class FileJournal implements Journal {
  private static final String RUN = "run.json";
  private static final String LINES = "journal.jsonl";
  private final Home home;
  private final Clock clock;

  public FileJournal(Home home, Clock clock) {
    this.home = home;
    this.clock = clock;
  }

  @Override
  public void recordRunStart(
      String runId, String operation, Optional<String> planId, Instant startedAt) {
    ObjectNode n = Json.mapper().createObjectNode();
    n.put("runId", runId).put("operation", operation).put("startedAt", startedAt.toString());
    planId.ifPresent(p -> n.put("planId", p));
    write(runId, n);
  }

  @Override
  public void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    ObjectNode n =
        (ObjectNode) readRun(runId).orElseThrow(() -> new JournalException("no run " + runId));
    n.put("endedAt", endedAt.toString())
        .put("terminalState", state.name())
        .put("exitCode", exitCode);
    write(runId, n);
  }

  @Override
  public synchronized Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail) {
    Path file = home.runDir(runId).resolve(LINES);
    try {
      Files.createDirectories(file.getParent());
      long seq = countLines(file) + 1;
      Transition t =
          new Transition(seq, clock.instant(), runId, stepId, phase, fromState, toState, detail);
      byte[] line = (Json.write(t) + "\n").getBytes(StandardCharsets.UTF_8);
      try (FileChannel ch =
          FileChannel.open(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND)) {
        ch.write(ByteBuffer.wrap(line));
        ch.force(true);
      }
      return t;
    } catch (IOException e) {
      throw new JournalException("cannot append to " + file + ": " + e.getMessage(), e);
    }
  }

  private static long countLines(Path file) throws IOException {
    if (!Files.exists(file)) {
      return 0;
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.count();
    }
  }

  @Override
  public Optional<RunRecord> run(String runId) {
    return readRun(runId).map(FileJournal::toRecord);
  }

  @Override
  public List<RunRecord> pendingRuns() {
    if (!Files.isDirectory(home.runs())) {
      return List.of();
    }
    List<RunRecord> out = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(home.runs())) {
      dirs.filter(d -> Files.isRegularFile(d.resolve(RUN)))
          .sorted()
          .forEach(
              d -> run(d.getFileName().toString()).filter(RunRecord::pending).ifPresent(out::add));
    } catch (IOException e) {
      throw new JournalException("cannot list " + home.runs() + ": " + e.getMessage(), e);
    }
    return List.copyOf(out);
  }

  @Override
  public List<Transition> transitions(String runId) {
    Path file = home.runDir(runId).resolve(LINES);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.filter(l -> !l.isBlank()).map(l -> Json.read(l, Transition.class)).toList();
    } catch (IOException e) {
      throw new JournalException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  private Optional<JsonNode> readRun(String runId) {
    Path file = home.runDir(runId).resolve(RUN);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new JournalException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  private void write(String runId, ObjectNode n) {
    Path file = home.runDir(runId).resolve(RUN);
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(RUN + ".tmp");
      Files.writeString(tmp, Json.writePretty(n), StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(
          tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Durability.syncDirectory(file.toAbsolutePath().getParent());
    } catch (IOException e) {
      throw new JournalException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private static RunRecord toRecord(JsonNode n) {
    return new RunRecord(
        n.get("runId").asText(),
        n.get("operation").asText(),
        Optional.ofNullable(n.get("planId")).map(JsonNode::asText),
        Instant.parse(n.get("startedAt").asText()),
        Optional.ofNullable(n.get("endedAt")).map(x -> Instant.parse(x.asText())),
        Optional.ofNullable(n.get("terminalState")).map(x -> TerminalState.valueOf(x.asText())),
        Optional.ofNullable(n.get("exitCode")).map(JsonNode::asInt));
  }
}
