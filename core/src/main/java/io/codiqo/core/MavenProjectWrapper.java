package io.codiqo.core;

import java.io.File;
import java.util.Collection;
import java.util.Optional;

import com.google.common.collect.Lists;

import io.codiqo.api.Project;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString
public class MavenProjectWrapper implements Project {
    private String name;
    private String description;
    private String version;
    private File baseDirectory;
    private File outputDirectory;
    private Optional<File> coverage = Optional.empty();
    private Collection<File> compileSourceRoots = Lists.newArrayList();
    private Collection<File> compileClasspathElements = Lists.newArrayList();
    private Collection<File> testCompileSourceRoots = Lists.newArrayList();
    private Collection<File> testClasspathElements = Lists.newArrayList();

    @Override
    public Optional<File> coverage() {
        return coverage;
    }
}
