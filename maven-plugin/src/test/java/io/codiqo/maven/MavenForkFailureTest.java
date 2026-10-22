package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.codiqo.client.model.AnalysisExcludeCategory;

class MavenForkFailureTest {
    private static final String SPOTLESS_OUTAGE = """
            [ERROR] Failed to execute goal com.diffplug.spotless:spotless-maven-plugin:2.44.4:apply (spotless-apply) on project app: \
            Execution spotless-apply of goal com.diffplug.spotless:spotless-maven-plugin:2.44.4:apply failed: java.io.IOException: \
            Failed to load eclipse jdt formatter: java.lang.UnsupportedOperationException: We could not parse the content at \
            https://download.eclipse.org/eclipse/updates/4.35/p2.index:
            <!DOCTYPE html>
            <html lang="en">
            <head><title>Temporary shutdown.</title></head>
            """;

    @Test
    void aPluginThatGotAnOutagePageInsteadOfItsDownloadIsTheEnvironmentsFailure() {
        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, Maven.classifyForkFailure(List.of(), SPOTLESS_OUTAGE));
    }
    @Test
    void aPluginWhoseConnectionFailedIsTheEnvironmentsFailure() {
        String failure = "[ERROR] Failed to execute goal com.github.eirslett:frontend-maven-plugin:1.15.1:install-node-and-npm: "
                + "Could not download Node.js from https://nodejs.org/dist/v22.11.0/node-v22.11.0-linux-x64.tar.gz: java.net.UnknownHostException: nodejs.org";

        assertEquals(AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, Maven.classifyForkFailure(List.of(), failure));
    }
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
    @Test
    void aUrlWithoutAFailedFetchStaysTheAuthorsFailure() {
        String failure = "[ERROR] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies failed, see https://maven.apache.org/enforcer/";

        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, Maven.classifyForkFailure(List.of(), failure));
        assertEquals(AnalysisExcludeCategory.BUILD_FAILURE, Maven.classifyForkFailure(List.of(), StringUtils.EMPTY));
    }
}
