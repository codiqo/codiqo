package io.codiqo.submit.outcome;

import org.apache.commons.lang3.tuple.ImmutableTriple;

import io.codiqo.client.model.ClientInfoModel;

/** the project a run analyses, as its submissions name it, and the plugin that analysed it */
public final class AnalysedProject extends ImmutableTriple<String, String, ClientInfoModel> {
    private static final long serialVersionUID = 1L;

    /** @param code the code the REST API names the project by */
    public AnalysedProject(String code, String name, ClientInfoModel clientInfo) {
        super(code, name, clientInfo);
    }
    public String getCode() {
        return getLeft();
    }
    public String getName() {
        return getMiddle();
    }
    public ClientInfoModel getClientInfo() {
        return getRight();
    }
}
