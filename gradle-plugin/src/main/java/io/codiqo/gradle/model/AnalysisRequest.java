package io.codiqo.gradle.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.client.model.AnalysisExcludeCategory;
import lombok.Data;

/**
 * Plain, serializable snapshot of the Gradle project model, collected in the daemon off the task-execution path and
 * handed to the analysis worker. Carries no Gradle types, so it round-trips as JSON on the worker's stdin. The run's
 * settings travel as text under their names ({@code io.codiqo.api.Settings}), which the worker binds onto its RunArgs;
 * the fields beside them are what is not a setting: the project, the login, the fork's heap and what the build did.
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
    private String analysisMaxHeap;

    private String apiUrl;
    private String apiKey;
    /** without a key the worker uses the stored browser login of this auth server, refreshing it as it ages */
    private String authUrl;
    private String resourceUrl;

    /**
     * Set by the task from the build-failure recorder, because it is only known once the build has run: the detail, whose
     * failure it is, and the line that shows the compiler crashed, classified before the detail was cut to its limit.
     */
    private String buildFailureDetail;
    private AnalysisExcludeCategory buildFailureCategory;
    private String compilerCrash;

    /** hidden-source mode and its HMAC key, resolved from {@code env:VAR} in the daemon like the API key */
    private boolean sourceHidden;
    private String sourceHashKey;

    private String lombokAgentJar;
    private String lombokAgentError;

    private Map<String, String> settings = Maps.newTreeMap();
    private List<ModuleData> modules = Lists.newArrayList();
}
