package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewGuardTest {
    @TempDir
    Path home;

    @Test
    void thePluginIsWrittenIntoTheHomeAndToldEachAgentsStepCap() throws IOException {
        ObjectNode config = JsonMapper.builder().build().createObjectNode();
        ObjectNode agents = config.putObject("agents");
        agents.putObject(OpenCodeReviewConfig.REVIEWER).put("steps", 256);
        agents.putObject(OpenCodeReviewConfig.COORDINATOR).put("steps", 128);

        ObjectNode installed = ReviewGuard.install(config, home.toFile());

        File plugin = home.resolve(ReviewGuard.DIRECTORY).toFile();
        assertTrue(new File(plugin, "package.json").isFile());
        assertTrue(new File(plugin, "index.js").isFile());
        JsonNode entry = installed.path("plugins").get(0);
        assertEquals(plugin.getAbsolutePath(), entry.path("package").asString());
        assertEquals(256, entry.path("options").path("steps").path(OpenCodeReviewConfig.REVIEWER).asInt());
        assertEquals(128, entry.path("options").path("steps").path(OpenCodeReviewConfig.COORDINATOR).asInt());
        assertFalse(config.has("plugins"), "the configuration passed in is left as it was");
    }
}
