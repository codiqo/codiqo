package io.codiqo.llm.client;

import java.io.Closeable;

/**
 * root of the client family in this package: scoring, single-shot JSON completion and web search.
 *
 * it narrows {@link Closeable#close()} to not throw, which is the whole point — every implementation here
 * only releases an HTTP client or a connection pool and none of them has ever thrown, so callers get
 * try-with-resources without an IOException catch they would have nothing to do with.
 *
 * it deliberately declares no call method. the clients answer genuinely different questions (score a
 * submission, classify a message, search the web) and a shared execute(...) would exist only to be
 * ignored — what they actually share is lifecycle and {@link LlmUsage} reporting on their results.
 */
public interface LlmClient extends Closeable {
    @Override
    void close();
}
