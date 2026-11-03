package io.codiqo.submit;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.model.PreviousFindingsModel;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.submit.auth.CodiqoApiClients;
import io.codiqo.submit.auth.CodiqoCredential;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.experimental.UtilityClass;

/**
 * The bugs earlier runs of a commit reported, which the triage turn of its local review is shown as a hint of where to
 * look again. A commit never analysed before has none, and the review runs as it always did. The hint is best-effort:
 * one attempt, and a failed call or credential leaves the review without it, since a review is worth running without a hint and an
 * {@link ApiRetry} wait (up to 15 minutes on a failing endpoint) would hold up the review it only helps.
 */
@UtilityClass
public class PreviousFindings {
    /**
     * 404: a project the server does not know yet, or a server older than the endpoint, where no route matches the path.
     * 405: a server that routes the path elsewhere with another method.
     */
    private static final Set<Integer> NONE_KNOWN = Set.of(HttpResponseStatus.NOT_FOUND.code(), HttpResponseStatus.METHOD_NOT_ALLOWED.code());

    /**
     * @param sourceHidden whether the project submits in hidden-source mode, where the server keeps a bug's path as a
     *        keyed hash and no title: such a hint matches no finding the triage reads, so none is fetched
     */
    public List<PreviousFinding> fetch(
            String apiUrl,
            CodiqoCredential credential,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            String projectId,
            String commitSha,
            boolean sourceHidden,
            Log log) {
        List<PreviousFinding> toReturn = List.of();
        if (sourceHidden) {
            log.info("previous findings: not fetched, the server keeps no readable path or title of a hidden-source commit's bugs");
        } else {
            AnalysisApi client = new AnalysisApi(CodiqoApiClients.newApiClient(apiUrl, credential, connectTimeoutSeconds, readTimeoutSeconds));
            try {
                PreviousFindingsModel known = client.listPreviousFindings(projectId, commitSha);
                toReturn = CollectionUtils.emptyIfNull(known.getFindings()).stream()
                        .map(finding -> new PreviousFinding(finding.getFile(), finding.getLine(), finding.getType(), finding.getSeverity(), finding.getTitle()))
                        .toList();
                log.info("previous findings: %d bugs earlier runs of %s reported, for the triage to look at again", toReturn.size(), commitSha);
            } catch (ApiException err) {
                if (NONE_KNOWN.contains(err.getCode())) {
                    log.info("previous findings: the server has none for %s in project %s (HTTP %d)", commitSha, projectId, err.getCode());
                } else {
                    log.warn("previous findings: none, the review runs without the hint (%s): %s", apiUrl, ApiRetry.summarize(err));
                }
            } catch (UncheckedIOException err) {
                /** a credential the request interceptor could not refresh ({@link CodiqoApiClients}) */
                log.warn("previous findings: none, the review runs without the hint (%s): %s", apiUrl, ApiRetry.summarize(err));
            }
        }
        return toReturn;
    }
}
