package io.codiqo.llm.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import io.codiqo.llm.PromptBuilder.WebSearchResultItem;

public interface WebSearchClient extends Closeable {
    String searchAsMarkdown(String query, int maxResults) throws IOException;
    List<WebSearchResultItem> search(String query, int maxResults) throws IOException;
}
