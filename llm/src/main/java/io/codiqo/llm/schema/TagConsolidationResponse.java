package io.codiqo.llm.schema;

import java.util.List;

import com.google.common.collect.Lists;

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
    private List<TagMapping> technical = Lists.newArrayList();
    @Builder.Default
    private List<TagMapping> functional = Lists.newArrayList();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagMapping {
        private String canonical;
        @Builder.Default
        private List<String> merged = Lists.newArrayList();
    }
}
