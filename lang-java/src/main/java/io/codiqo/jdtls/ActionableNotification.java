package io.codiqo.jdtls;

import java.util.List;

import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import lombok.Data;

@Data
public class ActionableNotification {
	@SerializedName("severity")
	@Expose
	private MessageType severity;

	@SerializedName("message")
	@Expose
	private String message;

	@SerializedName("data")
	@Expose
	private Object data;

	@SerializedName("commands")
	@Expose
	private List<Command> commands;

	@Override
	public String toString() {
		return MessageJsonHandler.toString(this);
	}
}
