package io.codiqo.gradle;

import io.codiqo.api.RunArgs;
import lombok.Getter;
import lombok.Setter;

/**
 * The {@code codiqo { }} block. A property named after a setting ({@link GradleSettings}) has no default: one left
 * unset keeps the {@link RunArgs} default, and a {@code -P} property of the setting's name wins over it. The others are
 * the plugin's own: the login, the forked JVM's heap, the index window and the test tasks' failure handling.
 */
@Getter
@Setter
public class CodiqoExtension {
    private String apiUrl = RunArgs.DEFAULT_API_URL;
    private String apiKey;
    /** where the browser login ({@code codiqoLogin}) signs in; a run without {@code apiKey} uses that login */
    private String authUrl = RunArgs.DEFAULT_AUTH_URL;
    private String resourceUrl = RunArgs.DEFAULT_RESOURCE_URL;
    private Long connectTimeoutSeconds;
    private Long readTimeoutSeconds;

    private String javaHome;
    private String jdtlsJavaHome;
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

    private Boolean skipOnBuildFailure;

    /**
     * Hidden-source mode, as on Maven: nothing sent to Codiqo carries source code, and identifiers are hashed with
     * {@code sourceHashKey} ({@code env:VAR} reads it from the environment), the same key for every run of the project.
     */
    private boolean sourceHidden;
    private String sourceHashKey;

    /**
     * Local mode, as on Maven: OpenCode agents review and assess every analysed commit next to the analysis, and the
     * server scores it from that assessment alone.
     */
    private Boolean review;
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

    private Boolean firstParentOnly;
    private Boolean excludeRevertedCommits;
    private String includeBranches;
    private String includeAuthorEmails;
    private String excludeAuthorEmails;

    private Boolean ignoreCoverage;
    private Boolean ignoreCpd;
    private String excludeProjects;
    private String excludePaths;
    private Boolean ignoreDiagnostics;
    private Boolean ignoreComplexity;
    private Boolean hotspots;

    private Long testTimeoutMinutes;
    private Long perTestTimeoutMinutes;
    private boolean ignoreTestFailures = true;

    private String jdtlsVersion;
    private Boolean jdtlsUseSnapshot;
    private Boolean jdtUseSharedIndex;
    private Boolean jdtIncludeDecompiledSources;
    private Boolean jdtLombokAgent;
    private Long importTimeoutMinutes;
    private Long lspQueryTimeoutSeconds;

    private Boolean failOnJdtlsError;
    private Boolean failOnUninstrumentedModule;
    /** 1 or more turns the check off for a project whose tests need what CI lacks */
    private String testFailureHoldShare;
    private String testFailureHoldMinTests;
}
