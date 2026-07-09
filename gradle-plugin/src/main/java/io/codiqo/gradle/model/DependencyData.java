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
}
