package io.codiqo.api;

import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Uploads the LLM response document for one analysis, under the object name {@link #objectName} builds. codiqo
 * carries no storage client of its own: the engine discovers an implementation through
 * {@link java.util.ServiceLoader}, and the server wires one as a bean — the object layout is shared so either side
 * writes where the other reads.
 *
 * <p>Delivery is the implementation's own concern, including which bucket it writes to and whether it uploads on the
 * calling thread.
 */
public interface LlmResponseUploader {
    String OBJECT_PREFIX = "codiqo";
    String OBJECT_SUFFIX = "-llm-response";

    /** a run of anything that would add a level to the layout, or need escaping in the URL it is read back through */
    Pattern UNSAFE_IN_SEGMENT = Pattern.compile("[^A-Za-z0-9._:-]+");

    void upload(String objectName, byte[] content) throws IOException;

    /** {@code codiqo/<project>/<sha>-llm-response.<extension>} — the bucket belongs to the implementation. */
    static String objectName(String projectCode, String commitSha, String extension) {
        return StringUtils.joinWith("/",
                OBJECT_PREFIX,
                segment(projectCode),
                segment(commitSha) + OBJECT_SUFFIX + FilenameUtils.EXTENSION_SEPARATOR_STR + extension);
    }
    private static String segment(String value) {
        return UNSAFE_IN_SEGMENT.matcher(value).replaceAll("-");
    }
}
