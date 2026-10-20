package io.codiqo.gradle;

import io.codiqo.api.RunArgs;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodiqoExtension {
    private String apiUrl = RunArgs.DEFAULT_API_URL;
    private String apiKey;
    /** where the browser login ({@code codiqoLogin}) signs in; a run without {@code apiKey} uses that login */
    private String authUrl = RunArgs.DEFAULT_AUTH_URL;
    private String resourceUrl = RunArgs.DEFAULT_RESOURCE_URL;
    private long connectTimeoutSeconds = 30;
    private long readTimeoutSeconds = 60;

    private String javaHome;
    private String outputDirectory;

    /**
     * Heap for the forked analysis JVM, the Gradle counterpart of the -Xmx the Maven path takes from MAVEN_OPTS.
     * Deliberately NOT the analysed project's org.gradle.jvmargs: a project pinned to -Xmx1g would otherwise decide
     * how much memory codiqo's ClassGraph scan and JDT import get. The default matches the -Xmx8g measured against
     * real projects, so a commit is not analyzed under a smaller envelope merely because it builds with Gradle.
     */
    private String analysisMaxHeap = "8g";

    private String commitWindow = "P3M";
    private String indexOutputFile;
    /** where the resolved language-server version lands for the job's cache key; the GitHub Action reads it */
    private String indexMetadataFile;

    private boolean skipOnBuildFailure = true;

    /**
     * Hidden-source mode, as on Maven: nothing sent to Codiqo carries source code, and identifiers are hashed with
     * {@code sourceHashKey} ({@code env:VAR} reads it from the environment), the same key for every run of the project.
     */
    private boolean sourceHidden;
    private String sourceHashKey;

    /**
     * Local mode, as on Maven: OpenCode agents review and assess every analysed commit next to the analysis, and the
     * server scores it from that assessment alone. Each review setting left unset keeps its {@link RunArgs} default.
     */
    private boolean review = true;
    private String reviewExecutable;
    private String reviewBaseUrl;
    private String reviewCoordinatorModel;
    private String reviewReviewerModel;
    private String reviewTimeoutMinutes;
    private String reviewTemperature;
    private String reviewTopP;
    private String reviewRelayRetries;
    private String reviewRelayTimeoutSeconds;
    /** where the review's trace of every session and tool call goes; unset, the analysis's output directory */
    private String reviewTraceDirectory;

    private boolean firstParentOnly = true;
    private boolean excludeRevertedCommits = true;
    private String includeBranches;
    private String includeAuthorEmails;
    private String excludeAuthorEmails = "*bot*";

    private boolean ignoreCoverage;
    private boolean ignoreCpd;
    private String excludeProjects;
    private String excludePaths;
    private boolean ignoreDiagnostics;
    private boolean ignoreComplexity;
    private boolean hotspots = true;

    private long testTimeoutMinutes = 30;
    private Long perTestTimeoutMinutes;
    private boolean ignoreTestFailures = true;

    private String jdtlsVersion = "1.63.0";
    private boolean jdtlsUseSnapshot;
    private boolean jdtUseSharedIndex = true;
    private boolean jdtIncludeDecompiledSources;
    private boolean jdtLombokAgent = true;
    private long importTimeoutMinutes = 15;
    private long lspQueryTimeoutSeconds = 30;

    private boolean failOnJdtlsError;
    private boolean failOnUninstrumentedModule = true;
    /** unset keeps RunArgs' threshold; 1 or more turns the check off for a project whose tests need what CI lacks */
    private String testFailureHoldShare;
    private String testFailureHoldMinTests;
}
