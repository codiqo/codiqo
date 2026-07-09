package io.codiqo.api;

import java.io.File;

import org.apache.commons.collections4.BidiMap;
import org.apache.maven.artifact.Artifact;

public interface MavenProjectSpec extends JvmProjectSpec {
    BidiMap<Artifact, File> getArtifacts();
}
