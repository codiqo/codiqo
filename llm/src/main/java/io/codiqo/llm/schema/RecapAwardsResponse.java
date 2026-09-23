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
public class RecapAwardsResponse {
    private String intro;
    /** a light joke for the reel's interlude card, about the work and never at anyone's expense */
    private String joke;
    /** the closing card's line */
    private String signOff;
    @Builder.Default
    private List<Award> awards = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Award {
        private int rank;
        private String title;
        private String citation;
    }
}
