package io.codiqo.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

public class CodiqoGradlePlugin implements Plugin<Project> {
    private static final String EXTENSION_NAME = "codiqo";
    private static final String TASK_GROUP = "codiqo";
    private static final String DUMP_TASK = "codiqoDumpAnalysis";

    @Override
    public void apply(Project project) {
        CodiqoExtension ext = project.getExtensions().create(EXTENSION_NAME, CodiqoExtension.class);
        project.getTasks().register(DUMP_TASK, CodiqoDumpAnalysisTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Analyze the current checkout and dump the Codiqo analysis submission as YAML");

            // When tests run in the same build, the dump reads build/jacoco/test.exec directly, so it
            // must run after every Test task has written it — otherwise the dump's fast JDT import
            // finishes first under parallel execution and coverage comes out empty. Lazy Callable: the
            // subproject test tasks are registered later (the init script applies jacoco), so they are
            // enumerated only when Gradle resolves the execution graph.
            task.mustRunAfter((Callable<List<Test>>) () -> allTestTasks(project));
        });
        project.getTasks().register("codiqoIndexCommits", CodiqoIndexCommitsTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Walk git history over the commit window and write analyzable commit SHAs to a file");
        });

        // Snapshot the Gradle model off the task-execution path so subproject classpaths are
        // resolved at configuration time (works under parallel execution, no --no-parallel).
        project.getGradle().getTaskGraph().whenReady(graph -> {
            CodiqoDumpAnalysisTask task = (CodiqoDumpAnalysisTask) project.getTasks().findByName(DUMP_TASK);
            if (task != null && graph.hasTask(task)) {
                task.setRequest(GradleModelCollector.collect(project.getRootProject(), ext));
            }
        });
    }

    private static List<Test> allTestTasks(Project project) {
        List<Test> toReturn = new ArrayList<>();
        for (Project p : project.getRootProject().getAllprojects()) {
            toReturn.addAll(p.getTasks().withType(Test.class));
        }
        return toReturn;
    }
}
