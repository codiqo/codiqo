package io.codiqo.gradle;

import io.codiqo.api.RunArgs;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodiqoExtension {
    private String apiUrl = RunArgs.DEFAULT_API_URL;
    private String apiKey;
    private long connectTimeoutSeconds = 30;
    private long readTimeoutSeconds = 60;

    private String javaHome;
    private String outputDirectory;

    /**
     * heap for the forked analysis JVM, the Gradle counterpart of the -Xmx the Maven path takes from MAVEN_OPTS.
     * Deliberately NOT the analysed project's org.gradle.jvmargs: a project pinned to -Xmx1g would otherwise decide
     * how much memory codiqo's ClassGraph scan and JDT import get. The default matches the -Xmx8g measured against
     * real projects, so a commit is not analyzed under a smaller envelope merely because it builds with Gradle.
     */
    private String analysisMaxHeap = "8g";

    private String commitWindow = "P3M";
    private String indexOutputFile;

    private boolean skipOnBuildFailure = true;
    private boolean scoreOnBuildFailure;

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

    private long testTimeoutMinutes = 30;
    private Long perTestTimeoutMinutes;
    private boolean ignoreTestFailures = true;

    private String jdtlsVersion = "1.60.0";
    private boolean jdtlsUseSnapshot;
    private boolean jdtUseSharedIndex = true;
    private boolean jdtIncludeDecompiledSources;
    private long importTimeoutMinutes = 15;
    private long lspQueryTimeoutSeconds = 30;

    private boolean failOnJdtlsError;
    private boolean failOnUninstrumentedModule = true;
}
