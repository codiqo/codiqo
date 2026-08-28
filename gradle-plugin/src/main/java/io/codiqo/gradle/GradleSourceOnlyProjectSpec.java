package io.codiqo.gradle;

import java.io.File;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import io.codiqo.core.NoOpClassGraphSpec;
import io.codiqo.gradle.model.AnalysisRequest;

/**
 * A whole-worktree {@link GradleProjectWrapper} for the source-only degraded path, mirroring the Maven
 * {@code SourceOnlyProjectSpec}. A failed build has no trustworthy class output, so the per-module model is set aside
 * and one owner takes every file under the work tree: the PMD index can then attribute and parse the whole project,
 * which is what the driver-score statistics need, while the class graph stays empty. Test sources are classified by
 * the {@code src/test/} layout rather than by Gradle's source sets, for the same reason.
 */
public class GradleSourceOnlyProjectSpec extends GradleProjectWrapper {
    private static final String TEST_PATH_SEGMENT = File.separator + "src" + File.separator + "test" + File.separator;
    private static final String JAR_PACKAGING = "jar";

    @Override
    public boolean isTestResource(File destination) {
        return Strings.CS.contains(destination.toPath().normalize().toString(), TEST_PATH_SEGMENT);
    }
    /**
     * The id is the root project's Gradle path, the same namespace {@link GradleModelCollector} stamps on every
     * healthy module. Using the project code instead would file one project under two identifiers depending on
     * whether its build happened to compile, and anything keyed on the module id would see two projects where there
     * is one.
     */
    public static GradleSourceOnlyProjectSpec forWorkTree(File workTree, AnalysisRequest request) {
        GradleSourceOnlyProjectSpec toReturn = new GradleSourceOnlyProjectSpec();
        toReturn.setId(request.getRootPath());
        toReturn.setGroupId(StringUtils.substringBefore(request.getRootCode(), ":"));
        toReturn.setArtifactId(request.getRootName());
        toReturn.setName(request.getRootName());
        toReturn.setPackaging(JAR_PACKAGING);
        toReturn.setVersion(request.getRootVersion());
        toReturn.setBaseDirectory(workTree);
        toReturn.setScan(new NoOpClassGraphSpec());
        return toReturn;
    }
}
