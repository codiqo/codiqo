package io.codiqo.jdtls;

import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import io.codiqo.api.jdtls.ServiceStatus;
import lombok.Data;

@Data
public class StatusReport {
    @SerializedName("type")
    @Expose
    private ServiceStatus type;

    @SerializedName("message")
    @Expose
    private String message;

    @Override
    public String toString() {
        return MessageJsonHandler.toString(this);
    }
}
