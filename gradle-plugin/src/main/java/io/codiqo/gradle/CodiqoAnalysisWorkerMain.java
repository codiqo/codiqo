package io.codiqo.gradle;

import tools.jackson.databind.ObjectMapper;

import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.gradle.model.AnalysisRequest;
import lombok.experimental.UtilityClass;

/**
 * Entry point of the forked analysis JVM: reads the {@link AnalysisRequest} the dump task piped in and runs the shared
 * engine against it, outside the Gradle daemon and therefore outside the analysed project's heap settings. The request
 * arrives on stdin so the fork owns no temporary state and validates no path argument.
 */
@UtilityClass
public class CodiqoAnalysisWorkerMain {
    public void main(String[] args) throws Exception {
        AnalysisRequest request = new ObjectMapper().readValue(System.in, AnalysisRequest.class);
        AnalysisEngine.run(request, new SlfLogFactory());
    }
}
