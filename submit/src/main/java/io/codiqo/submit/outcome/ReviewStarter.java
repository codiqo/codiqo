package io.codiqo.submit.outcome;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.ConventionGuidance;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.review.LocalReviewRun;

/** Starts the local review of the checked-out commit, beside its analysis. */
@FunctionalInterface
public interface ReviewStarter {
    /**
     * The agents' review in the commit's work tree, with the project's tags, the commit's previous findings and the
     * model end point the server gives.
     */
    ReviewStarter LOCAL = (args, server, logFactory) -> LocalReviewRun.start(
            args,
            args.getGit().getWorkTree().toPath(),
            args.getCommitId(),
            server.reviewEndpoint(args),
            ConventionGuidance.read(args, logFactory.getLogger(ConventionGuidance.class)),
            server::projectTags,
            () -> server.previousFindings(args.getCommitId()),
            DefaultLanguageProcessors.reviewLanguages(),
            logFactory);

    LocalReviewRun start(RunArgs args, CodiqoServer server, LogFactory logFactory) throws Exception;
}
