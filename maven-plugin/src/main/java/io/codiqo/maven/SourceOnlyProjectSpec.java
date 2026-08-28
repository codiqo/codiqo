package io.codiqo.maven;

import java.io.File;

import org.apache.commons.lang3.Strings;
import org.apache.maven.project.MavenProject;

import io.codiqo.core.NoOpClassGraphSpec;

/**
 * A whole-worktree {@link MavenProjectWrapper} for the source-only degraded path, where a failed build leaves no
 * resolved reactor. It owns every file under the work tree so the PMD index can attribute and parse the whole project
 * for the driver-score statistics, classifies test sources by the {@code src/test/} layout, and exposes an empty
 * class graph.
 */
public class SourceOnlyProjectSpec extends MavenProjectWrapper {
    private static final String TEST_PATH_SEGMENT = File.separator + "src" + File.separator + "test" + File.separator;

    @Override
    public boolean isTestResource(File destination) {
        return Strings.CS.contains(destination.toPath().normalize().toString(), TEST_PATH_SEGMENT);
    }
    public static SourceOnlyProjectSpec forWorkTree(File workTree, MavenProject project) {
        SourceOnlyProjectSpec toReturn = new SourceOnlyProjectSpec();
        toReturn.setId(project.getId());
        toReturn.setCode(project.getGroupId() + ":" + project.getArtifactId());
        toReturn.setGroupId(project.getGroupId());
        toReturn.setArtifactId(project.getArtifactId());
        toReturn.setName(project.getName());
        toReturn.setPackaging(project.getPackaging());
        toReturn.setVersion(project.getVersion());
        toReturn.setBaseDirectory(workTree);
        toReturn.setScan(new NoOpClassGraphSpec());
        return toReturn;
    }
}
