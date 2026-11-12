package io.codiqo.submit.outcome;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.submit.SubmissionContext;

/**
 * The commit's build, as its build tool runs it: what a {@link CommitRun} needs that differs between Maven and Gradle.
 * Maven builds the commit in a fork when asked; Gradle built it before the analysis started and only reports what the
 * build did. Every method throws for a failure on Codiqo's side, which halts the project.
 */
public interface CommitBuild {
    /**
     * Builds the commit's production code. It is asked after the exclusion gates that need git alone, and a commit's
     * review starts once it passed, or once it failed on the author.
     */
    BuildVerdict compile() throws Exception;
    /** runs the tests, beside the review; a run that measures coverage passes with the test run */
    BuildVerdict test() throws Exception;
    /**
     * The analysable projects of the built commit, added to the run's projects, and the class graph they share, which
     * the run closes once the commit is analysed.
     */
    ClassGraphSpec projects() throws Exception;
    /**
     * One project that owns every file of the work tree, for the source-only index of a commit whose build failed and
     * left no trustworthy model of its projects.
     */
    ProjectSpec sourceOnlyProject() throws Exception;
    /** the build tool's model of the project, its modules and dependencies, into the submission */
    void populateProjectModel(SubmissionContext ctx);
}
