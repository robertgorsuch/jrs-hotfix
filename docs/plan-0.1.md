# jrs-hotfix Implementation Plan

> **Historical record.** This is the plan jrs-hotfix 0.1 was built from, written on 2026-09-28 in
> the jrsctl repository (branch `docs/jrs-hotfix-design`, never merged there) and moved here
> unchanged except that the author's home folder is shown as `~`. The spec it names is this
> repository's `docs/spec.md`, whose first version is that spec verbatim. The tool has moved on
> since 0.1 (see `docs/spec-no-ledger.md`, `docs/spec-targets.md` and `docs/releases/`); do not
> follow this plan to change the current code.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `jrs-hotfix`, a single-module tool that applies, verifies, records, lists and rolls back official Jaspersoft cumulative hotfix packages on JasperReports Server 10.x (Tomcat, Windows and Linux), in a new repository, by copying the proven engine, platform, snapshot and hotfix code out of jrsctl and cutting everything else.

**Architecture:** One Maven module, package root `com.jaspersoft.jrshotfix`. The step engine (`engine`), platform layer (`platform`), snapshot store (`snapshot`) and event types (`event`) are copied verbatim from jrsctl with only the package renamed. A JSON ledger and a JSONL run journal (`state`) replace SQLite. The official package is read directly (`pkg`) and staged straight from the ZIP; no derived bundle. The plans (`hotfix`) reuse jrsctl's step classes with the manifest, signing, SQL and REST removed. A picocli front end plus a seven-entry menu (`app`) sits on top.

**Tech Stack:** Java 21, Maven (wrapper), picocli 4.7.7, Jackson databind 2.22.2, JLine 3.30.17 (terminal, reader, terminal-jni), JUnit 5.14.4, AssertJ 3.27.7, Error Prone 2.50.0, google-java-format 1.22.0 via Spotless 2.43.0, Jacoco 0.8.15.

**Spec:** `docs/superpowers/specs/2026-09-28-jrs-hotfix-design.md` in the jrsctl repository (copy it to `docs/spec.md` in the new repository in Task 0; the plan argues from it).

**Source repository for copies:** every "copy" step below names a path under the jrsctl checkout at `~\jrs-ctl` (referred to as `$JRSCTL`). The new repository is created at `~\jrs-hotfix` (referred to as `$NEW`). Copies are made with `cp` from Git Bash, then the package is renamed with `sed -i` over the copied files. All shell blocks in this plan are Git Bash.

## Global Constraints

- Java 21; records, sealed interfaces, pattern-matching `switch` with no `default` over sealed types; no `null` from public APIs (`Optional` or a sealed result); one-paragraph Javadoc stating invariants on every public class.
- Package root `com.jaspersoft.jrshotfix`. Sub-packages: `engine`, `event`, `json`, `redact`, `platform`, `snapshot`, `home`, `state`, `pkg`, `service`, `hotfix`, `app`.
- Runtime dependencies: picocli, jackson-databind, jline-terminal, jline-reader, jline-terminal-jni. Nothing else. No sqlite-jdbc, no logback, no slf4j, no commons-compress, no commons-lang3, no json-schema-validator, no semver4j.
- Input: official Jaspersoft packages only (readme.txt plus `jasperserver[-pro].zip` and/or `js-install.zip`, or an unpacked `jasperserver[-pro]/` tree). No manifest, no signature, no SQL execution.
- Mutations only inside a `Step`; every `Step.execute` is idempotent and has a compensation. Read-only commands (verify, list, settings show, runs list/show) are plain functions.
- The service is always stopped before the swap. No replace-on-restart path.
- All I/O through `Platform`; no `java.io.File`; streaming only, no whole-file byte arrays for payloads.
- No `Runtime.exec(String)`; arguments as lists via `Platform.processes()`.
- Every output stream passes the `Redactor` filter.
- Home is `<installDir>/jrs-hotfix/`, overridable by `--home` or `JRS_HOTFIX_HOME`.
- Exit codes: 0 ok, 1 usage, 2 precheck failed (nothing mutated), 3 failed and rolled back, 4 rollback incomplete, 5 cancelled, 6 unsupported, 8 recovery required, 9 lock held. No code 7.
- Tests are named `should_<behaviour>_when_<condition>`. Conventional Commits, one logical change per commit.
- Build through `scripts/mvn.cmd` or `scripts/mvn.sh` (JDK 21 pinned); `scripts/fast.sh test <TestClass>` for one class; `scripts/fast.sh fmt` before every commit.
- Licence GPL-3 (copied code is GPL-3 under jrsctl ADR-0010).

## Review Focus

1. A package ZIP whose inner archive names carry a version suffix (`jasperserver-pro-10.0.0-hotfix.zip`) or whose readme is upper-cased (`README.TXT`) must still be recognised as official. Test pinned in Task 6.
2. A second `apply` of the same package after a successful first apply must report "already installed" with exit 2 and touch nothing, not stage and swap again. Test pinned in Task 8.
3. A readme "Deleted files" entry naming a file that does not exist on this installation must be skipped silently, never fail preflight. Test pinned in Task 6.
4. A run killed between the service stop and the swap must resume by completing the swap and starting the service, and must roll back by starting the service only, with the files untouched. Test pinned in Task 14.
5. `rollback` of a hotfix whose snapshot directory was deleted by hand must refuse with exit 2 naming the missing directory, not report success with the old files still in place. Test pinned in Task 9.

---

## File structure

```
jrs-hotfix/
  pom.xml                               single module, artifact jrs-hotfix
  mvnw, mvnw.cmd, .mvn/wrapper/         Maven wrapper (copy from $JRSCTL)
  scripts/mvn.sh, mvn.cmd, fast.sh, fast.cmd
  .gitattributes, .gitignore, .editorconfig, LICENSE, README.md
  docs/spec.md                          the design spec, verbatim
  docs/decisions/                       ADRs written during this plan
  src/main/java/com/jaspersoft/jrshotfix/
    Version.java                        version from jrs-hotfix-version.properties
    engine/                             copied: Step, Plan, Runner, Recovery, RunLock, Journal, ...
    event/                              copied: Event, EventSink, EventBus
    json/                               copied: Json, EventMixin, StepFailureMixin
    redact/                             copied: Redactor (no Secret overload), RedactingEventSink
    platform/                           copied verbatim (42 files)
    snapshot/                           copied verbatim (5 files)
    home/Home.java                      paths under the home directory
    home/Settings.java                  the settings record + toServiceConfig()
    home/SettingsStore.java             load/save settings.json
    home/Detection.java                 install candidates -> default Settings
    home/JrsVersion.java                copied from ops.JrsVersion
    state/LedgerEntry.java, OwnedFile.java, HotfixState.java, Origin.java
    state/Ledger.java                   ledger.json, atomic writes
    state/FileJournal.java              implements engine.Journal over runs/<id>/
    state/RunPlans.java                 plan.json per run (operation, args, fingerprint)
    pkg/Action.java                     ADD, REPLACE, DELETE
    pkg/PackageContents.java            what a package would do to this installation
    pkg/OfficialPackage.java            adapted: shape, describe, read (no bundle)
    pkg/PackagePaths.java               adapted from ops.hotfix.HotfixPaths
    pkg/FileTarget.java                 adapted (no siblings)
    service/ServerProbe.java            unauthenticated GET serverInfo
    service/ServiceRuntime.java         adapted (probe instead of identity)
    service/ServiceSteps.java           adapted
    service/CompanionDatabase.java      copied
    hotfix/HotfixException.java, Failures.java, PriorState.java, OwnerRestore.java  copied
    hotfix/HotfixRuntime.java           new record
    hotfix/ApplyInput.java, ApplySteps.java, BackupSteps.java, ApplyPhaseSteps.java, RecordSteps.java  adapted
    hotfix/RollbackSteps.java, RollbackChain.java  adapted
    hotfix/HotfixPlans.java             planApply, planRollback, record, verify, list, rebuild
    app/Main.java, RootCommand.java, GlobalOptions.java, ExitCodes.java
    app/ApplyCommand.java, RollbackCommand.java, VerifyCommand.java, ListCommand.java, RecordCommand.java, RunsCommand.java, SettingsCommand.java
    app/Menu.java                       the seven-entry menu (from GuidedMode)
    app/RunService.java                 lock, pending, run, resume, rollback glue
    app/PlanExecutor.java, PlanPrinter.java, ProgressRenderer.java  adapted
    app/Prompter.java, PathCompleter.java, Pager.java, Confirm.java, Terminal.java, SystemTerminal.java, TextTable.java, Ansi.java, LogFile.java, RunGuard.java, RunState.java, Env.java, OwnerOnlyFiles.java  copied
    app/EmbeddedDoc.java                prints README.md
  src/main/resources/jrs-hotfix-version.properties
  src/main/resources/docs/README.md     the one documentation page
  src/test/java/com/jaspersoft/jrshotfix/...   unit tests per package, fakes in engine/ and platform/
  src/test/java/com/jaspersoft/jrshotfix/acceptance/  Cli, Fixture, ScenarioTest, CrashRecoveryTest (Failsafe, against the shaded jar)
  .github/workflows/ci.yml, release.yml
  dist/bin/jrs-hotfix, jrs-hotfix.cmd   launchers for the portable archive
```

---

### Task 0: Repository scaffold and build

**Files:**
- Create: `$NEW/pom.xml`, `$NEW/scripts/mvn.sh`, `$NEW/scripts/mvn.cmd`, `$NEW/scripts/fast.sh`, `$NEW/scripts/fast.cmd`, `$NEW/.gitattributes`, `$NEW/.gitignore`, `$NEW/.editorconfig`, `$NEW/LICENSE`, `$NEW/README.md`, `$NEW/docs/spec.md`
- Create: `$NEW/src/main/java/com/jaspersoft/jrshotfix/Version.java`, `$NEW/src/main/resources/jrs-hotfix-version.properties`
- Test: `$NEW/src/test/java/com/jaspersoft/jrshotfix/VersionTest.java`

**Interfaces:**
- Produces: `Version.current()` returning `Version` with `version()`, `product()` ("jrs-hotfix"), `vendor()` ("Jaspersoft"). The Maven coordinates `com.jaspersoft.jrshotfix:jrs-hotfix:0.1.0-SNAPSHOT`. Shaded jar at `target/jrs-hotfix.jar`, main class `com.jaspersoft.jrshotfix.app.Main` (created in Task 11; until then the shade plugin is configured but `Main` is a stub that prints the version).

- [ ] **Step 1: Create the repository and copy the wrapper and dotfiles**

```bash
mkdir -p ~/jrs-hotfix && cd ~/jrs-hotfix && git init -b main
JRSCTL=~/jrs-ctl
cp -r $JRSCTL/.mvn $JRSCTL/mvnw $JRSCTL/mvnw.cmd .
cp $JRSCTL/LICENSE $JRSCTL/.editorconfig .
printf '%s\n' '* text=auto eol=lf' '*.cmd text eol=crlf' '*.bat text eol=crlf' '*.jar binary' '*.zip binary' > .gitattributes
printf '%s\n' 'target/' '*.class' '*.log' '.idea/' '*.iml' '.vscode/' '.DS_Store' 'hs_err_pid*' '.flattened-pom.xml' > .gitignore
mkdir -p docs/decisions scripts src/main/java/com/jaspersoft/jrshotfix src/main/resources src/test/java/com/jaspersoft/jrshotfix
cp $JRSCTL/docs/superpowers/specs/2026-09-28-jrs-hotfix-design.md docs/spec.md
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.jaspersoft.jrshotfix</groupId>
  <artifactId>jrs-hotfix</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>jrs-hotfix</name>
  <description>Apply, verify, record and roll back official Jaspersoft hotfix packages on JasperReports Server 10.x</description>
  <licenses><license><name>GPL-3.0-only</name><url>https://www.gnu.org/licenses/gpl-3.0.txt</url></license></licenses>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.build.outputTimestamp>2026-09-28T00:00:00Z</project.build.outputTimestamp>
    <picocli.version>4.7.7</picocli.version>
    <jackson.version>2.22.2</jackson.version>
    <jline.version>3.30.17</jline.version>
    <junit.version>5.14.4</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <errorprone.version>2.50.0</errorprone.version>
    <google-java-format.version>1.22.0</google-java-format.version>
    <spotless.version>2.43.0</spotless.version>
    <surefire.version>3.2.5</surefire.version>
    <jacoco.version>0.8.15</jacoco.version>
    <jacoco.line.minimum>0.80</jacoco.line.minimum>
    <acceptance.skip>false</acceptance.skip>
  </properties>

  <dependencies>
    <dependency><groupId>info.picocli</groupId><artifactId>picocli</artifactId><version>${picocli.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jsr310</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jdk8</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>org.jline</groupId><artifactId>jline-terminal</artifactId><version>${jline.version}</version></dependency>
    <dependency><groupId>org.jline</groupId><artifactId>jline-reader</artifactId><version>${jline.version}</version></dependency>
    <dependency><groupId>org.jline</groupId><artifactId>jline-terminal-jni</artifactId><version>${jline.version}</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>${assertj.version}</version><scope>test</scope></dependency>
  </dependencies>

  <build>
    <resources>
      <resource><directory>src/main/resources</directory><filtering>true</filtering><includes><include>jrs-hotfix-version.properties</include></includes></resource>
      <resource><directory>src/main/resources</directory><filtering>false</filtering><excludes><exclude>jrs-hotfix-version.properties</exclude></excludes></resource>
    </resources>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
        <configuration>
          <release>21</release>
          <compilerArgs>
            <arg>-XDcompilePolicy=simple</arg>
            <arg>--should-stop=ifError=FLOW</arg>
            <arg>-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode -Xep:StringConcatToTextBlock:OFF</arg>
            <arg>-Werror</arg>
          </compilerArgs>
          <annotationProcessorPaths>
            <path><groupId>com.google.errorprone</groupId><artifactId>error_prone_core</artifactId><version>${errorprone.version}</version></path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>${surefire.version}</version>
        <configuration><excludes><exclude>**/acceptance/**</exclude></excludes></configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.2</version>
        <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>
          <configuration>
            <finalName>jrs-hotfix</finalName>
            <createDependencyReducedPom>false</createDependencyReducedPom>
            <filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude><exclude>module-info.class</exclude></excludes></filter></filters>
            <transformers>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>com.jaspersoft.jrshotfix.app.Main</mainClass>
                <manifestEntries><Multi-Release>true</Multi-Release></manifestEntries>
              </transformer>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
            </transformers>
          </configuration>
        </execution></executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>${surefire.version}</version>
        <configuration>
          <skip>${acceptance.skip}</skip>
          <includes><include>**/acceptance/*Test.java</include></includes>
          <forkCount>2</forkCount><reuseForks>false</reuseForks>
          <systemPropertyVariables><jrshotfix.jar>${project.build.directory}/jrs-hotfix.jar</jrshotfix.jar></systemPropertyVariables>
        </configuration>
        <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId><version>${spotless.version}</version>
        <configuration><java><googleJavaFormat><version>${google-java-format.version}</version></googleJavaFormat></java></configuration>
        <executions><execution><id>spotless-check</id><phase>validate</phase><goals><goal>check</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>${jacoco.version}</version>
        <executions>
          <execution><id>prepare-agent</id><goals><goal>prepare-agent</goal></goals></execution>
          <execution><id>report</id><phase>test</phase><goals><goal>report</goal></goals></execution>
          <execution><id>check</id><phase>verify</phase><goals><goal>check</goal></goals>
            <configuration><rules><rule><element>BUNDLE</element><limits><limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>${jacoco.line.minimum}</minimum></limit></limits></rule></rules>
              <excludes><exclude>com/jaspersoft/jrshotfix/acceptance/**</exclude></excludes></configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write the scripts**

`scripts/mvn.sh`:
```bash
#!/usr/bin/env bash
# Runs Maven with the JDK 21 this project requires. Usage: scripts/mvn.sh verify
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JRSHOTFIX_JDK:=}"
if [ -z "$JRSHOTFIX_JDK" ]; then
  for c in /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 "$HOME/tools/jdk-21" "/c/Program Files/Microsoft/jdk-21.0.9.10-hotspot"; do
    [ -x "$c/bin/java" ] && JRSHOTFIX_JDK="$c" && break
  done
fi
[ -x "$JRSHOTFIX_JDK/bin/java" ] || { echo "JDK 21 not found; set JRSHOTFIX_JDK" >&2; exit 1; }
export JAVA_HOME="$JRSHOTFIX_JDK"; export PATH="$JAVA_HOME/bin:$PATH"
exec ./mvnw -B "$@"
```

`scripts/mvn.cmd`:
```bat
@echo off
rem Runs Maven with the JDK 21 this project requires. Usage: scripts\mvn.cmd verify
setlocal
if "%JRSHOTFIX_JDK%"=="" set "JRSHOTFIX_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
if not exist "%JRSHOTFIX_JDK%\bin\java.exe" ( echo JDK 21 not found at "%JRSHOTFIX_JDK%". Set JRSHOTFIX_JDK. 1>&2 & exit /b 1 )
set "JAVA_HOME=%JRSHOTFIX_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0..\mvnw.cmd" -B %*
```

`scripts/fast.sh`:
```bash
#!/usr/bin/env bash
# fast.sh test <TestClass[,TestClass]>   compile (Error Prone, -Werror) + those unit tests
# fast.sh accept [TestClass]             build the shaded jar and run the acceptance tests
# fast.sh fmt                            google-java-format (spotless:apply)
set -euo pipefail
cd "$(dirname "$0")/.."
COMMON=(-Djacoco.skip=true -Dspotless.check.skip=true -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false)
case "${1:-}" in
  test)   bash scripts/mvn.sh -q test "${COMMON[@]}" -Dacceptance.skip=true -Dtest="$2" ;;
  accept) bash scripts/mvn.sh -q verify "${COMMON[@]}" -DskipTests=false -Dsurefire.skip=true ${2:+-Dit.test="$2"} ;;
  fmt)    bash scripts/mvn.sh -q spotless:apply ;;
  *)      sed -n '2,4p' "$0" | sed 's/^# //'; exit 1 ;;
esac
```

`scripts/fast.cmd`:
```bat
@echo off
rem Delegates to fast.sh through Git Bash. Usage: scripts\fast.cmd test VersionTest
bash "%~dp0fast.sh" %*
```

- [ ] **Step 4: Write the version resource, `Version`, a stub `Main`, and the failing test**

`src/main/resources/jrs-hotfix-version.properties`:
```properties
version=${project.version}
product=jrs-hotfix
vendor=Jaspersoft
```

`src/main/java/com/jaspersoft/jrshotfix/Version.java`:
```java
package com.jaspersoft.jrshotfix;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The build's own identity, read once from {@code jrs-hotfix-version.properties} (filtered by
 * Maven). Invariants: never empty; a missing resource is a packaging bug and fails fast.
 */
public final class Version {
  private static final Version CURRENT = load();
  private final String version;
  private final String product;
  private final String vendor;

  private Version(String version, String product, String vendor) {
    this.version = version;
    this.product = product;
    this.vendor = vendor;
  }

  public static Version current() {
    return CURRENT;
  }

  public String version() {
    return version;
  }

  public String product() {
    return product;
  }

  public String vendor() {
    return vendor;
  }

  private static Version load() {
    Properties p = new Properties();
    try (InputStream in = Version.class.getResourceAsStream("/jrs-hotfix-version.properties")) {
      if (in == null) {
        throw new IllegalStateException("jrs-hotfix-version.properties is missing from the jar");
      }
      p.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Version(
        p.getProperty("version", "0"), p.getProperty("product", "jrs-hotfix"), p.getProperty("vendor", "Jaspersoft"));
  }
}
```

`src/main/java/com/jaspersoft/jrshotfix/app/Main.java` (stub, replaced in Task 11):
```java
package com.jaspersoft.jrshotfix.app;

import com.jaspersoft.jrshotfix.Version;

/** Process entry point. Invariant: the only place that calls {@code System.exit}. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.out.println(Version.current().product() + " " + Version.current().version());
  }
}
```

`src/test/java/com/jaspersoft/jrshotfix/VersionTest.java`:
```java
package com.jaspersoft.jrshotfix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {
  @Test
  void should_read_product_and_vendor_when_resource_is_filtered() {
    assertThat(Version.current().product()).isEqualTo("jrs-hotfix");
    assertThat(Version.current().vendor()).isEqualTo("Jaspersoft");
    assertThat(Version.current().version()).doesNotContain("${");
  }
}
```

- [ ] **Step 5: Run the test and the shade build**

Run: `bash scripts/fast.sh test VersionTest`
Expected: PASS.
Run: `bash scripts/mvn.sh -q -DskipTests package && java -jar target/jrs-hotfix.jar`
Expected: prints `jrs-hotfix 0.1.0-SNAPSHOT`.

- [ ] **Step 6: Write `README.md` (short) and commit**

`README.md` holds three lines: what the tool is, `scripts/mvn.sh verify` to build, and "docs/spec.md is the design". The full operator page comes in Task 13.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "build: single-module scaffold with Error Prone, Spotless, Jacoco, shade and Failsafe"
```

---

### Task 1: Copy the step engine, events, JSON and redaction

**Files:**
- Create (copied): `src/main/java/com/jaspersoft/jrshotfix/engine/*.java` (24 files from `$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/engine/`), `event/*.java` (3), `json/*.java` (3), `redact/Redactor.java`, `redact/RedactingEventSink.java`
- Create: `src/main/java/com/jaspersoft/jrshotfix/home/Home.java`
- Test (copied then adapted): `src/test/java/com/jaspersoft/jrshotfix/engine/{EngineFixture,FakePlatform,FakeStep,RunnerTest,RecoveryTest,RunLockTest,RunLockChildHolder,RunIdsAndOutcomeTest,MemoryJournal}.java`, `event/{EventBusTest,RecordingSink}.java`, `json/JsonTest.java`, `redact/{RedactorTest,RedactingEventSinkTest}.java`

**Interfaces:**
- Consumes: nothing.
- Produces: unchanged jrsctl engine API under the new package: `Step`, `Plan(planId, steps, summary, fingerprint)`, `Context(runId, Home home, Platform platform, CancellationToken cancel, Map<Class<?>,Object> services)`, `Runner(Journal, EventSink, Clock, Sleeper[, Redactor])` with `run/resume/rollback`, `Recovery(Journal, Runner)` with `pendingRuns/journal/resume/rollback`, `RunLock(Home, runId, Instant)`, `Journal` interface (six methods), `RunRecord`, `Transition`, `RunOutcome`, `StepResult`, `CheckResult`, `StepFailure`, `PlanFingerprint.of(Map)`, `PlanSummary`, `RunOptions`, `RunIds.next(Clock)`, `Sleeper`, `TerminalState`, `StepState`. `Home(Path root)` with `settingsFile()`, `ledgerFile()`, `runLock()`, `runs()`, `runDir(id)`, `stagingDir(id)`, `snapshots()`, `nativeTemp()`, `logFile(id)`. `Redactor` with `register(String)`, `redact(String)`, `global()`. `Json.mapper()/pretty()/write/writePretty/read`. Test helper `MemoryJournal implements Journal`.

- [ ] **Step 1: Copy and rename**

```bash
cd ~/jrs-hotfix; JRSCTL=~/jrs-ctl
S=$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core; D=src/main/java/com/jaspersoft/jrshotfix
T=$JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core; DT=src/test/java/com/jaspersoft/jrshotfix
mkdir -p $D/{engine,event,json,redact,home} $DT/{engine,event,json,redact}
cp $S/engine/*.java $D/engine/; cp $S/event/*.java $D/event/; cp $S/json/*.java $D/json/
cp $S/redact/Redactor.java $S/redact/RedactingEventSink.java $D/redact/
cp $T/engine/{EngineFixture,FakePlatform,FakeStep,RunnerTest,RecoveryTest,RunLockTest,RunLockChildHolder,RunIdsAndOutcomeTest}.java $DT/engine/
cp $T/event/{EventBusTest,RecordingSink}.java $DT/event/; cp $T/json/JsonTest.java $DT/json/
cp $T/redact/{RedactorTest,RedactingEventSinkTest}.java $DT/redact/
find src -name '*.java' | xargs sed -i -e 's/com\.jaspersoft\.jrsctl\.core\.\(engine\|event\|json\|redact\)/com.jaspersoft.jrshotfix.\1/g' \
  -e 's/com\.jaspersoft\.jrsctl\.core\.JrsctlHome/com.jaspersoft.jrshotfix.home.Home/g' -e 's/\bJrsctlHome\b/Home/g' \
  -e 's/com\.jaspersoft\.jrsctl\.core\.platform/com.jaspersoft.jrshotfix.platform/g'
```

- [ ] **Step 2: Write `home/Home.java`**

```java
package com.jaspersoft.jrshotfix.home;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Every path under the jrs-hotfix home. Invariants: {@code root} is absolute and normalised;
 * nothing here touches the disk; run and snapshot directories are keyed by run id only.
 */
public record Home(Path root) {
  public Home {
    root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
  }

  public Path settingsFile() {
    return root.resolve("settings.json");
  }

  public Path ledgerFile() {
    return root.resolve("ledger.json");
  }

  public Path runLock() {
    return root.resolve("lock");
  }

  public Path runs() {
    return root.resolve("runs");
  }

  public Path runDir(String runId) {
    return runs().resolve(runId);
  }

  public Path stagingDir(String runId) {
    return runDir(runId).resolve("staging");
  }

  public Path logFile(String runId) {
    return runDir(runId).resolve("run.log");
  }

  public Path snapshots() {
    return root.resolve("snapshots");
  }

  public Path nativeTemp() {
    return root.resolve("tmp");
  }
}
```

- [ ] **Step 3: Trim `Redactor` and fix the copied tests to compile**

In `redact/Redactor.java` delete the `import ...core.secrets.Secret;` line and the `public boolean register(Secret secret)` method (it delegates to `register(String)` after `new String(secret.value())`; nothing else uses it). In `RedactorTest.java` delete the tests that construct a `Secret` (search `Secret`).

The engine tests use `StateStore` as the journal through `EngineFixture`. Replace it with an in-memory journal:

`src/test/java/com/jaspersoft/jrshotfix/engine/MemoryJournal.java`:
```java
package com.jaspersoft.jrshotfix.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A journal in memory for engine tests: same semantics as the file journal, no disk. */
public final class MemoryJournal implements Journal {
  private final Map<String, RunRecord> runs = new LinkedHashMap<>();
  private final List<Transition> transitions = new ArrayList<>();

  @Override
  public synchronized void recordRunStart(String runId, String operation, Optional<String> planId, Instant startedAt) {
    runs.put(runId, new RunRecord(runId, operation, planId, startedAt, Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Override
  public synchronized void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    RunRecord r = runs.get(runId);
    runs.put(runId, new RunRecord(r.runId(), r.operation(), r.planId(), r.startedAt(), Optional.of(endedAt), Optional.of(state), Optional.of(exitCode)));
  }

  @Override
  public synchronized Transition appendTransition(String runId, String stepId, String phase, Optional<String> fromState, String toState, Optional<String> detail) {
    Transition t = new Transition(transitions.size() + 1, Instant.now(), runId, stepId, phase, fromState, toState, detail);
    transitions.add(t);
    return t;
  }

  @Override
  public synchronized Optional<RunRecord> run(String runId) {
    return Optional.ofNullable(runs.get(runId));
  }

  @Override
  public synchronized List<RunRecord> pendingRuns() {
    return runs.values().stream().filter(RunRecord::pending).toList();
  }

  @Override
  public synchronized List<Transition> transitions(String runId) {
    return transitions.stream().filter(t -> t.runId().equals(runId)).toList();
  }
}
```

In `EngineFixture.java`: replace the `StateStore store` field and its `StateStore.open(...)` construction with `public final MemoryJournal store = new MemoryJournal();`, drop the `close()` body's store close, and drop the `core.state` import. Check `RunRecord`'s constructor argument order against the copied record (`runId, operation, planId, startedAt, endedAt, terminalState, exitCode`) and fix `MemoryJournal` if the copied record differs.

- [ ] **Step 4: Compile and run the copied tests**

Run: `bash scripts/fast.sh test RunnerTest,RecoveryTest,RunLockTest,RunIdsAndOutcomeTest,EventBusTest,JsonTest,RedactorTest,RedactingEventSinkTest`
Expected: PASS. If a test references `StateStore`-only behaviour (a `JournalException` from SQLite), delete that single test method and note it in the commit body.

- [ ] **Step 5: Commit**

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(engine): copy the step engine, events, JSON and redaction from jrsctl"
```

---

### Task 2: The file journal

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/state/FileJournal.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/state/FileJournalTest.java`, and copy `$JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core/engine/JournalPortTest.java` to `src/test/java/com/jaspersoft/jrshotfix/state/JournalPortTest.java`, retargeted at `FileJournal`

**Interfaces:**
- Consumes: `engine.Journal`, `engine.RunRecord`, `engine.Transition`, `engine.TerminalState`, `home.Home`, `json.Json`.
- Produces: `new FileJournal(Home home, Clock clock)` implementing `Journal`. Layout: `runs/<runId>/run.json` and `runs/<runId>/journal.jsonl`.

- [ ] **Step 1: Write the failing test**

```java
package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.TerminalState;
import com.jaspersoft.jrshotfix.home.Home;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileJournalTest {
  @TempDir Path tmp;

  @Test
  void should_list_a_run_as_pending_until_it_ends() throws Exception {
    FileJournal j = new FileJournal(new Home(tmp), Clock.systemUTC());
    j.recordRunStart("r1", "hotfix.apply", Optional.of("p1"), Instant.parse("2026-09-28T10:00:00Z"));
    assertThat(j.pendingRuns()).extracting("runId").containsExactly("r1");
    j.appendTransition("r1", "snapshot", "backup", Optional.empty(), "RUNNING", Optional.empty());
    j.appendTransition("r1", "snapshot", "backup", Optional.of("RUNNING"), "SUCCEEDED", Optional.of("12 files"));
    j.recordRunEnd("r1", Instant.parse("2026-09-28T10:05:00Z"), TerminalState.SUCCEEDED, 0);
    assertThat(j.pendingRuns()).isEmpty();
    assertThat(j.run("r1").orElseThrow().exitCode()).contains(0);
    assertThat(j.transitions("r1")).hasSize(2);
    assertThat(j.transitions("r1").get(1).seq()).isEqualTo(2);
    assertThat(Files.readAllLines(tmp.resolve("runs/r1/journal.jsonl"))).hasSize(2);
  }

  @Test
  void should_survive_a_reopen_when_the_process_restarts() throws Exception {
    new FileJournal(new Home(tmp), Clock.systemUTC()).recordRunStart("r2", "hotfix.apply", Optional.empty(), Instant.now());
    assertThat(new FileJournal(new Home(tmp), Clock.systemUTC()).pendingRuns()).extracting("runId").containsExactly("r2");
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/fast.sh test FileJournalTest` — Expected: compile error, `FileJournal` missing.

- [ ] **Step 3: Write `FileJournal`**

```java
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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  public void recordRunStart(String runId, String operation, Optional<String> planId, Instant startedAt) {
    ObjectNode n = Json.mapper().createObjectNode();
    n.put("runId", runId).put("operation", operation).put("startedAt", startedAt.toString());
    planId.ifPresent(p -> n.put("planId", p));
    write(runId, n);
  }

  @Override
  public void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    ObjectNode n = (ObjectNode) readRun(runId).orElseThrow(() -> new JournalException("no run " + runId));
    n.put("endedAt", endedAt.toString()).put("terminalState", state.name()).put("exitCode", exitCode);
    write(runId, n);
  }

  @Override
  public synchronized Transition appendTransition(String runId, String stepId, String phase, Optional<String> fromState, String toState, Optional<String> detail) {
    Path file = home.runDir(runId).resolve(LINES);
    try {
      Files.createDirectories(file.getParent());
      long seq = Files.exists(file) ? Files.lines(file, StandardCharsets.UTF_8).count() + 1 : 1;
      Transition t = new Transition(seq, clock.instant(), runId, stepId, phase, fromState, toState, detail);
      byte[] line = (Json.write(t) + "\n").getBytes(StandardCharsets.UTF_8);
      try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
        ch.write(java.nio.ByteBuffer.wrap(line));
        ch.force(true);
      }
      return t;
    } catch (IOException e) {
      throw new JournalException("cannot append to " + file + ": " + e.getMessage(), e);
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
      dirs.filter(d -> Files.isRegularFile(d.resolve(RUN))).sorted()
          .forEach(d -> run(d.getFileName().toString()).filter(RunRecord::pending).ifPresent(out::add));
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
      Durability.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new JournalException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private static RunRecord toRecord(JsonNode n) {
    return new RunRecord(
        n.get("runId").asText(), n.get("operation").asText(),
        Optional.ofNullable(n.get("planId")).map(JsonNode::asText),
        Instant.parse(n.get("startedAt").asText()),
        Optional.ofNullable(n.get("endedAt")).map(x -> Instant.parse(x.asText())),
        Optional.ofNullable(n.get("terminalState")).map(x -> TerminalState.valueOf(x.asText())),
        Optional.ofNullable(n.get("exitCode")).map(JsonNode::asInt));
  }
}
```

`Durability` is in `platform`, which is copied in Task 3. To keep this task green on its own, copy `$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/platform/Durability.java` now (rename its package with the same `sed`); Task 3 overwrites it with the identical file. `Transition` must serialise through Jackson: check the copied `json/Json.java` registers `Jdk8Module` and `JavaTimeModule` (jrsctl's does); records need no annotations.

- [ ] **Step 4: Run the tests**

Run: `bash scripts/fast.sh test FileJournalTest,JournalPortTest` — Expected: PASS. `JournalPortTest` was written against the `Journal` contract; change its construction to `new FileJournal(new Home(tmp), Clock.systemUTC())` and drop any `StateStore` import.

- [ ] **Step 5: Commit**

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(state): file journal with run.json and fsynced journal.jsonl"
```

---

### Task 3: Copy the platform layer

**Files:**
- Create (copied verbatim, package renamed): all 42 files of `$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/platform/` into `src/main/java/com/jaspersoft/jrshotfix/platform/`, minus `DefaultHome.java` and `HomeRedirect.java`
- Test (copied): from `$JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core/platform/`: `DefaultFileOpsTest, DefaultProcessRunnerTest, DiskSpaceTest, DurabilityTest, FailingFileOps, FakeProcessRunner, FakeTomcatProcessFinder, LinuxInitTest, NativeTempDirTest, NaturalOrderTest, PermissionBypass, PlatformDetectionTest, ProcessRunnerHelper, RunningTomcatsTest, ScriptServiceControllerForceStopTest, ServerXmlPortsTest, ServiceControllersTest, StalePidFileTest, SystemdSocketAndPidFileTest, TreesTest, UserPathsTest, WindowsCodePageTest, WindowsTomcatProcessesTest` (skip `DefaultHomeTest` and `HomeRedirectTest`)

**Interfaces:**
- Produces: `Platform` (`os()`, `services(ServiceConfig)`, `files()`, `processes()`, `detectTomcat(Path)`, `candidateInstallDirs()`, `scanInstallDirs()`, `withInstallDir(Path)`, `runningTomcats(Path)`), `Platforms.detect(OperatorPrompt)`, `Platforms.forTesting(OsFamily, Arch, ProcessRunner, FileOps, OperatorPrompt)`, `FileOps`, `ServiceController`, `ServiceConfig(Kind, Optional<String> name, Optional<Path> scriptPath, Duration stopTimeout, Optional<Duration> forceStopAfter)`, `ServiceConfig.Kind {WINDOWS_SERVICE, SYSTEMD, CTLSCRIPT, CATALINA, MANUAL}`, `TomcatLayout`, `InstallScan`, `Trees`, `DiskSpace`, `Durability`, `NativeTempDir`, `OperatorPrompt`, `UserPaths`, `ServerXml.httpPort(Path)` (made `public static` here because `home.Detection` needs it), `FakeProcessRunner` (test helper, made `public`).

- [ ] **Step 1: Copy and rename**

```bash
cd ~/jrs-hotfix; JRSCTL=~/jrs-ctl
S=$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/platform; T=$JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core/platform
mkdir -p src/main/java/com/jaspersoft/jrshotfix/platform src/test/java/com/jaspersoft/jrshotfix/platform
cp $S/*.java src/main/java/com/jaspersoft/jrshotfix/platform/
rm src/main/java/com/jaspersoft/jrshotfix/platform/DefaultHome.java src/main/java/com/jaspersoft/jrshotfix/platform/HomeRedirect.java
for f in DefaultFileOpsTest DefaultProcessRunnerTest DiskSpaceTest DurabilityTest FailingFileOps FakeProcessRunner FakeTomcatProcessFinder LinuxInitTest NativeTempDirTest NaturalOrderTest PermissionBypass PlatformDetectionTest ProcessRunnerHelper RunningTomcatsTest ScriptServiceControllerForceStopTest ServerXmlPortsTest ServiceControllersTest StalePidFileTest SystemdSocketAndPidFileTest TreesTest UserPathsTest WindowsCodePageTest WindowsTomcatProcessesTest; do cp $T/$f.java src/test/java/com/jaspersoft/jrshotfix/platform/; done
find src -name '*.java' | xargs sed -i -e 's/com\.jaspersoft\.jrsctl\.core\.platform/com.jaspersoft.jrshotfix.platform/g' -e 's/com\.jaspersoft\.jrsctl\.core\.JrsctlHome/com.jaspersoft.jrshotfix.home.Home/g' -e 's/\bJrsctlHome\b/Home/g'
sed -i -e 's/^final class ServerXml/public final class ServerXml/' -e 's/^  static Optional<Integer> httpPort/  public static Optional<Integer> httpPort/' src/main/java/com/jaspersoft/jrshotfix/platform/ServerXml.java
sed -i -e 's/^final class FakeProcessRunner/public final class FakeProcessRunner/' -e 's/^  FakeProcessRunner on(/  public FakeProcessRunner on(/' -e 's/^  static Response ok(/  public static Response ok(/' -e 's/^  record Response(/  public record Response(/' src/test/java/com/jaspersoft/jrshotfix/platform/FakeProcessRunner.java
grep -rln 'jrsctl' src/main/java/com/jaspersoft/jrshotfix/platform src/test/java/com/jaspersoft/jrshotfix/platform
```

The last `grep` lists files that still say jrsctl inside strings (the system property `jrsctl.containerCheck`, the default home directory name, remediation text). On each listed file run `sed -i 's/jrsctl\.containerCheck/jrshotfix.containerCheck/g; s/jrsctl/jrs-hotfix/g'` and re-read the changed lines: the only acceptable remaining mentions are prose in Javadoc that cites a jrsctl ADR number.

- [ ] **Step 2: Compile and run**

Run: `bash scripts/fast.sh test 'DefaultFileOpsTest,DefaultProcessRunnerTest,DiskSpaceTest,DurabilityTest,LinuxInitTest,NativeTempDirTest,NaturalOrderTest,PlatformDetectionTest,RunningTomcatsTest,ScriptServiceControllerForceStopTest,ServerXmlPortsTest,ServiceControllersTest,StalePidFileTest,SystemdSocketAndPidFileTest,TreesTest,UserPathsTest,WindowsCodePageTest,WindowsTomcatProcessesTest'`
Expected: PASS on Windows; OS-conditional tests keep their `@EnabledOnOs` annotations from jrsctl.

- [ ] **Step 3: Commit**

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(platform): copy the platform layer from jrsctl"
```

---

### Task 4: Copy the snapshot store

**Files:**
- Create (copied): the 5 files of `$JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/snapshot/` into `src/main/java/com/jaspersoft/jrshotfix/snapshot/`
- Test (copied): `$JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core/snapshot/SnapshotStoreTest.java`

**Interfaces:**
- Produces: `SnapshotStore(Home, FileOps[, Clock])` with `create(runId, stepId, List<Path> paths, Path baseDir)`, `verify(Snapshot)`, `restore(Snapshot)`, `find(runId, stepId)`, `list()`, `prune(Duration, int, Set<String> protectedRunIds)`; `Snapshot(runId, stepId, dir, manifest)` with `manifestFile()`, `payloadDir()`; `SnapshotManifest.Entry(path, sha256, size, permissions)`.

- [ ] **Step 1: Copy, rename, run**

```bash
cd ~/jrs-hotfix; JRSCTL=~/jrs-ctl
mkdir -p src/main/java/com/jaspersoft/jrshotfix/snapshot src/test/java/com/jaspersoft/jrshotfix/snapshot
cp $JRSCTL/core/src/main/java/com/jaspersoft/jrsctl/core/snapshot/*.java src/main/java/com/jaspersoft/jrshotfix/snapshot/
cp $JRSCTL/core/src/test/java/com/jaspersoft/jrsctl/core/snapshot/SnapshotStoreTest.java src/test/java/com/jaspersoft/jrshotfix/snapshot/
find src -name '*.java' | xargs sed -i -e 's/com\.jaspersoft\.jrsctl\.core\.\(snapshot\|platform\|json\)/com.jaspersoft.jrshotfix.\1/g' -e 's/com\.jaspersoft\.jrsctl\.core\.JrsctlHome/com.jaspersoft.jrshotfix.home.Home/g' -e 's/\bJrsctlHome\b/Home/g'
bash scripts/fast.sh test SnapshotStoreTest
```
Expected: PASS.

- [ ] **Step 2: Commit**

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(snapshot): copy the snapshot store from jrsctl"
```

---

### Task 5: Settings, home resolution and detection

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/home/Settings.java`, `home/SettingsStore.java`, `home/HomeResolver.java`, `home/Detection.java`
- Create (copied): `home/JrsVersion.java` from `$JRSCTL/ops/src/main/java/com/jaspersoft/jrsctl/ops/JrsVersion.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/home/{SettingsStoreTest,HomeResolverTest,DetectionTest}.java`

**Interfaces:**
- Consumes: `platform.Platform`, `platform.ServiceConfig`, `platform.TomcatLayout`, `platform.InstallScan`, `platform.ServerXml.httpPort`, `platform.ProcessRunner`, `platform.LinuxInit`, `platform.Durability`, `json.Json`.
- Produces:
  - `record Settings(Path installDir, Path tomcatDir, String webappName, ServiceConfig.Kind serviceKind, Optional<String> serviceName, Optional<Path> serviceScriptPath, int stopTimeoutSeconds, Optional<Integer> forceStopAfterSeconds, URI baseUrl)` with `toServiceConfig()`, `webappDir()`, `fingerprintInput()`, `keys()` (ordered key to value map) and `withKey(String, String)` (keys `installDir, tomcatDir, webappName, service.kind, service.name, service.scriptPath, service.stopTimeoutSeconds, service.forceStopAfterSeconds, baseUrl`; unknown key throws `IllegalArgumentException` naming the keys).
  - `SettingsStore.load(Home) -> Optional<Settings>`, `SettingsStore.save(Home, Settings)`.
  - `HomeResolver.resolve(Optional<Path> flag, Map<String,String> env, Optional<Path> installDir) -> Home`.
  - `Detection.candidates(Platform) -> InstallScan`; `Detection.defaults(Platform, Path installDir) -> Optional<Settings>`.
  - `JrsVersion.ofWebapp(Path webappDir) -> Optional<String>`.

- [ ] **Step 1: Write the failing tests**

`SettingsStoreTest.java`:
```java
package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
  @TempDir Path tmp;

  static Settings sample(Path tmp) {
    return new Settings(tmp.resolve("jrs"), tmp.resolve("jrs/apache-tomcat"), "jasperserver-pro",
        ServiceConfig.Kind.MANUAL, Optional.empty(), Optional.empty(), 180, Optional.empty(),
        URI.create("http://localhost:8080/jasperserver-pro"));
  }

  @Test
  void should_round_trip_when_saved_and_loaded() {
    Home home = new Home(tmp.resolve("home"));
    assertThat(SettingsStore.load(home)).isEmpty();
    SettingsStore.save(home, sample(tmp));
    assertThat(SettingsStore.load(home)).contains(sample(tmp));
  }

  @Test
  void should_apply_a_typed_key_when_set_from_the_command_line() {
    Settings s = sample(tmp).withKey("service.kind", "systemd").withKey("service.name", "jasperreportsTomcat").withKey("service.stopTimeoutSeconds", "60");
    assertThat(s.toServiceConfig().kind()).isEqualTo(ServiceConfig.Kind.SYSTEMD);
    assertThat(s.toServiceConfig().name()).contains("jasperreportsTomcat");
    assertThat(s.toServiceConfig().stopTimeout().toSeconds()).isEqualTo(60);
    assertThatThrownBy(() -> s.withKey("nope", "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service.kind");
  }

  @Test
  void should_derive_the_webapp_dir_when_asked() {
    assertThat(sample(tmp).webappDir()).isEqualTo(tmp.resolve("jrs/apache-tomcat/webapps/jasperserver-pro").toAbsolutePath().normalize());
  }
}
```

`HomeResolverTest.java`:
```java
package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeResolverTest {
  @Test
  void should_prefer_flag_then_env_then_install_dir_when_resolving() {
    Path flag = Path.of("a").toAbsolutePath(), install = Path.of("c").toAbsolutePath();
    Map<String, String> env = Map.of("JRS_HOTFIX_HOME", Path.of("b").toAbsolutePath().toString());
    assertThat(HomeResolver.resolve(Optional.of(flag), env, Optional.of(install)).root()).isEqualTo(flag.normalize());
    assertThat(HomeResolver.resolve(Optional.empty(), env, Optional.of(install)).root()).isEqualTo(Path.of("b").toAbsolutePath().normalize());
    assertThat(HomeResolver.resolve(Optional.empty(), Map.of(), Optional.of(install)).root()).isEqualTo(install.resolve("jrs-hotfix").normalize());
  }
}
```

`DetectionTest.java`:
```java
package com.jaspersoft.jrshotfix.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.platform.FakeProcessRunner;
import com.jaspersoft.jrshotfix.platform.OperatorPrompt;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.Platforms;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetectionTest {
  @TempDir Path tmp;

  Path install() throws Exception {
    Path lib = tmp.resolve("jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("jasperserver-api-10.0.0.jar"), "x");
    Files.createDirectories(tmp.resolve("jrs/apache-tomcat/conf"));
    Files.writeString(tmp.resolve("jrs/apache-tomcat/conf/server.xml"), "<Server><Service><Connector port=\"8081\" protocol=\"HTTP/1.1\"/></Service></Server>");
    return tmp.resolve("jrs");
  }

  Platform platform(Platform.OsFamily os, FakeProcessRunner runner) {
    // PlatformDetectionTest (copied in Task 3) shows the FileOps construction that works here; reuse it.
    return Platforms.forTesting(os, Platform.Arch.X86_64, runner, PlatformDetectionTest.files(), OperatorPrompt.nonInteractive());
  }

  @Test
  void should_pick_catalina_kind_and_the_server_xml_port_when_no_service_is_registered() throws Exception {
    Path install = install();
    Files.createDirectories(install.resolve("apache-tomcat/bin"));
    Files.writeString(install.resolve("apache-tomcat/bin/catalina.sh"), "#!/bin/sh");
    Settings s = Detection.defaults(platform(Platform.OsFamily.LINUX, new FakeProcessRunner()), install).orElseThrow();
    assertThat(s.serviceKind()).isEqualTo(ServiceConfig.Kind.CATALINA);
    assertThat(s.serviceScriptPath()).contains(install.resolve("apache-tomcat/bin/catalina.sh").toAbsolutePath().normalize());
    assertThat(s.baseUrl().toString()).isEqualTo("http://localhost:8081/jasperserver-pro");
    assertThat(s.webappName()).isEqualTo("jasperserver-pro");
  }

  @Test
  void should_pick_the_windows_service_when_sc_query_lists_one() throws Exception {
    Path install = install();
    FakeProcessRunner runner = new FakeProcessRunner().on(Detection.SC_QUERY, FakeProcessRunner.ok("SERVICE_NAME: jasperreportsTomcat", "SERVICE_NAME: jasperreportsPostgreSQL"));
    Settings s = Detection.defaults(platform(Platform.OsFamily.WINDOWS, runner), install).orElseThrow();
    assertThat(s.serviceKind()).isEqualTo(ServiceConfig.Kind.WINDOWS_SERVICE);
    assertThat(s.serviceName()).contains("jasperreportsTomcat");
  }

  @Test
  void should_return_empty_when_the_directory_holds_no_tomcat() {
    assertThat(Detection.defaults(platform(Platform.OsFamily.LINUX, new FakeProcessRunner()), tmp)).isEmpty();
  }
}
```

If `PlatformDetectionTest` has no reusable `files()` helper, add a package-private `static FileOps files()` to it returning what its own tests construct.

- [ ] **Step 2: Run them to see them fail**

Run: `bash scripts/fast.sh test SettingsStoreTest,HomeResolverTest,DetectionTest` — Expected: compile errors, classes missing.

- [ ] **Step 3: Write `Settings`**

```java
package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the tool knows about one installation. Invariants: paths are absolute and normalised;
 * the base URL is used by the wait probe only; there are no credentials anywhere in this record.
 */
public record Settings(
    Path installDir, Path tomcatDir, String webappName, ServiceConfig.Kind serviceKind,
    Optional<String> serviceName, Optional<Path> serviceScriptPath, int stopTimeoutSeconds,
    Optional<Integer> forceStopAfterSeconds, URI baseUrl) {

  public static final List<String> KEYS = List.of("installDir", "tomcatDir", "webappName", "service.kind",
      "service.name", "service.scriptPath", "service.stopTimeoutSeconds", "service.forceStopAfterSeconds", "baseUrl");

  public Settings {
    installDir = installDir.toAbsolutePath().normalize();
    tomcatDir = tomcatDir.toAbsolutePath().normalize();
    Objects.requireNonNull(webappName, "webappName");
    Objects.requireNonNull(serviceKind, "serviceKind");
    Objects.requireNonNull(serviceName, "serviceName");
    serviceScriptPath = serviceScriptPath.map(p -> p.toAbsolutePath().normalize());
    Objects.requireNonNull(forceStopAfterSeconds, "forceStopAfterSeconds");
    Objects.requireNonNull(baseUrl, "baseUrl");
  }

  public Path webappDir() {
    return tomcatDir.resolve("webapps").resolve(webappName);
  }

  public ServiceConfig toServiceConfig() {
    return new ServiceConfig(serviceKind, serviceName, serviceScriptPath, Duration.ofSeconds(stopTimeoutSeconds), forceStopAfterSeconds.map(Duration::ofSeconds));
  }

  public String fingerprintInput() {
    return installDir + "|" + tomcatDir + "|" + webappName;
  }

  public Map<String, String> keys() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("installDir", installDir.toString());
    m.put("tomcatDir", tomcatDir.toString());
    m.put("webappName", webappName);
    m.put("service.kind", serviceKind.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    m.put("service.name", serviceName.orElse(""));
    m.put("service.scriptPath", serviceScriptPath.map(Path::toString).orElse(""));
    m.put("service.stopTimeoutSeconds", Integer.toString(stopTimeoutSeconds));
    m.put("service.forceStopAfterSeconds", forceStopAfterSeconds.map(String::valueOf).orElse(""));
    m.put("baseUrl", baseUrl.toString());
    return m;
  }

  public Settings withKey(String key, String value) {
    Optional<String> text = value.isBlank() ? Optional.empty() : Optional.of(value);
    return switch (key) {
      case "installDir" -> new Settings(Path.of(value), tomcatDir, webappName, serviceKind, serviceName, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "tomcatDir" -> new Settings(installDir, Path.of(value), webappName, serviceKind, serviceName, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "webappName" -> new Settings(installDir, tomcatDir, value, serviceKind, serviceName, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "service.kind" -> new Settings(installDir, tomcatDir, webappName, ServiceConfig.Kind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')), serviceName, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "service.name" -> new Settings(installDir, tomcatDir, webappName, serviceKind, text, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "service.scriptPath" -> new Settings(installDir, tomcatDir, webappName, serviceKind, serviceName, text.map(Path::of), stopTimeoutSeconds, forceStopAfterSeconds, baseUrl);
      case "service.stopTimeoutSeconds" -> new Settings(installDir, tomcatDir, webappName, serviceKind, serviceName, serviceScriptPath, Integer.parseInt(value), forceStopAfterSeconds, baseUrl);
      case "service.forceStopAfterSeconds" -> new Settings(installDir, tomcatDir, webappName, serviceKind, serviceName, serviceScriptPath, stopTimeoutSeconds, text.map(Integer::parseInt), baseUrl);
      case "baseUrl" -> new Settings(installDir, tomcatDir, webappName, serviceKind, serviceName, serviceScriptPath, stopTimeoutSeconds, forceStopAfterSeconds, URI.create(value));
      default -> throw new IllegalArgumentException("unknown setting " + key + "; the keys are " + String.join(", ", KEYS));
    };
  }
}
```

- [ ] **Step 4: Write `SettingsStore`, `HomeResolver`, `Detection`, and copy `JrsVersion`**

`SettingsStore.java`:
```java
package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** settings.json under the home. Invariants: absent file is "no settings"; a save is atomic. */
public final class SettingsStore {
  private SettingsStore() {}

  public static Optional<Settings> load(Home home) {
    Path file = home.settingsFile();
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Json.read(Files.readString(file, StandardCharsets.UTF_8), Settings.class));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  public static void save(Home home, Settings settings) {
    Path file = home.settingsFile();
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling("settings.json.tmp");
      Files.writeString(tmp, Json.writePretty(settings), StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}
```
If `Json.read` cannot map `Optional`/`Path`/`URI`, register `Jdk8Module` in the copied `Json.mapper()` (jrsctl's already does; `Path` and `URI` map as strings by default).

`HomeResolver.java`:
```java
package com.jaspersoft.jrshotfix.home;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** The home: the flag, then JRS_HOTFIX_HOME, then installDir/jrs-hotfix, then ./jrs-hotfix. Invariant: pure. */
public final class HomeResolver {
  public static final String ENV = "JRS_HOTFIX_HOME";

  private HomeResolver() {}

  public static Home resolve(Optional<Path> flag, Map<String, String> env, Optional<Path> installDir) {
    if (flag.isPresent()) {
      return new Home(flag.get());
    }
    String fromEnv = env.get(ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return new Home(Path.of(fromEnv));
    }
    return new Home(installDir.map(d -> d.resolve("jrs-hotfix")).orElseGet(() -> Path.of("jrs-hotfix")));
  }
}
```

`Detection.java`:
```java
package com.jaspersoft.jrshotfix.home;

import com.jaspersoft.jrshotfix.platform.InstallScan;
import com.jaspersoft.jrshotfix.platform.LinuxInit;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ProcessRunner;
import com.jaspersoft.jrshotfix.platform.ServerXml;
import com.jaspersoft.jrshotfix.platform.ServiceConfig;
import com.jaspersoft.jrshotfix.platform.TomcatLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Default settings for an installation, read from disk and the host's service manager. Invariants:
 * read-only; a failing service listing means "no service found", never an error; the operator
 * confirms every value in the wizard.
 */
public final class Detection {
  public static final List<String> SC_QUERY = List.of("sc.exe", "query", "type=", "service", "state=", "all");

  private Detection() {}

  public static InstallScan candidates(Platform platform) {
    return platform.scanInstallDirs();
  }

  public static Optional<Settings> defaults(Platform platform, Path installDir) {
    Optional<TomcatLayout> layout = platform.detectTomcat(installDir);
    if (layout.isEmpty()) {
      return Optional.empty();
    }
    Path tomcat = layout.get().tomcatDir();
    String webapp = layout.get().webappName();
    int port = ServerXml.httpPort(tomcat.resolve("conf").resolve("server.xml")).orElse(8080);
    URI base = URI.create("http://localhost:" + port + "/" + webapp);
    if (platform.os() == Platform.OsFamily.WINDOWS) {
      Optional<String> svc = windowsServices(platform.processes()).stream().filter(n -> lower(n).contains("jasper") && lower(n).contains("tomcat")).findFirst();
      if (svc.isPresent()) {
        return Optional.of(of(installDir, tomcat, webapp, ServiceConfig.Kind.WINDOWS_SERVICE, svc, Optional.empty(), base));
      }
    } else {
      Optional<String> unit = LinuxInit.systemdUnits(platform.processes()).stream().filter(n -> lower(n).contains("jasper") || lower(n).contains("tomcat")).findFirst();
      if (unit.isPresent()) {
        return Optional.of(of(installDir, tomcat, webapp, ServiceConfig.Kind.SYSTEMD, unit, Optional.empty(), base));
      }
    }
    for (String s : List.of("ctlscript.sh", "ctlscript.bat")) {
      if (Files.isRegularFile(installDir.resolve(s))) {
        return Optional.of(of(installDir, tomcat, webapp, ServiceConfig.Kind.CTLSCRIPT, Optional.empty(), Optional.of(installDir.resolve(s)), base));
      }
    }
    for (String s : List.of("catalina.sh", "catalina.bat")) {
      if (Files.isRegularFile(tomcat.resolve("bin").resolve(s))) {
        return Optional.of(of(installDir, tomcat, webapp, ServiceConfig.Kind.CATALINA, Optional.empty(), Optional.of(tomcat.resolve("bin").resolve(s)), base));
      }
    }
    return Optional.of(of(installDir, tomcat, webapp, ServiceConfig.Kind.MANUAL, Optional.empty(), Optional.empty(), base));
  }

  static List<String> windowsServices(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    runner.run(new ProcessRunner.Request(SC_QUERY, Optional.empty(), java.util.Map.of(), Duration.ofSeconds(20)),
        line -> { String t = line.text().strip(); if (t.startsWith("SERVICE_NAME:")) names.add(t.substring("SERVICE_NAME:".length()).strip()); });
    return names;
  }

  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  private static Settings of(Path install, Path tomcat, String webapp, ServiceConfig.Kind kind, Optional<String> name, Optional<Path> script, URI base) {
    return new Settings(install, tomcat, webapp, kind, name, script, 180, Optional.empty(), base);
  }
}
```
`LinuxInit.systemdUnits(ProcessRunner)` is the name to use for the copied class's unit listing; open `platform/LinuxInit.java`, find the method that runs `systemctl list-units` and returns names, make it `public static` with that name. `windowsServices` mirrors `CompanionDatabase.windowsServices` in `$JRSCTL/jrs/src/main/java/com/jaspersoft/jrsctl/jrs/service/CompanionDatabase.java`; copy its exact command list into `SC_QUERY` if it differs from the one above.

```bash
cp ~/jrs-ctl/ops/src/main/java/com/jaspersoft/jrsctl/ops/JrsVersion.java src/main/java/com/jaspersoft/jrshotfix/home/
sed -i 's/^package com\.jaspersoft\.jrsctl\.ops;/package com.jaspersoft.jrshotfix.home;/' src/main/java/com/jaspersoft/jrshotfix/home/JrsVersion.java
```

- [ ] **Step 5: Run the tests, then commit**

Run: `bash scripts/fast.sh test SettingsStoreTest,HomeResolverTest,DetectionTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(home): settings file, home resolution and install detection"
```

---

### Task 6: The ledger

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/state/{HotfixState,Origin,OwnedFile,LedgerEntry,Ledger}.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/state/LedgerTest.java`

**Interfaces:**
- Consumes: `home.Home`, `json.Json`, `platform.Durability`.
- Produces:
  - `enum HotfixState { INSTALLED, ROLLED_BACK }`, `enum Origin { TOOL, RECORDED }`.
  - `record OwnedFile(Path path, String action, Optional<String> beforeSha256, Optional<String> afterSha256)` (`action` is `add`, `replace` or `delete`).
  - `record LedgerEntry(String id, String release, String edition, String build, String title, HotfixState state, Origin origin, String runId, Optional<String> snapshotRef, Instant installedAt, List<OwnedFile> files)` with `recorded()`.
  - `Ledger(Home)`: `recordInstalled(LedgerEntry)` (id must not exist), `updateState(String id, HotfixState)`, `delete(String id) -> boolean`, `find(String id) -> Optional<LedgerEntry>`, `all() -> List<LedgerEntry>` (install order), `installed() -> List<LedgerEntry>` (state INSTALLED, install order), `files(String id) -> List<OwnedFile>`, `filesOwnedBy(Collection<Path>) -> List<Map.Entry<String, OwnedFile>>` (hotfix id with the owned file, for every installed hotfix whose file list contains one of the paths).

- [ ] **Step 1: Write the failing test**

```java
package com.jaspersoft.jrshotfix.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.home.Home;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerTest {
  @TempDir Path tmp;

  LedgerEntry entry(String id, String runId, Path... paths) {
    List<OwnedFile> files = java.util.Arrays.stream(paths).map(p -> new OwnedFile(p, "replace", Optional.of("aaa"), Optional.of("bbb"))).toList();
    return new LedgerEntry(id, "10.0.0", "PRO", "20260730_0457", "hotfix " + id, HotfixState.INSTALLED, Origin.TOOL, runId, Optional.of("snapshots/" + runId), Instant.parse("2026-09-28T10:00:00Z"), files);
  }

  @Test
  void should_persist_entries_in_install_order_when_reopened() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1", tmp.resolve("x.jar")));
    l.recordInstalled(entry("B", "r2", tmp.resolve("x.jar"), tmp.resolve("y.jar")));
    Ledger reopened = new Ledger(new Home(tmp));
    assertThat(reopened.installed()).extracting(LedgerEntry::id).containsExactly("A", "B");
    assertThat(reopened.files("B")).hasSize(2);
    assertThat(reopened.filesOwnedBy(List.of(tmp.resolve("x.jar")))).extracting(e -> e.getKey()).containsExactly("A", "B");
  }

  @Test
  void should_refuse_a_duplicate_id_when_recording() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1"));
    assertThatThrownBy(() -> l.recordInstalled(entry("A", "r9"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("A");
  }

  @Test
  void should_drop_a_rolled_back_entry_from_installed_but_keep_it_in_all() {
    Ledger l = new Ledger(new Home(tmp));
    l.recordInstalled(entry("A", "r1"));
    l.updateState("A", HotfixState.ROLLED_BACK);
    assertThat(l.installed()).isEmpty();
    assertThat(l.all()).extracting(LedgerEntry::state).containsExactly(HotfixState.ROLLED_BACK);
    assertThat(l.delete("A")).isTrue();
    assertThat(l.all()).isEmpty();
  }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `bash scripts/fast.sh test LedgerTest` — Expected: compile errors.

- [ ] **Step 3: Write the types and `Ledger`**

`HotfixState.java`, `Origin.java`: plain enums with a one-line Javadoc each. `OwnedFile.java` and `LedgerEntry.java`: records as in the interface, with `Objects.requireNonNull` on every component and `List.copyOf(files)`; `LedgerEntry.recorded()` returns `origin == Origin.RECORDED`.

`Ledger.java`:
```java
package com.jaspersoft.jrshotfix.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.json.Json;
import com.jaspersoft.jrshotfix.platform.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of hotfixes on this installation, {@code ledger.json} under the home. Invariants:
 * one entry per id; entries stay in install order; every write replaces the file atomically after
 * an fsync; reads parse the whole file, which stays small (hundreds of paths per hotfix).
 */
public final class Ledger {
  private static final TypeReference<List<LedgerEntry>> LIST = new TypeReference<>() {};
  private final Path file;

  public Ledger(Home home) {
    this.file = home.ledgerFile();
  }

  public synchronized void recordInstalled(LedgerEntry entry) {
    List<LedgerEntry> all = new ArrayList<>(all());
    if (all.stream().anyMatch(e -> e.id().equals(entry.id()))) {
      throw new IllegalStateException(entry.id() + " is already in the ledger");
    }
    all.add(entry);
    write(all);
  }

  public synchronized void updateState(String id, HotfixState state) {
    List<LedgerEntry> all = new ArrayList<>();
    for (LedgerEntry e : all()) {
      all.add(e.id().equals(id) ? new LedgerEntry(e.id(), e.release(), e.edition(), e.build(), e.title(), state, e.origin(), e.runId(), e.snapshotRef(), e.installedAt(), e.files()) : e);
    }
    write(all);
  }

  public synchronized boolean delete(String id) {
    List<LedgerEntry> kept = all().stream().filter(e -> !e.id().equals(id)).toList();
    boolean removed = kept.size() != all().size();
    if (removed) {
      write(kept);
    }
    return removed;
  }

  public Optional<LedgerEntry> find(String id) {
    return all().stream().filter(e -> e.id().equals(id)).findFirst();
  }

  public List<LedgerEntry> installed() {
    return all().stream().filter(e -> e.state() == HotfixState.INSTALLED).toList();
  }

  public List<OwnedFile> files(String id) {
    return find(id).map(LedgerEntry::files).orElse(List.of());
  }

  public List<Map.Entry<String, OwnedFile>> filesOwnedBy(Collection<Path> paths) {
    Set<Path> wanted = new HashSet<>();
    for (Path p : paths) {
      wanted.add(p.toAbsolutePath().normalize());
    }
    List<Map.Entry<String, OwnedFile>> out = new ArrayList<>();
    for (LedgerEntry e : installed()) {
      for (OwnedFile f : e.files()) {
        if (wanted.contains(f.path().toAbsolutePath().normalize())) {
          out.add(new AbstractMap.SimpleImmutableEntry<>(e.id(), f));
        }
      }
    }
    return List.copyOf(out);
  }

  public List<LedgerEntry> all() {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      return List.copyOf(Json.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), LIST));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private void write(List<LedgerEntry> all) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling("ledger.json.tmp");
      Files.writeString(tmp, Json.writePretty(all), StandardCharsets.UTF_8);
      Durability.sync(tmp);
      Durability.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}
```

- [ ] **Step 4: Run, then commit**

Run: `bash scripts/fast.sh test LedgerTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(state): JSON ledger of installed and recorded hotfixes"
```

---

### Task 7: Read an official package

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/pkg/{Action,PackageContents,PackagePaths,OfficialPackage,FileTarget}.java`
- Create (copied, adapted): `hotfix/HotfixException.java` from `$JRSCTL/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix/HotfixException.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/pkg/{OfficialPackageTest,PackagePathsTest,Packages}.java`

**Interfaces:**
- Consumes: `platform.FileOps`, `json.Json`.
- Produces:
  - `enum Action { ADD, REPLACE, DELETE }`.
  - `record PackagePaths(Path installDir, Path tomcatDir)`: `resolve(String packagePath) -> Path` (a path starting `webapps/` resolves under `tomcatDir`, anything else under `installDir`), `static requiresServiceStop(String)` (true when the lower-cased path contains `/web-inf/lib/` or `/web-inf/classes/`), `static pathProblems(String) -> List<String>`, `static commonAncestor(List<Path>) -> Optional<Path>`, `commonBase()`. Adapted from `$JRSCTL/.../ops/hotfix/HotfixPaths.java`: drop `from(Config, Platform)`; the rest is copied.
  - `record PackageContents(String id, String release, String edition, String build, String title, String sha256, List<Entry> entries, List<String> notes)` with `record Entry(String path, Action action, Optional<String> sha256, Optional<String> source, String entryName)` where `source` is the inner archive's outer entry name (`jasperserver-pro.zip`, `js-install.zip`) or empty for a file shipped in an unpacked tree and `entryName` is the name inside that archive (or in the outer ZIP). Helpers: `requiresServiceStop()` (true always for a package with any WEB-INF entry; the plan stops regardless), `adds()`, `replaces()`, `deletes()`.
  - `OfficialPackage`: `static boolean looksOfficial(Path zip)`, `static Described describe(Path zip)` (`record Described(String id, String title, String release, String edition, String build)`), `static PackageContents read(Path zip, PackagePaths paths, String webappName, FileOps files)` (streams the ZIP once, hashes every payload entry, decides add/replace against the disk, expands deletions and globs, collects notes and the package SHA-256). Everything is adapted from `$JRSCTL/.../ops/hotfix/OfficialPackage.java`: keep `Shape`, `Kind`, `Header`, `Readme`, `Section`, `expand`, `deletions`, `configNotes`, `isSettingsFile`, `readLines`; replace `convert`/`inner`/`copyEntry` with a hashing pass that does not write; drop `Converted`, `notesFile`, `read(Path bundle)`, `manifest(...)`, `writeManifest`.
  - `record FileTarget(PackageContents.Entry entry, Path target, Optional<String> before)` with `action()`, `packagePath()`, `after()`, `existedBefore()`, `static resolve(PackageContents, PackagePaths, FileOps) -> List<FileTarget>`, `static hashOf(FileOps, Path) -> Optional<String>`. Adapted from `$JRSCTL/.../ops/hotfix/FileTarget.java` without the `replaces` siblings.
  - `HotfixException(String kind, String message, String remediation[, Throwable])` with `kind()`, `remediation()` and constants `PRECHECK`, `UNSUPPORTED`, `LOCK`. Copied; drop the `SIGNATURE` constant.

- [ ] **Step 1: Write the test package builder and the failing tests**

`Packages.java` (test helper; builds a package the way jrsctl's `OfficialHotfixTest` did):
```java
package com.jaspersoft.jrshotfix.pkg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds official-looking packages and fake installations for tests. */
public final class Packages {
  public static final String OUTER_README = """
      JasperReports Server Pro hotfix
      Product: JasperReports Server Pro
      Release version: 10.0.0
      Build version: [20260730_0457]
      Installation
      1. Stop the server
      """;
  public static final String WEBAPP_README = """
      Added files:
      WEB-INF/lib/new-1.0.jar
      Modified files:
      WEB-INF/lib/foo-1.2.3.jar
      Deleted files:
      WEB-INF/lib/bar-0.9.jar
      WEB-INF/lib/never-installed-1.0.jar
      IMPORTANT
      If an earlier hotfix is installed delete
      WEB-INF/lib/foo-1.*.jar
      Additional Notes:
      For PostgreSQL run the SQL in js-install/sql/postgresql.sql
      """;
  public static final String LIB = "WEB-INF/lib/";

  private Packages() {}

  public static byte[] zipBytes(Map<String, String> files, String readme) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
      if (readme != null) {
        zos.putNextEntry(new ZipEntry("readme.txt"));
        zos.write(readme.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      for (Map.Entry<String, String> e : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  public static Path zip(Path file, Map<String, byte[]> entries) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return file;
  }

  /** readme.txt + jasperserver-pro.zip + js-install.zip, standard names. */
  public static Path standard(Path file) throws IOException {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", zipBytes(Map.of(LIB + "foo-1.2.3.jar", "patched foo", LIB + "new-1.0.jar", "brand new"), WEBAPP_README));
    outer.put("js-install.zip", zipBytes(Map.of("buildomatic/lib/tool-2.0.jar", "patched tool"), null));
    return zip(file, outer);
  }

  /** A fake installation: tomcat under installDir, webapp with the jars a package expects. */
  public static PackagePaths install(Path installDir) throws IOException {
    Path tomcat = installDir.resolve("apache-tomcat");
    Path lib = tomcat.resolve("webapps/jasperserver-pro/" + LIB);
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("jasperserver-api-10.0.0.jar"), "api");
    Files.writeString(lib.resolve("foo-1.2.3.jar"), "old foo");
    Files.writeString(lib.resolve("foo-1.0.0.jar"), "older foo left by an earlier hotfix");
    Files.writeString(lib.resolve("bar-0.9.jar"), "bar");
    Files.createDirectories(installDir.resolve("buildomatic/lib"));
    Files.writeString(installDir.resolve("buildomatic/lib/tool-2.0.jar"), "old tool");
    return new PackagePaths(installDir, tomcat);
  }
}
```

`OfficialPackageTest.java`:
```java
package com.jaspersoft.jrshotfix.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrshotfix.hotfix.HotfixException;
import com.jaspersoft.jrshotfix.platform.DefaultFileOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialPackageTest {
  @TempDir Path tmp;
  // use the same FileOps construction PlatformDetectionTest uses
  final com.jaspersoft.jrshotfix.platform.FileOps files = com.jaspersoft.jrshotfix.platform.PlatformDetectionTest.files();

  @Test
  void should_derive_id_add_replace_and_delete_when_read_against_this_installation() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c = OfficialPackage.read(Packages.standard(tmp.resolve("dl/hotfix.zip")), paths, "jasperserver-pro", files);
    assertThat(c.id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
    assertThat(c.release()).isEqualTo("10.0.0");
    assertThat(c.edition()).isEqualTo("PRO");
    assertThat(c.replaces()).extracting(PackageContents.Entry::path).containsExactlyInAnyOrder("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar", "buildomatic/lib/tool-2.0.jar");
    assertThat(c.adds()).extracting(PackageContents.Entry::path).containsExactly("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar");
    assertThat(c.deletes()).extracting(PackageContents.Entry::path).containsExactlyInAnyOrder("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar", "webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar");
    assertThat(c.replaces().get(0).sha256()).isPresent();
    assertThat(c.sha256()).hasSize(64);
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("Additional Notes"));
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("left by an earlier hotfix"));
  }

  @Test
  void should_skip_a_listed_deletion_when_the_file_is_not_installed() throws Exception {
    PackagePaths paths = Packages.install(tmp.resolve("jrs"));
    PackageContents c = OfficialPackage.read(Packages.standard(tmp.resolve("dl/hotfix.zip")), paths, "jasperserver-pro", files);
    assertThat(c.deletes()).extracting(PackageContents.Entry::path).doesNotContain("webapps/jasperserver-pro/WEB-INF/lib/never-installed-1.0.jar");
  }

  @Test
  void should_recognise_names_with_a_version_suffix_and_upper_case_readme_when_shaped() throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("hotfix/README.TXT", Packages.OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("hotfix/jasperserver-pro-10.0.0-hotfix.zip", Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), null));
    Path zip = Packages.zip(tmp.resolve("dl/odd.zip"), outer);
    assertThat(OfficialPackage.looksOfficial(zip)).isTrue();
    assertThat(OfficialPackage.describe(zip).id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
  }

  @Test
  void should_refuse_a_zip_that_is_not_a_package_when_read() throws Exception {
    Path zip = Packages.zip(tmp.resolve("dl/other.zip"), Map.of("a.txt", new byte[] {1}));
    assertThat(OfficialPackage.looksOfficial(zip)).isFalse();
    assertThatThrownBy(() -> OfficialPackage.describe(zip)).isInstanceOf(HotfixException.class).hasMessageContaining("readme.txt");
  }

  @Test
  void should_refuse_a_readme_without_a_build_when_read() throws Exception {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", "Release version: 10.0.0\n".getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", Packages.zipBytes(Map.of(Packages.LIB + "foo-1.2.3.jar", "x"), null));
    assertThatThrownBy(() -> OfficialPackage.describe(Packages.zip(tmp.resolve("dl/nobuild.zip"), outer))).isInstanceOf(HotfixException.class).hasMessageContaining("build");
  }
}
```

`PackagePathsTest.java`: three assertions: `resolve("webapps/jasperserver-pro/x")` is under `tomcatDir`; `resolve("buildomatic/x")` is under `installDir`; `requiresServiceStop("webapps/jasperserver-pro/WEB-INF/lib/a.jar")` is true and `requiresServiceStop("webapps/jasperserver-pro/scripts/a.js")` is false; `pathProblems("../x")` is non-empty.

- [ ] **Step 2: Run to see them fail**

Run: `bash scripts/fast.sh test OfficialPackageTest,PackagePathsTest` — Expected: compile errors.

- [ ] **Step 3: Copy and adapt `HotfixException`, `PackagePaths`, `FileTarget`**

```bash
cd ~/jrs-hotfix; H=~/jrs-ctl/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix
mkdir -p src/main/java/com/jaspersoft/jrshotfix/{pkg,hotfix}
cp $H/HotfixException.java src/main/java/com/jaspersoft/jrshotfix/hotfix/
cp $H/HotfixPaths.java src/main/java/com/jaspersoft/jrshotfix/pkg/PackagePaths.java
cp $H/FileTarget.java src/main/java/com/jaspersoft/jrshotfix/pkg/FileTarget.java
cp $H/OfficialPackage.java src/main/java/com/jaspersoft/jrshotfix/pkg/OfficialPackage.java
sed -i 's/^package com\.jaspersoft\.jrsctl\.ops\.hotfix;/package com.jaspersoft.jrshotfix.hotfix;/' src/main/java/com/jaspersoft/jrshotfix/hotfix/HotfixException.java
sed -i -e 's/^package com\.jaspersoft\.jrsctl\.ops\.hotfix;/package com.jaspersoft.jrshotfix.pkg;/' -e 's/com\.jaspersoft\.jrsctl\.core\.\(platform\|json\)/com.jaspersoft.jrshotfix.\1/g' -e 's/\bHotfixPaths\b/PackagePaths/g' src/main/java/com/jaspersoft/jrshotfix/pkg/*.java
sed -i 's/^import com\.jaspersoft\.jrsctl\.ops\.hotfix\.HotfixException;//' src/main/java/com/jaspersoft/jrshotfix/pkg/*.java
sed -i '1a import com.jaspersoft.jrshotfix.hotfix.HotfixException;' src/main/java/com/jaspersoft/jrshotfix/pkg/{PackagePaths,FileTarget,OfficialPackage}.java
```

Then by hand:
- `HotfixException`: remove the `SIGNATURE` constant; keep `PRECHECK`, `UNSUPPORTED`, `LOCK` (add `LOCK = "lock"` if absent), `kind()`, `remediation()`.
- `PackagePaths`: delete `from(Config, Platform)` and the `Config`/`Platform`/`TomcatLayout` imports. Make the record and every remaining static method `public`.
- `FileTarget`: change the record to `FileTarget(PackageContents.Entry entry, Path target, Optional<String> before)`; delete `Sibling`, `replaces`, `snapshotPaths`' sibling handling; `manifestPath()` becomes `packagePath()` returning `entry.path()`; `after()` returns `entry.sha256()`; `action()` returns `entry.action()`; `resolve(PackageContents c, PackagePaths paths, FileOps files)` maps every entry to `new FileTarget(e, paths.resolve(e.path()), hashOf(files, target))`; `touched()` returns `List.of(target)`; `snapshotPaths()` returns `List.of(target)` when `existedBefore()` else `List.of()`. Make the record public.

- [ ] **Step 4: Write `Action`, `PackageContents`, and adapt `OfficialPackage`**

`Action.java`: `public enum Action { ADD, REPLACE, DELETE }`.

`PackageContents.java`:
```java
package com.jaspersoft.jrshotfix.pkg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one official package would do to this installation, decided at read time. Invariants:
 * paths are package paths ({@code webapps/<name>/...} or installation-relative); an add or replace
 * carries the SHA-256 of the payload; a delete carries none; entries are in package order with
 * deletions last; notes are the readme's manual steps, never executed.
 */
public record PackageContents(String id, String release, String edition, String build, String title, String sha256, List<Entry> entries, List<String> notes) {
  public PackageContents {
    entries = List.copyOf(entries);
    notes = List.copyOf(notes);
  }

  /** One file: where it lands, what happens, and where its bytes are in the package. */
  public record Entry(String path, Action action, Optional<String> sha256, Optional<String> source, String entryName) {
    public Entry {
      Objects.requireNonNull(path);
      Objects.requireNonNull(action);
      Objects.requireNonNull(sha256);
      Objects.requireNonNull(source);
      Objects.requireNonNull(entryName);
    }
  }

  public List<Entry> adds() {
    return entries.stream().filter(e -> e.action() == Action.ADD).toList();
  }

  public List<Entry> replaces() {
    return entries.stream().filter(e -> e.action() == Action.REPLACE).toList();
  }

  public List<Entry> deletes() {
    return entries.stream().filter(e -> e.action() == Action.DELETE).toList();
  }

  public boolean touchesWebInf() {
    return entries.stream().anyMatch(e -> PackagePaths.requiresServiceStop(e.path()));
  }
}
```

`OfficialPackage` adaptation, in the copied file:
1. Delete `Converted`, `notesFile`, `read(Path bundle)`, `manifest(...)`, `writeManifest`, and the `Manifest`/`HotfixBundle`/`Json` imports. Make the class `public final`.
2. `Described` gains `edition` and `build`: `public record Described(String id, String title, String release, String edition, String build)`; `describe` fills them from `Header`. Make `describe`, `looksOfficial` public.
3. Replace `convert` with:
```java
public static PackageContents read(Path source, PackagePaths paths, String webappName, FileOps files) throws IOException {
  Shape shape = shape(source).orElseThrow(() -> new HotfixException(HotfixException.PRECHECK, source + " is not a readable hotfix package", "point jrs-hotfix at the hotfix ZIP as it was downloaded"));
  if (shape.readme().isEmpty()) { throw new HotfixException(HotfixException.PRECHECK, "no readme.txt in " + source, "point jrs-hotfix at the hotfix ZIP as it was downloaded, not at an unpacked copy"); }
  if (!shape.payload()) { throw new HotfixException(HotfixException.PRECHECK, "no jasperserver or js-install archive in " + source, "point jrs-hotfix at the hotfix ZIP as it was downloaded"); }
  String webappPrefix = PackagePaths.WEBAPPS_PREFIX + webappName + "/";
  Header header = null;
  Readme treeReadme = null;
  List<PackageContents.Entry> entries = new ArrayList<>();
  Set<String> added = new LinkedHashSet<>();
  List<Readme> readmes = new ArrayList<>();
  List<String> notes = new ArrayList<>();
  MessageDigest whole = sha256();
  try (InputStream in = new DigestInputStream(Files.newInputStream(source), whole); ZipInputStream outer = new ZipInputStream(in)) {
    ZipEntry entry;
    while ((entry = outer.getNextEntry()) != null) {
      if (entry.isDirectory()) continue;
      String name = entry.getName().replace('\\', '/');
      switch (shape.kind(name)) {
        case README -> header = Header.parse(readLines(outer));
        case WEBAPP_ZIP -> readmes.add(inner(outer, name, webappPrefix, paths, entries, added));
        case INSTALL_ZIP -> readmes.add(inner(outer, name, "", paths, entries, added));
        case WEBAPP_FILE -> {
          String under = shape.underWebapp(name);
          if (under.equalsIgnoreCase(README)) { treeReadme = Readme.parse(webappPrefix, readLines(outer)); }
          else { entries.add(hashEntry(outer, webappPrefix + under, Optional.empty(), entry.getName(), paths, added)); }
        }
        case IGNORE -> {}
      }
    }
    // drain so the whole-file digest covers every byte
    outer.transferTo(OutputStream.nullOutputStream());
  }
  if (header == null) { throw new HotfixException(HotfixException.PRECHECK, "no readme.txt in " + source, "point jrs-hotfix at the hotfix ZIP as it was downloaded, not at an unpacked copy"); }
  if (entries.isEmpty()) { throw new HotfixException(HotfixException.PRECHECK, "no jasperserver or js-install archive in " + source, "point jrs-hotfix at the hotfix ZIP as it was downloaded"); }
  if (treeReadme != null) readmes.add(treeReadme);
  for (Readme r : readmes) { entries.addAll(deletions(r, added, paths, notes)); notes.addAll(r.notes()); }
  notes.addAll(configNotes(entries));
  return new PackageContents(header.id(), header.release(), header.edition(), header.build(), header.title(),
      HexFormat.of().formatHex(whole.digest()), entries, List.copyOf(new LinkedHashSet<>(notes)));
}
```
4. `inner(InputStream source, String outerName, String prefix, PackagePaths paths, List<Entry> entries, Set<String> added)`: as the copied `inner`, but every non-readme entry goes through `hashEntry(zip, prefix + name, Optional.of(outerName), entry.getName(), paths, added)`.
5. `hashEntry(InputStream in, String path, Optional<String> source, String entryName, PackagePaths paths, Set<String> added)`: validate the path with `PackagePaths.pathProblems` (throw the same `HotfixException` the copied `copyEntry` throws), stream the bytes through a `DigestOutputStream` over `OutputStream.nullOutputStream()`, decide `REPLACE` when `Files.isRegularFile(paths.resolve(path))` else `ADD`, add `path` to `added`, return `new PackageContents.Entry(path, action, Optional.of(hex), source, entryName)`.
6. `deletions` returns `List<PackageContents.Entry>` built as `new PackageContents.Entry(path, Action.DELETE, Optional.empty(), Optional.empty(), path)`; `configNotes` filters `Action.REPLACE`.
7. Replace the word `jrsctl` in every message with `jrs-hotfix`.

- [ ] **Step 5: Run the tests, then commit**

Run: `bash scripts/fast.sh test OfficialPackageTest,PackagePathsTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(pkg): read an official package into a change list without a derived bundle"
```

---

### Task 8: Service control steps and the server probe

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/service/ServerProbe.java`
- Create (copied, adapted): `service/ServiceRuntime.java`, `service/ServiceSteps.java`, `service/CompanionDatabase.java` from `$JRSCTL/jrs/src/main/java/com/jaspersoft/jrsctl/jrs/service/`
- Test: `src/test/java/com/jaspersoft/jrshotfix/service/{ServerProbeTest,ServiceStepsTest,FakeServiceController}.java`; copy `$JRSCTL/jrs/src/test/java/com/jaspersoft/jrsctl/jrs/service/CompanionDatabaseTest.java` and `$JRSCTL/jrs/src/test/java/com/jaspersoft/jrsctl/jrs/FakeServiceController.java`

**Interfaces:**
- Consumes: `engine.*`, `event.*`, `platform.ServiceController`, `platform.ServiceConfig`, `platform.Platform`, `platform.ProcessRunner`.
- Produces:
  - `interface ServerProbe { Optional<String> problem(); static ServerProbe http(URI baseUrl); }`: empty means the server answered `GET <baseUrl>/rest_v2/serverInfo` with 200; otherwise the reason (status code or exception message). Connect and read timeouts 5 seconds, `java.net.http.HttpClient`, never follows redirects to another host.
  - `interface ServiceRuntime { ServiceController controller(); default Optional<ServiceController> databaseController(); Duration serviceTimeout(); Clock clock(); Sleeper sleeper(); ServerProbe probe(); }` with the copied `Source` / `Fixed` nested types.
  - `ServiceSteps.stop(ServiceRuntime, phase, id)`, `start(...)`, `waitForServer(...)`, constants `STOP = "stop-service"`, `START = "start-service"`, `WAIT = "wait-for-server"`, `WAIT_CAP = 10 min`; `controllerCheck(ServiceRuntime)`; static `stop(ServiceRuntime, BooleanSupplier)` and `start(...)`.
  - `CompanionDatabase.controller(Platform, ServiceConfig) -> Optional<ServiceController>`.

- [ ] **Step 1: Copy and rename**

```bash
cd ~/jrs-hotfix; J=~/jrs-ctl/jrs/src
mkdir -p src/main/java/com/jaspersoft/jrshotfix/service src/test/java/com/jaspersoft/jrshotfix/service
cp $J/main/java/com/jaspersoft/jrsctl/jrs/service/{ServiceRuntime,ServiceSteps,CompanionDatabase}.java src/main/java/com/jaspersoft/jrshotfix/service/
cp $J/test/java/com/jaspersoft/jrsctl/jrs/service/CompanionDatabaseTest.java $J/test/java/com/jaspersoft/jrsctl/jrs/FakeServiceController.java src/test/java/com/jaspersoft/jrshotfix/service/
find src/main/java/com/jaspersoft/jrshotfix/service src/test/java/com/jaspersoft/jrshotfix/service -name '*.java' | xargs sed -i -e 's/^package com\.jaspersoft\.jrsctl\.jrs\(\.service\)\?;/package com.jaspersoft.jrshotfix.service;/' -e 's/com\.jaspersoft\.jrsctl\.jrs\.service/com.jaspersoft.jrshotfix.service/g' -e 's/com\.jaspersoft\.jrsctl\.core\.\(engine\|event\|platform\)/com.jaspersoft.jrshotfix.\1/g' -e 's/com\.jaspersoft\.jrsctl\.jrs\.FakeServiceController/com.jaspersoft.jrshotfix.service.FakeServiceController/g'
```

- [ ] **Step 2: Write the failing tests**

`ServerProbeTest.java` (uses the JDK's `com.sun.net.httpserver.HttpServer` on an ephemeral port):
```java
package com.jaspersoft.jrshotfix.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ServerProbeTest {
  @Test
  void should_answer_empty_when_server_info_returns_200() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/jasperserver-pro/rest_v2/serverInfo", ex -> { byte[] b = "{\"version\":\"10.0.0\"}".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
    s.start();
    try {
      ServerProbe p = ServerProbe.http(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/jasperserver-pro"));
      assertThat(p.problem()).isEmpty();
    } finally {
      s.stop(0);
    }
  }

  @Test
  void should_name_the_status_when_the_server_answers_503() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/jasperserver-pro/rest_v2/serverInfo", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
    s.start();
    try {
      assertThat(ServerProbe.http(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/jasperserver-pro")).problem()).contains("503");
    } finally {
      s.stop(0);
    }
  }

  @Test
  void should_name_the_connection_failure_when_nothing_listens() {
    assertThat(ServerProbe.http(URI.create("http://127.0.0.1:1/jasperserver-pro")).problem()).isPresent();
  }
}
```

`ServiceStepsTest.java`:
```java
package com.jaspersoft.jrshotfix.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrshotfix.engine.CancellationToken;
import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.engine.Step;
import com.jaspersoft.jrshotfix.engine.StepResult;
import com.jaspersoft.jrshotfix.event.EventSink;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceStepsTest {
  @TempDir Path tmp;

  ServiceRuntime runtime(FakeServiceController c, ServerProbe probe) {
    return new ServiceRuntime() {
      public ServiceController controller() { return c; }
      public Duration serviceTimeout() { return Duration.ofSeconds(5); }
      public Clock clock() { return Clock.systemUTC(); }
      public Sleeper sleeper() { return Sleeper.none(); }
      public ServerProbe probe() { return probe; }
    };
  }

  Context ctx() {
    return new Context("r1", new Home(tmp), new com.jaspersoft.jrshotfix.engine.FakePlatform(tmp), new CancellationToken(), Map.of());
  }

  @Test
  void should_start_the_service_on_compensation_only_when_this_run_stopped_it() throws Exception {
    FakeServiceController c = new FakeServiceController();
    c.start(Duration.ofSeconds(1));
    Step stop = ServiceSteps.stop(runtime(c, Optional::empty), "apply", ServiceSteps.STOP);
    assertThat(stop.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(c.state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(Files.isRegularFile(tmp.resolve("runs/r1/stop-service.stopped"))).isTrue();
    assertThat(stop.compensate(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(c.state()).isEqualTo(ServiceController.State.RUNNING);
  }

  @Test
  void should_wait_until_the_probe_answers_when_the_server_is_starting() {
    AtomicInteger calls = new AtomicInteger();
    ServerProbe probe = () -> calls.incrementAndGet() < 3 ? Optional.of("connection refused") : Optional.empty();
    Step wait = ServiceSteps.waitForServer(runtime(new FakeServiceController(), probe), "apply", ServiceSteps.WAIT);
    assertThat(wait.execute(ctx(), EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(calls.get()).isEqualTo(3);
  }
}
```
`Context`'s constructor and `FakePlatform`'s constructor are whatever the copies in Task 1 offer; adjust the two calls to them. `FakeServiceController` copied from jrsctl starts `STOPPED`; if its initial state differs, set it explicitly.

- [ ] **Step 3: Run to see them fail**

Run: `bash scripts/fast.sh test ServerProbeTest,ServiceStepsTest` — Expected: compile errors.

- [ ] **Step 4: Write `ServerProbe`, adapt `ServiceRuntime` and `ServiceSteps`**

`ServerProbe.java`:
```java
package com.jaspersoft.jrshotfix.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks the server whether it is up: an unauthenticated GET of {@code /rest_v2/serverInfo}.
 * Invariants: no credentials, no redirects, five-second timeouts; the answer is empty for 200 and
 * a one-line reason otherwise; nothing is cached between calls.
 */
@FunctionalInterface
public interface ServerProbe {
  Optional<String> problem();

  static ServerProbe http(URI baseUrl) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    URI target = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/rest_v2/serverInfo");
    return () -> {
      try {
        HttpResponse<Void> r = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
        return r.statusCode() == 200 ? Optional.empty() : Optional.of("HTTP " + r.statusCode() + " from " + target);
      } catch (IOException e) {
        return Optional.of(target + ": " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.of("interrupted");
      }
    };
  }
}
```

`ServiceRuntime`: delete `ServerIdentity refreshIdentity()` and its import; add `ServerProbe probe();`. Keep `Source`, `Fixed`, `fixed(...)`.

`ServiceSteps`:
- Delete the `ConfigException`, `JrsUnreachableException`, `ServerIdentity`, `RestException` imports and the `catch (ConfigException e)` clause in `controllerCheck` (keep the `RuntimeException` clause).
- Replace `CONFIG_REMEDIATION` text with `"check service.kind, service.name and service.scriptPath with `jrs-hotfix settings show`"` and the `config.yaml` mentions in messages with `settings`.
- In `WaitForServer.execute`, replace the `try { ServerIdentity identity = rt.refreshIdentity(); log(... "server answered: " ...); return StepResult.ok(); } catch (...) { problem = e.getMessage(); }` block with:
```java
Optional<String> problem = rt.probe().problem();
if (problem.isEmpty()) {
  log(rt, ctx, out, this, Event.Log.Level.INFO, "server answered");
  return StepResult.ok();
}
```
  and use `problem.get()` where the old code used `problem`.
- Replace `jrsctl` in strings with `jrs-hotfix`.

`CompanionDatabase` needs no change beyond the package rename. `CompanionDatabaseTest` and `FakeServiceController` compile after the rename; run them too.

- [ ] **Step 5: Run and commit**

Run: `bash scripts/fast.sh test ServerProbeTest,ServiceStepsTest,CompanionDatabaseTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(service): stop, start and wait steps with an unauthenticated server probe"
```

---

### Task 9: The apply plan

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/hotfix/{HotfixRuntime,ApplyInput,HotfixPlans}.java`
- Create (copied, adapted) from `$JRSCTL/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix/`: `Failures.java`, `PriorState.java`, `OwnerRestore.java`, `ApplySteps.java`, `HotfixBackupSteps.java` → `BackupSteps.java`, `HotfixApplyPhaseSteps.java` → `ApplyPhaseSteps.java`, `HotfixRecordSteps.java` → `RecordSteps.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/hotfix/{HotfixFixture,ApplyPlanTest,ApplyIdempotencyTest,PreflightTest}.java`; copy `$JRSCTL/ops/src/test/java/com/jaspersoft/jrsctl/ops/hotfix/OwnerRestoreTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `record HotfixRuntime(Home home, Settings settings, Platform platform, Ledger ledger, SnapshotStore snapshots, Clock clock, Sleeper sleeper, ServerProbe probe) implements ServiceRuntime` with `files()`, `paths()` (`new PackagePaths(settings.installDir(), settings.tomcatDir())`), `controller()` (`platform.services(settings.toServiceConfig())`), `databaseController()` (`CompanionDatabase.controller(platform, settings.toServiceConfig())`), `serviceTimeout()`.
  - `record ApplyInput(Path packageFile, PackageContents contents, PackagePaths paths, List<FileTarget> targets)` with `stagingDir(ctx)`, `staged(ctx, target)`, `snapshotPaths()`, `touched()`.
  - `ApplySteps` constants: phases `VERIFY="verify"`, `BACKUP="backup"`, `APPLY="apply"`, `RECORD="record"`; ids `PREFLIGHT="preflight"`, `SNAPSHOT="snapshot"`, `STAGE_FILES="stage-files"`, `ATOMIC_SWAP="atomic-swap"`, `RECORD_INSTALLED="record-installed"`; audit ids `AUDIT_CHECKSUM_CONFIRMED="hotfix.checksum-confirmed"`, `AUDIT_APPLIED="hotfix.applied"`, `AUDIT_ROLLED_BACK="hotfix.rolled-back"`; the `ReadOnly` base class.
  - Steps: `ApplySteps.Preflight`, `BackupSteps.TakeSnapshot`, `ApplyPhaseSteps.StageFiles`, `ApplyPhaseSteps.AtomicSwap`, `RecordSteps.RecordInstalled`.
  - `HotfixPlans(HotfixRuntime rt)`: `planApply(Path packageFile, boolean checksumConfirmed) -> Plan` with plan id `hotfix-apply-<runId>`, operation `hotfix.apply`; `record ApplyArgs(Path packageFile, boolean checksumConfirmed)` and `applyArgsJson`/`applyArgs` for the run store; other methods come in Tasks 10 and 11.
  - `HotfixFixture` (test): `create(Path root) -> HotfixFixture` with fields `home`, `settings`, `platform` (ops-style `FakePlatform` with real files), `ledger`, `snapshots`, `runtime`, `plans`, `paths`; `packageFile()` (the standard package from `pkg.Packages`), `run(Plan, runId) -> RunOutcome` (a `Runner` over a `FileJournal`), `ctx(runId)`, `sha(Path)`, `target(String packagePath)`, `static ids(Plan)`, `static step(Plan, id)`.

- [ ] **Step 1: Copy, rename and delete what is gone**

```bash
cd ~/jrs-hotfix; H=~/jrs-ctl/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix; D=src/main/java/com/jaspersoft/jrshotfix/hotfix
cp $H/Failures.java $H/PriorState.java $H/OwnerRestore.java $H/ApplySteps.java $D/
cp $H/HotfixBackupSteps.java $D/BackupSteps.java; cp $H/HotfixApplyPhaseSteps.java $D/ApplyPhaseSteps.java; cp $H/HotfixRecordSteps.java $D/RecordSteps.java
cp ~/jrs-ctl/ops/src/test/java/com/jaspersoft/jrsctl/ops/hotfix/OwnerRestoreTest.java src/test/java/com/jaspersoft/jrshotfix/hotfix/
find $D src/test/java/com/jaspersoft/jrshotfix/hotfix -name '*.java' | xargs sed -i -e 's/^package com\.jaspersoft\.jrsctl\.ops\.hotfix;/package com.jaspersoft.jrshotfix.hotfix;/' -e 's/com\.jaspersoft\.jrsctl\.core\.\(engine\|event\|platform\|snapshot\|json\)/com.jaspersoft.jrshotfix.\1/g' -e 's/com\.jaspersoft\.jrsctl\.ops\.hotfix\.HotfixException/com.jaspersoft.jrshotfix.hotfix.HotfixException/g' -e 's/\bHotfixBackupSteps\b/BackupSteps/g; s/\bHotfixApplyPhaseSteps\b/ApplyPhaseSteps/g; s/\bHotfixRecordSteps\b/RecordSteps/g' -e 's/\bManifest\.Action\b/Action/g' -e 's/\bHotfixPaths\b/PackagePaths/g' -e 's/\.manifestPath()/.packagePath()/g'
sed -i '1a import com.jaspersoft.jrshotfix.pkg.Action;\nimport com.jaspersoft.jrshotfix.pkg.FileTarget;\nimport com.jaspersoft.jrshotfix.pkg.PackagePaths;\nimport com.jaspersoft.jrshotfix.pkg.PackageContents;' $D/ApplySteps.java $D/BackupSteps.java $D/ApplyPhaseSteps.java $D/RecordSteps.java
```

Then by hand:
- `ApplySteps`: delete `VERIFY_SIGNATURE`, `VALIDATE_MANIFEST`, `RUN_PRECHECKS`, `APPLY_SQL`, `RUN_POSTCHECKS`, `AUDIT_ALLOW_UNSIGNED`; delete `bundle(Context)` and the `HotfixBundle`/`HotfixVerifySteps` references. Add the `Preflight` step (below).
- `ApplyPhaseSteps`: delete the whole `ApplySql` class, the `SqlProgress`/`SqlRunner`/`JdbcSettings` imports. In `StageFiles.execute`, replace the `Files.copy(in.payload(ctx, t), staged, ...)` line: staging now extracts from the package. Replace the per-target loop with a single pass over the package (below). In `AtomicSwap.execute` delete the `for (FileTarget.Sibling s : t.replaces())` loop. In `AtomicSwap.precheck` keep the lock check and the "not staged and not in place" check; replace the remediation text's jrsctl wording.
- `BackupSteps.TakeSnapshot.execute`: delete the `rt.store().recordSnapshot(new SnapshotRecord(...))` call and the `SnapshotRecord` import (the ledger entry's `snapshotRef` records the snapshot in Task 9's `RecordInstalled`).
- `RecordSteps.RecordInstalled`: replace `StateStore store = rt.store()` with `Ledger ledger = rt.ledger()`; `store.hotfix(id)` → `ledger.find(id)`; `h.installedRunId()` → `h.runId()`; `store.deleteHotfix(id)` → `ledger.delete(id)`; `store.recordHotfixInstalled(new HotfixInstalled(...), rows(id, before))` → `ledger.recordInstalled(new LedgerEntry(id, c.release(), c.edition(), c.build(), c.title(), HotfixState.INSTALLED, Origin.TOOL, ctx.runId(), Optional.of(ctx.runId() + "/" + ApplySteps.SNAPSHOT), rt.clock().instant(), rows(before)))` where `c = in.contents()`; `store.audit(actor, AUDIT_APPLIED, text)` → `audit(ctx, out, ApplySteps.AUDIT_APPLIED, text)` implemented as `out.emit(new Event.Log(rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), Event.Log.Level.INFO, "audit " + kind + ": " + text))`; `rows(before)` builds `OwnedFile(t.target(), t.action().name().toLowerCase(Locale.ROOT), before.known() ? before.before(t.target()) : t.before(), t.after())` per target. `compensate`: `ledger.find(id)` state `INSTALLED` and `runId` equal → `ledger.updateState(id, HotfixState.ROLLED_BACK)`.
- `PriorState.of(SnapshotStore, Context, String)` and `OwnerRestore` need only the package rename.

- [ ] **Step 2: Write `HotfixRuntime`, `ApplyInput`, `Preflight`, the staging pass**

`HotfixRuntime.java`:
```java
package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.Sleeper;
import com.jaspersoft.jrshotfix.home.Home;
import com.jaspersoft.jrshotfix.home.Settings;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import com.jaspersoft.jrshotfix.platform.FileOps;
import com.jaspersoft.jrshotfix.platform.Platform;
import com.jaspersoft.jrshotfix.platform.ServiceController;
import com.jaspersoft.jrshotfix.service.CompanionDatabase;
import com.jaspersoft.jrshotfix.service.ServerProbe;
import com.jaspersoft.jrshotfix.service.ServiceRuntime;
import com.jaspersoft.jrshotfix.snapshot.SnapshotStore;
import com.jaspersoft.jrshotfix.state.Ledger;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Everything a hotfix step needs, built once per command. Invariant: no field is null; nothing here does I/O at construction. */
public record HotfixRuntime(Home home, Settings settings, Platform platform, Ledger ledger, SnapshotStore snapshots, Clock clock, Sleeper sleeper, ServerProbe probe) implements ServiceRuntime {
  public HotfixRuntime {
    Objects.requireNonNull(home); Objects.requireNonNull(settings); Objects.requireNonNull(platform); Objects.requireNonNull(ledger);
    Objects.requireNonNull(snapshots); Objects.requireNonNull(clock); Objects.requireNonNull(sleeper); Objects.requireNonNull(probe);
  }

  public FileOps files() {
    return platform.files();
  }

  public PackagePaths paths() {
    return new PackagePaths(settings.installDir(), settings.tomcatDir());
  }

  @Override
  public ServiceController controller() {
    return platform.services(settings.toServiceConfig());
  }

  @Override
  public Optional<ServiceController> databaseController() {
    return CompanionDatabase.controller(platform, settings.toServiceConfig());
  }

  @Override
  public Duration serviceTimeout() {
    return Duration.ofSeconds(settings.stopTimeoutSeconds());
  }
}
```

`ApplyInput.java`:
```java
package com.jaspersoft.jrshotfix.hotfix;

import com.jaspersoft.jrshotfix.engine.Context;
import com.jaspersoft.jrshotfix.pkg.FileTarget;
import com.jaspersoft.jrshotfix.pkg.PackageContents;
import com.jaspersoft.jrshotfix.pkg.PackagePaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The resolved inputs of one apply plan. Invariants: targets are absolute; the package file is the one the operator named. */
public record ApplyInput(Path packageFile, PackageContents contents, PackagePaths paths, List<FileTarget> targets) {
  public ApplyInput {
    packageFile = packageFile.toAbsolutePath().normalize();
    targets = List.copyOf(targets);
  }

  public Path stagingDir(Context ctx) {
    return ctx.home().stagingDir(ctx.runId());
  }

  public Path staged(Context ctx, FileTarget t) {
    return stagingDir(ctx).resolve(t.packagePath().replace('/', java.io.File.separatorChar));
  }

  public List<Path> snapshotPaths() {
    List<Path> out = new ArrayList<>();
    for (FileTarget t : targets) {
      out.addAll(t.snapshotPaths());
    }
    return out;
  }

  public List<Path> touched() {
    return targets.stream().map(FileTarget::target).toList();
  }
}
```
(`java.io.File.separatorChar` is a constant, not file I/O; it stays.)

`ApplySteps.Preflight` (add to `ApplySteps`, a `ReadOnly` step with id `PREFLIGHT`, phase `VERIFY`, title "check this installation"):
```java
@Override
public CheckResult precheck(Context ctx) {
  List<String> problems = new ArrayList<>();
  String installed = JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("");
  if (!installed.equals(in.contents().release())) {
    problems.add("the package is for release " + in.contents().release() + " but " + rt.settings().webappDir() + " is " + (installed.isEmpty() ? "unknown" : installed));
  }
  boolean pro = rt.settings().webappName().endsWith("-pro");
  if (in.contents().edition().equals("PRO") != pro) {
    problems.add("the package is for the " + in.contents().edition() + " edition but the webapp is " + rt.settings().webappName());
  }
  if (rt.ledger().find(in.contents().id()).filter(e -> e.state() == HotfixState.INSTALLED).isPresent()) {
    problems.add(in.contents().id() + " is already installed");
  }
  for (Path dir : List.of(rt.settings().webappDir(), rt.settings().installDir(), rt.home().root())) {
    if (!rt.files().isWritable(dir)) { problems.add(dir + " is not writable by this account"); }
  }
  long payload = 0; // staged bytes ≈ package size; snapshot ≈ size of replaced files
  try { payload = Files.size(in.packageFile()); } catch (IOException e) { problems.add("cannot read " + in.packageFile() + ": " + e.getMessage()); }
  problems.addAll(DiskSpace.problems(rt.files(), List.of(new DiskSpace.Need("staging", rt.home().root(), payload * 2), new DiskSpace.Need("snapshot", rt.home().root(), snapshotBytes()))));
  if (!problems.isEmpty()) {
    return CheckResult.fail(String.join("; ", problems), "fix the listed problems, then run again; nothing was changed");
  }
  return ServiceSteps.controllerCheck(rt);
}
```
`snapshotBytes()` sums `Files.size` over `in.snapshotPaths()` that exist. `execute` returns `StepResult.ok()`.

Staging pass, replacing the body of `StageFiles.execute`:
```java
Map<String, FileTarget> wanted = new HashMap<>();
for (FileTarget t : in.targets()) {
  if (t.action() != Action.DELETE && !FileTarget.hashOf(files, in.staged(ctx, t)).map(h -> h.equals(t.after().orElse(""))).orElse(false)) {
    wanted.put(t.packagePath(), t);
  }
}
if (wanted.isEmpty()) { return StepResult.ok(); }
try {
  PackageStager.stage(in.packageFile(), in.contents(), wanted.keySet(), path -> in.staged(ctx, wanted.get(path)), ctx.cancel());
  for (FileTarget t : wanted.values()) {
    String actual = files.sha256(in.staged(ctx, t));
    if (!actual.equals(t.after().orElse(""))) {
      return Failures.recoverable("staged " + t.packagePath() + " hashes to " + actual + ", expected " + t.after().orElse("?"), "the package changed since it was planned; plan again");
    }
  }
} catch (IOException | UncheckedIOException e) {
  return Failures.recoverable("cannot stage from " + in.packageFile() + ": " + e.getMessage(), "check free space under " + in.stagingDir(ctx));
}
return StepResult.ok();
```
`pkg/PackageStager.java` (new, in `pkg`): `static void stage(Path zip, PackageContents contents, Set<String> paths, Function<String, Path> destination, CancellationToken cancel)` walks the outer ZIP with `ZipInputStream`; for an entry whose name is one of the `source` values in `contents.entries()`, opens a nested `ZipInputStream` over it and, for each inner entry whose `(source, entryName)` matches an entry in `paths`, copies the bytes to `destination.apply(path)` (creating parents, `REPLACE_EXISTING`); for a `WEBAPP_FILE` entry (source empty) matches on `entryName` alone. Reuses the `Shape`/`Kind` logic by calling `OfficialPackage.shape(zip)` (make `shape` and `Shape.kind` package-visible). It calls `cancel.checkpoint()` per entry.

- [ ] **Step 3: Write `HotfixPlans.planApply`**

```java
package com.jaspersoft.jrshotfix.hotfix;

// imports: engine.*, pkg.*, service.ServiceSteps, json.Json, home.JrsVersion, state.*, java.io/nio/util/time

/**
 * Builds every plan and answers every read-only question. Invariants: planning touches nothing
 * outside the home's run log; a plan is rebuilt from its arguments alone, so recovery can compare
 * fingerprints; refusals are {@link HotfixException}s with a remediation.
 */
public final class HotfixPlans {
  public static final String APPLY = "hotfix.apply";
  public static final String ROLLBACK = "hotfix.rollback";
  private final HotfixRuntime rt;

  public HotfixPlans(HotfixRuntime rt) { this.rt = rt; }

  public record ApplyArgs(Path packageFile, boolean checksumConfirmed) {}

  public Plan planApply(ApplyArgs args) {
    Path file = args.packageFile().toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new HotfixException(HotfixException.PRECHECK, file + " does not exist", "point jrs-hotfix at the hotfix ZIP as support published it");
    }
    if (!OfficialPackage.looksOfficial(file)) {
      throw new HotfixException(HotfixException.UNSUPPORTED, file + OfficialPackage.NEITHER_SHAPE_SHORT, "point jrs-hotfix at the hotfix ZIP as support published it");
    }
    PackageContents contents;
    try {
      contents = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files());
    } catch (IOException e) {
      throw new HotfixException(HotfixException.PRECHECK, "cannot read " + file + ": " + e.getMessage(), "check the download; it must be the ZIP as support published it", e);
    }
    List<FileTarget> targets = FileTarget.resolve(contents, rt.paths(), rt.files());
    ApplyInput in = new ApplyInput(file, contents, rt.paths(), targets);
    List<String> warnings = new ArrayList<>();
    warnings.add("package " + file.getFileName() + " (sha256 " + contents.sha256() + "); compare it with the checksum on the support portal"
        + (args.checksumConfirmed() ? " (confirmed)" : ""));
    warnings.addAll(contents.notes());
    warnings.add("the service is stopped for the swap; this node only, other cluster nodes are not touched");

    List<Step> steps = new ArrayList<>();
    steps.add(new ApplySteps.Preflight(rt, in));
    steps.add(new BackupSteps.TakeSnapshot(rt, in));
    steps.add(new ApplyPhaseSteps.StageFiles(rt, in));
    steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    steps.add(new ApplyPhaseSteps.AtomicSwap(rt, in));
    steps.add(ServiceSteps.start(rt, ApplySteps.APPLY, ServiceSteps.START));
    steps.add(ServiceSteps.waitForServer(rt, ApplySteps.APPLY, ServiceSteps.WAIT));
    steps.add(new RecordSteps.RecordInstalled(rt, in));

    Path snapshotDir = rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing mutated");
    rollbackPoints.put(ApplySteps.BACKUP, "snapshot written, server untouched");
    rollbackPoints.put(ApplySteps.APPLY, "restore " + snapshotDir + ", restart service");
    rollbackPoints.put(ApplySteps.RECORD, "restore " + snapshotDir + ", restart service, no ledger entry");
    PlanSummary summary = new PlanSummary(APPLY, contents.id() + " " + contents.title(), in.touched(), List.of(), true, List.of(snapshotDir), rollbackPoints, "official-package", warnings);

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("package", contents.sha256());
    inputs.put("settings", rt.settings().fingerprintInput());
    inputs.put("installed", JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("unknown"));
    for (FileTarget t : targets) {
      inputs.put("target:" + t.packagePath(), t.before().orElse("absent"));
    }
    return new Plan("hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  public static String applyArgsJson(ApplyArgs a) { return Json.write(Map.of("packageFile", a.packageFile().toString(), "checksumConfirmed", a.checksumConfirmed())); }
  public static ApplyArgs applyArgs(String json) { var n = Json.mapper().readTree(json) /* wrap in try, rethrow UncheckedIOException */; return new ApplyArgs(Path.of(n.get("packageFile").asText()), n.get("checksumConfirmed").asBoolean()); }
}
```
`PlanSummary`'s constructor parameter list is the copied record's; match it (jrsctl's is `operation, target, filesTouched, resourcesTouched, serviceRestart, backupLocations, rollbackPoints, strategy, warnings`).

- [ ] **Step 4: Write the fixture and the tests**

`HotfixFixture.java` (test): copy `$JRSCTL/ops/src/test/java/com/jaspersoft/jrsctl/ops/FakePlatform.java` to `src/test/java/com/jaspersoft/jrshotfix/hotfix/FakePlatform.java` (rename its package to `com.jaspersoft.jrshotfix.hotfix` and its core imports as in Step 1; it is the fake with `realFiles`, a scripted `ProcessRunner` and a `FakeServiceController` in `services(...)`; delete anything referencing `Config`). Then:
```java
public final class HotfixFixture {
  public final Path root; public final Home home; public final Settings settings; public final FakePlatform platform;
  public final Ledger ledger; public final SnapshotStore snapshots; public final HotfixRuntime runtime; public final HotfixPlans plans; public final PackagePaths paths;
  public final FileJournal journal; public final List<String> probeAnswers = new ArrayList<>();

  public static HotfixFixture create(Path root) throws IOException {
    PackagePaths paths = Packages.install(root.resolve("jrs"));
    Home home = new Home(root.resolve("jrs/jrs-hotfix"));
    Settings settings = new Settings(paths.installDir(), paths.tomcatDir(), "jasperserver-pro", ServiceConfig.Kind.MANUAL, Optional.empty(), Optional.empty(), 5, Optional.empty(), URI.create("http://localhost:8080/jasperserver-pro"));
    FakePlatform platform = new FakePlatform(Platform.OsFamily.LINUX, home.root()); platform.realFiles = true;
    Ledger ledger = new Ledger(home);
    SnapshotStore snapshots = new SnapshotStore(home, platform.files());
    HotfixFixture f = new HotfixFixture(...); // assign fields; runtime = new HotfixRuntime(home, settings, platform, ledger, snapshots, Clock.systemUTC(), Sleeper.none(), Optional::empty)
    return f;
  }
  public Path packageFile() throws IOException { return Packages.standard(root.resolve("dl/hotfix.zip")); }
  public Context ctx(String runId) { return new Context(runId, home, platform, new CancellationToken(), Map.of()); }
  public RunOutcome run(Plan plan, String runId) { Runner r = new Runner(journal, EventSink.discard(), Clock.systemUTC(), Sleeper.none()); return r.run(plan, ctx(runId), plan.fingerprint(), RunOptions.DEFAULT); }
  public Path target(String packagePath) { return paths.resolve(packagePath); }
  public String sha(Path p) throws IOException { return platform.files().sha256(p); }
  public static List<String> ids(Plan plan) { return plan.steps().stream().map(Step::id).toList(); }
}
```
Check `Runner.run`'s signature against the copy (`run(Plan, Context, PlanFingerprint recomputed, RunOptions)`); `RunOptions.DEFAULT` exists in the copy or use `new RunOptions(false)`.

`ApplyPlanTest.java`:
```java
@Test
void should_build_eight_steps_in_order_when_planning_a_standard_package() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {   // make HotfixFixture AutoCloseable with an empty close
    Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), false));
    assertThat(HotfixFixture.ids(plan)).containsExactly("preflight", "snapshot", "stage-files", "stop-service", "atomic-swap", "start-service", "wait-for-server", "record-installed");
    assertThat(plan.summary().target()).startsWith("JRSHF-10.0.0-20260730-0457");
    assertThat(plan.summary().warnings()).anySatisfy(w -> assertThat(w).contains("sha256"));
    assertThat(plan.fingerprint().inputs()).containsKey("target:webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar");
  }
}

@Test
void should_swap_delete_and_record_when_the_plan_runs() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
    assertThat(f.run(plan, "r1")).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("patched foo");
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar")).exists();
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar")).doesNotExist();
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar")).doesNotExist();
    assertThat(Files.readString(f.target("buildomatic/lib/tool-2.0.jar"))).isEqualTo("patched tool");
    LedgerEntry e = f.ledger.find("JRSHF-10.0.0-20260730-0457").orElseThrow();
    assertThat(e.state()).isEqualTo(HotfixState.INSTALLED);
    assertThat(e.files()).extracting(OwnedFile::action).contains("replace", "add", "delete");
    assertThat(f.home.stagingDir("r1")).doesNotExist();
    assertThat(f.snapshots.find("r1", "snapshot")).isPresent();
  }
}

@Test
void should_refuse_with_precheck_when_the_same_package_is_applied_twice() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
    RunOutcome second = f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r2");
    assertThat(second).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) second).message()).contains("already installed");
  }
}

@Test
void should_restore_the_snapshot_when_the_swap_fails() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    f.platform.failAtomicReplaceFor(f.target("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar")); // add this hook to FakePlatform: atomicReplace throws IOException for that target
    Plan plan = f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true));
    assertThat(f.run(plan, "r1")).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar")).exists();
    assertThat(f.ledger.find("JRSHF-10.0.0-20260730-0457")).isEmpty();
  }
}
```

`ApplyIdempotencyTest.java`: for each mutating step of a planned apply, run `execute` twice against the same context and assert the second call returns `Ok` and the disk is unchanged (hash every target before and after the second call); then `compensate` twice and assert the same. Port the structure of `$JRSCTL/ops/src/test/java/com/jaspersoft/jrsctl/ops/hotfix/HotfixStepIdempotencyTest.java`, dropping the SQL and signature cases.

`PreflightTest.java`: (a) a webapp whose jar says `9.0.0` → `Preflight.precheck` fails mentioning `release`; (b) `FakePlatform` reporting `freeSpaceBytes = 1` → fails mentioning `space`; (c) `isWritable` false for the webapp dir → fails naming the directory; (d) a locked target (`FakePlatform.locked.add(path)`) → `AtomicSwap.precheck` fails with `still locked`.

- [ ] **Step 5: Run everything in the package, then commit**

Run: `bash scripts/fast.sh test ApplyPlanTest,ApplyIdempotencyTest,PreflightTest,OwnerRestoreTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(hotfix): eight-step apply plan staged straight from the package"
```

---

### Task 10: The rollback plan

**Files:**
- Create (copied, adapted) from `$JRSCTL/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix/`: `RollbackSteps.java`, `RollbackChain.java`
- Modify: `src/main/java/com/jaspersoft/jrshotfix/hotfix/HotfixPlans.java` (add `planRollback`)
- Test: `src/test/java/com/jaspersoft/jrshotfix/hotfix/{RollbackPlanTest,RollbackChainTest}.java`

**Interfaces:**
- Consumes: `state.Ledger`, `state.LedgerEntry`, `state.OwnedFile`, `snapshot.SnapshotStore`, `service.ServiceSteps`, `HotfixRuntime`.
- Produces:
  - `RollbackSteps.Input(LedgerEntry hotfix, String phase, String suffix)` with `id()`, `files()` (the entry's owned files), `touched()`, `preRollbackStepId()`; steps `RollbackSteps.RestoreSnapshot`, `RollbackSteps.RecordRolledBack`; constant `RollbackSteps.PHASE = "rollback"`.
  - `RollbackChain.of(Ledger, LedgerEntry target, boolean cascade) -> List<String>` (ids newest first, the target last; throws `HotfixException(PRECHECK)` naming the blockers when not cascading).
  - `HotfixPlans.planRollback(RollbackArgs)` with `record RollbackArgs(String hotfixId, boolean cascade)`, `rollbackArgsJson`, `rollbackArgs`; operation `hotfix.rollback`; plan id `hotfix-rollback-<runId>`.

- [ ] **Step 1: Copy and adapt**

```bash
cd ~/jrs-hotfix; H=~/jrs-ctl/ops/src/main/java/com/jaspersoft/jrsctl/ops/hotfix; D=src/main/java/com/jaspersoft/jrshotfix/hotfix
cp $H/RollbackSteps.java $H/RollbackChain.java $D/
sed -i -e 's/^package com\.jaspersoft\.jrsctl\.ops\.hotfix;/package com.jaspersoft.jrshotfix.hotfix;/' -e 's/com\.jaspersoft\.jrsctl\.core\.\(engine\|event\|platform\|snapshot\)/com.jaspersoft.jrshotfix.\1/g' -e 's/\bHotfixPaths\b/PackagePaths/g' -e 's/\bHotfixInstalled\b/LedgerEntry/g; s/\bHotfixFile\b/OwnedFile/g; s/\bStateStore\b/Ledger/g' -e '/import com\.jaspersoft\.jrsctl\.core\.state/d' -e '/import com\.jaspersoft\.jrsctl\.ops\.db/d' $D/RollbackSteps.java $D/RollbackChain.java
sed -i '1a import com.jaspersoft.jrshotfix.state.Ledger;\nimport com.jaspersoft.jrshotfix.state.LedgerEntry;\nimport com.jaspersoft.jrshotfix.state.OwnedFile;\nimport com.jaspersoft.jrshotfix.state.HotfixState;\nimport com.jaspersoft.jrshotfix.pkg.PackagePaths;\nimport com.jaspersoft.jrshotfix.pkg.FileTarget;' $D/RollbackSteps.java $D/RollbackChain.java
```

`RollbackSteps` by hand:
- `Input` becomes `record Input(LedgerEntry hotfix, String phase, String suffix)`; delete `manifest`, `bundleDir`, `sqlScripts`, `needsServiceStop`, `rollbackScripts`, `forwardScripts`; `files()` returns `hotfix.files()`; `touched()` maps `OwnedFile::path`.
- Delete the `RunSqlRollback` class and every `SqlProgress`/`SqlRunner` reference.
- `RestoreSnapshot`: `snapshot()` becomes `rt.snapshots().find(in.hotfix().runId(), ApplySteps.SNAPSHOT)`; `backups()` uses `in.hotfix().runId()`. Add to `precheck`: when `snapshot()` is empty or its directory is missing, return `CheckResult.fail("snapshot " + rt.home().snapshots().resolve(in.hotfix().runId()) + " is missing", "restore it from a backup of the jrs-hotfix home, or remove the hotfix by hand following the vendor's readme")`. In `execute`, `f.beforeSha256().isEmpty()` means the file was added and is deleted; `f.afterSha256().isEmpty()` (a delete) is restored by the snapshot; keep the post-restore hash check.
- `RecordRolledBack`: `rt.store()` → `rt.ledger()`; `store.hotfix(id)` → `ledger.find(id)`; `store.updateHotfixState` → `ledger.updateState`; the audit call becomes an `Event.Log` line as in Task 9 (`audit(ctx, out, ApplySteps.AUDIT_ROLLED_BACK, ...)`).

`RollbackChain.of` becomes:
```java
static List<String> of(Ledger ledger, LedgerEntry target, boolean cascade) {
  List<LedgerEntry> installed = ledger.installed();
  Map<String, Integer> order = new LinkedHashMap<>();
  for (int i = 0; i < installed.size(); i++) order.put(installed.get(i).id(), i);
  Set<String> selected = new LinkedHashSet<>();
  Deque<String> pending = new ArrayDeque<>(List.of(target.id()));
  List<String> directBlockers = new ArrayList<>();
  while (!pending.isEmpty()) {
    String id = pending.pop();
    if (!selected.add(id)) continue;
    int position = order.getOrDefault(id, -1);
    List<Path> paths = ledger.files(id).stream().map(OwnedFile::path).toList();
    Set<String> blockers = new LinkedHashSet<>();
    for (Map.Entry<String, OwnedFile> owned : ledger.filesOwnedBy(paths)) {
      if (!owned.getKey().equals(id) && order.getOrDefault(owned.getKey(), -1) > position) blockers.add(owned.getKey());
    }
    if (id.equals(target.id())) directBlockers.addAll(blockers);
    pending.addAll(blockers);
  }
  if (!directBlockers.isEmpty() && !cascade) {
    throw new HotfixException(HotfixException.PRECHECK, "rollback of " + target.id() + " is blocked by later hotfixes owning the same files: " + String.join(", ", directBlockers), "roll those back first, or re-run with --cascade");
  }
  List<String> ordered = new ArrayList<>(selected);
  ordered.sort((a, b) -> Integer.compare(order.getOrDefault(b, -1), order.getOrDefault(a, -1)));
  return List.copyOf(ordered);
}
```

- [ ] **Step 2: Add `planRollback` to `HotfixPlans`**

```java
public record RollbackArgs(String hotfixId, boolean cascade) {}

public Plan planRollback(RollbackArgs args) {
  Ledger ledger = rt.ledger();
  LedgerEntry target = ledger.find(args.hotfixId()).orElseThrow(() -> new HotfixException(HotfixException.PRECHECK, "unknown hotfix " + args.hotfixId(), "run jrs-hotfix list"));
  if (target.state() != HotfixState.INSTALLED) {
    throw new HotfixException(HotfixException.PRECHECK, args.hotfixId() + " is not installed (state " + target.state() + ")", "run jrs-hotfix list");
  }
  if (target.recorded()) {
    throw new HotfixException(HotfixException.PRECHECK, args.hotfixId() + " was applied by hand and only recorded; there is no snapshot to put back", "remove the hotfix by hand following the vendor's readme; the entry stays as the inventory of this server");
  }
  List<String> chain = RollbackChain.of(ledger, target, args.cascade());
  List<Step> steps = new ArrayList<>();
  List<Path> touched = new ArrayList<>();
  List<Path> backups = new ArrayList<>();
  List<String> warnings = new ArrayList<>();
  Map<String, String> inputs = new LinkedHashMap<>();
  inputs.put("settings", rt.settings().fingerprintInput());
  for (String id : chain) {
    LedgerEntry hotfix = ledger.find(id).orElseThrow();
    String suffix = chain.size() > 1 ? ":" + id : "";
    String phase = chain.size() > 1 ? RollbackSteps.PHASE + ":" + id : RollbackSteps.PHASE;
    RollbackSteps.Input in = new RollbackSteps.Input(hotfix, phase, suffix);
    steps.add(ServiceSteps.stop(rt, phase, ServiceSteps.STOP + suffix));
    steps.add(new RollbackSteps.RestoreSnapshot(rt, in));
    steps.add(ServiceSteps.start(rt, phase, ServiceSteps.START + suffix));
    steps.add(ServiceSteps.waitForServer(rt, phase, ServiceSteps.WAIT + suffix));
    steps.add(new RollbackSteps.RecordRolledBack(rt, in));
    touched.addAll(in.touched());
    backups.add(rt.home().snapshots().resolve(hotfix.runId()).resolve(ApplySteps.SNAPSHOT));
    inputs.put("hotfix:" + id, hotfix.runId());
    for (OwnedFile f : hotfix.files()) inputs.put("file:" + f.path(), FileTarget.hashOf(rt.files(), f.path()).orElse("absent"));
    if (!id.equals(target.id())) warnings.add(id + " is rolled back first because it owns files " + target.id() + " also owns (--cascade)");
  }
  Map<String, String> rollbackPoints = new LinkedHashMap<>();
  rollbackPoints.put(RollbackSteps.PHASE, "re-apply from the pre-rollback snapshot, restart service");
  PlanSummary summary = new PlanSummary(ROLLBACK, String.join(", ", chain), touched, List.of(), true, backups, rollbackPoints, "snapshot", warnings);
  return new Plan("hotfix-rollback-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
}

public static String rollbackArgsJson(RollbackArgs a) { return Json.write(Map.of("hotfixId", a.hotfixId(), "cascade", a.cascade())); }
public static RollbackArgs rollbackArgs(String json) { /* parse as applyArgs does */ }
```

A rollback of several hotfixes stops and starts the service once per hotfix. That is one outage per hotfix; acceptable for the rare cascade and simpler than merging phases. Note it in `docs/decisions/0001-one-outage-per-cascaded-hotfix.md` (three paragraphs: context, decision, consequence).

- [ ] **Step 3: Write the tests**

`RollbackPlanTest.java`:
```java
@Test
void should_restore_replaced_files_remove_added_and_put_back_deleted_when_rolled_back() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
    Plan rb = f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false));
    assertThat(HotfixFixture.ids(rb)).containsExactly("stop-service", "restore-snapshot", "start-service", "wait-for-server", "record-rolled-back");
    assertThat(f.run(rb, "r2")).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar")).doesNotExist();
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar")).exists();
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.0.0.jar")).exists();
    assertThat(f.ledger.find("JRSHF-10.0.0-20260730-0457").orElseThrow().state()).isEqualTo(HotfixState.ROLLED_BACK);
  }
}

@Test
void should_refuse_when_the_snapshot_directory_was_deleted_by_hand() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
    Trees.deleteRecursively(f.home.snapshots().resolve("r1"));
    RunOutcome out = f.run(f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false)), "r2");
    assertThat(out).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) out).message()).contains("snapshot").contains("r1");
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("patched foo");
  }
}

@Test
void should_refuse_a_recorded_entry_when_rolled_back() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    f.ledger.recordInstalled(new LedgerEntry("JRSHF-10.0.0-20260101-0000", "10.0.0", "PRO", "20260101_0000", "by hand", HotfixState.INSTALLED, Origin.RECORDED, "recorded", Optional.empty(), Instant.now(), List.of()));
    assertThatThrownBy(() -> f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260101-0000", false))).isInstanceOf(HotfixException.class).hasMessageContaining("by hand");
  }
}
```

`RollbackChainTest.java`: build a second package (`Packages.zip` with a readme whose build is `20260830_0100` and a `jasperserver-pro.zip` that replaces `foo-1.2.3.jar` again), apply both, then: (a) `planRollback(first, cascade=false)` throws naming the second id; (b) `planRollback(first, cascade=true)` yields a plan whose phases are `rollback:<second>` then `rollback:<first>` and whose run restores `foo-1.2.3.jar` to `old foo`; (c) `planRollback(second, false)` succeeds alone.

- [ ] **Step 4: Run and commit**

Run: `bash scripts/fast.sh test RollbackPlanTest,RollbackChainTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(hotfix): rollback plan with LIFO ownership check and cascade"
```

---

### Task 11: Record, verify, list, and rebuilding a plan for recovery

**Files:**
- Modify: `src/main/java/com/jaspersoft/jrshotfix/hotfix/HotfixPlans.java`
- Create: `src/main/java/com/jaspersoft/jrshotfix/state/RunPlans.java`
- Test: `src/test/java/com/jaspersoft/jrshotfix/hotfix/{RecordTest,VerifyTest}.java`, `src/test/java/com/jaspersoft/jrshotfix/state/RunPlansTest.java`

**Interfaces:**
- Produces:
  - `HotfixPlans.record(Path packageFile) -> LedgerEntry`: refuses a missing file, a non-package, or an id already in the ledger (`HotfixException(PRECHECK)`); writes an entry with `Origin.RECORDED`, `runId = "recorded"`, no snapshot, and `files` built from `OfficialPackage.read` (every add/replace entry as an `OwnedFile(path, action, hashOnDiskNow, sha256FromPackage)`).
  - `HotfixPlans.verify(Path packageFile) -> VerifyReport` with `record VerifyReport(boolean readable, String id, String title, String release, String edition, boolean applicable, List<String> problems, List<String> adds, List<String> replaces, List<String> deletes, List<String> notes)` and `ok()` (readable and applicable). Applicability: installed release equals the package release; edition matches the webapp name; not already installed.
  - `HotfixPlans.list() -> List<LedgerEntry>` (all entries, install order).
  - `HotfixPlans.rebuild(String operation, String argsJson) -> Plan` (dispatches to `planApply`/`planRollback`).
  - `RunPlans(Home)`: `store(String runId, Plan plan, String operation, String argsJson)` writes `runs/<runId>/plan.json` `{planId, operation, args, fingerprint, stepIds, summary}`; `load(String runId) -> Optional<Stored>` with `record Stored(String planId, String operation, String argsJson, String fingerprint, List<String> stepIds)`.

- [ ] **Step 1: Write the failing tests**

`RecordTest.java`:
```java
@Test
void should_write_a_recorded_entry_with_the_package_files_when_recording() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    LedgerEntry e = f.plans.record(f.packageFile());
    assertThat(e.origin()).isEqualTo(Origin.RECORDED);
    assertThat(e.id()).isEqualTo("JRSHF-10.0.0-20260730-0457");
    assertThat(e.files()).extracting(OwnedFile::path).contains(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"));
    assertThat(f.ledger.installed()).hasSize(1);
    assertThatThrownBy(() -> f.plans.record(f.packageFile())).isInstanceOf(HotfixException.class).hasMessageContaining("already");
  }
}

@Test
void should_block_a_later_rollback_that_overlaps_a_recorded_entry() throws Exception {
  try (HotfixFixture f = HotfixFixture.create(tmp)) {
    // install a package, then record a hand-applied later package owning the same jar
    f.run(f.plans.planApply(new HotfixPlans.ApplyArgs(f.packageFile(), true)), "r1");
    f.plans.record(Packages.later(tmp.resolve("dl/later.zip")));   // add Packages.later: build 20260830_0100, replaces foo-1.2.3.jar
    assertThatThrownBy(() -> f.plans.planRollback(new HotfixPlans.RollbackArgs("JRSHF-10.0.0-20260730-0457", false))).hasMessageContaining("JRSHF-10.0.0-20260830-0100");
  }
}
```

`VerifyTest.java`: (a) the standard package on the standard fixture → `ok()`, `adds` has one, `replaces` two, `deletes` two, `notes` non-empty; (b) after applying it, `verify` reports `applicable=false` with a problem containing `already installed`; (c) a fixture whose jar says `9.0.0` → problem containing `release`; (d) a non-package ZIP → `readable=false` with the shape problem, no exception.

`RunPlansTest.java`: `store` then `load` round-trips `planId`, `operation`, `argsJson`, `fingerprint`, `stepIds`; `load` of an unknown run is empty.

- [ ] **Step 2: Implement `record`, `verify`, `list`, `rebuild`, `RunPlans`**

`record`:
```java
public LedgerEntry record(Path packageFile) {
  Path file = packageFile.toAbsolutePath().normalize();
  if (!Files.isRegularFile(file)) throw new HotfixException(HotfixException.PRECHECK, file + " does not exist", "point jrs-hotfix record at the hotfix ZIP as support published it");
  PackageContents c;
  try { c = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files()); }
  catch (IOException e) { throw new HotfixException(HotfixException.PRECHECK, "cannot read " + file + ": " + e.getMessage(), "check the file, then run again", e); }
  Optional<LedgerEntry> existing = rt.ledger().find(c.id());
  if (existing.isPresent()) {
    LedgerEntry h = existing.get();
    throw new HotfixException(HotfixException.PRECHECK, c.id() + " is already in the ledger (" + h.state() + ", " + (h.recorded() ? "recorded" : "applied by jrs-hotfix in run " + h.runId()) + ")", "run jrs-hotfix list; a rolled-back entry must be removed with `jrs-hotfix runs prune` before the same id is recorded again");
  }
  List<OwnedFile> files = new ArrayList<>();
  for (PackageContents.Entry e : c.entries()) {
    if (e.action() == Action.DELETE) continue;
    Path target = rt.paths().resolve(e.path());
    files.add(new OwnedFile(target, e.action().name().toLowerCase(Locale.ROOT), FileTarget.hashOf(rt.files(), target), e.sha256()));
  }
  LedgerEntry entry = new LedgerEntry(c.id(), c.release(), c.edition(), c.build(), c.title(), HotfixState.INSTALLED, Origin.RECORDED, RECORDED_RUN_ID, Optional.empty(), rt.clock().instant(), files);
  rt.ledger().recordInstalled(entry);
  return entry;
}
public static final String RECORDED_RUN_ID = "recorded";
```
Note the ledger's `delete` is exposed to the operator through `runs prune` in Task 12 only for `ROLLED_BACK` entries whose snapshot is gone.

`verify`:
```java
public VerifyReport verify(Path packageFile) {
  Path file = packageFile.toAbsolutePath().normalize();
  if (!Files.isRegularFile(file) || !OfficialPackage.looksOfficial(file)) {
    return new VerifyReport(false, "", "", "", "", false, List.of(file + OfficialPackage.NEITHER_SHAPE_SHORT), List.of(), List.of(), List.of(), List.of());
  }
  PackageContents c;
  try { c = OfficialPackage.read(file, rt.paths(), rt.settings().webappName(), rt.files()); }
  catch (IOException | HotfixException e) { return new VerifyReport(false, "", "", "", "", false, List.of(e.getMessage()), List.of(), List.of(), List.of(), List.of()); }
  List<String> problems = new ArrayList<>();
  String installed = JrsVersion.ofWebapp(rt.settings().webappDir()).orElse("");
  if (!installed.equals(c.release())) problems.add("the package is for release " + c.release() + " but the webapp is " + (installed.isEmpty() ? "unknown" : installed));
  if (c.edition().equals("PRO") != rt.settings().webappName().endsWith("-pro")) problems.add("the package is for the " + c.edition() + " edition but the webapp is " + rt.settings().webappName());
  if (rt.ledger().find(c.id()).filter(e -> e.state() == HotfixState.INSTALLED).isPresent()) problems.add(c.id() + " is already installed");
  return new VerifyReport(true, c.id(), c.title(), c.release(), c.edition(), problems.isEmpty(), problems,
      c.adds().stream().map(PackageContents.Entry::path).toList(), c.replaces().stream().map(PackageContents.Entry::path).toList(), c.deletes().stream().map(PackageContents.Entry::path).toList(), c.notes());
}
```
The release and edition checks are the same as `Preflight`'s: move them into a package-private `static List<String> applicability(HotfixRuntime, PackageContents)` used by both.

`list()` returns `rt.ledger().all()`. `rebuild(operation, argsJson)` is a `switch` on `APPLY` → `planApply(applyArgs(argsJson))`, `ROLLBACK` → `planRollback(rollbackArgs(argsJson))`, default → `IllegalArgumentException`.

`RunPlans.java`: `store` writes `Json.writePretty(Map.of("planId", plan.planId(), "operation", op, "args", Json.mapper().readTree(argsJson), "fingerprint", plan.fingerprint().value(), "stepIds", ids, "summary", plan.summary()))` to `runs/<runId>/plan.json` through the same temp+sync+move pattern as `SettingsStore`; `load` parses it back (args re-serialised with `Json.write`).

- [ ] **Step 3: Run and commit**

Run: `bash scripts/fast.sh test RecordTest,VerifyTest,RunPlansTest,RollbackChainTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(hotfix): record, verify, list and plan rebuild for recovery"
```

---

### Task 12: The command line front end

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/app/{Main,RootCommand,GlobalOptions,ExitCodes,Bootstrap,RunService,PlanExecutor,ApplyCommand,RollbackCommand,VerifyCommand,ListCommand,RecordCommand,RunsCommand,SettingsCommand}.java`
- Create (copied from `$JRSCTL/app/src/main/java/com/jaspersoft/jrsctl/app/`, package renamed, jrsctl strings replaced): `Prompter, PathCompleter, Pager, Confirm, Terminal, SystemTerminal, TextTable, Ansi, LogFile, RunGuard, RunState, Env, OwnerOnlyFiles, PlanPrinter, ProgressRenderer`
- Test: `src/test/java/com/jaspersoft/jrshotfix/app/{CommandsTest,RunServiceTest,ExitCodesTest}.java`; copy `PagerTest, PathCompleterTest, PrompterTest, TextTableTest, RunGuardTest, LogFileTest, OwnerOnlyFilesTest, AnsiTest` from `$JRSCTL/app/src/test/java/com/jaspersoft/jrsctl/app/`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `Main.main(String[])` → `System.exit(Main.run(args))`; `Main.run(String...)` builds the picocli `CommandLine` over `RootCommand` with `ExitCodes.Handler` and `ExitCodes.ParameterHandler`, registers the `Path` converter with `UserPaths.expand`.
  - `RootCommand` (`@Command(name = "jrs-hotfix", header = "jrs-hotfix - JasperReports Server hotfix tool (Jaspersoft)", mixinStandardHelpOptions = true, versionProvider = ...)`) with subcommands `apply, rollback, verify, list, record, runs, settings` and options `--docs` (prints the embedded page, Task 14) . With no subcommand: at a terminal → `Menu` (Task 13), else usage with exit 1.
  - `GlobalOptions` mixin: `--home <dir>`, `--yes`, `--non-interactive`, `--no-color`, `--no-pager`, `--ascii`.
  - `ExitCodes` constants (spec table) plus `Handler` (maps `HotfixException` by kind: `PRECHECK` → 2, `UNSUPPORTED` → 6, `LOCK` → 9; `LockHeldException` → 9; anything else → 4 with the message) and `fail(PrintWriter err, int code, String message, Optional<String> remediation)`.
  - `Bootstrap.open(GlobalOptions, Map<String,String> env, Clock) -> Bootstrap` with `home()`, `settings() -> Optional<Settings>`, `platform()`, `runtime() -> HotfixRuntime` (throws `HotfixException(PRECHECK, "no settings yet", "run jrs-hotfix settings detect, or start jrs-hotfix at a terminal for the wizard")` when settings are absent), `plans()`, `redactor()`, `interactive()`.
  - `RunService(Bootstrap)`: `lockHolder() -> Optional<RunLock.Holder>`, `pendingRuns() -> List<RunRecord>`, `journal() -> FileJournal`, `plans() -> RunPlans`, `context(runId) -> Context`, `runner(EventSink) -> Runner`, `run(Runner, Plan, Context, String operation, String argsJson) -> RunOutcome` (stores plan.json, takes the `RunLock`, runs), `resume(Runner, Plan, runId, Context)`, `rollback(Runner, Plan, runId, Context)` (both through `Recovery`), `prune(Duration olderThan) -> PruneResult` (run directories of ended runs older than the cut-off; snapshots of those runs unless referenced by an `INSTALLED` ledger entry; `ROLLED_BACK` ledger entries whose snapshot is gone).
  - `PlanExecutor(Bootstrap, RunService, PrintWriter out, PrintWriter err, Ansi)`: `execute(Plan, String operation, String argsJson, boolean showOnly) -> int` (lock check → 9; pending runs → 8; print plan; `--plan` → 0; confirm unless `--yes`; run; render outcome; return `outcome.exitCode()`), `recover(String runId, boolean resume) -> int` (loads `plan.json`, rebuilds through `HotfixPlans.rebuild`, compares fingerprints → 2 on mismatch naming `changedKeys`, then `Recovery`).
  - The seven subcommands, each a `Callable<Integer>` printing through `spec.commandLine().getOut()`.

- [ ] **Step 1: Copy the front-end helpers**

```bash
cd ~/jrs-hotfix; A=~/jrs-ctl/app/src/main/java/com/jaspersoft/jrsctl/app; AT=~/jrs-ctl/app/src/test/java/com/jaspersoft/jrsctl/app
mkdir -p src/main/java/com/jaspersoft/jrshotfix/app src/test/java/com/jaspersoft/jrshotfix/app
for f in Prompter PathCompleter Pager Confirm Terminal SystemTerminal TextTable Ansi LogFile RunGuard RunState Env OwnerOnlyFiles PlanPrinter ProgressRenderer; do cp $A/$f.java src/main/java/com/jaspersoft/jrshotfix/app/; done
for f in PagerTest PathCompleterTest PrompterTest TextTableTest RunGuardTest LogFileTest OwnerOnlyFilesTest AnsiTest; do cp $AT/$f.java src/test/java/com/jaspersoft/jrshotfix/app/; done
find src/main/java/com/jaspersoft/jrshotfix/app src/test/java/com/jaspersoft/jrshotfix/app -name '*.java' | xargs sed -i -e 's/^package com\.jaspersoft\.jrsctl\.app;/package com.jaspersoft.jrshotfix.app;/' -e 's/com\.jaspersoft\.jrsctl\.app/com.jaspersoft.jrshotfix.app/g' -e 's/com\.jaspersoft\.jrsctl\.core\.\(engine\|event\|platform\|redact\|json\)/com.jaspersoft.jrshotfix.\1/g' -e 's/com\.jaspersoft\.jrsctl\.core\.JrsctlHome/com.jaspersoft.jrshotfix.home.Home/g; s/\bJrsctlHome\b/Home/g' -e 's/JRSCTL_/JRS_HOTFIX_/g; s/jrsctl\.log/jrs-hotfix.log/g; s/"jrsctl"/"jrs-hotfix"/g; s/jrsctl /jrs-hotfix /g'
```
Then by hand:
- `Ansi`: it imports `ops.ReportItem` for `status(ReportItem.Status)`; delete that method and the import (nothing in jrs-hotfix prints report items).
- `ProgressRenderer`: delete the `json` constructor flag and every `if (json)` branch (there is no `--json`); the constructor becomes `(Plan, PrintWriter, Ansi, Redactor)`.
- `LogFile`: it configured logback. Replace its body with a class that opens `home.logFile(runId)` lazily through a `PrintWriter` and exposes `static void configure(Home home)` and `static PrintWriter open(String runId)`; `RunService.run/resume/rollback` subscribe a sink that writes every event's `type`, `stepId`, and message as one line to that writer. Keep `LogFileTest` only for what survives (the path derivation); delete the rest.
- `PlanPrinter`: unchanged apart from the rename; it prints phases, steps, `detail()`, rollback points and warnings.
- `Pager`, `Prompter`, `PathCompleter`, `Confirm`, `Terminal`, `SystemTerminal`, `TextTable`, `RunGuard`, `RunState`, `Env`, `OwnerOnlyFiles`: rename only.

- [ ] **Step 2: Write the failing tests**

`CommandsTest.java` drives `Main.run` against a fixture home (`--home`) with `--non-interactive --yes` and captures stdout/stderr through `CommandLine`'s `setOut/setErr` (expose `Main.commandLine(PrintWriter out, PrintWriter err)`):
```java
@Test
void should_apply_verify_list_and_roll_back_through_the_commands() throws Exception {
  Fixture f = Fixture.create(tmp);   // app-level fixture: writes settings.json into the home with kind manual and a baseUrl pointing at a stub HttpServer answering 200
  assertThat(f.run("verify", f.pkg.toString())).isEqualTo(0);
  assertThat(f.out()).contains("JRSHF-10.0.0-20260730-0457").contains("replace");
  assertThat(f.run("apply", f.pkg.toString(), "--plan")).isEqualTo(0);
  assertThat(f.out()).contains("preflight").contains("atomic-swap");
  assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
  assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
  assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("patched foo");
  assertThat(f.run("list")).isEqualTo(0);
  assertThat(f.out()).contains("INSTALLED");
  assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(2);
  assertThat(f.run("rollback", "JRSHF-10.0.0-20260730-0457", "--yes")).isEqualTo(0);
  assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
}

@Test
void should_exit_2_without_touching_anything_when_confirmation_is_missing_non_interactively() throws Exception {
  Fixture f = Fixture.create(tmp);
  assertThat(f.run("apply", f.pkg.toString())).isEqualTo(2);
  assertThat(f.err()).contains("--yes");
}

@Test
void should_exit_9_when_another_process_holds_the_lock() throws Exception {
  Fixture f = Fixture.create(tmp);
  try (RunLock held = new RunLock(f.home, "other", Instant.now())) {
    assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(9);
  }
}

@Test
void should_exit_8_until_a_pending_run_is_recovered() throws Exception {
  Fixture f = Fixture.create(tmp);
  new FileJournal(f.home, Clock.systemUTC()).recordRunStart("stuck", "hotfix.apply", Optional.of("p"), Instant.now());
  new RunPlans(f.home).store("stuck", f.plans().planApply(new HotfixPlans.ApplyArgs(f.pkg, true)), "hotfix.apply", HotfixPlans.applyArgsJson(new HotfixPlans.ApplyArgs(f.pkg, true)));
  assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(8);
  assertThat(f.run("runs", "rollback", "stuck", "--yes")).isEqualTo(0);
  assertThat(f.run("apply", f.pkg.toString(), "--yes")).isEqualTo(0);
}

@Test
void should_show_and_set_settings_when_asked() throws Exception {
  Fixture f = Fixture.create(tmp);
  assertThat(f.run("settings", "set", "service.stopTimeoutSeconds", "42")).isEqualTo(0);
  assertThat(f.run("settings", "show")).isEqualTo(0);
  assertThat(f.out()).contains("service.stopTimeoutSeconds").contains("42");
  assertThat(f.run("settings", "set", "nope", "1")).isEqualTo(1);
}

@Test
void should_exit_6_when_the_zip_is_not_a_package() throws Exception {
  Fixture f = Fixture.create(tmp);
  Path other = Packages.zip(tmp.resolve("dl/other.zip"), Map.of("a.txt", new byte[] {1}));
  assertThat(f.run("apply", other.toString(), "--yes")).isEqualTo(6);
}
```
`RunServiceTest.java`: `prune` removes an ended run older than the cut-off and keeps the snapshot of an installed hotfix; `ExitCodesTest.java`: the handler maps the three `HotfixException` kinds and `LockHeldException` to 2, 6, 9, 9.

- [ ] **Step 3: Implement the front end**

`ExitCodes.java`: constants `SUCCESS=0, USAGE=1, PRECHECK_FAILED=2, FAILED_ROLLED_BACK=3, FAILED_ROLLBACK_INCOMPLETE=4, CANCELLED=5, UNSUPPORTED=6, RECOVERY_REQUIRED=8, LOCK_HELD=9`; copy the `Handler` and `ParameterHandler` classes from `$JRSCTL/app/.../ExitCodes.java` and strip every `JsonOut` branch, the `SecretException`/`ConfigException`/`RestException`/`JrsUnreachableException`/`UnsupportedVersionException` clauses; `HotfixException` maps by `kind()`.

`Bootstrap.open`:
```java
static Bootstrap open(GlobalOptions options, Map<String, String> env, Clock clock) {
  boolean interactive = !options.nonInteractive() && Terminal.present();
  OperatorPrompt prompt = interactive ? new ConsolePrompt() : OperatorPrompt.nonInteractive();
  Platform detected = Platforms.detect(prompt);
  // the home needs the install dir; the install dir is in the settings; so: flag/env first, else look for settings under each candidate's jrs-hotfix/ and take the first that has one
  Home home = HomeResolver.resolve(options.home(), env, Optional.empty());
  Optional<Settings> settings = SettingsStore.load(home);
  if (settings.isEmpty() && options.home().isEmpty() && !env.containsKey(HomeResolver.ENV)) {
    for (Path c : detected.scanInstallDirs().candidates()) {
      Home h = HomeResolver.resolve(Optional.empty(), env, Optional.of(c));
      Optional<Settings> s = SettingsStore.load(h);
      if (s.isPresent()) { home = h; settings = s; break; }
    }
  }
  Files.createDirectories(home.root()); // wrap IOException -> HotfixException(PRECHECK, "cannot create the home " + home.root(), "check permissions or pass --home")
  NativeTempDir.use(home.nativeTemp());
  Platform platform = settings.map(s -> detected.withInstallDir(s.installDir())).orElse(detected);
  return new Bootstrap(home, settings, platform, Redactor.global(), clock, interactive);
}
HotfixRuntime runtime() {
  Settings s = settings.orElseThrow(() -> new HotfixException(HotfixException.PRECHECK, "no settings yet", "run `jrs-hotfix settings detect`, or start jrs-hotfix at a terminal for the wizard"));
  return new HotfixRuntime(home, s, platform, new Ledger(home), new SnapshotStore(home, platform.files(), clock), clock, Sleeper.system(), ServerProbe.http(s.baseUrl()));
}
```

`RunService`: fields `Bootstrap boot`, `FileJournal journal = new FileJournal(boot.home(), boot.clock())`, `RunPlans plans = new RunPlans(boot.home())`.
```java
public RunOutcome run(Runner runner, Plan plan, Context ctx, String operation, String argsJson) {
  plans.store(ctx.runId(), plan, operation, argsJson);
  try (RunLock lock = new RunLock(boot.home(), ctx.runId(), boot.clock().instant())) {
    return runner.run(plan, ctx, plan.fingerprint(), RunOptions.DEFAULT);
  }
}
public RunOutcome resume(Runner runner, Plan plan, String runId, Context ctx) { try (RunLock l = new RunLock(...)) { return new Recovery(journal, runner).resume(plan, runId, ctx, RunOptions.DEFAULT); } }
public RunOutcome rollback(Runner runner, Plan plan, String runId, Context ctx) { try (RunLock l = new RunLock(...)) { return new Recovery(journal, runner).rollback(plan, runId, ctx); } }
public Runner runner(EventSink sink) { return new Runner(journal, new RedactingEventSink(sink, boot.redactor()), boot.clock(), Sleeper.system(), boot.redactor()); }
public Context context(String runId) { return new Context(runId, boot.home(), boot.platform(), new CancellationToken(), Map.of()); }
public String newRunId() { return RunIds.next(boot.clock()); }
```
`prune(Duration olderThan)`: list `runs/*` with `run.json` whose `endedAt` is present and older than `now - olderThan`; delete the run directory; collect the run ids; `snapshots.prune(olderThan, Integer.MAX_VALUE, protectedRunIds)` where `protectedRunIds` = run ids of `INSTALLED` ledger entries; for every `ROLLED_BACK` entry whose `runId` directory under `snapshots/` no longer exists, `ledger.delete(id)`. Return `record PruneResult(List<String> runsRemoved, List<String> snapshotsRemoved, List<String> ledgerEntriesRemoved)`.

`PlanExecutor.execute` follows jrsctl's (read `$JRSCTL/app/.../PlanExecutor.java`) with these changes: no `--json`; no `storePlan/claim` (the plan is stored by `RunService.run`); the run id comes from `runs.newRunId()`; the fingerprint recheck before running is `boot.plans().rebuild(operation, argsJson).fingerprint()` compared with `plan.fingerprint()` → exit 2 naming `changedKeys` on mismatch; the `ProgressRenderer` is subscribed on an `EventBus` together with the log-file sink; the outcome is rendered with `renderer.outcome(runId, outcome)` and the method returns `outcome.exitCode()`. `recover(runId, resume)` loads `RunPlans.load(runId)` (absent → exit 2 "run has no stored plan"), rebuilds, compares the stored fingerprint with the rebuilt one (mismatch → exit 2 listing what changed; for a rollback recovery, file hashes will differ by design after a partial restore, so compare only the `settings` and `hotfix:*` inputs for `hotfix.rollback`), then runs `runs.resume` or `runs.rollback`.

Subcommands (picocli, all `@Mixin GlobalOptions global`, `@Spec CommandSpec spec`):
- `ApplyCommand`: `@Parameters <package.zip>`, `@Option --plan`. Body: `Bootstrap boot = Bootstrap.open(...)`; `boolean confirmed = false; if (!global.yes() && boot.interactive()) { print the sha256 line; confirmed = Confirm.ask(out, "Does this checksum match the support portal? [y/N] "); if (!confirmed) return 2 with "checksum not confirmed"; }`; `Plan plan = boot.plans().planApply(new ApplyArgs(file, confirmed))`; `return new PlanExecutor(...).execute(plan, HotfixPlans.APPLY, HotfixPlans.applyArgsJson(args), plan)`.
- `RollbackCommand`: `<id>`, `--cascade`, `--plan`.
- `VerifyCommand`: prints `id`, `title`, `release/edition`, counts, then each add/replace/delete path under a heading, then the notes; returns 0 when `ok()` else 2, listing `problems` on stderr.
- `ListCommand`: a `TextTable` with columns `id, state, origin, release, build, installed, run`; "no hotfixes recorded" when empty.
- `RecordCommand`: `<package.zip>`; prints the entry's id and file count.
- `RunsCommand` with subcommands `list` (id, operation, started, ended, state, exit), `show <id>` (the run record, then the transitions as a table, then the stored plan's step ids), `resume <id>`, `rollback <id>`, `prune --older-than <days>` (default 30; prints the three removed lists).
- `SettingsCommand` with `show` (the `keys()` table), `set <key> <value>` (exit 1 with the key list on `IllegalArgumentException`), `detect` (runs `Detection.candidates` and, non-interactively, takes the first candidate that yields defaults, saves them and prints `show`; interactively, the wizard from Task 13).
- `RootCommand.call()`: no subcommand and `Terminal.present()` and not `--non-interactive` → `new Menu(...).run()` (Task 13); otherwise print usage and return 1.

- [ ] **Step 4: Run all app tests and commit**

Run: `bash scripts/fast.sh test CommandsTest,RunServiceTest,ExitCodesTest,PagerTest,PathCompleterTest,PrompterTest,TextTableTest,RunGuardTest,OwnerOnlyFilesTest,AnsiTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(app): picocli commands, run service, plan executor and recovery"
```

---

### Task 13: The menu and the settings wizard

**Files:**
- Create: `src/main/java/com/jaspersoft/jrshotfix/app/{Menu,SettingsWizard}.java` (from `$JRSCTL/app/src/main/java/com/jaspersoft/jrsctl/app/GuidedMode.java`, trimmed)
- Test: `src/test/java/com/jaspersoft/jrshotfix/app/{MenuTest,SettingsWizardTest}.java` (from `GuidedModeTest`'s structure: `Prompter.override(Reader)` feeds answers, a recording `runner` captures the argv the menu would run)

**Interfaces:**
- Produces: `Menu(PrintWriter out, List<String> globalArgs, Function<String[], Integer> runner, Supplier<List<String>> pendingRuns, Supplier<Optional<Settings>> settings, Supplier<String> installedRelease)` with `int run()`; `SettingsWizard(PrintWriter out, Platform platform, Home home)` with `Optional<Settings> run()` (detects candidates, lets the operator pick or type an install dir, shows the detected defaults, asks to confirm each service value, saves).

- [ ] **Step 1: Write the failing tests**

`MenuTest.java`:
```java
@Test
void should_print_seven_entries_and_run_apply_when_1_is_chosen() {
  StringWriter sw = new StringWriter(); List<String[]> ran = new ArrayList<>();
  Prompter.override(new StringReader("1\n" + pkg + "\nq\n"));
  Menu m = new Menu(new PrintWriter(sw), List.of(), a -> { ran.add(a); return 0; }, List::of, () -> Optional.of(settings), () -> "10.0.0 PRO");
  assertThat(m.run()).isZero();
  assertThat(sw.toString()).contains("1) Apply a hotfix").contains("7) Settings").contains("q) Quit").contains("release 10.0.0 PRO");
  assertThat(ran).hasSize(1);
  assertThat(ran.get(0)).containsExactly("apply", pkg.toString());
}

@Test
void should_offer_only_recovery_when_a_run_is_pending() {
  Prompter.override(new StringReader("1\n6\n1\nq\n"));
  Menu m = new Menu(out, List.of(), recorder, () -> List.of("stuck"), () -> Optional.of(settings), () -> "10.0.0 PRO");
  m.run();
  assertThat(text()).contains("interrupted").contains("stuck");
  assertThat(ran).hasSize(1);                      // choosing 1 while pending printed a refusal, did not run apply
  assertThat(ran.get(0)).containsExactly("runs", "resume", "stuck");
}

@Test
void should_run_the_wizard_first_when_no_settings_exist() { ... settings supplier returns empty; the runner receives ["settings", "detect"] before the menu prints ... }

@Test
void should_pass_cascade_when_the_operator_says_yes_to_later_hotfixes() { ... "2\nJRSHF-1\ny\nq\n" → ["rollback", "JRSHF-1", "--cascade"] ... }
```
`SettingsWizardTest`: with a `FakeProcessRunner`-backed platform whose `scanInstallDirs()` returns one candidate (the `DetectionTest` fixture), answers `"1\n\n\n\n\n"` (pick candidate 1, accept every default) produce a saved `settings.json` whose `webappName` is `jasperserver-pro`; answering a different service kind at the service prompt (`"1\ncatalina\n<path>\n\n\n"`) saves that kind.

- [ ] **Step 2: Write `Menu` and `SettingsWizard`**

`Menu.run()` prints:
```
jrs-hotfix - JasperReports Server hotfix tool   (release <installedRelease> at <installDir>)

! An earlier job was interrupted and must be finished or undone first:     (only when pending)
    jrs-hotfix runs resume <id>     (or runs rollback <id>)
  Choose 6 below to do this.

What do you want to do?
  1) Apply a hotfix
  2) Roll back a hotfix
  3) Verify a hotfix package
  4) List installed hotfixes
  5) Record a hotfix applied by hand
  6) Recent runs and recovery
  7) Settings
  q) Quit
Each entry runs an ordinary command and prints it first. jrs-hotfix --help lists every command.
Choose [1-7, q]:
```
Entries 1, 2, 3, 5 refuse with "finish or undo the interrupted job first (entry 6)" while a run is pending. Entry 1: `existingFile("Hotfix package (.zip)")` → `execute("apply", path)`. Entry 2: `execute("list")`, then `text("Hotfix id to roll back")`, then `Prompter.yes(out, "Also roll back the hotfixes applied after it, if any? [y/N] ", false)` → `execute("rollback", id[, "--cascade"])`. Entry 3: file → `execute("verify", path)`. Entry 4: `execute("list")`. Entry 5: file → `execute("record", path)`. Entry 6: `execute("runs", "list")`, then when pending: `1) Finish job <id>` → `runs resume <id>`, `2) Undo job <id>` → `runs rollback <id>`. Entry 7: `execute("settings", "show")` then `1) Change a value` (`text` key, `text` value → `settings set k v`), `2) Detect again` (`settings detect`). `execute` prints `Running: jrs-hotfix <args>` first and `Done.` or `Finished with exit code N (jrs-hotfix --docs explains the codes).` after, exactly as `GuidedMode.execute` did. Copy `text`, `path`, `existingFile`, `choice` and the constructor pattern from `GuidedMode`; delete `settings()`, `backup()`, `restore()`, `upgrade()`, `docs()`, `keyAlias`, `detect`, `changeSettings`.

`SettingsWizard.run()`: prints the candidates from `Detection.candidates(platform)` numbered, with `(running)` after those in `running` and the `processScanLimit` sentence when present; `choice` picks one or `Enter a path` accepts a typed directory; `Detection.defaults(platform, chosen)` (empty → "no Tomcat webapp found under <dir>" and ask again); then for each of `service.kind`, `service.name` or `service.scriptPath` (whichever the kind uses), `service.stopTimeoutSeconds`, `baseUrl`: `Prompter.edit(out, "<key> [<default>]: ", default, completions)` (Enter keeps it); `SettingsStore.save(home, settings)`; prints the `keys()` table. `SettingsCommand.detect` calls it when `boot.interactive()`.

- [ ] **Step 3: Run and commit**

Run: `bash scripts/fast.sh test MenuTest,SettingsWizardTest` — Expected: PASS.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "feat(app): seven-entry menu and first-run settings wizard"
```

---

### Task 14: The one documentation page and help examples

**Files:**
- Create: `src/main/resources/docs/README.md`, `src/main/java/com/jaspersoft/jrshotfix/app/EmbeddedDoc.java`
- Modify: `README.md` (repository root: same content plus the build section), `RootCommand.java` (`--docs`), every command's `@Command(footer = ...)` example
- Test: `src/test/java/com/jaspersoft/jrshotfix/app/{HelpExamplesTest,EmbeddedDocTest}.java`

**Interfaces:**
- Produces: `EmbeddedDoc.text() -> String` (the resource), `jrs-hotfix --docs` prints it through `Pager`; `HelpExamplesTest` parses every `Examples:` footer line and asserts the named command and options exist in the picocli model (port of `$JRSCTL/app/src/test/java/com/jaspersoft/jrsctl/app/HelpExamplesTest.java`, which reads the `CommandLine` tree).

- [ ] **Step 1: Write the page**

`src/main/resources/docs/README.md`, about 150 lines, these sections in this order, each a short paragraph or a list, no other headings:
1. `# jrs-hotfix` — one paragraph: what it does, which inputs (the ZIP as support published it), which servers (10.x on Tomcat, Windows and Linux).
2. `## Start` — `jrs-hotfix` at a terminal opens the menu; the first run detects the installation and asks you to confirm the service; scripts use the subcommands below.
3. `## Commands` — the nine lines from spec section 5, one per line, plus `--home`, `--yes`, `--plan`, `--non-interactive`.
4. `## What apply does` — the eight steps as a numbered list, one line each, then the sentence "The service is always stopped for the swap."
5. `## Rollback` — snapshot restore, the LIFO rule, `--cascade`, recorded entries cannot be rolled back.
6. `## Manual steps` — the readme's SQL, properties and re-applied settings are printed and saved to `runs/<id>/notes.txt`, never run.
7. `## If something goes wrong` — the exit codes table (spec section 6), then: a pending run blocks everything until `runs resume` or `runs rollback`; where the snapshot is; what `runs show <id>` prints.
8. `## Files` — the home layout from spec section 3 as a code block.
9. `## Settings` — the keys table from spec section 2.

`EmbeddedDoc.text()` reads the resource as UTF-8. `RootCommand` gets `@Option(names = "--docs")` that prints it through `Pager.print(text, out, !global.noPager())` and returns 0.

- [ ] **Step 2: Add one example per command**

Each `@Command` gets `footer = {"", "Example:", "  jrs-hotfix apply C:\\Downloads\\hotfix_JRSPro10.0.0_20260730.zip --plan"}` and so on: `rollback JRSHF-10.0.0-20260730-0457 --cascade`, `verify <zip>`, `list`, `record <zip>`, `runs show <id>`, `runs prune --older-than 30`, `settings set service.stopTimeoutSeconds 300`. `HelpExamplesTest` walks the tree: for every command whose footer has an `Example:` line, tokenise the next line, strip the leading `jrs-hotfix`, and assert `commandLine.getSubcommands()` resolves the command path and every `--option` exists on it.

- [ ] **Step 3: Run and commit**

Run: `bash scripts/fast.sh test HelpExamplesTest,EmbeddedDocTest` — Expected: PASS. Then `java -jar target/jrs-hotfix.jar --docs | head` after `bash scripts/mvn.sh -q -DskipTests package` prints the page.

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "docs: one embedded documentation page and an example per command"
```

---

### Task 15: Acceptance tests against the shaded jar

**Files:**
- Create: `src/test/java/com/jaspersoft/jrshotfix/acceptance/{Cli,Fixture,StubServer,ScenarioTest,CrashRecoveryTest}.java`
- Modify: `src/main/java/com/jaspersoft/jrshotfix/app/RunService.java` (test pause hook)

**Interfaces:**
- Consumes: the shaded jar at `System.getProperty("jrshotfix.jar")` (Failsafe passes it; run `bash scripts/mvn.sh -q -DskipTests package` first).
- Produces:
  - `Cli(Path home, Map<String,String> env)`: `run(String... args) -> Result(exitCode, stdout, stderr)`, `start(String... args) -> Running` (`pid()`, `alive()`, `collect()`); copied in shape from `$JRSCTL/acceptance/src/test/java/com/jaspersoft/jrsctl/acceptance/Cli.java` (launches `java -jar <jar> --home <home> --non-interactive <args>` with the test's `java.home`, files for stdout and stderr, never `Process.destroyForcibly` for the crash test).
  - `Fixture.create(Path tmp)`: the fake installation from `pkg.Packages.install` plus a `settings.json` (kind `manual`, `baseUrl` at the `StubServer`'s port), the standard package and a later package; `pkg()`, `laterPkg()`, `target(String)`, `home`, `cli`.
  - `StubServer`: `com.sun.net.httpserver.HttpServer` on an ephemeral port answering 200 on `/jasperserver-pro/rest_v2/serverInfo`; `port()`, `close()`.
  - The pause hook: when the environment variable `JRS_HOTFIX_TEST_PAUSE_AT=<stepId>` is set, `RunService.runner(...)` wraps the sink so that on `Event.StepRunning` for that step id it writes `runs/<runId>/paused` and sleeps 120 seconds before returning (the step has not executed yet). The hook is read from `Env.vars()` and is documented in a one-line comment as test-only.

- [ ] **Step 1: Write the scenario tests (the nine scenarios of the spec)**

```java
@Test void s1_should_apply_a_package_to_a_clean_install() {
  Fixture f = Fixture.create(tmp);
  Cli.Result r = f.cli.run("apply", f.pkg().toString(), "--yes");
  assertThat(r.exitCode()).as(r.stderr()).isZero();
  assertThat(r.stdout()).contains("preflight").contains("record-installed").contains("JRSHF-10.0.0-20260730-0457");
  assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("patched foo");
  assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar")).doesNotExist();
  assertThat(Files.readString(f.target("buildomatic/lib/tool-2.0.jar"))).isEqualTo("patched tool");
  assertThat(f.home.resolve("runs")).isDirectoryContaining(p -> Files.exists(p.resolve("notes.txt")));
}
@Test void s2_should_apply_a_second_package_over_the_first_and_remove_leftovers() { apply pkg, apply laterPkg (whose readme's Important glob removes foo-1.*.jar left behind); assert both INSTALLED in `list`; assert foo-1.2.3.jar has the later content }
@Test void s3_should_roll_back_the_latest_hotfix() { apply; rollback --yes exit 0; original bytes back; added file gone; deleted file back; list shows ROLLED_BACK }
@Test void s4_should_refuse_an_older_rollback_unless_cascade() { apply both; rollback first → exit 2 and stderr names the later id; rollback first --cascade --yes → exit 0; both ROLLED_BACK }
@Test void s6_should_record_a_hand_applied_package_and_refuse_to_roll_it_back() { record → exit 0; list shows RECORDED; rollback → exit 2 "by hand" }
@Test void s7_should_preview_and_verify_without_touching_anything() { apply --plan → exit 0, files unchanged, no runs/ directory with a journal; verify → exit 0 listing adds/replaces/deletes; verify of a non-package → exit 6 }
@Test void s8_should_hold_the_lock_and_report_exit_9() { start `apply --yes` with JRS_HOTFIX_TEST_PAUSE_AT=atomic-swap, wait for `paused`, run `list` → 0 (read-only), run `apply --yes` → 9; kill; runs rollback → 0 }
@Test void s9_should_print_the_manual_notes_in_the_plan_and_after_the_run() { apply --plan stdout contains "Additional Notes"; apply --yes stdout contains it again; notes.txt holds it }
@Test void should_exit_8_and_offer_recovery_when_a_run_is_pending() { covered by CrashRecoveryTest }
```
Scenario 5 and the Review Focus item 4 are the crash test below. Each test uses its own `@TempDir` and its own `StubServer` port.

- [ ] **Step 2: Write the crash test**

```java
class CrashRecoveryTest {
  @TempDir Path tmp;

  Cli.Running startAndPauseAt(Fixture f, String step) throws Exception {
    Cli.Running r = f.cli.withEnv("JRS_HOTFIX_TEST_PAUSE_AT", step).start("apply", f.pkg().toString(), "--yes");
    Path marker = f.home.resolve("runs");
    Awaitility-free loop: for up to 60 s, every 200 ms, look for any `runs/*/paused`; fail the test when it never appears (print r.collect()).
    return r;
  }

  void kill(Cli.Running r) throws Exception {  // the OS's own command, as jrsctl's Phase 8 did
    if (System.getProperty("os.name").startsWith("Windows")) new ProcessBuilder("taskkill", "/F", "/PID", Long.toString(r.pid())).start().waitFor();
    else new ProcessBuilder("kill", "-9", Long.toString(r.pid())).start().waitFor();
    for (int i = 0; i < 100 && r.alive(); i++) Thread.sleep(100);
  }

  @Test
  void should_resume_the_swap_and_start_the_service_when_killed_after_the_stop() throws Exception {
    Fixture f = Fixture.create(tmp);
    kill(startAndPauseAt(f, "atomic-swap"));
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
    Cli.Result blocked = f.cli.run("apply", f.pkg().toString(), "--yes");
    assertThat(blocked.exitCode()).isEqualTo(8);
    String runId = f.pendingRunId();   // the one runs/<id>/run.json without endedAt
    Cli.Result resumed = f.cli.run("runs", "resume", runId, "--yes");
    assertThat(resumed.exitCode()).as(resumed.stderr()).isZero();
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("patched foo");
    assertThat(f.cli.run("list").stdout()).contains("INSTALLED");
  }

  @Test
  void should_roll_back_by_starting_the_service_only_when_killed_after_the_stop() throws Exception {
    Fixture f = Fixture.create(tmp);
    kill(startAndPauseAt(f, "atomic-swap"));
    String runId = f.pendingRunId();
    Cli.Result rolled = f.cli.run("runs", "rollback", runId, "--yes");
    assertThat(rolled.exitCode()).as(rolled.stderr()).isZero();
    assertThat(Files.readString(f.target("webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar"))).isEqualTo("old foo");
    assertThat(f.target("webapps/jasperserver-pro/WEB-INF/lib/new-1.0.jar")).doesNotExist();
    assertThat(f.cli.run("list").stdout()).doesNotContain("JRSHF-10.0.0-20260730-0457");
    assertThat(f.cli.run("apply", f.pkg().toString(), "--yes").exitCode()).isZero();
  }

  @Test
  void should_resume_after_a_kill_during_the_record_step() throws Exception {
    Fixture f = Fixture.create(tmp);
    kill(startAndPauseAt(f, "record-installed"));
    assertThat(f.cli.run("runs", "resume", f.pendingRunId(), "--yes").exitCode()).isZero();
    assertThat(f.cli.run("list").stdout()).contains("INSTALLED");
  }
}
```
With kind `manual` the service steps are no-ops that log; the pause hook proves the journal, lock, plan.json and recovery paths, which is what the scenario is about.

- [ ] **Step 3: Run the suite against the jar**

Run: `bash scripts/fast.sh accept` — Expected: every test passes on Windows. Then, from the Linux laptop (memory note `jrsctl-linux-laptop`), `scp` the repository as a tarball, run `bash scripts/mvn.sh verify` there, and read the result before committing: the lock, kill and file-permission behaviour differs between the two OSes.

- [ ] **Step 4: Commit**

```bash
bash scripts/fast.sh fmt && git add -A && git commit -q -m "test(acceptance): the nine scenarios and crash recovery against the shaded jar"
```

---

### Task 16: CI and the portable release

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `dist/bin/jrs-hotfix`, `dist/bin/jrs-hotfix.cmd`, `scripts/package.sh`, `docs/decisions/0002-portable-archive-with-jlink-runtime.md`
- Modify: `README.md` (install section)

**Interfaces:**
- Produces: on every push and pull request, `verify` on `ubuntu-latest` and `windows-latest`; on a `v*` tag, `jrs-hotfix-<version>-linux-x64.tar.gz`, `jrs-hotfix-<version>-windows-x64.zip`, `jrs-hotfix.jar` and `SHA256SUMS` attached to a GitHub release. The archive layout: `jrs-hotfix-<version>/bin/jrs-hotfix[.cmd]`, `lib/jrs-hotfix.jar`, `runtime/` (jlink image), `README.md`, `LICENSE`.

- [ ] **Step 1: Write `scripts/package.sh`**

```bash
#!/usr/bin/env bash
# Builds the portable archive for this OS: shaded jar + jlink runtime + launchers.
# Usage: scripts/package.sh <version> <linux-x64|windows-x64>
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="$1"; PLATFORM="$2"; NAME="jrs-hotfix-$VERSION"; OUT="target/dist/$NAME"
rm -rf target/dist; mkdir -p "$OUT/bin" "$OUT/lib"
cp target/jrs-hotfix.jar "$OUT/lib/"
MODULES=$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 21 target/jrs-hotfix.jar)
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES,jdk.crypto.ec,jdk.charsets" --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$OUT/runtime"
cp dist/bin/jrs-hotfix dist/bin/jrs-hotfix.cmd "$OUT/bin/"; chmod +x "$OUT/bin/jrs-hotfix"
cp README.md LICENSE "$OUT/"
cd target/dist
if [ "$PLATFORM" = "windows-x64" ]; then powershell -NoProfile -Command "Compress-Archive -Path '$NAME' -DestinationPath '$NAME-$PLATFORM.zip'"; else tar -czf "$NAME-$PLATFORM.tar.gz" "$NAME"; fi
sha256sum "$NAME-$PLATFORM".* > "SHA256SUMS-$PLATFORM"
```
`dist/bin/jrs-hotfix`: `#!/usr/bin/env bash`, resolve its own directory, `exec "$DIR/../runtime/bin/java" -jar "$DIR/../lib/jrs-hotfix.jar" "$@"`. `dist/bin/jrs-hotfix.cmd`: `@echo off` then `"%~dp0..\runtime\bin\java.exe" -jar "%~dp0..\lib\jrs-hotfix.jar" %*` and `exit /b %ERRORLEVEL%`.

- [ ] **Step 2: Write the workflows**

`ci.yml`: `on: [push, pull_request]`; job `build` with `strategy.matrix.os: [ubuntu-latest, windows-latest]`; steps: checkout, `actions/setup-java` (temurin 21, cache maven), `./mvnw -B -q spotless:check`, `./mvnw -B verify`; upload `target/surefire-reports` and `target/failsafe-reports` on failure. Pin action versions by SHA as `$JRSCTL/.github/workflows/ci.yml` does (copy the `uses:` lines).

`release.yml`: `on.push.tags: ["v*"]`; job `package` matrix `[ubuntu-latest → linux-x64, windows-latest → windows-x64]`: checkout, setup-java, `./mvnw -B -q org.codehaus.mojo:versions-maven-plugin:2.22.0:set -DnewVersion="${GITHUB_REF_NAME#v}"`, `./mvnw -B -DskipTests package`, `bash scripts/package.sh "${GITHUB_REF_NAME#v}" <platform>`, smoke: unpack the archive and run `bin/jrs-hotfix --version` and `bin/jrs-hotfix --docs | head -3`, upload artifacts; job `release` (needs package): download both, `gh release create "$GITHUB_REF_NAME" target/dist/*.tar.gz target/dist/*.zip target/jrs-hotfix.jar SHA256SUMS --title "$GITHUB_REF_NAME" --notes-file docs/releases/$GITHUB_REF_NAME.md` (the notes file is written by hand before tagging; the first is `docs/releases/v0.1.0.md`).

- [ ] **Step 3: ADR and README**

`docs/decisions/0002-portable-archive-with-jlink-runtime.md`: context (JRS hosts carry Java 11 or 17 from the vendor bundle; jrsctl field tests needed a bundled runtime), decision (jlink Temurin 21 image in the archive, no installer, no signing, checksums only), consequences (~40 MB per archive; a security update to the runtime means a new tag).

`README.md` install section: download the archive for the OS, unpack next to the installation or anywhere, run `bin/jrs-hotfix` (Windows: `bin\jrs-hotfix.cmd`) as the account that owns the installation (Administrator on Windows), verify the checksum with `sha256sum -c SHA256SUMS`.

- [ ] **Step 4: Push and read CI**

```bash
git add -A && git commit -q -m "ci: verify on ubuntu and windows; tag-driven portable release with jlink runtime"
gh repo create robertgorsuch/jrs-hotfix --private --source=. --push   # owner and visibility as the maintainer decides
gh run watch; gh run view --log-failed
```
Both legs must be green before Task 17. The Ubuntu leg is the one the Windows dev box cannot reproduce (memory note `jrsctl-check-ubuntu-ci-leg`).

---

### Task 17: Live check on real servers and the first release

**Files:**
- Create: `docs/releases/v0.1.0.md`, `docs/live-check.md`

**Interfaces:**
- Consumes: the real JRS 10.0.0 on this machine (`C:\Jaspersoft`, port 8081, Windows service; memory note `jrsctl-real-server-on-this-machine`), the Linux laptop install (script kind; memory note `jrsctl-linux-laptop`), and a real cumulative hotfix package from support (the sample in `tx-geocoder`'s field-test material, see memory `jrsctl-vendor-pdfs-location`, or one the maintainer supplies).

- [ ] **Step 1: Write `docs/live-check.md`, then run it on Windows**

The runbook, executed by hand and ticked as it goes:
1. `bin\jrs-hotfix.cmd` at an elevated prompt: the wizard finds `C:\Jaspersoft\...`, proposes the `jasperreportsTomcat` Windows service and port 8081; confirm; `settings show` matches.
2. `verify <package>`: exit 0, adds/replaces/deletes plausible, notes list the SQL step.
3. `apply <package> --plan`: eight steps, checksum line, warnings.
4. Confirm the checksum, `apply <package>`: the service stops (check `sc query`), files swap, service starts, `wait-for-server` passes against `http://localhost:8081/jasperserver-pro/rest_v2/serverInfo`, `list` shows INSTALLED. Log in to the server and open a report.
5. `apply <package>` again: exit 2 "already installed".
6. `rollback <id>`: files back (compare `certutil -hashfile` on two jars against the snapshot manifest), service up, `list` shows ROLLED_BACK.
7. `apply <package>` once more, then Ctrl-C during `atomic-swap` (the swap is quick; if it cannot be caught, use `JRS_HOTFIX_TEST_PAUSE_AT=atomic-swap` in the environment): exit 5, then `runs resume <id>`: exit 0.
8. `runs prune --older-than 0`: removes only ended, unreferenced runs.
Record every exit code and anything surprising in the file.

- [ ] **Step 2: Run it on the Linux laptop**

Copy the tarball with `scp`, unpack under the install owner's home, repeat steps 1 to 8 with the script kind (`ctlscript` or `catalina`, whichever the wizard detects). Note any difference in `docs/live-check.md`.

- [ ] **Step 3: Fix what the live check found, then release**

Each finding is its own commit with a test where a test can catch it. Then:
```bash
printf '# v0.1.0\n\nFirst release of jrs-hotfix, split out of jrsctl 2.3.0.\n\n- Official Jaspersoft cumulative packages only.\n- Apply, verify, record, list, roll back with cascade, resume and roll back interrupted runs.\n- Windows service, systemd, vendor scripts, manual.\n- Live-checked on JRS 10.0.0 on Windows and Linux (docs/live-check.md).\n' > docs/releases/v0.1.0.md
git add -A && git commit -q -m "docs(release): notes for 0.1.0 and the live-check record"
git tag v0.1.0 && git push --tags && gh run watch
```
Expected: the release workflow attaches two archives, the jar and `SHA256SUMS`. Download the Windows archive, verify the checksum, run `bin\jrs-hotfix.cmd --version`.

---

## Self-review notes

- **Spec coverage.** Section 1 (project shape): Task 0, 16. Section 2 (settings, home, detection): Task 5, 12, 13. Section 3 (ledger, journal, snapshots, lock, staging): Tasks 2, 4, 6, 9, 12 (`prune`). Section 4.1 apply: Task 9; 4.2 rollback: Task 10; 4.3 record, 4.4 verify, 4.5 recovery: Tasks 11, 12. Section 5 commands, menu, docs: Tasks 12, 13, 14. Section 6 exit codes: Task 12. Section 7 testing: Tasks 15, 16, 17. Section 8 copy list: Tasks 1, 3, 4, 8, 9, 10, 12.
- **Review Focus pins.** Item 1 → `OfficialPackageTest.should_recognise_names_with_a_version_suffix_and_upper_case_readme_when_shaped` (Task 7). Item 2 → `ApplyPlanTest.should_refuse_with_precheck_when_the_same_package_is_applied_twice` (Task 9). Item 3 → `OfficialPackageTest.should_skip_a_listed_deletion_when_the_file_is_not_installed` (Task 7). Item 4 → `CrashRecoveryTest`'s two `killed_after_the_stop` tests (Task 15). Item 5 → `RollbackPlanTest.should_refuse_when_the_snapshot_directory_was_deleted_by_hand` (Task 10).
- **Type consistency.** `HotfixPlans.ApplyArgs(Path, boolean)` and `RollbackArgs(String, boolean)` are used identically in Tasks 9, 10, 11, 12, 15. `LedgerEntry.runId()` (not `installedRunId`) everywhere. `PackageContents.Entry.path()` is a package path; `FileTarget.target()` is absolute; `OwnedFile.path()` is absolute. `ServiceRuntime.probe()` replaces `refreshIdentity()` in Tasks 8 and 9. `Home` has no `stateDb()`, `keys()`, `secretsFile()` or config-file methods, and Task 1's `sed` renames `JrsctlHome` in the copied engine to it.
- **Known judgement calls left to the implementer.** The exact constructor of the copied `Context`, `FakePlatform`, `DefaultFileOps` and `PlanSummary` records is whatever jrsctl's file says on the day of the copy; the plan names the jrsctl file to read in each case.
