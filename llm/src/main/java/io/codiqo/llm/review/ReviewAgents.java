package io.codiqo.llm.review;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts the agents of one local review: OpenCode in a build ({@link OpenCodeSessions#start}), scripted ones in a test
 * that runs a whole review.
 */
@FunctionalInterface
public interface ReviewAgents {
    /**
     * Starts the agents for one review of the work tree and returns once they can reach the answer server, since without
     * it no answer can be given.
     *
     * @param model where the agents call the model: the review's relay, which counts what the provider failed
     * @param answers where the agents give every answer, each through its {@link AnswerTool}
     * @param namingRules how a reviewer writes a unit's signature, one rule per language the commit changes
     */
    ReviewSessions start(Path workTree, ReviewEndpoint model, ReviewAnswers.Endpoint answers, List<String> namingRules) throws IOException;
}
