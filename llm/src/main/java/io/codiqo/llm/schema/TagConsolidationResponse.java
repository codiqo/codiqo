package io.codiqo.llm.schema;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagConsolidationResponse {
    @Builder.Default
    private List<TagMapping> technical = new ArrayList<>();
    @Builder.Default
    private List<TagMapping> functional = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagMapping {
        private String canonical;
        @Builder.Default
        private List<String> merged = new ArrayList<>();
    }
}
