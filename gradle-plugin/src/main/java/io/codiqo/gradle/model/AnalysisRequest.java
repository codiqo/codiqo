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
    private String analysisMaxHeap;
    private String buildProgressFile;

    /**
     * Set by the task, not the collector: codiqoDumpAnalysis and codiqoSubmitAnalysis share one collected model, so
     * the collector cannot know which of them will run.
     */
    private boolean submit;
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
    private boolean scoreOnBuildFailure;

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

    private String jdtlsVersion;
    private boolean jdtlsUseSnapshot;
    private boolean jdtUseSharedIndex = true;
    private boolean jdtIncludeDecompiledSources;
    private long importTimeoutMinutes;
    private long lspQueryTimeoutSeconds;

    private List<ModuleData> modules = Lists.newArrayList();
}
