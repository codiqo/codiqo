package io.codiqo.gradle.model;

import java.io.Serializable;

import lombok.Data;

@Data
public class DependencyData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String groupId;
    private String artifactId;
    private String version;
    private String type;
    private String classifier;
    private String filePath;
    private String coordinate;
    /** the configuration it was resolved from, the first of {@code DEPENDENCY_CONFIGURATIONS} that has it */
    private String scope;
    /** whether that configuration received it through another dependency rather than declaring it */
    private Boolean transitive;
}
