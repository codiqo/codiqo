package io.codiqo.submit;

import java.util.Set;

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.model.ExistingTagsModel;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.submit.auth.CodiqoApiClients;
import io.codiqo.submit.auth.CodiqoCredential;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.experimental.UtilityClass;

/**
 * The project's tag vocabulary, which an assessing local review reuses instead of coining near-duplicates of tags the
 * project's commits already carry. Without it the review still runs and only coins its tags freely.
 */
@UtilityClass
public class ProjectTags {
    /**
     * 404: a project the server does not know yet, or a server older than the endpoint, where no route matches the path.
     * 405: a server that routes the path elsewhere with another method.
     */
    private static final Set<Integer> NONE_KNOWN = Set.of(HttpResponseStatus.NOT_FOUND.code(), HttpResponseStatus.METHOD_NOT_ALLOWED.code());

    public ReviewTags fetch(String apiUrl, CodiqoCredential credential, long connectTimeoutSeconds, long readTimeoutSeconds, String projectId, Log log) throws Exception {
        AnalysisApi client = new AnalysisApi(CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds));
        ReviewTags toReturn = ReviewTags.NONE;
        try {
            /** one attempt: the tags only steer the review's wording */
            ExistingTagsModel known = client.listProjectTags(projectId);
            toReturn = ReviewTags.of(known.getTechnical(), known.getFunctional());
            log.info("project tags: %d technical and %d functional for the local review to reuse", toReturn.getTechnical().size(), toReturn.getFunctional().size());
        } catch (ApiException err) {
            if (NONE_KNOWN.contains(err.getCode())) {
                log.info("project tags: the server has none for project %s (HTTP %d)", projectId, err.getCode());
            } else {
                throw err;
            }
        }
        return toReturn;
    }
}
