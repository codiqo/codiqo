package io.codiqo.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

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
}
