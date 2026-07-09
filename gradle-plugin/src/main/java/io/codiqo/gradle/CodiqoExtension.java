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
    private boolean ignoreDiagnostics;
    private boolean ignoreComplexity;

    private boolean failOnJdtlsError;
}
