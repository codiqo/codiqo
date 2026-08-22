package io.codiqo.gradle;

import io.codiqo.api.RunArgs;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodiqoExtension {
    private String apiUrl = RunArgs.DEFAULT_API_URL;
    private String apiKey;

    private String javaHome;
    private String outputDirectory;

    private String commitWindow = "P3M";
    private String indexOutputFile;

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
