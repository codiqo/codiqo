package io.codiqo.llm.review;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.apache.commons.io.FileUtils;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenCode plugin every review runs with ({@code review-guard/index.js}): it ends each tool result with the step the
 * call was made in, and refuses a third identical tool call in one session. Agents did not keep the step count their
 * prompts asked for, and a coordinator spent its whole cap repeating one grep; OpenCode has a guard for neither.
 *
 * <p>
 * The plugin is written into the review's home, never into the reviewed repository, whose own plugins OpenCode is told
 * to ignore ({@code OPENCODE_DISABLE_PROJECT_CONFIG}). It loads only as a directory with its {@code package.json}.
 */
@UtilityClass
class ReviewGuard {
    static final String DIRECTORY = "review-guard";
    private static final List<String> FILES = List.of("package.json", "index.js");

    /** @return a copy of the configuration that loads the plugin written under {@code home}, told each agent's step cap */
    ObjectNode install(ObjectNode config, File home) throws IOException {
        File plugin = new File(home, DIRECTORY);
        for (String name : FILES) {
            String resource = DIRECTORY + "/" + name;
            try (InputStream in = ReviewGuard.class.getResourceAsStream(resource)) {
                FileUtils.copyInputStreamToFile(Objects.requireNonNull(in, resource), new File(plugin, name));
            }
        }

        ObjectNode toReturn = config.deepCopy();
        ObjectNode entry = toReturn.putArray("plugins").addObject();
        entry.put("package", plugin.getAbsolutePath());
        ObjectNode steps = entry.putObject("options").putObject("steps");
        config.path("agents").properties().forEach(agent -> steps.put(agent.getKey(), agent.getValue().path("steps").asInt()));
        return toReturn;
    }
}
