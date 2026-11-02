package io.codiqo.llm.review;

import lombok.Value;

/**
 * A bug an earlier run of the commit reported, which the triage turn is shown as a hint of where to look again. The run
 * supplies the list, as it supplies the project's {@link ReviewTags}.
 */
@Value
public class PreviousFinding {
    String file;
    Integer line;
    String type;
    String severity;
    String title;
}
