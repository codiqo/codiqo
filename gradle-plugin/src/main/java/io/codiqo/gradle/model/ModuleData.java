package io.codiqo.gradle.model;

import java.io.Serializable;
import java.util.List;

import com.google.common.collect.Lists;

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

    private List<String> compileSourceRoots = Lists.newArrayList();
    private List<String> testCompileSourceRoots = Lists.newArrayList();
    private List<String> testReportDirectories = Lists.newArrayList();
    private List<String> compileClasspathElements = Lists.newArrayList();
    private List<String> testClasspathElements = Lists.newArrayList();
    private List<DependencyData> dependencies = Lists.newArrayList();
}
