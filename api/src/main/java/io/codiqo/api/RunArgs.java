package io.codiqo.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Option.Builder;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Repository;

import edu.umd.cs.findbugs.Priorities;
import io.codiqo.util.JGit;
import io.codiqo.util.Split;
import lombok.Data;
import net.sourceforge.pmd.lang.rule.RulePriority;
import okhttp3.HttpUrl;

/**
 * Every tunable input to one analysis run: what to analyze, how to build it, which analyzers to run, what to send
 * the LLM, and every coefficient of the scoring model. One instance is assembled per commit — by the Maven mojo,
 * the Gradle plugin, the CLI ({@link #from(CommandLine)}) or the backend's replay builder — and threaded through
 * the whole pipeline.
 *
 * <p><b>Four kinds of field live here and they behave very differently:</b>
 * <ul>
 * <li><i>Engine behaviour</i> — timeouts, tool toggles, paths, JDT / PMD / SpotBugs configuration. Read directly
 * by the code that does the work.</li>
 * <li><i>Server-enforced scoring</i> — the effort and volume maths in {@code VolumeScoreCalculator} and
 * {@code FinalScoreCalculator}. The LLM cannot influence these; changing one moves the score deterministically.</li>
 * <li><i>Prompt-only advisory</i> — interpolated into {@code thymeleaf/templates/system-prompt.txt} as the penalty
 * tables, thresholds and formulas the model is instructed to follow. Nothing re-checks the result afterwards: the
 * model returns impacts and the only hard guard is the {@link #qualityMultiplierMin} / {@link #qualityMultiplierMax}
 * clamp. Tuning one of these is a request to the model, not a change to the arithmetic — each is marked
 * <i>prompt-only</i> below.</li>
 * <li><i>Report presentation</i> — thresholds that only pick a band or badge in the HTML report.</li>
 * </ul>
 *
 * <p><b>Persistence and replay.</b> Non-transient fields are mirrored onto the submission's scoring config by
 * {@code ScoringConfigs}, persisted by the backend, and restored on server-side replay — so a re-score reproduces
 * the offline numbers even after server defaults move. {@code transient} marks a field as local to this process: it
 * is excluded from the generated CLI and never travels with the submission. {@code DRIVER_SCORE.md} is the canonical
 * derivation of the volume-scoring knobs and worth reading before tuning any of them.
 *
 * <p><b>CLI generation.</b> {@link #options()} reflects over the non-transient, non-static fields and derives one
 * {@code --kebab-case} option per field; {@link #from(CommandLine)} parses them back and calls {@link #validate()}.
 * Two consequences: {@code @Nullable} is what marks an option <i>optional</i> — hence its presence even on
 * primitives, where dropping it would make the option required — and a {@code boolean} field becomes a valueless
 * flag, so a boolean that defaults to {@code true} cannot be switched off from the command line.
 */
@Data
public class RunArgs {
    public static final String DEFAULT_API_URL = "https://api.codiqo.io";
    public static final String DEFAULT_AUTH_URL = "https://codiqo.io";

    public static final int DEFAULT_NUM_CTX = 256 * 1024;
    // head room reserved on top of the request JSON for the system prompt (~25k) + completion (~33k) + margin
    public static final int PROMPT_TOKEN_RESERVE = 72 * 1024;
    public static final int DEFAULT_MAX_CALLERS_PER_BLOCK = 64;
    // sized above a single large instruction file (observed 40-75 KB) so enabling the feature does not fail on its own default
    public static final int DEFAULT_CONVENTION_FILES_MAX_CHARS = 64 * 1024;
    public static final int DEFAULT_SEED = 42;
    public static final Duration PER_TEST_TIMEOUT_MAX = Duration.ofMinutes(15);
    public static final Map<String, String> JDTLS_CONFIG = Map.of(
            "osx-x86_64", "config_mac",
            "osx-aarch_64", "config_mac_arm",
            "linux-x86_64", "config_linux",
            "linux-aarch_64", "config_linux_arm",
            "windows-x86_64", "config_win");
    public static final Path JDT_SHARED_INDEX = FileSystems.getDefault().getPath(System.getProperty("user.home"), ".cache", "jdtls");
    private static final Pattern JDTLS_ARCHIVE_VERSION = Pattern.compile("jdt-language-server-(\\d+\\.\\d+\\.\\d+)-");
    private static final Pattern CAMEL_HUMP = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    static {
        try {
            Files.createDirectories(JDT_SHARED_INDEX);
        } catch (IOException err) {
            throw new ExceptionInInitializerError(err);
        }
    }

    /**
     * The commit under analysis, or blank for an uncommitted-changes run against the working tree. Blank disables
     * every path that needs a reproducible checkout: no forked build, no time machine, and no build-failure
     * degradation — those all gate on a commit being present.
     */
    @Nullable
    private String commitId;

    /**
     * Eclipse JDT language server milestone to download when {@link #jdtlsUseSnapshot} is off, and the fallback
     * when a version cannot be parsed out of the resolved archive name. The shared index is keyed by
     * {@link #effectiveJdtlsVersion()}, so moving this forces a full re-index of every dependency jar.
     */
    @Nullable
    private String jdtlsVersion = "1.60.0";

    /**
     * Resolve the language server from the {@code snapshots} channel instead of a pinned milestone. Snapshots move,
     * so {@link #jdtlsVersion} is then ignored and the concrete build is whatever {@code latest.txt} names at run
     * time — which also means the shared-index key changes underneath you as upstream publishes.
     */
    @Nullable
    private boolean jdtlsUseSnapshot = false;

    /**
     * The resolved {@code jdt-language-server-*.tar.gz} file name, memoized by {@link #resolveJdtlsArchiveName()}
     * after the first {@code latest.txt} fetch. transient: a per-process cache of a remote lookup, not a setting.
     */
    @Nullable
    private transient String jdtlsArchiveName;

    /**
     * Least-severe PMD rule priority still executed. Accepts the constant name, the hyphenated spelling
     * ({@code medium-high}) and PMD's own display form ({@code Medium High}); {@link #validate()} canonicalises it
     * to a {@link RulePriority} constant name and rejects anything else up front.
     *
     * <p>The {@code HIGH} default is not merely a noise filter. Only PMD priority 1 maps to {@code error} severity
     * downstream, and only {@code error} findings carry a static-analysis penalty — so relaxing this adds
     * warning/info findings that give the model context but cannot move the score, at the cost of PMD runtime and
     * prompt tokens.
     */
    @Nullable
    private String pmdMinPriority = RulePriority.HIGH.name();

    /**
     * Ruleset resources handed to PMD, resolved from the classpath. {@code codiqo/pmd/java-codestyle.xml} stands in
     * for PMD's own codestyle category: the stock ruleset reports underscore-separated JUnit method names as
     * violations, so it flagged a legitimate test convention as error severity.
     */
    @Nullable
    private List<String> pmdRules = new ArrayList<>(List.of(
            "category/java/bestpractices.xml",
            "codiqo/pmd/java-codestyle.xml",
            "category/java/design.xml",
            "category/java/errorprone.xml",
            "category/java/performance.xml",
            "category/java/multithreading.xml",
            "category/java/security.xml"));

    /**
     * SpotBugs <i>confidence</i> cut-off on the {@link Priorities} scale, where {@code 1} is most confident: a
     * finding is dropped when its priority number exceeds this. Distinct from bug <i>rank</i>, which is the axis
     * that maps to severity and drives the penalty tables — this knob decides what SpotBugs reports at all.
     */
    @Nullable
    private Integer spotbugsPriorityThreshold = Priorities.HIGH_PRIORITY;

    /**
     * Comma-separated SpotBugs detector short names to disable before the analysis runs. The escape hatch for a
     * detector that crashes or is pathologically slow on a given codebase.
     */
    @Nullable
    private String spotbugsOmitVisitors;

    /**
     * Treat untracked working-tree files as additions. Only meaningful for an uncommitted-changes run — a commit
     * analysis reads its file set from the diff, where nothing is untracked by construction.
     */
    @Nullable
    private boolean includeUntracked = true;

    /**
     * Enables the language server's workspace autobuild. Off by default: codiqo only asks JDT for call-hierarchy
     * data, which it answers from its own index, so a second JDT-driven compile on top of the forked build buys
     * nothing.
     */
    @Nullable
    private boolean autoBuild = false;

    /**
     * Write the assembled submission to disk as YAML or JSON, into {@link #outputDirectory} when one is set and a
     * temp file otherwise. This is exactly the payload the backend would receive, so it is the artefact to diff when
     * a score changes unexpectedly between runs.
     */
    @Nullable
    private boolean dumpAnalysis = true;

    /**
     * Skip coverage end to end: the forked build runs with {@code -DskipTests} and no JaCoCo agent, and no coverage
     * is captured. Because tests never run, {@link #perTestTimeout} is inert on this path too.
     *
     * <p>The commit is not then scored as having zero coverage: the coverage inputs are simply absent from the
     * payload, so the model has no coverage row to apply. Compare {@link #failOnUninstrumentedModule}, which exists
     * to stop coverage going missing by accident on a run that did want it.
     */
    @Nullable
    private boolean ignoreCoverage = false;

    /**
     * Fail the analysis when a module that has tests produced no coverage data at all. On by default because the
     * failure mode it catches is silent: an agent that never attached looks identical to a genuinely untested
     * commit, and the commit then scores as small and clean rather than as a broken measurement.
     */
    @Nullable
    private boolean failOnUninstrumentedModule = true;

    /** Skip cyclomatic / cognitive complexity capture. Complexity is an input to the model's difficulty judgment, not a score term of its own. */
    @Nullable
    private boolean ignoreComplexity = false;

    /**
     * Skip PMD CPD duplication detection. Also suppresses the {@link #cpdCleanBonus}: uncollected duplication data
     * is not evidence of clean code, so it must not earn the reward for having none.
     */
    @Nullable
    private boolean ignoreCpd = false;

    /**
     * Skip PMD and SpotBugs entirely. Also suppresses {@link #staticAnalysisCleanBonus}, for the same reason
     * {@link #ignoreCpd} suppresses the CPD bonus.
     */
    @Nullable
    private boolean ignoreDiagnostics = false;

    /**
     * Fail the analysis when a language-server call-hierarchy query errors, instead of continuing with an incomplete
     * call graph. Off by default so a flaky JDT query cannot lose a whole commit — with the consequence that a
     * missing caller list is indistinguishable from a symbol that genuinely has no callers, and blast radius is
     * then under-reported rather than reported as unknown.
     */
    @Nullable
    private boolean failOnJdtlsError = false;

    /**
     * On a failed or timed-out fork build, exclude the commit with a {@code BUILD_FAILURE} category instead of
     * raising. Off means a hard error.
     *
     * <p>Neither setting silently scores the commit: {@code clean} has already wiped {@code target/} for every
     * module the failure stopped, so the reactor left behind reads as a small, clean commit rather than as the
     * failure it is. Scoring a broken build is opt-in through {@link #scoreOnBuildFailure} instead, which switches
     * to diff-only inputs rather than trusting the wreckage.
     */
    @Nullable
    private boolean skipOnBuildFailure = true;

    /**
     * Score a build-failed commit in degraded mode — from the git diff and commit metadata alone — instead of
     * excluding it. The change still represents real developer work, but coverage, duplication, static analysis,
     * complexity, code blocks and blast radius are all missing rather than clean, so the quality multiplier is
     * capped at {@code 1.0} regardless of {@link #qualityMultiplierMax}. Falls back to exclusion when the commit
     * has no analyzable diff files.
     */
    @Nullable
    private boolean scoreOnBuildFailure = false;

    /**
     * When a revert commit is detected, retro-exclude the commit it reverted as well, so reverted work stops
     * counting toward effort on both ends. A reverted original outside the indexing window is simply not known to
     * the backend and is left alone.
     */
    @Nullable
    private boolean excludeRevertedCommits = true;

    /**
     * transient so the reflective CLI builder skips it: this is a server-side policy read from the scoring
     * config, and the engine has no consumer for it. Left non-transient it generates a
     * {@code --honor-skip-requests} option that is accepted, never transmitted, and silently ignored.
     */
    private transient boolean honorSkipRequests = true;

    /**
     * Character ceiling on captured build diagnostics — forked build stdout/stderr, the build-failure report file,
     * and abbreviated stack traces. Bounds what a runaway build log can push into the failure detail that gets
     * persisted and, in degraded mode, prompted.
     */
    @Nullable
    private int buildErrorCaptureLimit = 8 * 1024;

    /** JDK used for the forked build and for the language-server JVM. Unset means the JDK running codiqo. */
    @Nullable
    private transient File javaHome;

    /** Maven installation used to invoke the forked build. Unset lets the invoker resolve one from the environment. */
    @Nullable
    private transient File mavenHome;

    /** Gradle installation for the Gradle path. Currently carried but unread — the Gradle plugin runs in the daemon's own distribution. */
    @Nullable
    private transient File gradleHome;

    /**
     * Bytecode index over the built reactor, assigned by the Maven analyze mojo for the duration of the analysis.
     * Readers reach the same scan through their own {@link ProjectSpec} rather than off this field, which is why it
     * has no getter call sites in the engine.
     */
    @Nullable
    private transient ClassGraphSpec classGraph;

    /**
     * Wall clock for the whole forked build. Exceeding it is treated as a build failure, so it interacts with
     * {@link #skipOnBuildFailure} / {@link #scoreOnBuildFailure} rather than merely aborting. Sized below the
     * per-commit deadline of the surrounding pipeline: a value that outlives that deadline converts a slow build
     * into a lost commit instead of a recorded failure.
     */
    @Nullable
    private Duration buildTimeout = Duration.ofMinutes(45);

    /** Ceiling on the whole Surefire fork, passed through as {@code -Dsurefire.timeout}. One hung test still burns the entire budget — {@link #perTestTimeout} is what bounds a single test. */
    @Nullable
    private Duration testTimeout = Duration.ofMinutes(30);

    /**
     * Per-test-method timeout, injected as a JUnit 5 {@code timeout.default} on a separate thread. Unset derives
     * {@link #testTimeout} / 2 in {@link #validate()}; any value — derived or explicit — is then hard-capped at
     * {@link #PER_TEST_TIMEOUT_MAX}, and zero or negative leaves the feature off.
     *
     * <p>Only applied on the coverage path, since that is the only path that runs tests, and only when the injector
     * extension resolves — an unresolvable injector downgrades to a warning rather than failing the analysis.
     */
    @Nullable
    private Duration perTestTimeout;

    /** How long to wait for the language server to hand over an imported project. A large multi-module reactor is the case that needs headroom here. */
    @Nullable
    private Duration importTimeout = Duration.ofMinutes(15);

    /** Per-query timeout on a single call-hierarchy request. Interacts with {@link #failOnJdtlsError}: a timeout is one of the errors that flag decides how to treat. */
    @Nullable
    private Duration lspQueryTimeout = Duration.ofSeconds(30);

    /** Connect timeout for codiqo's own HTTP client (tool downloads, backend calls). Not the LLM client, which uses {@link #llmReadTimeout}. */
    @Nullable
    private Duration connectTimeout = Duration.ofSeconds(30);

    /** Read timeout for codiqo's own HTTP client, reused as the JGit transport timeout when a commit has to be fetched before it can be resolved. */
    @Nullable
    private Duration readTimeout = Duration.ofMinutes(1);

    /** OkHttp dispatcher ceiling on in-flight requests for codiqo's HTTP client. */
    @Nullable
    private int maxRequests = 256;

    /** OkHttp dispatcher ceiling on in-flight requests per host — the one that actually binds, since a run talks to few hosts. */
    @Nullable
    private int maxRequestsPerHost = 128;

    /**
     * Minimum duplicated token run CPD will report. Lowering it finds shorter clones and grows the clone set
     * quickly, which feeds both {@link #cpdIntroducedThreshold} and the prompt payload.
     */
    @Nullable
    private int cpdMinimumTileSize = 64;

    /**
     * Unified-diff context lines around each hunk. This is not cosmetic: the model is asked to classify every
     * changed line and to pair deletions with additions, and context is what lets it recognise a pair as one
     * in-place edit rather than a delete plus an unrelated add.
     */
    @Nullable
    private int diffContextLines = 10;

    /**
     * Threshold ratio (0.0-1.0) for determining if a clone was "introduced" in the commit.
     * A clone is considered "introduced" only if this percentage of its lines overlap with added lines.
     * Default: 0.4 (40%) - just modifying 1 line of a pre-existing clone doesn't mean the duplication was introduced.
     */
    @Nullable
    private double cpdIntroducedThreshold = 0.4;

    /** The reactor's modules, one {@link ProjectSpec} each. {@link #owner(File)} maps a changed path back to the module that owns it. */
    @Nullable
    private transient List<ProjectSpec> projects = new ArrayList<>();

    /**
     * Java agent jars attached to the language-server JVM. Lombok is added here so JDT resolves generated members
     * (builders, accessors) and call hierarchy does not stop dead at them.
     */
    @Nullable
    private transient List<File> agents = new ArrayList<>();

    /** The JGit repository under analysis; its work tree is the root every analyzed path is relative to. */
    @Nullable
    private transient Repository git;

    /** Default branch recorded on the submission's project identity. Unset leaves whatever the backend already knows. */
    @Nullable
    private transient String defaultBranch;

    /** Remote URLs recorded on the submission, normalised to URIs. This is how the backend identifies which project a submission belongs to. */
    @Nullable
    private transient Set<String> remoteUrls = new HashSet<>();

    /** API key for the OpenAI-compatible LLM endpoint. Also authenticates the web-search tool when {@link #llmEnableWebSearchTool} is on. */
    @Nullable
    private String llmApiKey = System.getProperty("ollama.apiKey");

    /** Model id for scoring. Also selects the tokenizer used for prompt-budget estimates, so it affects how aggressively {@link #llmMaxCallersPerBlock} is trimmed. */
    @Nullable
    private String llmModel = System.getProperty("ollama.model", "deepseek-v4-pro:cloud");

    /** Base URL of the OpenAI-compatible endpoint. Unset falls back to the SDK's own default rather than to a codiqo default. */
    @Nullable
    private String llmBaseUrl = System.getProperty("ollama.url", "https://ollama.com/v1");

    /**
     * Sampling temperature, sent both top-level and inside Ollama's native {@code options}. Zero is deliberate:
     * scoring should be reproducible. It gets close but not all the way — a hosted model is not byte-reproducible
     * even at {@code temperature=0} with a fixed {@link #llmSeed}, which is why the difficulty-category spread is
     * kept narrow enough that a borderline block flipping one tier barely moves the score.
     */
    @Nullable
    private Double llmTemperature = 0.0;

    /** Nucleus-sampling cut-off, sent alongside {@link #llmTemperature} through the same two channels. */
    @Nullable
    private Double llmTopP = 0.8;

    /** Ceiling on completion tokens. The scoring response enumerates per-file line classifications and per-block categories, so a tight value truncates the tail of those lists rather than failing outright. */
    @Nullable
    private Integer llmMaxTokens = (int) Short.MAX_VALUE;

    /** Attempts for a transport or unparseable-response failure. Validated {@code >= 1} — this counts attempts, not extra retries — and clamped in {@link #validate()}. */
    @Nullable
    private Short llmMaxRetries = 3;

    /**
     * Extra round-trips that feed diff-classification validation failures back to the model as a critique of its
     * own prior answer, giving it a chance to repair line arithmetic it got wrong. Zero disables the loop; a
     * response that still fails validation is not discarded, its per-file reduction is simply skipped.
     */
    @Nullable
    private Short llmValidationMaxRetries = 1;

    /**
     * The model's context window, sent through Ollama's {@code options.num_ctx} (where it actually takes effect).
     * A non-positive value falls back to {@link #DEFAULT_NUM_CTX}. Unset, it is also the basis for the prompt
     * budget — see {@link #llmPromptTokenBudget}.
     */
    @Nullable
    private Integer llmNumCtx;

    /**
     * Estimated-token ceiling on the serialized request JSON embedded in the LLM prompt. Callers are
     * unbounded, so a change to a hot method can push the prompt past the model context window (observed 549k vs a 512k limit).
     *
     * <p>
     * Unset means "whatever the window allows" — {@code llmNumCtx} less {@link #PROMPT_TOKEN_RESERVE}.
     * It previously defaulted to a constant derived from a 256K window, which silently bound every model
     * with a larger one: an org running a 1M-context model still had its request trimmed to 188K, the
     * caller cap descended the whole ladder to zero, and large commits were scored with no call graph at
     * all (observed: the LLM reporting a blast radius of 193 against a persisted 586). Set this only to
     * spend less than the window permits.
     */
    @Nullable
    private Integer llmPromptTokenBudget;

    /**
     * Starting ceiling on callers listed per code block. When the request still exceeds the prompt budget the cap
     * descends the Fibonacci sequence from just below this value to zero, keeping the largest caller set that fits;
     * survivors are ranked production-before-test, then by call-site coupling, then by class concentration. Blocks
     * report how many callers were dropped, so a trimmed list is visible to the model rather than silent.
     */
    @Nullable
    private int llmMaxCallersPerBlock = DEFAULT_MAX_CALLERS_PER_BLOCK;

    /**
     * Agent instruction files, relative to the repository root, appended to the prompt as a hint for finding
     * triage: they stop the model reporting a project's deliberate idioms as defects and shape the fixes it
     * proposes. They are not a rule set to enforce — a convention violation is not by itself a bug. An entry
     * may name a file or a directory of rule files. Opt-in per project: the files are authored by the same
     * developers being scored, so the prompt forbids them from moving effort, volume, complexity, risk or any
     * other number. Combines with {@link #autoDiscoveryAgentInstructions}.
     */
    @Nullable
    private List<String> llmConventionFiles = new ArrayList<>();

    /**
     * Also pick up the well-known agent instruction locations (AGENTS.md, CLAUDE.md, Copilot, Cursor,
     * Windsurf, Cline, Gemini, Junie, Aider) without naming them one by one. On by default: a repository
     * that documents its idioms should not have them reported as defects. Costs prompt tokens only when
     * such a file exists, and the prompt bars the content from moving any score.
     */
    @Nullable
    private boolean autoDiscoveryAgentInstructions = true;

    /**
     * Ceiling on the assembled instruction text. Exceeding it fails the analysis instead of trimming the
     * text: a truncated rule set silently changes which findings the model suppresses, so the ceiling is
     * raised deliberately. Zero disables instruction loading entirely.
     */
    @Nullable
    private int llmConventionFilesMaxChars = DEFAULT_CONVENTION_FILES_MAX_CHARS;

    /** Sampling seed, sent with {@link #llmTemperature} for reproducibility. Fixed rather than random so two runs of the same commit differ as little as the provider allows. */
    @Nullable
    private Integer llmSeed = DEFAULT_SEED;

    /** Expose the web-search tool to the model, for checking an unfamiliar library or CVE while judging a change. Only the direct Ollama path can actually serve the call. */
    @Nullable
    private boolean llmEnableWebSearchTool = true;

    /**
     * Idle guard on the LLM connection, applied per streamed chunk to connect / read / write. The total-call
     * timeout is deliberately left disabled: budgeting the whole call would abort a long but healthily streaming
     * completion mid-generation, which is exactly what a large commit produces.
     */
    @Nullable
    private Duration llmReadTimeout = Duration.ofMinutes(10);

    /** Directory for the submission dump, the HTML report and the result YAML. Unset writes them to temp files, whose paths are logged. */
    @Nullable
    private transient File outputDirectory;

    /** Sidecar directory where the time-machine extension records which snapshot timestamp each dependency resolved to. Owned by the mojo: recreated per attempt and deleted when the run leaves time-machine mode. */
    @Nullable
    private transient File timeMachineMetaDir;

    /**
     * Private local Maven repository holding one pinned timestamp per snapshot, so the back-off ladder's attempts
     * cannot overwrite each other's pins and the language server's own resolution reads the same versions the build
     * used. Its path is stable per project rather than per commit, because JDT's shared index is keyed by absolute
     * jar path and a fresh directory would re-index every dependency.
     */
    @Nullable
    private transient File localRepositoryDir;

    /** Generated {@code settings.xml} pointing the fork and the language server at {@link #localRepositoryDir}. Owned by the mojo alongside it. */
    @Nullable
    private transient File mavenUserSettings;

    /**
     * How far before the commit instant to resolve snapshots. Non-null also switches on host-side pinning and an
     * isolated repository session for model building in this JVM, so it doubles as the "time machine is active for
     * this attempt" signal; the back-off ladder walks it outward when a commit-date snapshot is unavailable.
     */
    @Nullable
    private transient Duration timeMachineTargetOffset;

    /** File the build-failure EventSpy writes structured failure detail to. Preferred over scraped stdout/stderr when present, and read under {@link #buildErrorCaptureLimit}. */
    @Nullable
    private transient File buildFailureReportFile;

    /**
     * Comma-separated <b>regular expressions</b> matched against a commit's branches; empty accepts everything.
     * Applied at indexing time as well as analysis time, because a commit this rejects is not worth a build.
     */
    @Nullable
    private String includeBranches;

    /**
     * Comma-separated author emails to analyze, matched <b>exactly</b> — no wildcards, unlike
     * {@link #excludeAuthorEmails}. Empty accepts everyone. On a merge node the filter sees the side-branch sole
     * author rather than whoever clicked merge, so a bot merge does not drop a developer's work.
     */
    @Nullable
    private String includeAuthorEmails;

    /** Comma-separated author email patterns to exclude, matched as case-insensitive <b>wildcards</b> ({@code *bot@example.com}). Applied after {@link #includeAuthorEmails}, so exclusion wins. */
    @Nullable
    private String excludeAuthorEmails;

    /**
     * Index only the first-parent (mainline) history, dropping commits that arrived as merged-in feature-branch
     * work. On by default so a PR is counted once, at the merge node, rather than once per intermediate commit.
     */
    @Nullable
    private boolean firstParentOnly = true;

    /**
     * Divisor turning project size into the dimensionless size factor {@code cbrt(totalStatements) / divisor} that
     * drives both operation multipliers. Raising it flattens the size response of {@link #modifyMultiplierScale}
     * and {@link #addMultiplierScale} — it is the knob for "how quickly does a codebase count as large".
     */
    @Nullable
    private double sizeFactorDivisor = 100.0;

    /**
     * Asymptote of the modify multiplier: {@code modifyMult = base + min(scale · s/(1+s), cap)} for size factor
     * {@code s}. Because the base is a free parameter rather than pinned at {@code 1.0}, either operation can be
     * discounted outright, not only priced at a premium.
     */
    @Nullable
    private double modifyMultiplierBase = 1.0;

    /** Growth term of the modify multiplier — modifying entangled existing code is priced up as the codebase grows. Saturates at {@code base + scale}; see {@link #modifyMultiplierBase}. */
    @Nullable
    private double modifyMultiplierScale = 0.3;

    /** Safety clamp on the modify multiplier's growth term. The {@code s/(1+s)} form already saturates, so this only binds if {@link #modifyMultiplierScale} is set far above its default. */
    @Nullable
    private double modifyMultiplierCap = 0.2;

    /**
     * Asymptote of the add multiplier: {@code addMult = base + scale/(1+s)}, decaying toward the base as the
     * project grows. Below {@code 1.0} deliberately — new code in a large codebase is priced under modifying
     * entangled existing code.
     *
     * <p>The "new code is easier" discount currently rides here <i>and</i> in the {@code MECHANICAL} difficulty
     * coefficient, which fires precisely on boilerplate additions. When the planned structural-similarity discount
     * ships it becomes the single owner of the axis and this base returns to {@code 1.0}; until then, neither live
     * discount should be widened without re-checking their combined floor.
     */
    @Nullable
    private double addMultiplierBase = 0.8;

    /** Decay term of the add multiplier, worth most on a small codebase and vanishing on a large one. See {@link #addMultiplierBase}. */
    @Nullable
    private double addMultiplierScale = 0.1;

    /**
     * fraction of an equivalent in-place modify that a deletion-only file earns (delete effort ÷ the
     * same-size true_modify effort). only applies to surviving files whose change is purely deletion.
     * clamped to [0, 0.20] in validate() — a removal is worth at most ~20% of changing the same lines.
     */
    @Nullable
    private double deleteRewardWeight = 0.2;

    /**
     * absolute ceiling on a commit's total deletion reward, expressed in bucket-quantile units:
     * K × methodCapQuantile × modifyMult × deleteRewardWeight. the per-block global cap cannot bound a
     * deletion sweep — a mass removal is broad and shallow (thousands of lines over dozens of files), so
     * its cap budget grows with file count exactly as fast as its effort does and the cap never binds.
     * measured over 2105 analyses, K=10 leaves the median reward untouched (+2.1) and clips 12 commits,
     * pulling the worst case from +172 down to +46 — a large cleanup earns a medium commit, never a huge
     * one. 0 disables the ceiling. clamped to ≥ 0 in validate().
     *
     * those figures predate deletion blocks carrying the MECHANICAL coefficient. The clip is computed in
     * driver units, before the coefficient is applied in recompute, so the delivered reward — and hence the
     * effective ceiling — is 0.7x the numbers quoted above. The mechanism is unchanged; only the calibration
     * is now conservative, and re-measuring would be needed before treating +46 as the real worst case.
     */
    @Nullable
    private double deleteRewardMaxQuantileUnits = 10.0;

    /**
     * Floor on the quality multiplier. This clamp and its {@link #qualityMultiplierMax} counterpart are the only
     * hard guards on the model's quality arithmetic: every per-issue penalty and bonus below it is advisory, so a
     * model that mis-adds its own table is bounded here and nowhere else.
     */
    @Nullable
    private double qualityMultiplierMin = 0.5;

    /**
     * Ceiling on the quality multiplier. Independently lowered to {@code 1.0} for a degraded (build-failed)
     * analysis, where coverage, duplication and static analysis are unknown rather than clean and no bonus is
     * verifiable. See {@link #qualityMultiplierMin}.
     */
    @Nullable
    private double qualityMultiplierMax = 1.2;

    /**
     * Cap on the total static-analysis penalty. Enforced server-side on the pre-computed impact <i>and</i>
     * interpolated into the prompt, so the model's reference table states the same bound that is actually applied.
     */
    @Nullable
    private double staticAnalysisPenaltyCap = 0.2;

    /**
     * Penalty per distinct error-severity rule <b>introduced</b> by the commit. Server-computed and handed to the
     * model as the mandatory {@code RECOMMENDED IMPACT}, so it moves the score deterministically. Error rules
     * introduced only in test code are weighted down by {@link #testCodePenaltyWeight}.
     */
    @Nullable
    private double staticAnalysisIntroducedPenalty = -0.05;

    /**
     * Much smaller penalty per pre-existing error-severity rule the commit merely touched. Non-zero on purpose —
     * changed code inherits some responsibility for what it sits in — but an order of magnitude below
     * {@link #staticAnalysisIntroducedPenalty}, because pre-existing violations are not the author's doing.
     */
    @Nullable
    private double staticAnalysisPreExistingPenalty = -0.01;

    /**
     * Bonus when the commit introduces zero error-severity violations, mirroring {@link #cpdCleanBonus}. Withheld
     * when {@link #ignoreDiagnostics} is set: uncollected findings are not evidence of clean code.
     */
    @Nullable
    private double staticAnalysisCleanBonus = 0.05;

    /** Prompt-only cap on the total architecture/SOLID penalty. Stated to the model in its penalty table; unlike {@link #staticAnalysisPenaltyCap} nothing re-checks the sum afterwards. */
    @Nullable
    private double architecturePenaltyCap = 0.15;

    /** Prompt-only cap on the total quality-gate penalty. Also surfaced in the HTML report so a reader sees the bound the model was given. */
    @Nullable
    private double qualityGatePenaltyCap = 0.1;

    /**
     * Compression exponent applied to summed block effort, {@code blockEffortSum^exponent}, so marginal volume on a
     * large commit is worth less than the first lines of a small one. It owns commit size and nothing else — the
     * operation multipliers cannot shape size, and this cannot distinguish new lines from modified ones.
     */
    @Nullable
    private double volumeExponent = 0.85;

    /** Per-doubling breadth bonus: {@code log2(filesChanged) · coefficient}, capped by {@link #filesScopeMaxBonus}. Prices the coordination cost of touching many files, not the code in them. */
    @Nullable
    private double filesScopeLogCoefficient = 0.02;

    /** Ceiling on the breadth bonus, so a very wide commit gains at most ~10%. See {@link #filesScopeLogCoefficient}. */
    @Nullable
    private double filesScopeMaxBonus = 0.10;

    /**
     * Multiplier on the bucket-aware baseline budget that yields the global ceiling on summed block effort. Higher
     * is more permissive. Outlier protection deliberately lives here, on the sum, rather than per block: clipping
     * individual blocks would also strip the honest contributions of the well-sized blocks beside an outlier.
     */
    @Nullable
    private double driverScoreCapMultiplier = 2.5;

    /**
     * Per-block deviation threshold, serving two abuse signals: a NEW block is flagged a ratio outlier when its own
     * {@code S/L} or {@code I/L} deviates from the bucket median ratio by more than this, and any block is flagged
     * the cap driver when the global cap binds and that block alone owns more than this share of total effort.
     *
     * <p>Diagnostic, not a scoring input, and explicitly <b>not</b> a clamp on the bucket-level projection factors —
     * those stay raw so the project's real style survives. False positives are expected; raise it for a codebase
     * whose blocks legitimately diverge from their own median.
     */
    @Nullable
    private double driverFactorMaxDeviation = 0.75;

    /**
     * Audit-only mode for the global cap: the full cap maths and every abuse signal are computed and persisted, but
     * the effort sum is left unclipped, so the score matches pre-cap behaviour. For calibrating
     * {@link #driverScoreCapMultiplier} against real traffic, and for replaying history under new abuse detection
     * without rewriting the scores it produced. Replay must honour the persisted flag verbatim or volume diverges.
     */
    @Nullable
    private boolean driverScoreCapDryRun = false;

    /**
     * Which percentile of each bucket's driver-score distribution becomes that bucket's baseline contribution to
     * the cap budget. Clamped to {@code >= 0.85} in {@link #validate()}, which also rejects a non-finite value:
     * clamping alone cannot, since NaN propagates through {@code min}/{@code max} and would poison every product
     * downstream all the way to the persisted score.
     */
    @Nullable
    private double statsQuantile = 0.95;

    /** Line-coverage percentage below which a changed symbol is listed to the model as an uncovered path. Purely selects what the prompt highlights; it carries no penalty of its own. */
    @Nullable
    private double coverageLowThreshold = 50.0;

    /** Line-coverage percentage below which a changed symbol is labelled {@code CRITICAL} risk in the request payload. */
    @Nullable
    private double coverageCriticalThreshold = 10.0;

    /** Line-coverage percentage below which a changed symbol is labelled {@code HIGH} risk; at or above it the symbol is {@code MEDIUM}. See {@link #coverageCriticalThreshold}. */
    @Nullable
    private double coverageHighThreshold = 30.0;

    /**
     * Cyclomatic complexity above which a changed method is counted as high-complexity in the request's complexity
     * summary, split by new versus modified. Also sent to the model as the threshold those counts were derived
     * from, so it can read them without guessing the cut-off.
     */
    @Nullable
    private int highComplexityThreshold = 10;

    /**
     * Bonus when effective introduced clones fall at or below {@link #cpdCleanThreshold}. Server-computed and
     * handed to the model as its mandatory CPD impact, so this and the penalties below move the score
     * deterministically despite also appearing in the prompt's reference table. Withheld when {@link #ignoreCpd}
     * suppressed the measurement.
     */
    @Nullable
    private double cpdCleanBonus = 0.05;

    /** Penalty for the moderate duplication band. See {@link #cpdCleanBonus} for how the impact reaches the score. */
    @Nullable
    private double cpdModeratePenalty = -0.10;

    /** Penalty for the high duplication band. See {@link #cpdCleanBonus}. */
    @Nullable
    private double cpdHighPenalty = -0.20;

    /** Penalty for the severe duplication band. See {@link #cpdCleanBonus}. */
    @Nullable
    private double cpdSeverePenalty = -0.30;

    /**
     * Upper bound of the clean duplication band, measured in <i>effective</i> introduced clones — pre-existing
     * clones are excluded entirely and test-only clones are weighted by {@link #testCodePenaltyWeight}, so this
     * counts fractional units, not raw clone rows.
     */
    @Nullable
    private double cpdCleanThreshold = 1.0;

    /** Upper bound of the acceptable band, which carries neither bonus nor penalty. See {@link #cpdCleanThreshold}. */
    @Nullable
    private double cpdAcceptableThreshold = 3.0;

    /** Upper bound of the moderate band, charged {@link #cpdModeratePenalty}. See {@link #cpdCleanThreshold}. */
    @Nullable
    private double cpdModerateThreshold = 6.0;

    /** Upper bound of the high band, charged {@link #cpdHighPenalty}; anything above is severe. See {@link #cpdCleanThreshold}. */
    @Nullable
    private double cpdHighThreshold = 10.0;

    /**
     * Weight on a test block's effort and on its contribution to the cap budget. The driver score itself is already
     * fair — test code is calibrated against test code — so this is a product decision about what test work is
     * worth, applied uniformly at the effort step.
     */
    @Nullable
    private double testCodeScoreMultiplier = 0.4;

    /**
     * Weight on test-only findings when computing a penalty: applied to test-only clones in the CPD count and to
     * error rules introduced only in test code. Separate from {@link #testCodeScoreMultiplier} because rewarding
     * test work and forgiving test defects are different decisions — test duplication is often legitimate.
     */
    @Nullable
    private double testCodePenaltyWeight = 0.3;

    /**
     * Discount on config-file effort. Files with no code blocks to drive ({@code pom.xml}, {@code .proto}) are
     * scored from line count alone, and a 200-line dependency bump is real work but cheaper per line than 200 lines
     * of hand-written logic. Their synthetic blocks carry a zero cap baseline, so they never inflate the cap budget.
     */
    @Nullable
    private double configFileScoreMultiplier = 0.3;

    /**
     * Effort coefficient for a block the model labels {@code MECHANICAL} — rename, import swap, accessor, mirrored
     * unit, relocated code. Also the fallback for a block left uncategorized, and the fixed coefficient for
     * deletion blocks, which the model never sees.
     *
     * <p>The uncategorized fallback is empirical, not a neutral guess: omission tracks how many blocks the model
     * was asked to label rather than what any block contains (3% uncategorized on commits with ≤5 blocks, 68% on
     * commits with 40+, with output budget to spare), and 82% of the MODIFY blocks it does label are mechanical.
     * Defaulting to neutral instead coupled the score to the model's verbosity — one 119-file commit scored 325 and
     * 381 on identical inputs purely on how much of the list came back.
     *
     * <p>This coefficient family scales <b>effort only</b>, never the driver score: volume stays an honest
     * line-count magnitude and difficulty rides a separate axis, so a 200-line mechanical migration and a 200-line
     * intricate rewrite carry the same volume and different effort.
     */
    @Nullable
    private double categoryMechanicalCoeff = 0.7;

    /** Effort coefficient for {@code ROUTINE} — ordinary feature wiring, and the neutral anchor of the spread. See {@link #categoryMechanicalCoeff}. */
    @Nullable
    private double categoryRoutineCoeff = 1.0;

    /** Effort coefficient for {@code SUBSTANTIVE} — genuine new logic, a real design choice, careful edge-case work. See {@link #categoryMechanicalCoeff}. */
    @Nullable
    private double categorySubstantiveCoeff = 1.2;

    /**
     * Effort coefficient for {@code INTRICATE} — concurrency, lock-free code, subtle invariants,
     * correctness-critical logic. The whole spread is kept deliberately narrow around the neutral anchor because a
     * hosted model flips borderline blocks one tier between otherwise identical runs; a compressed spread bounds
     * how far one flip can move the final score. See {@link #categoryMechanicalCoeff}.
     */
    @Nullable
    private double categoryIntricateCoeff = 1.4;

    /**
     * Detect lines relocated within the commit — including across files — and charge a matched pair
     * {@link #movedLineCoefficient} instead of full weight. Relocating code is not writing it. Must match the
     * offline plugin's value on replay, since it changes per-block driver scores.
     */
    @Nullable
    private boolean moveDetectionEnabled = true;

    /**
     * Resolve each dependency's snapshot to the deploy closest to the commit date, so a historical commit is
     * analyzed against its contemporaneous dependencies. A failed attempt steps
     * {@link #timeMachineTargetOffset} outward along a back-off ladder before falling back to latest; disabled, only
     * the latest-snapshot build runs and no snapshot metadata is emitted.
     */
    @Nullable
    private boolean timeMachineEnabled = true;

    /**
     * threshold on multiset containment |tokens(A)∩tokens(B)| / min(|A|,|B|) — NOT Dice/Jaccard.
     * Relocation typically only adds tokens (e.g. re-qualified receivers: props.X → channel.props.X),
     * so genuine moves score 1.0 under containment while symmetric metrics drop below 0.95
     */
    @Nullable
    private double moveSimilarityThreshold = 0.95;

    /**
     * Charge for a matched moved pair instead of full weight, split half to each side so a cross-file move costs
     * exactly what an in-file one does. Applied as a per-block factor that scales the driver score as well as
     * effort, because moved lines are a volume correction rather than a difficulty judgment.
     */
    @Nullable
    private double movedLineCoefficient = 0.25;

    /** Report presentation: final-score floor for the "huge" band in the HTML report. Cosmetic banding only — no scoring path reads it. */
    @Nullable
    private int scoreThresholdHuge = 150;

    /** Report presentation: final-score floor for the "large" band. See {@link #scoreThresholdHuge}. */
    @Nullable
    private int scoreThresholdLarge = 90;

    /** Report presentation: final-score floor for the "medium" band. See {@link #scoreThresholdHuge}. */
    @Nullable
    private int scoreThresholdMedium = 50;

    /** Report presentation: final-score floor for the "small" band; below it the commit renders as trivial. See {@link #scoreThresholdHuge}. */
    @Nullable
    private int scoreThresholdSmall = 20;

    /** Report presentation: quality-dimension score at which the report renders the dimension as critical. Independent of the per-dimension gate thresholds the model is given. */
    @Nullable
    private int dimensionScoreCritical = 8;

    /** Report presentation: quality-dimension score rendered as major. See {@link #dimensionScoreCritical}. */
    @Nullable
    private int dimensionScoreMajor = 6;

    /** Report presentation: quality-dimension score rendered as moderate. See {@link #dimensionScoreCritical}. */
    @Nullable
    private int dimensionScoreModerate = 4;

    /**
     * Report presentation: caller count above which the report falls back to {@code HIGH} blast-radius risk when
     * the model returned no blast-radius analysis at all. A fallback for a missing answer, not a second opinion on
     * one that arrived.
     */
    @Nullable
    private int callerThresholdHigh = 10;

    /** Report presentation: caller count above which the same fallback yields {@code MODERATE}. See {@link #callerThresholdHigh}. */
    @Nullable
    private int callerThresholdModerate = 5;

    /** Report presentation: how many clone groups the duplication table lists before truncating. */
    @Nullable
    private int maxClonesToShow = 10;

    /** Carried through the config plumbing and exposed as an override, but currently read by no consumer — no prompt, score or report path uses it. */
    @Nullable
    private int maxSourceLines = 30;

    /** Carried through the config plumbing and exposed as an override, but currently read by no consumer. See {@link #maxSourceLines}. */
    @Nullable
    private int truncateSourceLines = 25;

    /**
     * Factor in the architecture bonus, {@code impactScore × baseEffort × factor × qualityFactor}, added after the
     * quality multiplier rather than multiplied into it. Server-enforced, on a model-supplied impact score clamped
     * to 0-10 — and suppressed entirely for a commit that changes only build descriptors, since a bare version bump
     * is not architecture work.
     */
    @Nullable
    private double architectureBonusFactor = 0.015;

    /** Prompt-only: per-issue impact the model is told to apply to a PMD priority-1 (blocker) finding. Advisory — the applied static-analysis impact is the server's own pre-computed value. */
    @Nullable
    private double pmdPriority1Penalty = -0.05;

    /** Prompt-only: per-issue impact for a PMD priority-2 (critical) finding. See {@link #pmdPriority1Penalty}. */
    @Nullable
    private double pmdPriority2Penalty = -0.03;

    /** Prompt-only: per-issue impact for a PMD priority-3 (important) finding. See {@link #pmdPriority1Penalty}. */
    @Nullable
    private double pmdPriority3Penalty = -0.01;

    /** Prompt-only: per-issue impact for a SpotBugs rank 1-4 (scariest) finding — the rank band that maps to {@code error} severity. See {@link #pmdPriority1Penalty}. */
    @Nullable
    private double spotbugsScariestPenalty = -0.08;

    /** Prompt-only: per-issue impact for a SpotBugs rank 5-9 (scary) finding. See {@link #pmdPriority1Penalty}. */
    @Nullable
    private double spotbugsScaryPenalty = -0.04;

    /** Prompt-only: per-issue impact for a SpotBugs rank 10-14 (troubling) finding. See {@link #pmdPriority1Penalty}. */
    @Nullable
    private double spotbugsTroublingPenalty = -0.02;

    /**
     * Prompt-only: quality-multiplier impact the model is told to award at excellent coverage of changed code. The
     * band edges are {@link #coverageImpactExcellentMin} and friends; nothing verifies the model applied the row it
     * was given.
     */
    @Nullable
    private double coverageExcellentBonus = 0.10;

    /** Prompt-only: impact for the good coverage band. See {@link #coverageExcellentBonus}. */
    @Nullable
    private double coverageGoodBonus = 0.05;

    /** Prompt-only: impact for the low coverage band. See {@link #coverageExcellentBonus}. */
    @Nullable
    private double coverageLowPenalty = -0.05;

    /** Prompt-only: impact for the poor coverage band. See {@link #coverageExcellentBonus}. */
    @Nullable
    private double coveragePoorPenalty = -0.10;

    /** Prompt-only: impact below the poor band. See {@link #coverageExcellentBonus}. */
    @Nullable
    private double coverageTerriblePenalty = -0.15;

    /** Prompt-only: per-issue impact for a minor style issue (magic number, deep nesting, over-long symbol), bounded in the prompt by {@link #architecturePenaltyCap}. */
    @Nullable
    private double architectureMinorPenalty = -0.01;

    /** Prompt-only: per-issue impact for a SOLID violation or a leaky abstraction. See {@link #architectureMinorPenalty}. */
    @Nullable
    private double architectureSolidPenalty = -0.03;

    /** Prompt-only: per-issue impact for a major architecture issue (god class, circular dependency). See {@link #architectureMinorPenalty}. */
    @Nullable
    private double architectureMajorPenalty = -0.05;

    /** Prompt-only: impact per failed quality gate — a dimension scoring at or above its threshold whose gate criterion is not met. Bounded in the prompt by {@link #qualityGatePenaltyCap}. */
    @Nullable
    private double qualityGateFailurePenalty = -0.03;

    /**
     * Prompt-only: architecture-impact dimension score at which its quality gate must be met. Its gate is purely
     * coverage-based — see {@link #architectureImpactCoverageRequired} — and the prompt says so explicitly, because
     * the model otherwise failed it on static-analysis findings it was told to ignore.
     */
    @Nullable
    private int architectureImpactScoreThreshold = 7;

    /** Prompt-only: coverage of changed code that satisfies the architecture-impact gate. See {@link #architectureImpactScoreThreshold}. */
    @Nullable
    private int architectureImpactCoverageRequired = 80;

    /**
     * Prompt-only: dimension score at which the concurrency-risk gate (explicit thread-safety tests) must be met.
     * Set lowest of the ten because concurrency defects are the hardest to reproduce after the fact. Also feeds the
     * model's risk-score formula through {@link #riskHighDimensionThreshold}.
     */
    @Nullable
    private int concurrencyRiskThreshold = 3;

    /** Prompt-only: dimension score at which the integration-surface gate (contract tests for genuinely new APIs) must be met. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int integrationSurfaceThreshold = 7;

    /** Prompt-only: dimension score at which the data-integrity gate (transactional tests) must be met. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int dataIntegrityThreshold = 7;

    /** Prompt-only: dimension score at which the security-sensitivity gate (security review) must be met. Set below the others because unreviewed auth and crypto work is worth flagging early. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int securitySensitivityThreshold = 5;

    /** Prompt-only: dimension score at which the scalability gate must be met — and only for newly written bottleneck code, never for a dependency upgrade. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int scalabilityImpactThreshold = 7;

    /** Prompt-only: dimension score at which the observability gate (operational review) must be met. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int observabilityThreshold = 7;

    /** Prompt-only: dimension score at which the resilience gate (failure-scenario tests) must be met. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int resilienceThreshold = 7;

    /** Prompt-only: dimension score at which the performance gate must be met — and only for newly written hot-path code. See {@link #concurrencyRiskThreshold}. */
    @Nullable
    private int performanceThreshold = 7;

    /** Report presentation: senior-review score at which the report renders the recommendation as major. */
    @Nullable
    private int seniorReviewThreshold = 7;

    /** Report presentation: senior-review score at which the recommendation renders as critical. See {@link #seniorReviewThreshold}. */
    @Nullable
    private int seniorReviewCriticalThreshold = 8;

    /** Report presentation: cyclomatic complexity at which a method renders as high complexity. Separate from {@link #highComplexityThreshold}, which decides what the model is told. */
    @Nullable
    private int complexityHighDisplayThreshold = 15;

    /** Report presentation: cyclomatic complexity at which a method renders as moderate. See {@link #complexityHighDisplayThreshold}. */
    @Nullable
    private int complexityModerateDisplayThreshold = 10;

    /** Report presentation: clone similarity at which a duplication finding renders as critical. */
    @Nullable
    private double similarityCriticalThreshold = 0.90;

    /** Report presentation: clone similarity at which a duplication finding renders as major. See {@link #similarityCriticalThreshold}. */
    @Nullable
    private double similarityMajorThreshold = 0.75;

    /**
     * Prompt-only: dimension score counted as "high" by the model's risk formula. The formula is
     * {@code min(riskScoreMax, maxDimScore × riskBaseMultiplier + modifiers)} and the prompt marks it mandatory —
     * the model is told to compute rather than judge it — but the result is not recomputed server-side.
     */
    @Nullable
    private int riskHighDimensionThreshold = 7;

    /** Prompt-only: multiplier on the highest non-null dimension score, forming the base risk. See {@link #riskHighDimensionThreshold}. */
    @Nullable
    private int riskBaseMultiplier = 7;

    /** Prompt-only: risk added per high-scoring dimension beyond the first — breadth of exposure, not just its peak. See {@link #riskHighDimensionThreshold}. */
    @Nullable
    private int riskHighDimensionPenalty = 5;

    /** Prompt-only: risk added when the changed module is a core library or shared utility, where a defect propagates to consumers. See {@link #riskHighDimensionThreshold}. */
    @Nullable
    private int riskCoreLibraryPenalty = 10;

    /** Prompt-only: risk added when the commit carries breaking changes. See {@link #riskHighDimensionThreshold}. */
    @Nullable
    private int riskBreakingChangesPenalty = 10;

    /** Prompt-only: ceiling on the risk score, and the top of the {@code CRITICAL} band. See {@link #riskHighDimensionThreshold}. */
    @Nullable
    private int riskScoreMax = 100;

    /** Prompt-only: top of the {@code LOW} risk band. The model derives its risk level from these four cut-offs rather than judging it. */
    @Nullable
    private int riskLevelLowMax = 25;

    /** Prompt-only: top of the {@code MODERATE} risk band. See {@link #riskLevelLowMax}. */
    @Nullable
    private int riskLevelModerateMax = 50;

    /** Prompt-only: top of the {@code HIGH} risk band. See {@link #riskLevelLowMax}. */
    @Nullable
    private int riskLevelHighMax = 75;

    /** Prompt-only: top of the {@code VERY_HIGH} risk band; above it is {@code CRITICAL} up to {@link #riskScoreMax}. See {@link #riskLevelLowMax}. */
    @Nullable
    private int riskLevelVeryHighMax = 90;

    /**
     * Prompt-only: floor of the excellent coverage band, and also quoted to the model as the coverage that earns a
     * full architecture-bonus quality factor. These five minimums are the band edges for
     * {@link #coverageExcellentBonus} and friends, and are stated in percent of changed-code coverage.
     */
    @Nullable
    private int coverageImpactExcellentMin = 90;

    /** Prompt-only: floor of the good coverage band. See {@link #coverageImpactExcellentMin}. */
    @Nullable
    private int coverageImpactGoodMin = 80;

    /** Prompt-only: floor of the acceptable band, which carries neither bonus nor penalty. See {@link #coverageImpactExcellentMin}. */
    @Nullable
    private int coverageImpactAcceptableMin = 70;

    /** Prompt-only: floor of the low band. See {@link #coverageImpactExcellentMin}. */
    @Nullable
    private int coverageImpactLowMin = 60;

    /** Prompt-only: floor of the poor band; below it is the terrible band. See {@link #coverageImpactExcellentMin}. */
    @Nullable
    private int coverageImpactPoorMin = 50;

    /**
     * Prompt-only: fan-out above which a block counts as high fan-out when the model judges difficulty. Explicitly
     * an input to that judgment and not the answer — a wide switch can be mechanical, a short lock-free helper
     * intricate.
     */
    @Nullable
    private int fanOutHighThreshold = 10;

    /** Prompt-only: NPath complexity above which a block counts as complex in the same difficulty judgment. See {@link #fanOutHighThreshold}. */
    @Nullable
    private int npathComplexThreshold = 16 * 1024;

    /**
     * Reuse a JDT index shared across runs, under {@link #JDT_SHARED_INDEX} keyed by
     * {@link #effectiveJdtlsVersion()}. The saving is large and the coupling is real: the index is keyed by
     * absolute jar path, which is why {@link #localRepositoryDir} is stable per project rather than per commit.
     */
    @Nullable
    private boolean jdtUseSharedIndex = true;

    /** Let JDT decompile class files to answer reference queries for dependencies with no source jar. Off by default — decompiled callers are outside the analyzed project and cost import time. */
    @Nullable
    private boolean jdtIncludeDecompiledSources = false;

    /** Debug port for the language-server JVM, attached with {@code suspend=y} so it waits for a debugger. Development only: set it and the analysis blocks until something connects. */
    @Nullable
    private transient Integer jdtDebugPort;

    public HttpUrl.Builder jdtlsBaseUrl() {
        HttpUrl.Builder toReturn = new HttpUrl.Builder().scheme("https").host("download.eclipse.org").addPathSegment("jdtls");
        if (jdtlsUseSnapshot) {
            return toReturn.addPathSegment("snapshots");
        }
        return toReturn.addPathSegment("milestones").addPathSegment(jdtlsVersion);
    }
    public String resolveJdtlsArchiveName() {
        if (StringUtils.isNotBlank(jdtlsArchiveName)) {
            return jdtlsArchiveName;
        }
        for (;;) {
            try (InputStream io = jdtlsBaseUrl().addPathSegment("latest.txt").build().url().openStream()) {
                jdtlsArchiveName = StringUtils.trim(IOUtils.toString(io, StandardCharsets.UTF_8));
                return jdtlsArchiveName;
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    public String effectiveJdtlsVersion() {
        Matcher matcher = JDTLS_ARCHIVE_VERSION.matcher(resolveJdtlsArchiveName());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return jdtlsVersion;
    }
    private static double requireFinite(double value, String name) {
        if (Double.isFinite(value)) {
            return value;
        }
        throw new IllegalArgumentException(name + " must be a finite number, got: " + value);
    }
    public Optional<ProjectSpec> owner(File filePath) {
        return projects.stream().filter(proj -> proj.contains(filePath)).findAny();
    }
    public boolean matchesByBranch(List<String> branches) {
        if (StringUtils.isEmpty(includeBranches)) {
            return true;
        }
        List<String> patterns = Split.on(includeBranches, ',');
        return branches.stream().anyMatch(branch -> patterns.stream().anyMatch(pattern -> branch.matches(pattern)));
    }
    public boolean matchesByAuthor(String authorEmail) {
        if (StringUtils.isEmpty(includeAuthorEmails)) {
            return true;
        }
        List<String> emails = Split.on(includeAuthorEmails, ',');
        return emails.contains(authorEmail);
    }
    public boolean isExcludedAuthor(String authorEmail) {
        if (StringUtils.isEmpty(excludeAuthorEmails)) {
            return false;
        }
        List<String> patterns = Split.on(excludeAuthorEmails, ',');
        return patterns.stream().anyMatch(pattern -> FilenameUtils.wildcardMatch(authorEmail, pattern, IOCase.INSENSITIVE));
    }
    public boolean isAuthorAllowed(String authorEmail) {
        return BooleanUtils.and(new boolean[] {
                matchesByAuthor(authorEmail),
                BooleanUtils.negate(isExcludedAuthor(authorEmail))
        });
    }
    /**
     * the PMD threshold as the enum, so the string is parsed in exactly one place. Callers get the canonical value
     * without re-deriving it from {@link #pmdMinPriority}, which validate() has already normalised.
     */
    public RulePriority pmdMinRulePriority() {
        return pmdRulePriority(this.pmdMinPriority);
    }
    public void validate() {
        if (Objects.isNull(this.perTestTimeout)) {
            this.perTestTimeout = this.testTimeout.dividedBy(2);
        }
        // hard ceiling: neither the testTimeout/2 derivation nor an explicit value may exceed PER_TEST_TIMEOUT_MAX (a 0/negative value stays disabled)
        if (this.perTestTimeout.compareTo(PER_TEST_TIMEOUT_MAX) > 0) {
            this.perTestTimeout = PER_TEST_TIMEOUT_MAX;
        }
        /**
         * canonicalise here rather than at the PMD call site: the natural spellings "medium-high"/"Medium High" are
         * not constant names, so they would otherwise reach RulePriority.valueOf and throw deep inside a parallel
         * stream, after the whole fork build has already been paid for. rejecting an unknown value now also keeps
         * the persisted scoring config canonical.
         */
        this.pmdMinPriority = pmdRulePriority(this.pmdMinPriority).name();

        /**
         * clamping alone cannot reject a non-finite value — Math.min/max propagate NaN rather than replacing
         * it — and a NaN that reaches the scoring maths poisons every product it touches all the way to the
         * persisted score, silently and without an exception at the point of misconfiguration
         */
        this.statsQuantile = Math.max(0.85, requireFinite(this.statsQuantile, "statsQuantile"));
        this.deleteRewardWeight = Math.min(0.20, Math.max(0.0, requireFinite(this.deleteRewardWeight, "deleteRewardWeight")));
        this.deleteRewardMaxQuantileUnits = Math.max(0.0, requireFinite(this.deleteRewardMaxQuantileUnits, "deleteRewardMaxQuantileUnits"));
        this.llmMaxRetries = (short) Math.max(1, this.llmMaxRetries);
        this.llmValidationMaxRetries = (short) Math.max(0, this.llmValidationMaxRetries);
        this.llmConventionFilesMaxChars = Math.max(0, this.llmConventionFilesMaxChars);
    }
    /**
     * resolves the spellings people actually write — the constant name, the hyphenated "medium-high", and PMD's own
     * display name "Medium High" — to a {@link RulePriority}. The comparison is per-character case-insensitive rather
     * than an uppercase-then-valueOf: under a Turkish locale {@code "medium-high".toUpperCase()} yields
     * {@code "MEDİUM-HİGH"}, so uppercasing threw on exactly the input it was meant to accept.
     */
    private static RulePriority pmdRulePriority(String value) {
        String candidate = StringUtils.trimToEmpty(value).replace('-', '_').replace(' ', '_');

        return Arrays.stream(RulePriority.values())
                .filter(priority -> priority.name().equalsIgnoreCase(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format(
                        "unknown pmdMinPriority '%s', expected one of %s",
                        value,
                        Arrays.stream(RulePriority.values()).map(RulePriority::name).toList())));
    }
    public static Options options() {
        Options toReturn = new Options();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String kebab = toKebabCase(field.getName());
            Builder builder = Option.builder().longOpt(kebab);
            if (field.getType().equals(boolean.class)) {
                builder = builder.hasArg(false);
            } else {
                builder = builder.hasArg();
            }
            builder = builder.required(Objects.isNull(field.getAnnotation(Nullable.class)));
            toReturn.addOption(builder.get());
        }
        return toReturn;
    }
    public static RunArgs from(CommandLine cmd) throws Exception {
        RunArgs toReturn = new RunArgs();
        Field[] fields = RunArgs.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (Modifier.isTransient(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String kebab = toKebabCase(field.getName());
            if (cmd.hasOption(kebab)) {
                if (field.getType().equals(boolean.class)) {
                    field.set(toReturn, true);
                    continue;
                }
                String value = cmd.getOptionValue(kebab);
                if (field.getType().equals(String.class)) {
                    field.set(toReturn, value);
                } else if (field.getType().equals(int.class) || field.getType().equals(Integer.class)) {
                    field.set(toReturn, Integer.parseInt(value));
                } else if (field.getType().equals(double.class) || field.getType().equals(Double.class)) {
                    field.set(toReturn, Double.parseDouble(value));
                } else if (field.getType().equals(Short.class)) {
                    field.set(toReturn, Short.parseShort(value));
                } else if (field.getType().equals(Duration.class)) {
                    field.set(toReturn, Duration.parse(value));
                } else if (field.getType().equals(File.class)) {
                    field.set(toReturn, new File(value));
                } else if (field.getType().equals(Repository.class)) {
                    field.set(toReturn, JGit.openRepository(new File(value)));
                } else if (field.getType().equals(List.class)) {
                    field.set(toReturn, Split.on(value, ','));
                }
            }
        }
        toReturn.validate();
        return toReturn;
    }
    private static String toKebabCase(String camel) {
        return CAMEL_HUMP.matcher(camel).replaceAll("-").toLowerCase(Locale.ROOT);
    }
}
