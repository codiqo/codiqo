package io.codiqo.llm.review;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaticFinding {
    private String tool;
    private String rule;
    private String severity;
    private String file;
    private int line;
    private String message;
}
