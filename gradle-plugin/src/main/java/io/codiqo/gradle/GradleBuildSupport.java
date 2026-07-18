package io.codiqo.gradle;

import java.io.File;

import org.gradle.api.Project;

import lombok.experimental.UtilityClass;

@UtilityClass
public class GradleBuildSupport {
    private static final String CODIQO_JACOCO_EXEC = "jacoco/codiqo.exec";

    public File jacocoExec(Project project) {
        /**
         * codiqo owns coverage in the analysis build: every Test task's jacoco extension is pointed at this
         * per-module file (the agent's append merges multiple test tasks), so a project's own destinationFile
         * customization or disabled tasks can never hide the exec data from the analysis
         */
        return new File(project.getLayout().getBuildDirectory().getAsFile().get(), CODIQO_JACOCO_EXEC);
    }
}
