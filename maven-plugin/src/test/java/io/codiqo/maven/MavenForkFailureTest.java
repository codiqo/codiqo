package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.client.model.AnalysisExcludeCategory;

class MavenForkFailureTest {
    @Test
    void aPluginMavenCouldNotDownloadIsTheEnvironmentsFailure() {
        List<String> help = List.of("[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException");

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, Maven.classifyForkFailure(help, "[ERROR] Plugin could not be resolved"));
    }
    @Test
    void aCompileErrorStaysTheAuthorsFailure() {
        String failure = "[ERROR] /work/src/main/java/app/Ledger.java:[42,17] cannot find symbol";

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, Maven.classifyForkFailure(List.of(), failure));
    }
}
