package io.codiqo.llm.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * the entire output surface of the skip-request classifier. deliberately two fields: a crafted commit message
 * can only ever move "should this commit be excluded", never anything the scoring call reads. "quote" exists so
 * the claim can be checked against the message in code rather than trusted
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkipRequestResponse {
    private boolean excludeRequested;
    private String quote;
}
