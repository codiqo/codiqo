package io.codiqo.gradle;

import java.io.File;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

import lombok.experimental.UtilityClass;

@UtilityClass
public class GradleBuildSupport {
    private static final String JACOCO_DIR = "jacoco";
    private static final String CODIQO_JACOCO_EXEC = "codiqo.exec";
    private static final String EXEC_EXTENSION = ".exec";

    public File jacocoExec(Project project) {
        return new File(jacocoDir(project), CODIQO_JACOCO_EXEC);
    }
    /**
     * one exec file per Test task, NOT one per module: Gradle registers a doFirst that deletes the task's destination
     * file before it runs, so pointing `test` and `integrationTest` at a shared file makes whichever runs last erase
     * the other's coverage, silently and with a green build. The parts are merged before the dump runs.
     */
    public File jacocoExecPart(Test test) {
        return new File(jacocoDir(test.getProject()), AnalysisEngine.EXEC_PART_PREFIX + test.getName() + EXEC_EXTENSION);
    }
    /**
     * where this task writes its JUnit XML, taken from the task's own report container rather than from Gradle's
     * default layout: the location is configurable per task, and it is the analysis's only evidence that the task
     * actually forked and ran something.
     */
    public File junitXmlDir(Test test) {
        return test.getReports().getJunitXml().getOutputLocation().getAsFile().get();
    }
    private static File jacocoDir(Project project) {
        return new File(project.getLayout().getBuildDirectory().getAsFile().get(), JACOCO_DIR);
    }
}
