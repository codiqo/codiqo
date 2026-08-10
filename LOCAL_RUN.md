# Running an analysis locally

How to score a real commit against a locally-built engine and a local `codiqo-server`, so engine
changes can be compared against what production already scored for the same commit.

## 1. Build and install the engine

```bash
JAVA_HOME=$(/usr/libexec/java_home -v26) mvn -o install -DskipTests
```

JDK 26 is required — the backend and the Ebean migration generator reject the Maven default JDK.

## 2. Restart the server

**This step is not optional and is the most common way to waste a run.** `codiqo-server` runs off
`codiqo/llm/target/classes` and `codiqo/api/target/classes` directly, not off the installed jars, and
the JVM loads those classes once at startup. Rebuilding while the server is up changes nothing that
is already loaded — the run will silently exercise the *old* engine and the results will look like
the change did nothing.

Verify rather than assume:

```bash
PID=$(lsof -nP -iTCP:7771 -sTCP:LISTEN -t | head -1)
ps -o lstart= -p $PID
stat -f "%Sm %N" -t "%H:%M:%S" llm/target/classes/io/codiqo/llm/VolumeScoreCalculator.class
```

The server's start time must be later than the class mtimes.

## 3. Submit the commit

Run from the target repository, not from `codiqo`:

```bash
cd ~/dev/turbospaces-boot

mvn io.codiqo:codiqo-maven-plugin:1.0-SNAPSHOT:submit-commit-analysis -T 1C \
  -Dmaven.ext.class.path="$(cat /tmp/codiqo-tm-ext.classpath)" \
  -Dcodiqo.javaHome=$(/usr/libexec/java_home -v26) \
  -Dcodiqo.apiUrl=http://localhost:7771 \
  -Dcodiqo.commitId=<sha> \
  -Dcodiqo.apiKey=<project api key> \
  -Dartifact.registry.url=artifactregistry://europe-maven.pkg.dev/patrianna-dev/nexus \
  -Dartifact.registry.mirror.url=artifactregistry://europe-west1-maven.pkg.dev/patrianna-prod/maven-mirror
```

### The `maven.ext.class.path` argument

The coverage injector needs nothing here — the plugin injects the JaCoCo agent per module inside the
forked build on its own (`[codiqo] injected JaCoCo agent into <module> test argLine`).

Time-machine is different. The fork enables it, but the **host** Maven — the one building the POM
model before the fork starts — does not have it unless it is on the extension class path, and the
plugin warns about exactly this:

> `codiqo-maven-time-machine is not loaded in the host Maven — host-side POM model building resolves
> LATEST snapshots and may fail on historical commits whose POMs no longer interpolate against them.`

Without it the host resolves `LATEST` for snapshot dependencies instead of what existed at the
commit. On commits a few weeks old that is usually the same thing, so results still match CI; on
older commits it diverges silently or the build fails outright. Generate the class path once:

```bash
cd ~/dev/codiqo/maven-time-machine
mvn -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/codiqo-tm-deps.classpath -DincludeScope=runtime
echo "$HOME/.m2/repository/io/codiqo/codiqo-maven-time-machine/1.0-SNAPSHOT/codiqo-maven-time-machine-1.0-SNAPSHOT.jar:$(cat /tmp/codiqo-tm-deps.classpath)" \
  > /tmp/codiqo-tm-ext.classpath
```

Regenerate it after any change to `maven-time-machine`'s dependencies. To confirm it took, the
warning above should be absent from the run log and `time-machine enabled for commit <sha>` present.

## 4. Scoring an opted-out commit

A commit whose message asks to be skipped (`honorSkipRequests`, default on) is excluded server-side
about 17 seconds after submission and never scored — the build is spent and no score appears. To
score one anyway, add an org-level override before the run finishes:

```sql
insert into codiqo.org_scoring_overrides
  (id, organization_id, project_id, ov_honor_skip_requests, version, created_at, modified_at)
values
  ((select coalesce(max(id),0)+1 from codiqo.org_scoring_overrides),
   '<better-auth org id>', null, false, 1, now(), now());
```

`project_id` must be null and there must be exactly one such row per org — `findByOrganization` uses
`findOneOrEmpty()` and throws on more. **Delete it afterwards**, or every later local run silently
ignores skip requests:

```sql
delete from codiqo.org_scoring_overrides where organization_id = '<better-auth org id>';
```

The same table overrides any `ov_*` scoring parameter, which is how to test a scoring config that the
client cannot submit.

## 5. Comparing against production

Group the metrics by what can legitimately move:

| group | metrics | expectation |
|---|---|---|
| diff-derived | `summary_files_changed`, `effort_lines_changed`, `effort_lines_new`/`_modified`, `effort_code_blocks_added`/`_modified`, `effort_test_files_changed`, `config_only`, `has_code_changes` | **exact match** — any difference is a regression |
| build-derived | `cov_*`, `pq_average_coverage`, `pq_changed_line_coverage`, `dup_*`, `pq_total_pmd_violations`, `pq_total_spotbugs_issues`, `pm_total_classes` | exact in practice; whole-project `cov_line_percentage` drifts in the third decimal |
| LLM-driven | `score`, per-block `driver_score` and `effort`, block categories, `task_complexity`, `risk_*`, tags, architecture bonus | drift expected — the model is not byte-reproducible |

The third group is why a single re-run's score is not a regression signal. Compare per-operation
block sums instead, and decompose `score_calculation` (`baseEffort × qualityMultiplier + archBonus`)
to see which factor actually moved. A small absolute change can cross a rounding boundary and show
up as a whole point — on `5624f6d0` a 0.02 engine change and a 0.37 architecture-bonus drift together
turned 14.78 into 14.39, i.e. 15 into 14.

Block categories are only recoverable from `code_units.category`, which is written **only when the
LLM emitted one** — `null` means uncategorized. Omission is no longer neutral: an uncategorized NEW
*or* MODIFY block is floored to `categoryMechanicalCoeff` (0.7), so it is now distinguishable from an
explicit `ROUTINE` (1.0) — the two used to be indistinguishable at 1.0. The run log reports coverage
as `block categories: N/M labelled by the LLM`, which is the number to watch when comparing runs: a
score change with the same inputs is often just a different N.

Recovering the coefficient arithmetically from `code_block_efforts` needs the DELETE caveat in
[DRIVER_SCORE.md](DRIVER_SCORE.md): deletion blocks persist an unscaled `driverScore`, so the standard
`effort / (driverScore × operationMult × testWeight)` reads `0.14`, not `0.7`. That is the mechanical
coefficient being applied, not missing.
