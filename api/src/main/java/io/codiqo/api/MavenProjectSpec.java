package io.codiqo.api;

import java.io.File;

import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;

public interface MavenProjectSpec extends JvmProjectSpec {
    BiMap<Artifact, File> getArtifacts();
}
