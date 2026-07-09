package io.codiqo.submit;

import java.time.ZoneOffset;

import io.codiqo.client.model.CommitModel;

public class CommitModelPopulator implements SubmissionPopulator {
    @Override
    public void accept(SubmissionContext ctx) {
        populateCommitModel(ctx);
        populateClientModel(ctx);
    }
    private static void populateCommitModel(SubmissionContext ctx) {
        CommitModel commitModel = new CommitModel();
        commitModel.setSha(ctx.getAnalysis().getCommitId());
        commitModel.setMessage(ctx.getAnalysis().getMessage());
        commitModel.setAuthor(ctx.getAnalysis().getAuthor());
        commitModel.setAuthorEmail(ctx.getAnalysis().getAuthorEmail());
        commitModel.setTimestamp(ctx.getAnalysis().getAuthorTimestamp().toInstant().atOffset(ZoneOffset.UTC));
        commitModel.setParents(ctx.getAnalysis().getParentIds());
        commitModel.setBranches(ctx.getAnalysis().getBranches());
        commitModel.setIsMerge(ctx.getAnalysis().isMergeCommit());
        commitModel.setIsRevert(ctx.getAnalysis().isRevertCommit());
        commitModel.setRevertedCommitId(ctx.getAnalysis().getRevertedCommitId());
        ctx.getSubmissionModel().setCommit(commitModel);
    }
    private static void populateClientModel(SubmissionContext ctx) {
        ctx.getSubmissionModel().setClient(ctx.getClientInfo());
    }
}
