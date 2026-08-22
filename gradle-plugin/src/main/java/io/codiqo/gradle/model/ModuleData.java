package io.codiqo.gradle.model;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;


import lombok.Data;

@Data
public class ModuleData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String groupId;
    private String artifactId;
    private String version;
    private String packaging;
    private String description;
    private String baseDirectory;
    private String outputDirectory;
    private String coveragePath;

    private List<String> compileSourceRoots = new ArrayList<>();
    private List<String> testCompileSourceRoots = new ArrayList<>();
    private List<String> testReportDirectories = new ArrayList<>();
    private List<String> compileClasspathElements = new ArrayList<>();
    private List<String> testClasspathElements = new ArrayList<>();
    private List<DependencyData> dependencies = new ArrayList<>();
}
