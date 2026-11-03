package io.codiqo.llm.review;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Value;

/**
 * A bug an earlier run of the commit reported, which the triage turn is shown as a hint of where to look again. The run
 * supplies the list, as it supplies the project's {@link ReviewTags}. A field the server has no value for (a bug without
 * a line) is left out of the prompt rather than written as null: the answer rules in the same prompt tell the model never
 * to write null, and a hint shaped like a verdict that shows {@code "line":null} invites a refused answer.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PreviousFinding {
    String file;
    Integer line;
    String type;
    String severity;
    String title;
}
