package io.codiqo.gradle.model;

import java.io.Serializable;
import java.util.List;

import com.google.common.collect.Lists;

import lombok.Data;

/**
 * Plain, serializable snapshot of the Gradle project model, collected in the daemon off the task-execution path and
 * handed to the analysis worker. Carries no Gradle types, so it round-trips as JSON on the worker's stdin, and it
 * includes the two settings that shape the fork itself (javaHome, analysisMaxHeap).
 */
@Data
public class AnalysisRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String rootDir;
    private String rootCode;
    private String rootName;
    private String rootPath;
    private String rootVersion;
    private String gradleVersion;

    private String commitId;
    private String outputDirectory;
    private String javaHome;
    private String jdtlsJavaHome;
    private String analysisMaxHeap;
    private String buildProgressFile;

    private String apiUrl;
    private String apiKey;
    /** without a key the worker uses the stored browser login of this auth server, refreshing it as it ages */
    private String authUrl;
    private String resourceUrl;
    private long connectTimeoutSeconds;
    private long readTimeoutSeconds;

    /**
     * Set by the task from the build-failure recorder, because it is only known once the build has run.
     */
    private String buildFailureDetail;
    private boolean skipOnBuildFailure = true;

    /** hidden-source mode and its HMAC key, resolved from {@code env:VAR} in the daemon like the API key */
    private boolean sourceHidden;
    private String sourceHashKey;

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
    private String reviewTraceDirectory;

    private boolean firstParentOnly = true;
    private boolean excludeRevertedCommits = true;
    private String includeBranches;
    private String includeAuthorEmails;
    private String excludeAuthorEmails;

    private boolean ignoreCoverage;
    private boolean ignoreCpd;
    private String excludeProjects;
    private String excludePaths;
    private boolean ignoreDiagnostics;
    private boolean ignoreComplexity;
    private boolean hotspots = true;
    private String hotspotsCommitId;
    private boolean failOnJdtlsError;
    private boolean failOnUninstrumentedModule = true;
    private String testFailureHoldShare;
    private String testFailureHoldMinTests;

    private String jdtlsVersion;
    private boolean jdtlsUseSnapshot;
    private boolean jdtUseSharedIndex = true;
    private boolean jdtIncludeDecompiledSources;
    private boolean jdtLombokAgent = true;
    private String lombokAgentJar;
    private String lombokAgentError;
    private long importTimeoutMinutes;
    private long lspQueryTimeoutSeconds;

    private List<ModuleData> modules = Lists.newArrayList();
}
