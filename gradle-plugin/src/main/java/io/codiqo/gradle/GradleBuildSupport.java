package io.codiqo.gradle;

import java.io.File;

import org.gradle.api.Project;

import lombok.experimental.UtilityClass;

@UtilityClass
public class GradleBuildSupport {
    private static final String DEFAULT_JACOCO_EXEC = "jacoco/test.exec";

    public File autoDetectJacocoExec(Project project) {
        // the jacoco plugin writes the `test` task's exec data here by default; a project that
        // customizes JacocoTaskExtension.destinationFile is out of scope for the PoC
        return new File(project.getLayout().getBuildDirectory().getAsFile().get(), DEFAULT_JACOCO_EXEC);
    }
}
