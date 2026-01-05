package io.codiqo.core;

import java.io.File;
import java.util.Collection;
import java.util.Optional;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.google.common.collect.Lists;

import io.codiqo.api.Project;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class MavenProjectWrapper implements Project {
    private String name;
    private String description;
    private String version;
    private File baseDirectory;
    private String outputDirectory;
    private Optional<File> coverage = Optional.empty();
    private Collection<File> compileSourceRoots = Lists.newArrayList();
    private Collection<File> compileClasspathElements = Lists.newArrayList();
    private Collection<File> testCompileSourceRoots = Lists.newArrayList();
    private Collection<File> testClasspathElements = Lists.newArrayList();

    @Override
    public Optional<File> coverage() {
        return coverage;
    }
    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.NO_CLASS_NAME_STYLE)
                .append("name", name)
                .append("description", description)
                .append("version", version)
                .append("baseDirectory", baseDirectory)
                .append("compileSourceRoots", compileSourceRoots)
                .append("compileClasspathElements", compileClasspathElements)
                .append("testCompileSourceRoots", testCompileSourceRoots)
                .append("testClasspathElements", testClasspathElements)
                .build();
    }
}
