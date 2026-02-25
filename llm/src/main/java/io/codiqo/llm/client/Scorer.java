package io.codiqo.llm.client;

public interface Scorer<P, R> {
    R score(P params) throws Throwable;
}
