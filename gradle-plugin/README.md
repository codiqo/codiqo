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
    --init-script <codiqo repo>/gradle-plugin/codiqo.init.gradle --continue \
    :spring-kafka-test:test codiqoDumpAnalysis \
    -Pcodiqo.commitId=<sha> -Pcodiqo.outputDirectory=<out dir>
```

`--continue` is required, not optional: codiqo caps every `Test` task with a timeout, and a task that
hits it still fails even though ordinary test failures are ignored — without `--continue` the build
stops before `codiqoDumpAnalysis` and you get no submission.

No `jacocoTestReport` is needed. Codiqo owns coverage: each `Test` task writes
`build/jacoco/codiqo-<task>.exec` and the dump merges those parts into the module's `codiqo.exec`.

Pick the test task(s) of the module that **owns the changed files** — changed-line coverage is
attributed per module, so running an unrelated module's tests yields a correct but useless
`0 files affected matched`.

Config overrides (project properties, or a `codiqo { … }` extension block): `-Pcodiqo.commitId`,
`-Pcodiqo.outputDirectory`, `-Pcodiqo.javaHome`, `-Pcodiqo.ignoreCoverage`, `-Pcodiqo.ignoreCpd`,
`-Pcodiqo.ignoreDiagnostics`, `-Pcodiqo.ignoreComplexity`, `-Pcodiqo.testTimeoutMinutes`,
`-Pcodiqo.perTestTimeoutMinutes`, `-Pcodiqo.importTimeoutMinutes`, `-Pcodiqo.lspQueryTimeoutSeconds`,
`-Pcodiqo.jdtlsVersion`, `-Pcodiqo.jdtUseSharedIndex`, `-Pcodiqo.failOnUninstrumentedModule`,
`-Pcodiqo.analysisMaxHeap`, `-Pcodiqo.skipOnBuildFailure`, `-Pcodiqo.scoreOnBuildFailure`,
`-Pcodiqo.firstParentOnly`, `-Pcodiqo.excludeRevertedCommits`, `-Pcodiqo.includeBranches`,
`-Pcodiqo.include/excludeAuthorEmails`, `-Pcodiqo.testTimeoutMinutes`,
`-Pcodiqo.perTestTimeoutMinutes`, `-Pcodiqo.ignoreTestFailures`.
For `codiqoIndexCommits`: `-Pcodiqo.indexRef`, `-Pcodiqo.commitWindow`, `-Pcodiqo.includeBranches`,
`-Pcodiqo.includeAuthorEmails`, `-Pcodiqo.excludeAuthorEmails`, `-Pcodiqo.firstParentOnly`.

The analysis runs in its **own forked JVM**, not in the Gradle daemon, so the analysed project's
`org.gradle.jvmargs` no longer decides how much memory it gets. Its heap is `codiqo.analysisMaxHeap`
(default `8g`, the Gradle counterpart of the `-Xmx8g` the showcase workflow passes as `MAVEN_OPTS`);
override with `-Pcodiqo.analysisMaxHeap=12g`. The daemon still needs enough heap to *build* the
project, which is the project's own concern.

Success = a `codiqo-submission-<sha>.yaml` with changed-line coverage (MODE=full), PMD + SpotBugs
diagnostics, CPD duplication and the resolved dependency registry — comparable to the Maven dump.

Gradle build files count as **config files**, the way `pom.xml` does on the Maven side: `build.gradle`
and `settings.gradle` (`.kts` too), any other `*.gradle` script, `gradle.properties`,
`gradle-wrapper.properties` and `*.versions.toml`. A commit that touches only those is analysable and
lands as `configOnly: true` / `hasCodeChanges: false`, rather than being dropped with "no diff files
match registered languages".

`-Pcodiqo.ignoreCpd=true` is no longer needed on large projects. PMD's shared CPD lexer can poison
itself on a big file set (micronaut-core's 5024 files still reproduce it); codiqo now retries in
batches, so an affected project reports partial duplication data instead of losing the analysis. The
whole set is always attempted first, so unaffected projects keep full cross-file accuracy.

## Operational requirements (from the spring-kafka run)

- **Heap.** The analysis forks its own JVM sized by `codiqo.analysisMaxHeap`, so the project's
  daemon heap only has to compile and test the project — spring-kafka's own 1536M is enough for that.
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
- **Engine runs in a forked JVM, via `javaexec` rather than `WorkerExecutor`.** The reason is heap,
  not classloader isolation: in-process the engine inherited the analysed project's
  `org.gradle.jvmargs`, so micrometer's `-Xmx1g` OOMed the ClassGraph scan and spring-framework's
  `-Xmx2048m` GC-thrashed the daemon. `javaexec` over worker isolation because the request is
  already JSON-serializable (worker isolation wants Gradle-typed parameters) and the engine forks
  JDT LS itself, so nesting inside a Gradle worker would add failure modes for no gain. The
  worker's classpath is the plugin classloader's own URLs — Gradle's uninstrumented transform
  variant, so it is safe on a bare `java -cp`, and it cannot drift from the version the daemon runs.

## codiqoSubmitAnalysis

Same analysis as `codiqoDumpAnalysis`, and then posts the submission to the backend — the Gradle
counterpart of the Maven `submit-commit-analysis` goal, sharing `AnalysisSubmitter` in
`codiqo-submit`. The YAML is still written first, so a submission the backend rejects leaves the
dump on disk to inspect.

```bash
./gradlew --init-script <codiqo repo>/gradle-plugin/codiqo.init.gradle --continue \
    test codiqoSubmitAnalysis \
    -Pcodiqo.commitId=<sha> -Pcodiqo.apiKey=env:CODIQO_API_KEY
```

The key is resolved in the daemon, so `env:VAR` works and a variable that is unset fails the build
in about a second rather than after a full analysis. Run this task **or** `codiqoDumpAnalysis`,
never both in one build.

## codiqoIndexCommits

Walks git history over a window, publishes it to the backend and writes the commits that still
lack an analysis (Gradle port of the Maven `index-commits` goal — shares `CommitIndexer` for the
walk and `CommitIndexPublisher` for the protocol). Squash/rebase duplicates are dropped by
patch-id; merge/revert commits are flagged. Feeds the per-commit submit loop.

Without an API key the backend half is skipped and every commit in the window is written instead,
which is what a local dump loop wants: there is no server to say which analyses already exist.

```bash
./gradlew --init-script <codiqo repo>/gradle-plugin/codiqo.init.gradle codiqoIndexCommits \
    -Pcodiqo.commitWindow=P3M -Pcodiqo.indexOutputFile=<out>/indexed-commits.txt \
    -Pcodiqo.apiKey=env:CODIQO_API_KEY
# overrides: -Pcodiqo.indexRef (default HEAD), -Pcodiqo.branch, -Pcodiqo.include/excludeAuthorEmails,
#            -Pcodiqo.missingAnalysesLimit
```

## Commit exclusion

A commit that must not be scored is reported as excluded rather than analyzed, using the same rules
as the Maven path — they live in `CommitExclusions` in `codiqo-submit`, so the two build tools
cannot drift. The gates are:

| Gate | Category | When |
| --- | --- | --- |
| merge | `MERGE_COMMIT` | all-commits mode, octopus merges, empty merges, no side-branch commits |
| author | `FILTERED_BY_RULES` | credited author matches `codiqo.excludeAuthorEmails` (default `*bot*`) |
| include-rules | `FILTERED_BY_RULES` | commit fails `codiqo.includeBranches` / `codiqo.includeAuthorEmails` |
| analyzable diff | `NO_ANALYZABLE_DIFF` | nothing changed that a language or config-file spec recognises |
| revert | `REVERT_COMMIT` + `REVERTED` | the revert itself, and with `codiqo.excludeRevertedCommits` the original too |

The merge and author gates run before the ClassGraph scan and the JDT import, so an excluded commit
costs a git walk rather than a full analysis. Excluded commits still carry their raw diff so the
backend records what changed. Under `codiqoDumpAnalysis` there is no backend to tell, so the reason
is logged and the run ends.

## Build failures

A commit whose build fails is reported rather than silently mis-scored. `BuildFailures` reads each
scheduled task's `TaskState` when the analysis runs, and the engine switches to the source-only
degraded path — PMD over the work tree, no ClassGraph scan, no JDT language server, no coverage.
On spring-kafka that turned a 1 m 6 s failing run into under 30 s.

Task state, not a build-event listener: Gradle hands listener events to a queue drained by its own
executor, and the only point at which that queue is guaranteed to be drained is `buildFinished` —
after the analysis has already run. A listener can therefore report an empty failure set for a
build that has already failed, and the analysis would score a broken commit as clean. Task state
is set as each task completes, and the analysis is ordered after every other task.

**Test-task failures do not count.** codiqo sets `ignoreFailures` on every Test task, so an
ordinary red test never fails the task; the only way one still fails is codiqo's own task timeout
firing. Demoting a commit whose classes compiled, because codiqo's own deadline elapsed, would be
self-inflicted, and it says nothing about whether the compiled output can be trusted.

By default the commit is reported excluded as `BUILD_FAILURE` with the failing task and failure
kind attached. `codiqo.scoreOnBuildFailure=true` scores it from the degraded submission instead,
and `codiqo.skipOnBuildFailure=false` makes a build failure a hard error rather than an exclusion —
both as the Maven side behaves. If the source-only index cannot be read, the submission degrades
again to diff-only rather than losing the commit.

The detail carries the failing task path and the failure chain, not the per-diagnostic compiler
output: Gradle emits that to the console and its incubating Problems report, never into the
`Failure` object, so there is no supported way to reach it. The full output stays in the build log.

This is also why the analysis task is ordered after **every** other task in the build rather than
just the `Test` tasks. Ordering against Test tasks alone was vacuous for a build invoked with
`testClasses` — no Test task is in the graph — so the analysis raced compilation, read a
half-written class directory and missed the failure entirely.

## Not yet at parity with Maven

- **Per-commit driving.** Maven's `submit-commit-analysis` checks out the commit itself and forks
  its own build; a Gradle build reads its model at startup, so the checkout has to happen outside
  the build. The loop therefore belongs to the caller (see `run-spring-kafka.sh`), not the plugin.
- **Time machine.** No Gradle equivalent of the `codiqo-maven-time-machine` snapshot resolver, so
  historical commits resolve today's snapshots.
- **Dependency-resolution failures** are not told apart from other build failures, so
  `DEPENDENCY_RESOLUTION_FAILURE` is never reported — a resolution failure lands as `BUILD_FAILURE`.
- **Agent instructions** (`AGENTS.md` / `CLAUDE.md`) are not collected into the submission, so a
  Gradle commit is scored without the conventions a Maven commit's prompt carries.
