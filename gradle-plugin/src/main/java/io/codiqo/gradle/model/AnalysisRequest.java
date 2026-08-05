package io.codiqo.gradle.model;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;


import lombok.Data;

/**
 * Plain, serializable snapshot of the Gradle project model collected in the daemon (off the task
 * execution path) and handed to the analysis worker. Contains no Gradle types so it round-trips
 * through JSON and runs in an isolated worker JVM.
 */
@Data
public class AnalysisRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String rootDir;
    private String rootCode;
    private String rootName;
    private String gradleVersion;

    private String commitId;
    private String outputDirectory;
    private String javaHome;

    private boolean ignoreCoverage;
    private boolean ignoreCpd;
    private boolean ignoreDiagnostics;
    private boolean ignoreComplexity;
    private boolean failOnJdtlsError;
    private boolean failOnUninstrumentedModule = true;

    private String jdtlsVersion;
    private boolean jdtlsUseSnapshot;
    private boolean jdtUseSharedIndex = true;
    private boolean jdtIncludeDecompiledSources;
    private long importTimeoutMinutes;
    private long lspQueryTimeoutSeconds;

    private List<ModuleData> modules = new ArrayList<>();
}
