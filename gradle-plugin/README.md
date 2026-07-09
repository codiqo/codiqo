# Codiqo Gradle Plugin (feasibility PoC)

Runs the Codiqo analysis engine against a **Gradle** build and dumps the analysis submission as
YAML locally (no backend). It mirrors the Maven plugin's `dump-commit-analysis` goal and reuses
the shared engine (`codiqo-core`, `codiqo-lang-java`) and submission pipeline (`codiqo-submit`)
through the build-tool-neutral `JvmProjectSpec` seam.

## Status

- **Compiles** against `dev.gradleplugins:gradle-api:8.10.2` and passes the reactor build
  (`mvn install`).
- **Runtime-validated on spring-kafka** (Gradle 9.4.1, JDK 25, multi-module): the engine runs
  in-process, imports the project via JDT, resolves the dependency registry, and writes a
  ~590 KB submission YAML with all sections — see operational requirements below.

## What it does

`codiqoDumpAnalysis` analyzes the current checkout at `HEAD` (or `-Pcodiqo.commitId=<sha>`):

1. Iterates every `java` subproject, mapping each `SourceSet`/`Configuration`/jacoco exec into a
   `GradleProjectWrapper` (a `JvmProjectSpec`) and building a ClassGraph over the classpath.
2. Runs the shared engine: `DefaultLanguageProcessors` (JDT/PMD/SpotBugs/JaCoCo) +
   `JGitDeltaAnalyzer`, then the `codiqo-submit` populator pipeline.
3. Writes `build/codiqo/codiqo-submission-<sha>.yaml`.

## Run against a project (e.g. spring-kafka) without editing its build

```bash
# 1. publish codiqo artifacts locally
cd <codiqo repo> && mvn install -DskipTests

# 2. dump a batch of commits (checks out each, restores your branch at the end)
MODE=full /path/to/gradle-plugin/run-spring-kafka.sh    # tests + real coverage (slow)
MODE=fast /path/to/gradle-plugin/run-spring-kafka.sh    # compile only, no coverage (minutes)
```

Or a single commit manually:

```bash
cd <spring-kafka checkout>
git checkout <sha>
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew \
    --init-script <codiqo repo>/gradle-plugin/codiqo.init.gradle \
    :spring-kafka:test :spring-kafka:jacocoTestReport codiqoDumpAnalysis \
    -Pcodiqo.commitId=<sha> -Pcodiqo.outputDirectory=<out dir>
```

Config overrides (project properties, or a `codiqo { … }` extension block): `-Pcodiqo.commitId`,
`-Pcodiqo.outputDirectory`, `-Pcodiqo.javaHome`, `-Pcodiqo.ignoreCoverage`.

Success = a `codiqo-submission-<sha>.yaml` with changed-line coverage (MODE=full), PMD + SpotBugs
diagnostics, CPD duplication and the resolved dependency registry — comparable to the Maven dump.

## Operational requirements (from the spring-kafka run)

- **Heap.** The engine runs in-process in the Gradle daemon; bump it to
  `org.gradle.jvmargs=-Xmx6g` (spring-kafka defaults to 1536M, which OOMs). The batch script sets
  this per checkout.
- **jacoco.** spring-kafka applies no jacoco plugin — the init script force-applies it to every
  `java` subproject. Coverage only populates in MODE=full (tests must actually run).
- **JDK 25** matches spring-kafka's toolchain; run `./gradlew` with `JAVA_HOME` set to it.
- **Stale classes on re-checkout.** `git checkout` of a commit already built leaves same-content
  classes with older mtimes than the freshly-touched sources, tripping the engine's staleness
  guard. The batch script deletes `build/classes` per checkout to force a fresh compile.
- A `Resolution of ':spring-kafka:annotationProcessor' … without an exclusive lock` line in the
  log comes from **JDT-LS's own** Gradle import, not this plugin — non-fatal; the dump completes.

## Design notes

- **No `--no-parallel` needed.** The Gradle model (subproject classpaths, dependencies) is
  snapshotted at configuration time (`taskGraph.whenReady`, see `GradleModelCollector`), so the
  task performs no cross-project configuration resolution during execution — it runs under
  Gradle's parallel execution.
- **Engine runs in-process, not in a worker.** A process-isolated worker was considered for
  Guava/classloader isolation, but that conflict never materialized (Gradle 9 isolates the plugin
  classloader; the spring-kafka run was clean), and Guava is used in 180+ sites across codiqo so
  dropping it is neither feasible nor necessary. In-process is simpler.

## codiqoIndexCommits

Walks git history over a window and writes the analyzable commit SHAs to a file (Gradle port of
the Maven `index-commits` goal, minus the backend round-trip — shares `CommitIndexer` in
`codiqo-submit`). Squash/rebase duplicates are dropped by patch-id; merge/revert commits are
flagged. Feeds the per-commit dump loop.

```bash
./gradlew --init-script <codiqo repo>/gradle-plugin/codiqo.init.gradle codiqoIndexCommits \
    -Pcodiqo.commitWindow=P3M -Pcodiqo.indexOutputFile=<out>/indexed-commits.txt
# overrides: -Pcodiqo.indexRef (default HEAD), -Pcodiqo.branch, -Pcodiqo.include/excludeAuthorEmails
```

## Next steps

- **Full historical fan-out** (per-commit clone/checkout/build via a custom Gradle Tooling-API
  model, so no manual git-checkout loop) and **backend submission / LLM scoring** are deferred.
