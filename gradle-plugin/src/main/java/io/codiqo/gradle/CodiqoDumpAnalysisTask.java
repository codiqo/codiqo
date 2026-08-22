package io.codiqo.gradle;

import java.util.Objects;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import io.codiqo.gradle.logging.GradleLogFactory;
import io.codiqo.gradle.model.AnalysisRequest;

/**
 * Runs the shared analysis engine against the model collected off the execution path
 * (see {@link GradleModelCollector}, wired at taskGraph.whenReady). Because the Gradle model is
 * resolved at configuration time, this task performs no cross-project configuration resolution
 * during execution, so it works under Gradle's parallel execution.
 */
public class CodiqoDumpAnalysisTask extends DefaultTask {
    private AnalysisRequest request;

    public void setRequest(AnalysisRequest request) {
        this.request = request;
    }
    @TaskAction
    public void dump() throws Exception {
        if (Objects.isNull(request)) {
            throw new IllegalStateException(
                    "Codiqo model was not collected — apply the plugin to the root project (via the codiqo init script) so it can snapshot the build.");
        }
        AnalysisEngine.run(request, new GradleLogFactory(getLogger()));
    }
}
