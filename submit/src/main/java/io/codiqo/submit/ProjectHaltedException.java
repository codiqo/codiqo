package io.codiqo.submit;

import io.codiqo.client.model.ProjectHaltModel;
import lombok.Getter;

/**
 * The server holds the project's analysis runs after an earlier run failed on codiqo's side. Analysing anything now would
 * start a paid local review that the same failure is likely to waste again, so the run stops before it begins.
 */
@Getter
public class ProjectHaltedException extends IllegalStateException {
    private final transient ProjectHaltModel halt;

    public ProjectHaltedException(String projectId, ProjectHaltModel halt) {
        super(String.format("project %s is halted since %s, when commit %s failed on codiqo's side: %s — investigate, then resume the project in codiqo-web",
                projectId,
                halt.getHaltedAt(),
                halt.getCommitSha(),
                halt.getReason()));
        this.halt = halt;
    }
}
